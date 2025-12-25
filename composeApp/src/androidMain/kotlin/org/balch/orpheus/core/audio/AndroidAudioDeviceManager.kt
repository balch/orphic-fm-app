package org.balch.orpheus.core.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.jsyn.devices.AudioDeviceInputStream
import com.jsyn.devices.AudioDeviceManager
import com.jsyn.devices.AudioDeviceOutputStream

/**
 * Android-specific AudioDeviceManager implementation for JSyn.
 * Uses Android's AudioTrack API for audio output.
 */
class AndroidAudioDeviceManager : AudioDeviceManager {
    
    override fun createInputStream(deviceID: Int, frameRate: Int, samplesPerFrame: Int): AudioDeviceInputStream {
        // Input not supported for now (no recording)
        throw UnsupportedOperationException("Audio input not supported on Android")
    }

    override fun createOutputStream(deviceID: Int, frameRate: Int, samplesPerFrame: Int): AudioDeviceOutputStream {
        return AndroidAudioOutputStream(frameRate, samplesPerFrame)
    }

    override fun getDefaultInputDeviceID(): Int = 0
    override fun getDefaultOutputDeviceID(): Int = 0
    override fun getDeviceCount(): Int = 1
    override fun getName(): String = "Android Audio Manager"
    override fun getDeviceName(deviceID: Int): String = "Android Audio"
    override fun getMaxInputChannels(deviceID: Int): Int = 0
    override fun getMaxOutputChannels(deviceID: Int): Int = 2
    
    override fun getDefaultLowInputLatency(deviceID: Int): Double = 0.0
    override fun getDefaultHighInputLatency(deviceID: Int): Double = 0.0
    override fun getDefaultLowOutputLatency(deviceID: Int): Double = 0.020 // 20ms estimate
    override fun getDefaultHighOutputLatency(deviceID: Int): Double = 0.100 // 100ms estimate
    
    override fun setSuggestedOutputLatency(latency: Double): Int {
        // Android manages its own latency
        return 0
    }

    override fun setSuggestedInputLatency(latency: Double): Int {
        // Not implemented
        return 0
    }
}

/**
 * AudioDeviceOutputStream implementation using Android's AudioTrack.
 */
private class AndroidAudioOutputStream(
    private val frameRate: Int,
    private val samplesPerFrame: Int
) : AudioDeviceOutputStream {
    
    private var audioTrack: AudioTrack? = null
    private val bufferSizeInBytes: Int
    private val shortBuffer: ShortArray
    
    init {
        // Calculate buffer size
        val minBufferSize = AudioTrack.getMinBufferSize(
            frameRate,
            if (samplesPerFrame == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        
        // Use 2x min buffer size for better performance
        bufferSizeInBytes = minBufferSize * 2
        
        // Allocate conversion buffer (from double to short)
        val framesInBuffer = bufferSizeInBytes / (samplesPerFrame * 2) // 2 bytes per short
        shortBuffer = ShortArray(framesInBuffer * samplesPerFrame)
    }

    override fun start() {
        if (audioTrack == null) {
            val channelConfig = if (samplesPerFrame == 2) {
                AudioFormat.CHANNEL_OUT_STEREO
            } else {
                AudioFormat.CHANNEL_OUT_MONO
            }
            
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(frameRate)
                        .setChannelMask(channelConfig)
                        .build()
                )
                .setBufferSizeInBytes(bufferSizeInBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            
            audioTrack?.play()
        }
    }

    override fun write(value: Double) {
        // Single sample write - less efficient, but required by interface
        write(doubleArrayOf(value), 0, 1)
    }

    override fun write(buffer: DoubleArray) {
        write(buffer, 0, buffer.size)
    }

    override fun write(buffer: DoubleArray, start: Int, count: Int) {
        val track = audioTrack ?: return
        
        // Convert double samples [-1.0, 1.0] to short samples [-32768, 32767]
        val samplesToWrite = minOf(count, shortBuffer.size)
        for (i in 0 until samplesToWrite) {
            val sample = buffer[start + i]
            // Clamp and convert to short
            val clamped = sample.coerceIn(-1.0, 1.0)
            shortBuffer[i] = (clamped * 32767.0).toInt().toShort()
        }
        
        // Write to AudioTrack
        track.write(shortBuffer, 0, samplesToWrite, AudioTrack.WRITE_BLOCKING)
    }

    override fun getLatency(): Double {
        // Estimate based on buffer size
        return (bufferSizeInBytes / (frameRate * samplesPerFrame * 2.0))
    }

    override fun stop() {
        audioTrack?.stop()
    }

    override fun close() {
        audioTrack?.release()
        audioTrack = null
    }
}
