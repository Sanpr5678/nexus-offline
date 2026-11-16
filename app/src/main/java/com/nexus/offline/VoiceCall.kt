package com.nexus.offline

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Microphone capture and speaker playback for one call.
 * Audio is 8 kHz mono, 20 ms frames (160 samples), compressed with G.711 mu-law (160 bytes per frame).
 */
class VoiceCall(ctx: Context, private val onFrame: (ByteArray) -> Unit) {

    companion object {
        const val RATE = 8000
        const val FRAME = 160
        private val SILENCE: Byte = 0xFF.toByte()   // mu-law zero
    }

    @Volatile
    var muted = false

    @Volatile
    private var running = false
    private var rec: AudioRecord? = null
    private var track: AudioTrack? = null
    private var capThread: Thread? = null
    private var playThread: Thread? = null
    private val queue = LinkedBlockingQueue<ShortArray>(12)
    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var prevMode = AudioManager.MODE_NORMAL
    private var prevSpeaker = false

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        try {
            val minRec = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val minPlay = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minRec <= 0 || minPlay <= 0) return false

            val r = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minRec, FRAME * 2 * 8)
            )
            if (r.state != AudioRecord.STATE_INITIALIZED) {
                r.release()
                return false
            }
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minPlay, FRAME * 2 * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            prevMode = am.mode
            prevSpeaker = am.isSpeakerphoneOn
            am.mode = AudioManager.MODE_IN_COMMUNICATION

            rec = r
            track = t
            running = true
            r.startRecording()
            t.play()

            capThread = Thread({ captureLoop(r) }, "nexus-capture").also { it.start() }
            playThread = Thread({ playLoop(t) }, "nexus-play").also { it.start() }
            return true
        } catch (e: Exception) {
            stop()
            return false
        }
    }

    private fun captureLoop(r: AudioRecord) {
        val pcm = ShortArray(FRAME)
        while (running) {
            var off = 0
            while (off < FRAME && running) {
                val n = r.read(pcm, off, FRAME - off)
                if (n <= 0) {
                    try { Thread.sleep(5) } catch (e: InterruptedException) { return }
                    continue
                }
                off += n
            }
            if (!running) break
            val out = ByteArray(FRAME)
            if (muted) {
                for (i in 0 until FRAME) out[i] = SILENCE
            } else {
                for (i in 0 until FRAME) out[i] = linearToMulaw(pcm[i].toInt())
            }
            onFrame(out)
        }
    }

    private fun playLoop(t: AudioTrack) {
        while (running) {
            val f: ShortArray? = try {
                queue.poll(60, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                return
            }
            if (f == null) continue
            t.write(f, 0, f.size)
        }
    }

    fun play(mulaw: ByteArray) {
        if (!running) return
        val n = minOf(mulaw.size, FRAME)
        val s = ShortArray(n)
        for (i in 0 until n) s[i] = mulawToLinear(mulaw[i])
        if (!queue.offer(s)) {
            queue.poll()          // keep latency bounded: drop the oldest frame
            queue.offer(s)
        }
    }

    @Suppress("DEPRECATION")
    fun setSpeaker(on: Boolean) {
        try {
            am.isSpeakerphoneOn = on
        } catch (e: Exception) {
            // ignore
        }
    }

    @Suppress("DEPRECATION")
    fun stop() {
        running = false
        capThread?.interrupt()
        playThread?.interrupt()
        try { rec?.stop() } catch (e: Exception) { }
        try { rec?.release() } catch (e: Exception) { }
        try { track?.stop() } catch (e: Exception) { }
        try { track?.release() } catch (e: Exception) { }
        rec = null
        track = null
        queue.clear()
        try {
            am.isSpeakerphoneOn = prevSpeaker
            am.mode = prevMode
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun linearToMulaw(sample: Int): Byte {
        var s = sample
        val sign = if (s < 0) { s = -s; 0x80 } else 0
        if (s > 32635) s = 32635
        s += 0x84
        var exp = 7
        var mask = 0x4000
        while (exp > 0 && (s and mask) == 0) {
            exp--
            mask = mask shr 1
        }
        val mant = (s shr (exp + 3)) and 0x0F
        return (sign or (exp shl 4) or mant).inv().toByte()
    }

    private fun mulawToLinear(b: Byte): Short {
        val u = b.toInt().inv() and 0xFF
        val sign = u and 0x80
        val exp = (u shr 4) and 0x07
        val mant = u and 0x0F
        var s = ((mant shl 3) + 0x84) shl exp
        s -= 0x84
        return (if (sign != 0) -s else s).toShort()
    }
}
