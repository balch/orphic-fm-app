package org.balch.orpheus.features.pulsar.score

import java.io.File
import java.util.Locale
import javax.sound.midi.MidiSystem
import kotlin.test.Test

/**
 * Prints a per-part summary of a MIDI file so a human can pick which track Task 9 should
 * import — `meanPolyphony` decides phase-A eligibility (must be monophonic), and
 * `velocityStdDev` says whether the source carries written dynamics at all.
 *
 * Run with a caller-supplied path:
 * ```
 * ./gradlew :features:pulsar:inspectScore -Pmidi=/path/to/score.mid
 * ```
 * A plain `jvmTest` run leaves `orpheus.score.midi` unset, so this must stay a no-op then —
 * it never fails a build that didn't ask for a report.
 */
class InspectScoreReportTest {

    @Test
    fun printScoreSummary() {
        val path = System.getProperty("orpheus.score.midi")
        if (path.isNullOrBlank()) return

        val parts = MidiScoreImporter.inspect(path)
        println()
        println("MIDI score summary: $path")
        println(
            String.format(
                Locale.ROOT,
                "%-4s %-24s %-5s %-7s %-5s %-5s %-10s %-6s %-10s",
                "Trk", "Name", "Prog", "Notes", "Low", "High", "MeanPoly", "Peak", "VelSDev",
            ),
        )
        parts.forEach { p ->
            println(
                String.format(
                    Locale.ROOT,
                    "%-4d %-24s %-5d %-7d %-5d %-5d %-10.2f %-6d %-10.2f",
                    p.midiTrack,
                    p.name.take(24),
                    p.program,
                    p.noteCount,
                    p.lowPitch,
                    p.highPitch,
                    p.meanPolyphony,
                    p.peakPolyphony,
                    p.velocityStdDev,
                ),
            )
        }

        // Separate from the table: no combination of per-part columns yields this. Summing
        // peaks assumes they coincide; taking the max assumes they never do.
        val sequence = MidiSystem.getSequence(File(path))
        println()
        println("Ensemble peak (all tracks at once) — the voice-pool floor:")
        listOf(0.0 to "note-off (floor)", 0.25 to "+1/16 tail", 0.5 to "+1/8 tail", 1.0 to "+1/4 tail")
            .forEach { (quarters, label) ->
                val peak = MidiScoreImporter.peakEnsemblePolyphony(
                    sequence, releaseQuarters = quarters,
                )
                println(String.format(Locale.ROOT, "  %-20s %d voices", label, peak))
            }
        println(
            "  A pool freed at note-off cuts release tails; size against a realistic tail, " +
                "not the floor.",
        )

        // How many parts fit a given pool? The all-tracks figure only answers "play
        // everything literally". When a generative band covers the rest, the real question
        // is how far down this curve a written-parts budget reaches.
        println()
        println("Cumulative ensemble peak, adding parts busiest-first:")
        println(String.format(Locale.ROOT, "  %-6s %-24s %-8s %s", "parts", "added track", "notes", "peak"))
        val ranked = parts.filter { it.noteCount > 0 }.sortedByDescending { it.noteCount }
        val chosen = mutableListOf<Int>()
        ranked.forEach { p ->
            chosen += p.midiTrack
            val peak = MidiScoreImporter.peakEnsemblePolyphony(sequence, chosen.toList())
            println(
                String.format(
                    Locale.ROOT, "  %-6d %-24s %-8d %d",
                    chosen.size, "${p.midiTrack} ${p.name.take(16)}", p.noteCount, peak,
                ),
            )
        }
    }
}
