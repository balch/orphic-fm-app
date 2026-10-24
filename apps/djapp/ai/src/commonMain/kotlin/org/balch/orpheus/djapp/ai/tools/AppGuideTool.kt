package org.balch.orpheus.djapp.ai.tools

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import kotlinx.serialization.Serializable
import org.balch.orpheus.core.ai.ToolProvider
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.features.ai.tools.loadDjAppGuide

@LLMDescription("How the DJ app works, for answering the user's how-to questions.")
@Serializable
data class AppGuideResult(
    @property:LLMDescription("The guide, in Markdown. Answer from it in plain words; name buttons as it does.")
    val guide: String,
)

@ContributesIntoSet(FeatureScope::class, binding = binding<ToolProvider>())
@Inject
class AppGuideTool : ToolProvider {
    override val tool: Tool<NoArgs, AppGuideResult> by lazy {
        object : Tool<NoArgs, AppGuideResult>(
            argsType = typeToken<NoArgs>(),
            resultType = typeToken<AppGuideResult>(),
            name = "dj_app_guide",
            description = """
                Call this when the user asks how the app works: the Mixer's controls, what its numbers (LUFS,
                pre-clip, past knee, corr, a strip's LU) mean and how to fix them, Reset to file, Capture, pins,
                the saved AI vibes list, or how an AI vibe becomes a vibe in the app's catalog.
                Returns the guide; answer from it, briefly.
            """.trimIndent(),
        ) {
            override suspend fun execute(args: NoArgs): AppGuideResult = AppGuideResult(loadDjAppGuide())
        }
    }
}
