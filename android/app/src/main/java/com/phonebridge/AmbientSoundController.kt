package com.phonebridge

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

object AmbientToneGenerator {
    fun generate(sampleRate: Int, durationSeconds: Int): ShortArray {
        require(sampleRate in 8_000..48_000)
        require(durationSeconds in 1..8)
        val count = sampleRate * durationSeconds
        val frequencies = doubleArrayOf(110.0, 165.0, 220.0, 275.0)
        val gains = doubleArrayOf(.46, .30, .16, .08)
        return ShortArray(count) { index ->
            val time = index.toDouble() / sampleRate
            val value = frequencies.indices.sumOf { tone ->
                sin(2.0 * PI * frequencies[tone] * time) * gains[tone]
            }
            (value * 8_000.0).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }
}

/** Generates a quiet local pad; it owns audio only while explicitly enabled in the visible stage. */
class AmbientSoundController(context: Context) : AutoCloseable {
    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)
    private var track: AudioTrack? = null
    private var focusRequest: AudioFocusRequest? = null

    fun start() {
        if (track?.playState == AudioTrack.PLAYSTATE_PLAYING) return
        val manager = audioManager ?: return
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { change -> if (change <= AudioManager.AUDIOFOCUS_LOSS) stop() }
            .build()
        if (manager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        focusRequest = focus
        val sampleRate = 22_050
        val samples = AmbientToneGenerator.generate(sampleRate, durationSeconds = 4)
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val created = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * Short.SIZE_BYTES)
                .build()
        }.getOrNull()
        if (created == null || created.state != AudioTrack.STATE_INITIALIZED) {
            created?.release()
            stop()
            return
        }
        val written = created.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
        if (written != samples.size || created.setLoopPoints(0, samples.size, -1) != AudioTrack.SUCCESS) {
            created.release()
            stop()
            return
        }
        created.setVolume(.07f)
        created.play()
        track = created
    }

    fun stop() {
        track?.let { current ->
            runCatching { if (current.playState == AudioTrack.PLAYSTATE_PLAYING) current.pause() }
            runCatching { current.flush() }
            runCatching { current.release() }
        }
        track = null
        val request = focusRequest
        if (request != null) runCatching { audioManager?.abandonAudioFocusRequest(request) }
        focusRequest = null
    }

    override fun close() = stop()
}
