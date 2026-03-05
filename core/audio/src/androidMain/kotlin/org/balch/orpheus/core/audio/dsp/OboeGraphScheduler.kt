package org.balch.orpheus.core.audio.dsp

import com.diamondedge.logging.logging
import kotlin.math.tanh

/**
 * Interface for units that participate in the Oboe render graph.
 */
interface OboeProcessable {
    fun process(numFrames: Int)
    fun allocateBuffers(maxFrames: Int)
    fun getInputPorts(): List<OboeAudioInput>
    fun getOutputPorts(): List<OboeAudioOutput> = emptyList()

    /** When false, the scheduler skips process() and zeros output buffers.
     *  Thread-safe: written from UI thread, read from audio thread. */
    var enabled: Boolean
        get() = true
        set(_) {}
}

/**
 * Runs the audio graph in topological order.
 * Called from the Oboe audio thread via JNI -> OboeAudioEngine.renderAudio().
 */
class OboeGraphScheduler {
    private val units = mutableListOf<OboeProcessable>()
    private val unitSet = HashSet<OboeProcessable>() // dedup guard
    private var maxFrames = 0

    val masterLeft = OboeAudioInput("MasterLeft")
    val masterRight = OboeAudioInput("MasterRight")

    val unitCount: Int get() = units.size

    fun addUnit(unit: OboeProcessable) {
        if (unitSet.add(unit)) {
            units.add(unit)
        }
    }

    /**
     * Sort units in topological order using Tarjan's SCC algorithm.
     * Handles feedback cycles by grouping them into Strongly Connected Components,
     * sorting the condensed DAG, then expanding SCCs in original insertion order.
     *
     * Must be called after all units are registered and all connections are wired.
     */
    fun sortTopologically() {
        val n = units.size
        if (n <= 1) return

        // 1. Build output → unit index map and adjacency list
        val outputOwner = HashMap<OboeAudioOutput, Int>(n * 2)
        val unitIndex = HashMap<OboeProcessable, Int>(n * 2)
        for (i in 0 until n) {
            val unit = units[i]
            unitIndex[unit] = i
            for (port in unit.getOutputPorts()) {
                outputOwner[port] = i
            }
        }

        val adj = Array(n) { mutableListOf<Int>() } // forward edges
        for (i in 0 until n) {
            for (input in units[i].getInputPorts()) {
                for (source in input.getSources()) {
                    val upIdx = outputOwner[source] ?: continue
                    if (upIdx != i) adj[upIdx].add(i)
                }
            }
        }

        // 2. Tarjan's SCC algorithm
        val sccId = IntArray(n) { -1 }     // which SCC each unit belongs to
        val disc = IntArray(n) { -1 }       // discovery time
        val low = IntArray(n)               // low-link value
        val onStack = BooleanArray(n)
        val stack = ArrayDeque<Int>(n)
        var timer = 0
        var sccCount = 0

        fun tarjanDfs(u: Int) {
            disc[u] = timer
            low[u] = timer
            timer++
            stack.addLast(u)
            onStack[u] = true

            for (v in adj[u]) {
                if (disc[v] == -1) {
                    tarjanDfs(v)
                    if (low[v] < low[u]) low[u] = low[v]
                } else if (onStack[v]) {
                    if (disc[v] < low[u]) low[u] = disc[v]
                }
            }

            if (low[u] == disc[u]) {
                // u is root of an SCC — pop all members
                val id = sccCount++
                while (true) {
                    val w = stack.removeLast()
                    onStack[w] = false
                    sccId[w] = id
                    if (w == u) break
                }
            }
        }

        for (i in 0 until n) {
            if (disc[i] == -1) tarjanDfs(i)
        }

        // 3. Build condensed DAG and sort with Kahn's
        val sccAdj = Array(sccCount) { mutableSetOf<Int>() }
        val sccInDegree = IntArray(sccCount)
        for (u in 0 until n) {
            for (v in adj[u]) {
                val su = sccId[u]
                val sv = sccId[v]
                if (su != sv && sccAdj[su].add(sv)) {
                    sccInDegree[sv]++
                }
            }
        }

        val sccQueue = ArrayDeque<Int>(sccCount)
        for (s in 0 until sccCount) {
            if (sccInDegree[s] == 0) sccQueue.add(s)
        }
        val sccOrder = ArrayList<Int>(sccCount)
        while (sccQueue.isNotEmpty()) {
            val s = sccQueue.removeFirst()
            sccOrder.add(s)
            for (dep in sccAdj[s]) {
                sccInDegree[dep]--
                if (sccInDegree[dep] == 0) sccQueue.add(dep)
            }
        }

        // 4. Expand SCCs: emit each SCC's units in original insertion order
        //    Group units by SCC, preserving insertion order within each group
        val sccMembers = Array(sccCount) { mutableListOf<Int>() }
        for (i in 0 until n) sccMembers[sccId[i]].add(i)

        val sorted = ArrayList<OboeProcessable>(n)
        for (s in sccOrder) {
            // Members are already in insertion order (we iterate 0..n-1 above)
            for (idx in sccMembers[s]) {
                sorted.add(units[idx])
            }
        }

        // Count cycles for diagnostics
        var cycleSccs = 0
        var cycleUnits = 0
        for (s in 0 until sccCount) {
            if (sccMembers[s].size > 1) {
                cycleSccs++
                cycleUnits += sccMembers[s].size
            }
        }

        units.clear()
        units.addAll(sorted)
        log.info { "sortTopologically: $n units, $sccCount SCCs ($cycleSccs feedback cycles containing $cycleUnits units)" }
    }

    fun allocate(framesPerBuffer: Int) {
        maxFrames = framesPerBuffer
        log.info { "allocate: framesPerBuffer=$framesPerBuffer, ${units.size} units registered" }
        masterLeft.allocate(maxFrames)
        masterRight.allocate(maxFrames)
        for (unit in units) {
            unit.allocateBuffers(maxFrames)
        }
        log.info { "allocate complete: all buffers allocated" }
    }

    /**
     * Process the entire audio graph for one buffer.
     * MUST be allocation-free — called from audio thread.
     */
    fun process(outputBuffer: FloatArray, numFrames: Int) {
        if (maxFrames == 0) return // Not yet allocated — skip silently

        // 1. Prepare inputs and process all units in topological order.
        //    Disabled units are skipped — their output buffers were zeroed
        //    on disable, so downstream units read zeros via prepare().
        for (unit in units) {
            if (unit.enabled) {
                for (input in unit.getInputPorts()) {
                    input.prepare(numFrames)
                }
                unit.process(numFrames)
            }
        }
        masterLeft.prepare(numFrames)
        masterRight.prepare(numFrames)

        // 2. Soft-clip and interleave master L/R into output buffer
        val left = masterLeft.getBuffer()
        val right = masterRight.getBuffer()
        for (i in 0 until numFrames) {
            outputBuffer[i * 2] = tanh(left[i] * MASTER_GAIN)
            outputBuffer[i * 2 + 1] = tanh(right[i] * MASTER_GAIN)
        }
    }

    companion object {
        private val log = logging("OboeGraphScheduler")
        /** Pre-limiter attenuation (~6dB) to keep signals in the linear region of tanh. */
        private const val MASTER_GAIN = 0.5f
    }
}
