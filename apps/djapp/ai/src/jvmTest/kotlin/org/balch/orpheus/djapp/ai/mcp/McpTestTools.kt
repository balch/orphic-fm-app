package org.balch.orpheus.djapp.ai.mcp

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import org.balch.orpheus.core.ai.ToolProvider
import org.balch.orpheus.djapp.ai.tools.reportProgress

@Serializable
enum class Flavor { SWEET, SOUR }

@LLMDescription("Nested settings.")
@Serializable
data class Inner(
    @property:LLMDescription("Inner gain.")
    val gain: Float,
    val tags: List<String> = emptyList(),
)

@LLMDescription("Every parameter kind.")
@Serializable
data class KindsArgs(
    @property:LLMDescription("A name.")
    val name: String,
    @property:LLMDescription("A count.")
    val count: Int,
    val ratio: Double,
    val on: Boolean,
    val flavor: Flavor,
    val steps: List<Int>,
    val inner: Inner,
    @property:LLMDescription("Optional track.")
    val track: Int? = null,
)

@Serializable
data class EchoArgs(
    @property:LLMDescription("Text to repeat.")
    val text: String,
    val times: Int = 1,
)

@Serializable
data class EchoResult(val ok: Boolean, val message: String, val echoed: String = "")

class KindsTool : Tool<KindsArgs, EchoResult>(
    argsType = typeToken<KindsArgs>(),
    resultType = typeToken<EchoResult>(),
    name = "kinds",
    description = "Takes every kind.",
) {
    override suspend fun execute(args: KindsArgs) = EchoResult(true, "ok", args.name)
}

/** Echoes [EchoArgs.text]; "boom" throws and "refuse" returns ok=false, as the real tools fail. */
class EchoTool(private val toolName: String = "echo") : Tool<EchoArgs, EchoResult>(
    argsType = typeToken<EchoArgs>(),
    resultType = typeToken<EchoResult>(),
    name = toolName,
    description = "Repeat text.",
) {
    override suspend fun execute(args: EchoArgs): EchoResult = when (args.text) {
        "boom" -> error("boom went the tool")
        "refuse" -> EchoResult(false, "track 9 out of range 0-7")
        else -> EchoResult(true, "ok", args.text.repeat(args.times))
    }
}

class FakeProvider(override val tool: Tool<*, *>) : ToolProvider

/** Reports 1 of 2 then 2 of 2, as a recording tool does, then echoes. */
class ProgressTool : Tool<EchoArgs, EchoResult>(
    argsType = typeToken<EchoArgs>(),
    resultType = typeToken<EchoResult>(),
    name = "progress",
    description = "Report progress.",
) {
    override suspend fun execute(args: EchoArgs): EchoResult {
        reportProgress(1.0, 2.0)
        reportProgress(2.0, 2.0, "done")
        return EchoResult(true, "ok", args.text)
    }
}

/** Takes [EchoArgs.times] seconds of real time, as a long capture does. */
class SlowTool : Tool<EchoArgs, EchoResult>(
    argsType = typeToken<EchoArgs>(),
    resultType = typeToken<EchoResult>(),
    name = "slow",
    description = "Wait, then echo.",
) {
    override suspend fun execute(args: EchoArgs): EchoResult {
        delay(args.times * 1_000L)
        return EchoResult(true, "ok", args.text)
    }
}
