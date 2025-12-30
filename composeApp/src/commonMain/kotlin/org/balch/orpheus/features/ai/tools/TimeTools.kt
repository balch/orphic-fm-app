package org.balch.orpheus.features.ai.tools

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable

/**
 * Provider for time-related tools.
 */
@SingleIn(AppScope::class)
class TimeTools @Inject constructor() {
    
    val currentDatetimeTool = CurrentDatetimeTool()
    
    /**
     * Tool for getting the current date and time.
     */
    class CurrentDatetimeTool(
        private val defaultTimeZone: TimeZone = TimeZone.currentSystemDefault(),
        private val clock: Clock = Clock.System,
    ) : Tool<CurrentDatetimeTool.Args, CurrentDatetimeTool.Result>(
        argsSerializer = Args.serializer(),
        resultSerializer = Result.serializer(),
        name = "current_datetime",
        description = "Get the current date and time in the specified timezone"
    ) {
        @Serializable
        data class Args(
            @property:LLMDescription("The timezone to get the current date and time in (e.g., 'UTC', 'America/New_York'). Defaults to system timezone.")
            val timezone: String? = null
        )

        @Serializable
        data class Result(
            val datetime: String,
            val date: String,
            val time: String,
            val timezone: String
        )

        override suspend fun execute(args: Args): Result {
            val zoneId = args.timezone?.let {
                try { TimeZone.of(it) }
                catch (_: Exception) { null }
            } ?: defaultTimeZone

            val now = clock.now()
            val localDateTime = now.toLocalDateTime(zoneId)
            val offset = zoneId.offsetAt(now)

            val time = localDateTime.time
            val timeStr = "${time.hour.toString().padStart(2, '0')}:${
                time.minute.toString().padStart(2, '0')
            }:${time.second.toString().padStart(2, '0')}"

            return Result(
                datetime = "${localDateTime.date}T$timeStr$offset",
                date = localDateTime.date.toString(),
                time = timeStr,
                timezone = zoneId.id
            )
        }
    }
}
