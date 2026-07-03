package org.balch.orpheus.features.ai

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.balch.orpheus.core.ai.AiKeyRepository
import org.balch.orpheus.core.ai.AiModelProvider
import org.balch.orpheus.core.ai.AiProvider
import org.balch.orpheus.core.coroutines.AppCoroutineScope
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.features.AgentGreetingMode
import org.balch.orpheus.core.features.FeatureCoroutineScope
import org.balch.orpheus.core.preferences.AppPreferences
import org.balch.orpheus.core.preferences.AppPreferencesRepository
import org.balch.orpheus.core.tidal.ReplCodeEventBus
import org.balch.orpheus.features.ai.chat.widgets.ChatMessageType
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Locks the greeting-mode contract: with [AgentGreetingMode.ON_FIRST_PROMPT] the agent must stay
 * SILENT after construction (opening the DJ AI panel), issuing no LLM request until the user
 * submits their first prompt. The only code that leaves the initial Loading state is `runAgent`,
 * which builds the LLM client and starts a Koog run; if the agent stays in Loading after the
 * scheduler drains, `runAgent` was never reached — i.e. no outbound traffic on open.
 *
 * COVERAGE GAP: this test does not drive a full run (Koog's LLM client / run() cannot be faked
 * without a live provider), so it does not assert that the FIRST run seeds on the user's prompt
 * rather than `config.initialAgentPrompt()`. That branch is verified by inspection of
 * `startAgentIfNeeded`. What is locked here is the observable, network-free property: opening the
 * panel produces zero state transitions (no greeting request).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OrpheusAgentGreetingTest {

    private val dispatcher = StandardTestDispatcher()

    private val dispatcherProvider = object : DispatcherProvider {
        override val main: CoroutineDispatcher = dispatcher
        override val io: CoroutineDispatcher = dispatcher
        override val default: CoroutineDispatcher = dispatcher
        override val unconfined: CoroutineDispatcher = dispatcher
    }

    /** Prefs supplying a user API key for Google (the default model's provider) so the null-key
     *  short-circuit in `startAgentIfNeeded` is NOT taken and we reach the seed-prompt branch. */
    private val prefs = object : AppPreferencesRepository {
        val stored = AppPreferences(userApiKeys = mapOf(AiProvider.Google.id to "test-google-key"))
        override suspend fun load(): AppPreferences = stored
        override suspend fun save(preferences: AppPreferences) {}
        override suspend fun update(transform: (AppPreferences) -> AppPreferences) {}
    }

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildAgent(mode: AgentGreetingMode): Pair<OrpheusAgent, () -> Unit> {
        val appScope = AppCoroutineScope(dispatcherProvider)
        val featureScope = FeatureCoroutineScope()
        val modelProvider = AiModelProvider(prefs, dispatcherProvider, appScope)
        val config = OrpheusAgentConfig(toolSet = emptySet(), aiModelProvider = modelProvider)
        val agent = OrpheusAgent(
            config = config,
            aiKeyRepository = AiKeyRepository(prefs, dispatcherProvider),
            aiModelProvider = modelProvider,
            replCodeEventBus = ReplCodeEventBus(),
            agentActivityEventBus = AgentActivityEventBus(),
            dispatcherProvider = dispatcherProvider,
            scope = featureScope,
            greetingMode = mode,
        )
        return agent to {
            featureScope.cancel()
            appScope.cancel()
        }
    }

    @Test
    fun `ON_FIRST_PROMPT stays silent until a prompt is sent`() = runTest(dispatcher) {
        val (agent, teardown) = buildAgent(AgentGreetingMode.ON_FIRST_PROMPT)
        try {
            // Drain everything the construction scheduled: getKey resolves, then the run-loop
            // suspends on userIntent.first(). No prompt is ever sent here.
            advanceUntilIdle()

            val state = agent.agentFlow.value
            // Still Loading: runAgent (and thus any LLM request) was never reached.
            assertTrue(
                state is AgentState.Loading,
                "ON_FIRST_PROMPT must stay in Loading before a prompt; was $state"
            )
            // No user/agent turn was produced — only the initial placeholder message exists.
            assertTrue(
                state.messages.none { it.type == ChatMessageType.User || it.type == ChatMessageType.Agent },
                "opening the panel must not produce any chat turn; messages=${state.messages.map { it.type }}"
            )
        } finally {
            teardown()
        }
    }
}
