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
import ai.koog.serialization.typeToken
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.balch.orpheus.core.ai.AiModelProvider
import org.balch.orpheus.core.ai.ToolProvider
import org.balch.orpheus.core.coroutines.AppCoroutineScope
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.preferences.AppPreferences
import org.balch.orpheus.core.preferences.AppPreferencesRepository
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TurnBudgetTest {

    @Test
    fun capsAreThreeAppliesAndTwelveMixEdits() {
        val budget = TurnBudget()
        assertEquals(listOf(true, true, true, false), List(4) { budget.tryApply() })
        assertEquals(List(12) { true } + false, List(13) { budget.tryMixEdit() })
    }

    @Test
    fun exemptCallsAreNeverCountedOrRefused() = runBlocking {
        val budget = TurnBudget()
        repeat(3) { budget.tryApply() }
        repeat(12) { budget.tryMixEdit() }
        assertFalse(budget.allowApply())
        withContext(TurnBudget.Exempt) {
            repeat(20) { assertTrue(budget.allowApply()); assertTrue(budget.allowMixEdit()) }
        }
    }

    /** Drives the real strategy graph: four applies in turn one, then one more in turn two. */
    @Test
    fun budgetResetsNextTurn() = runBlocking {
        val budget = TurnBudget()
        val outcomes = Collections.synchronizedList(mutableListOf<Boolean>())
        val spend = object : ToolProvider {
            override val tool = SpendTool(budget, outcomes)
        }
        val config = config(budget, setOf(spend))
        val executor = ScriptedExecutor(
            listOf(
                calls("spend", 4),
                text("Turn one done."),
                calls("spend", 1),
                text("Turn two done."),
            ),
        )
        val replies = mutableListOf<String>()
        val strategy = config.agentStrategy(
            name = "budget",
            onAssistantMessage = { reply ->
                replies += reply
                if (replies.size == 1) "again" else throw StopRun()
            },
            streamResponses = false,
            compressHistory = false,
        )
        val agent = AIAgent(
            promptExecutor = executor,
            strategy = strategy,
            agentConfig = AIAgentConfig(prompt("t") { system("test") }, config.model, maxAgentIterations = 50),
            toolRegistry = config.toolRegistry,
        )
        // Spent by an earlier, interrupted run: a new run starts a new turn.
        repeat(3) { budget.tryApply() }
        assertFailsWith<StopRun> { agent.run("first") }

        assertEquals(listOf("Turn one done.", "Turn two done."), replies)
        assertEquals(5, outcomes.size, "outcomes: $outcomes")
        assertEquals(3, outcomes.take(4).count { it }, "turn one allows three applies of four: $outcomes")
        assertTrue(outcomes[4], "turn two starts with a fresh budget")
    }

    /** Four turns of 20 tool rounds each: far past the old session-wide node cap of 100. */
    @Test
    fun manyBuildTurnsInOneSessionDoNotHitTheIterationCap() = runBlocking {
        val noop = object : ToolProvider {
            override val tool = SpendTool(TurnBudget(), mutableListOf(), name = "noop")
        }
        val config = config(TurnBudget(), setOf(noop))
        val turns = 4
        val rounds = 20
        val script = List(turns) { t -> List(rounds) { calls("noop", 1) } + text("Built vibe ${t + 1}.") }.flatten()
        val replies = mutableListOf<String>()
        val agent = AIAgent(
            promptExecutor = ScriptedExecutor(script),
            strategy = config.agentStrategy(
                name = "builds",
                onAssistantMessage = { reply ->
                    replies += reply
                    if (replies.size < turns) "another vibe" else throw StopRun()
                },
                streamResponses = false,
                compressHistory = false,
            ),
            agentConfig = AIAgentConfig(prompt("t") { system("test") }, config.model, config.maxAgentIterations),
            toolRegistry = config.toolRegistry,
        )
        assertFailsWith<StopRun> { agent.run("first") }
        assertEquals(List(turns) { "Built vibe ${it + 1}." }, replies)
    }

    @Test
    fun promptHasATweakPathAndConditionalMixing() {
        val prompt = config(TurnBudget(), emptySet()).systemInstruction
        assertTrue("pulsar_get_vibe('current')" in prompt, "the tweak path is missing")
        assertTrue("${TurnBudget.MAX_APPLIES} applies" in prompt)
        assertTrue("${TurnBudget.MAX_MIX_EDITS} mix edits when" in prompt)
        assertTrue("unless budgetSpent is true" in prompt)
        // The synth app has no mix tools: every Mixer rule sits under the mix-tools conditional.
        val head = "When the mix tools are available, you mix together with the user:"
        assertTrue(head in prompt, "the mixing rules lost their conditional")
        val mixing = prompt.substringAfter(head).substringBefore("A plain-text reply ends your turn")
        listOf("moves since your last turn", "only while the Mixer is open", "PINNED", "USER_MOVED", "confirmation", "short")
            .forEach { assertTrue(it in mixing, "the mixing rules lost \"$it\":\n$mixing") }
        // The listening pass (step 5) is conditional as a whole, and fixes the mix only with the Mixer open.
        val pass = prompt.substringAfter("5. When the mix tools are available").substringBefore("\n6. ")
        assertTrue("when the Mixer is open, small fixes with mix_set" in pass.replace(Regex("\\s+"), " "), pass)
        assertTrue(prompt.replace(mixing, "").replace(pass, "").lines().none { "Mixer" in it }, "a Mixer rule outside the conditional")
        assertFalse("when you're back" in prompt || "they are back" in prompt, "the back-and-forth rule is still there")
    }

    @Test
    fun promptHasNoTemplateCopyRule() {
        val prompt = config(TurnBudget(), emptySet()).systemInstruction
        assertFalse("EXACTLY ONCE" in prompt)
        assertFalse(prompt.contains("template", ignoreCase = true))
        assertTrue("pulsar_cast_vibe" in prompt)
    }

    private class StopRun : RuntimeException("stop")

    @Serializable
    class SpendArgs

    @Serializable
    data class SpendResult(val ok: Boolean)

    class SpendTool(private val budget: TurnBudget, private val outcomes: MutableList<Boolean>, name: String = "spend") :
        Tool<SpendArgs, SpendResult>(typeToken<SpendArgs>(), typeToken<SpendResult>(), name, "Spends one apply.") {
        override suspend fun execute(args: SpendArgs) = SpendResult(budget.allowApply()).also { outcomes += it.ok }
    }

    /** Returns the scripted replies in order; the graph only uses the batched execute. */
    private class ScriptedExecutor(script: List<Message.Assistant>) : PromptExecutor() {
        private val queue = ArrayDeque(script)
        override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>) =
            queue.removeFirstOrNull() ?: error("script ran out")
        override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> =
            error("streaming is not scripted")
        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = error("unused")
        override fun close() {}
    }

    private var callId = 0

    private fun calls(tool: String, n: Int) = Message.Assistant(
        parts = List(n) { MessagePart.Tool.Call(id = "c${callId++}", tool = tool, args = "{}") },
        metaInfo = ResponseMetaInfo.Empty,
    )

    private fun text(s: String) = Message.Assistant(parts = listOf(MessagePart.Text(s)), metaInfo = ResponseMetaInfo.Empty)

    private fun config(budget: TurnBudget, tools: Set<ToolProvider>): OrpheusAgentConfig {
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
        val provider = AiModelProvider(prefs, dispatchers, AppCoroutineScope(dispatchers))
        return OrpheusAgentConfig(toolSet = tools, aiModelProvider = provider, turnBudget = budget)
    }
}
