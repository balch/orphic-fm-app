// Copyright 2016 Emilie Gillet.
// Ported to Kotlin by Orpheus project. Original: MIT License.
// Port of plaits/dsp/speech/lpc_speech_synth_controller.h (LPCSpeechSynthWordBank)

package org.balch.orpheus.plugins.plaits.speech

import org.balch.orpheus.plugins.plaits.PlaitsSpeechData

/**
 * Decodes bit-packed LPC-10 frames from word bank byte arrays.
 * Each bank contains multiple words; frame/word boundaries are tracked.
 */
class LPCSpeechSynthWordBank {
    private var wordBanks = PlaitsSpeechData.WORD_BANKS
    private var numBanks = PlaitsSpeechData.NUM_WORD_BANKS
    private var loadedBank = -1
    var numFrames: Int = 0
        private set
    private var numWords = 0

    val frames = Array(PlaitsSpeechData.LPC_MAX_FRAMES) {
        PlaitsSpeechData.LpcFrame(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
    }
    private val wordBoundaries = IntArray(PlaitsSpeechData.LPC_MAX_WORDS)

    fun init() {
        reset()
    }

    fun reset() {
        loadedBank = -1
        numFrames = 0
        numWords = 0
        wordBoundaries.fill(0)
    }

    fun getWordBoundaries(address: Float): Pair<Int, Int> {
        if (numWords == 0) return Pair(-1, -1)
        var word = (address * numWords.toFloat()).toInt()
        if (word >= numWords) word = numWords - 1
        return Pair(wordBoundaries[word], wordBoundaries[word + 1] - 1)
    }

    fun load(bank: Int): Boolean {
        if (bank == loadedBank || bank >= numBanks) return false

        numFrames = 0
        numWords = 0

        val bankData = wordBanks[bank]
        val data = bankData.data
        var offset = 0
        var remaining = bankData.size

        while (remaining > 0) {
            wordBoundaries[numWords] = numFrames
            val consumed = loadNextWord(data, offset)
            offset += consumed
            remaining -= consumed
            numWords++
        }
        wordBoundaries[numWords] = numFrames
        loadedBank = bank
        return true
    }

    private fun loadNextWord(data: IntArray, startOffset: Int): Int {
        val bitstream = BitStream()
        bitstream.init(data, startOffset)

        var frameEnergy = 0
        var framePeriod = 0
        var frameK0 = 0; var frameK1 = 0
        var frameK2 = 0; var frameK3 = 0; var frameK4 = 0
        var frameK5 = 0; var frameK6 = 0; var frameK7 = 0
        var frameK8 = 0; var frameK9 = 0

        while (true) {
            val energy = bitstream.getBits(4)
            if (energy == 0) {
                frameEnergy = 0
            } else if (energy == 0x0F) {
                bitstream.flush()
                break
            } else {
                frameEnergy = PlaitsSpeechData.LPC_ENERGY_LUT[energy]
                val repeat = bitstream.getBits(1) != 0
                framePeriod = PlaitsSpeechData.LPC_PERIOD_LUT[bitstream.getBits(6)]
                if (!repeat) {
                    frameK0 = PlaitsSpeechData.LPC_K0_LUT[bitstream.getBits(5)]
                    frameK1 = PlaitsSpeechData.LPC_K1_LUT[bitstream.getBits(5)]
                    frameK2 = PlaitsSpeechData.LPC_K2_LUT[bitstream.getBits(4)]
                    frameK3 = PlaitsSpeechData.LPC_K3_LUT[bitstream.getBits(4)]
                    if (framePeriod != 0) {
                        frameK4 = PlaitsSpeechData.LPC_K4_LUT[bitstream.getBits(4)]
                        frameK5 = PlaitsSpeechData.LPC_K5_LUT[bitstream.getBits(4)]
                        frameK6 = PlaitsSpeechData.LPC_K6_LUT[bitstream.getBits(4)]
                        frameK7 = PlaitsSpeechData.LPC_K7_LUT[bitstream.getBits(3)]
                        frameK8 = PlaitsSpeechData.LPC_K8_LUT[bitstream.getBits(3)]
                        frameK9 = PlaitsSpeechData.LPC_K9_LUT[bitstream.getBits(3)]
                    }
                }
            }
            frames[numFrames++] = PlaitsSpeechData.LpcFrame(
                frameEnergy, framePeriod,
                frameK0, frameK1, frameK2, frameK3, frameK4,
                frameK5, frameK6, frameK7, frameK8, frameK9
            )
        }
        return bitstream.ptr - startOffset
    }
}
