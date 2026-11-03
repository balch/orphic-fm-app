package org.balch.orpheus.features.ai

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.toStreamFrames
import ai.koog.serialization.typeToken
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.balch.orpheus.core.ai.AiModelProvider
import org.balch.orpheus.core.ai.ToolProvider
import org.balch.orpheus.core.coroutines.AppCoroutineScope
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.preferences.AppPreferences
import org.balch.orpheus.core.preferences.AppPreferencesRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Drives the real strategy graph with scripted replies, on both the batched and the streamed path. */
class EmptyReplyTest {

    private val config = config()

    @Test
    fun blankReplyIsAskedAgainOnceAndLeavesNoTrace() = forBothPaths { streamed ->
        val run = run(listOf(empty("end_turn"), text("Done.")), turns = 1, streamed)

        assertEquals(listOf("Done."), run.replies)
        assertEquals(listOf(EmptyReply("end_turn", null, retrying = true)), run.empties)
        assertEquals(2, run.prompts.size)
        assertEquals(run.prompts[0].messages.size, run.prompts[1].messages.size, "the empty reply stayed in history")
    }

    @Test
    fun retriedReplyCanCallATool() = forBothPaths { streamed ->
        val run = run(listOf(empty("MALFORMED_FUNCTION_CALL"), calls(), text("Done.")), turns = 1, streamed)
        assertEquals(listOf("Done."), run.replies)
    }

    @Test
    fun secondBlankReplyEndsTheTurnAndTheSessionGoesOn() = forBothPaths { streamed ->
        val run = run(listOf(empty("STOP"), empty("STOP"), text("Done.")), turns = 2, streamed)

        val notice = config.emptyReplyMessage(EmptyReplyKind.BLANK, workDone = false)
        assertEquals(listOf(notice, "Done."), run.replies)
        assertEquals(listOf(true, false), run.empties.map { it.retrying })
        val history = run.prompts.last().messages.filterIsInstance<Message.Assistant>()
        assertEquals(listOf(notice), history.map { it.textOf() })
    }

    @Test
    fun outOfRoomIsNotAskedAgain() = forBothPaths { streamed ->
        val run = run(listOf(thinking("still working it out", "max_tokens")), turns = 1, streamed)

        assertEquals(listOf(config.emptyReplyMessage(EmptyReplyKind.OUT_OF_ROOM, workDone = false)), run.replies)
        assertEquals(1, run.prompts.size)
    }

    @Test
    fun refusalIsNotAskedAgain() = forBothPaths { streamed ->
        val run = run(listOf(empty("refusal")), turns = 1, streamed)

        assertEquals(listOf(config.emptyReplyMessage(EmptyReplyKind.REFUSED, workDone = false)), run.replies)
        assertEquals(1, run.prompts.size)
    }

    @Test
    fun noticeNeverSaysNothingChangedAfterAToolRan() = forBothPaths { streamed ->
        val run = run(listOf(calls(), empty("max_tokens")), turns = 1, streamed)

        assertEquals(listOf(config.emptyReplyMessage(EmptyReplyKind.OUT_OF_ROOM, workDone = true)), run.replies)
        assertFalse("Nothing changed" in run.replies.single())
    }

    @Test
    fun aNewTurnGetsItsOwnRetry() = forBothPaths { streamed ->
        val run = run(listOf(empty("STOP"), text("One."), empty("STOP"), text("Two.")), turns = 2, streamed)
        assertEquals(listOf("One.", "Two."), run.replies)
    }

    /** Koog's streaming request leaves the reply out of history, so the model forgot its own calls and replies. */
    @Test
    fun streamedRepliesReachHistoryWithoutTheirReasoning() {
        val toolTurn = Message.Assistant(
            parts = listOf(MessagePart.Reasoning("why"), MessagePart.Tool.Call("c1", "noop", "{}")),
            metaInfo = ResponseMetaInfo.Empty,
        )
        val run = run(listOf(toolTurn, text("First."), text("Second.")), turns = 2, streamed = true)

        assertEquals(listOf("First.", "Second."), run.replies)
        val sent = run.prompts.last().messages.filterIsInstance<Message.Assistant>()
        assertEquals(listOf("Tool.Call", "Text"), sent.flatMap { m -> m.parts.map { it.label() } })
        assertEquals("First.", sent.last().textOf())
    }

