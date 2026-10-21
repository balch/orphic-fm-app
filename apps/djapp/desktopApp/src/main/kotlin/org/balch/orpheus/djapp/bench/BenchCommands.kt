package org.balch.orpheus.djapp.bench

import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.bench.CaptureWriter
import org.balch.orpheus.features.pulsar.bench.Macro
import org.balch.orpheus.features.pulsar.bench.MixBenchController
import org.balch.orpheus.features.pulsar.bench.Scope
import org.balch.orpheus.features.pulsar.bench.capture

/** Thrown for a command the bench refuses; the message goes back to the page. */
internal class BenchCommandException(message: String) : Exception(message)

/**
 * Runs `POST /cmd` bodies one at a time. Level and sends take what the playing section should hear;
 * the controller picks the layer under its lock, so the page never needs to know which one.
 */
internal class BenchCommands(
    private val controller: MixBenchController,
    private val pulsar: PulsarFeature,
    private val transport: BenchTransport,
    private val captures: CaptureWriter,
    private val uiDispatcher: CoroutineDispatcher,
    private val baselineHash: () -> String?,
) {
    /** The last capture file written, for the page. */
    @Volatile var lastCapture: File? = null
        private set

    // One command at a time, in arrival order, so overlapping fetches can't reorder edits.
    private val serial = Mutex()

    suspend fun run(cmd: JsonObject): JsonObject = serial.withLock { dispatch(cmd) }

    private suspend fun dispatch(cmd: JsonObject): JsonObject {
        val op = cmd.str("op")
        when (op) {
            "play" -> withContext(uiDispatcher) { transport.play() }
            "pause" -> withContext(uiDispatcher) { transport.pause() }
            "vibe" -> {
                val name = cmd.str("name")
                val ok = withContext(uiDispatcher) { pulsar.applyVibeByName(name) }
                if (!ok) throw BenchCommandException("no vibe named \"$name\"")
            }
            "jump" -> controller.jumpTo(cmd.int("section"))
            "loop" -> controller.setLoop(cmd.bool("on"))
            "level" -> controller.setHeardLevelDb(cmd.track(), cmd.float("db"))
            "pan" -> controller.setPan(cmd.track(), cmd.float("v"))
            "reverb" -> setSend(cmd.track(), cmd.float("v"), reverb = true)
            "delay" -> setSend(cmd.track(), cmd.float("v"), reverb = false)
            "macro" -> controller.setMacro(cmd.enum<Macro>("m"), cmd.float("v"))
            "mute" -> controller.toggleMute(cmd.track())
            "solo" -> controller.toggleSolo(cmd.track())
            "scope" -> controller.setScope(cmd.enum<Scope>("s"))
            "reset" -> controller.resetToFile()
            "capture" -> return capture()
            else -> throw BenchCommandException("unknown op \"$op\"")
        }
        return OK
    }

    private fun capture(): JsonObject {
        val vibe = pulsar.vibeFlow.value
        val rows = capture(vibe, controller.layers.value)
        val written = captures.write(vibe, rows, baselineHash() ?: "")
        lastCapture = written.file
        return buildJsonObject {
            put("ok", true)
            put("file", written.file.absolutePath)
            put("rows", BenchJson.rows(rows))
            put("stale", written.capture.stale)
        }
    }

    private suspend fun setSend(t: Int, v: Float, reverb: Boolean) {
        if (!controller.setHeardSend(t, v, reverb)) {
            throw BenchCommandException("this send is 0 here, so a whole-vibe move can't scale it; edit it in section scope")
        }
    }

    private companion object {
        val OK = buildJsonObject { put("ok", true) }

        fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.content ?: throw BenchCommandException("missing \"$k\"")
        fun JsonObject.int(k: String) = prim(k).int
        fun JsonObject.float(k: String) = prim(k).float
        fun JsonObject.bool(k: String) = prim(k).boolean
        fun JsonObject.prim(k: String) = this[k]?.jsonPrimitive ?: throw BenchCommandException("missing \"$k\"")

        fun JsonObject.track(): Int {
            val t = int("t")
            if (t !in 0 until 8) throw BenchCommandException("track $t out of range")
            return t
        }

        inline fun <reified E : Enum<E>> JsonObject.enum(k: String): E {
            val v = str(k).uppercase()
            return enumValues<E>().firstOrNull { it.name == v } ?: throw BenchCommandException("bad \"$k\": $v")
        }
    }
}