    @Test
    fun readsEveryProvidersStopReasons() {
        listOf("max_tokens", "MAX_TOKENS", "length").forEach { assertEquals(EmptyReplyKind.OUT_OF_ROOM, emptyReplyKind(it)) }
        listOf("refusal", "SAFETY", "RECITATION", "content_filter").forEach { assertEquals(EmptyReplyKind.REFUSED, emptyReplyKind(it)) }
        listOf("end_turn", "STOP", "stop", "MALFORMED_FUNCTION_CALL", "completed", null).forEach { assertEquals(EmptyReplyKind.BLANK, emptyReplyKind(it)) }
    }

    @Test
    fun describeNamesTheStopReasonForTheErrorLog() {
        val text = EmptyReply("STOP", 12, retrying = true).describe()
        assertTrue("finishReason=STOP" in text && "outputTokens=12" in text && "retrying" in text, text)
    }

    private fun Message.Assistant.textOf() = parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.text }

    private fun MessagePart.label() = when (this) {
        is MessagePart.Tool.Call -> "Tool.Call"
        is MessagePart.Text -> "Text"
        else -> this::class.simpleName.orEmpty()
    }

    private fun forBothPaths(block: (Boolean) -> Unit) {
        block(false)
        block(true)
    }

    private class Run(val replies: List<String>, val empties: List<EmptyReply>, val prompts: List<Prompt>)

    private fun run(script: List<Message.Assistant>, turns: Int, streamed: Boolean): Run = runBlocking {
        val executor = ScriptedExecutor(script)
        val replies = mutableListOf<String>()
        val empties = mutableListOf<EmptyReply>()
        val agent = AIAgent(
            promptExecutor = executor,
            strategy = config.agentStrategy(
                name = "empty",
                onAssistantMessage = { reply ->
                    replies += reply
                    if (replies.size < turns) "again" else throw StopRun()
                },
                onEmptyReply = { empties += it },
                streamResponses = streamed,
                compressHistory = false,
            ),
            agentConfig = AIAgentConfig(prompt("t") { system("test") }, config.model, maxAgentIterations = 50),
            toolRegistry = config.toolRegistry,
        )
        assertFailsWith<StopRun> { agent.run("first") }
        Run(replies, empties, executor.prompts)
    }

    private class StopRun : RuntimeException("stop")

    private class ScriptedExecutor(script: List<Message.Assistant>) : PromptExecutor() {
        private val queue = ArrayDeque(script)
        val prompts = mutableListOf<Prompt>()
        private fun next(prompt: Prompt) = (queue.removeFirstOrNull() ?: error("script ran out")).also { prompts += prompt }
        override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>) = next(prompt)
        override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> =
            next(prompt).toStreamFrames().asFlow()
        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = error("unused")
        override fun close() {}
    }

    @Serializable
    class NoArgs

    @Serializable
    data class NoResult(val ok: Boolean)

    class NoopTool : Tool<NoArgs, NoResult>(typeToken<NoArgs>(), typeToken<NoResult>(), "noop", "Does nothing.") {
        override suspend fun execute(args: NoArgs) = NoResult(true)
    }

    private var callId = 0

    private fun calls() = Message.Assistant(
        parts = listOf(MessagePart.Tool.Call(id = "c${callId++}", tool = "noop", args = "{}")),
        metaInfo = ResponseMetaInfo.Empty,
    )

    private fun text(s: String) = Message.Assistant(parts = listOf(MessagePart.Text(s)), metaInfo = ResponseMetaInfo.Empty)

    private fun empty(reason: String) = Message.Assistant(parts = emptyList(), metaInfo = ResponseMetaInfo.Empty, finishReason = reason)

    private fun thinking(s: String, reason: String) =
        Message.Assistant(parts = listOf(MessagePart.Reasoning(s)), metaInfo = ResponseMetaInfo.Empty, finishReason = reason)

    private fun config(): OrpheusAgentConfig {
        val d = Dispatchers.Unconfined
        val dispatchers = object : DispatcherProvider {
            override val main: CoroutineDispatcher = d
            override val io: CoroutineDispatcher = d
            override val default: CoroutineDispatcher = d
            override val unconfined: CoroutineDispatcher = d
        }
        val prefs = object : AppPreferencesRepository {
            override suspend fun load() = AppPreferences()
            override suspend fun save(preferences: AppPreferences) {}
            override suspend fun update(transform: (AppPreferences) -> AppPreferences) {}
        }
        val noop = object : ToolProvider {
            override val tool = NoopTool()
        }
        return OrpheusAgentConfig(setOf(noop), AiModelProvider(prefs, dispatchers, AppCoroutineScope(dispatchers)), TurnBudget())
    }
}
