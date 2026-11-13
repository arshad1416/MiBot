package app.mibot.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import androidx.core.content.ContextCompat
import app.mibot.core.VoiceAudio
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.sqrt

/** How loud a chunk of 16-bit sound is, 0 to 1, shaped so that speech fills most of the range. */
internal fun loudness(pcm: ByteArray, length: Int = pcm.size): Float {
    var sum = 0.0
    var n = 0
    var i = 0
    while (i + 1 < length) {
        val s = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
        sum += s.toDouble() * s
        n++
        i += 2
    }
    if (n == 0) return 0f
    return (sqrt(sum / n) / 32768.0 * 6.0).coerceIn(0.0, 1.0).toFloat()
}

fun hasMicrophone(context: Context) = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

/**
 * The phone's microphone and speaker for a voice call. The call is set up as a phone call would be
 * (communication mode, the voice microphone), so the phone removes the bot's own voice from what
 * the microphone hears and the person can talk over it.
 */
class PhoneAudio(private val context: Context) : VoiceAudio {
    private val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private val queue = LinkedBlockingQueue<ByteArray>()
    @Volatile private var running = false
    /** Frames handed to the speaker since it was last emptied. */
    @Volatile private var written = 0L
    @Volatile private var lastWriteAt = 0L

    @Volatile override var micLevel = 0f
        private set
    @Volatile override var outLevel = 0f
        private set

    @SuppressLint("MissingPermission")
    override fun start(sampleRate: Int, onPcm: (ByteArray) -> Unit) {
        if (!hasMicrophone(context)) throw IllegalStateException("MiBot needs the microphone for a voice call.")
        manager.mode = AudioManager.MODE_IN_COMMUNICATION
        routeToSpeaker()

        val chunk = sampleRate / 25 * 2 // 40 ms
        val minIn = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minIn, chunk * 4))
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            stop()
            throw IllegalStateException("The microphone could not be opened.")
        }
        if (AcousticEchoCanceler.isAvailable()) runCatching { AcousticEchoCanceler.create(rec.audioSessionId)?.enabled = true }
        if (NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(rec.audioSessionId)?.enabled = true }

        val minOut = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val out = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(minOut, chunk * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        record = rec
        track = out
        running = true
        rec.startRecording()
        out.play()

        thread(name = "mibot-mic") {
            val buf = ByteArray(chunk)
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) {
                    micLevel = loudness(buf, n)
                    onPcm(buf.copyOf(n))
                } else if (n < 0) break
            }
        }
        thread(name = "mibot-speaker") {
            while (running) {
                val pcm = queue.poll(100, TimeUnit.MILLISECONDS)
                if (pcm == null) {
                    if (!playing) outLevel = 0f
                    continue
                }
                outLevel = loudness(pcm)
                val n = runCatching { out.write(pcm, 0, pcm.size) }.getOrDefault(-1)
                if (n > 0) {
                    written += n / 2
                    lastWriteAt = System.currentTimeMillis()
                }
            }
        }
    }

    /** The loudspeaker, unless a headset is there to take the call. */
    @Suppress("DEPRECATION")
    private fun routeToSpeaker() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = manager.availableCommunicationDevices
            val headset = devices.firstOrNull { it.type in HEADSETS }
            val target = headset ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            if (target != null) runCatching { manager.setCommunicationDevice(target) }
        } else manager.isSpeakerphoneOn = true
    }

    override fun play(pcm: ByteArray) {
        if (running) queue.offer(pcm)
    }

    override fun clearPlayback() {
        queue.clear()
        val out = track ?: return
        runCatching {
            out.pause()
            out.flush()
            written = 0
            out.play()
        }
    }

    override val playing: Boolean
        get() {
            if (queue.isNotEmpty()) return true
            val out = track ?: return false
            val head = runCatching { out.playbackHeadPosition.toLong() and 0xFFFFFFFFL }.getOrDefault(written)
            // What was written has not all come out yet. The time check covers a head that has stopped moving.
            return written - head > 480 && System.currentTimeMillis() - lastWriteAt < 4_000
        }

    @Suppress("DEPRECATION")
    override fun stop() {
        running = false
        queue.clear()
        runCatching { record?.stop() }
        runCatching { record?.release() }
        runCatching { track?.pause(); track?.flush(); track?.release() }
        record = null
        track = null
        micLevel = 0f
        outLevel = 0f
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) runCatching { manager.clearCommunicationDevice() }
        else manager.isSpeakerphoneOn = false
        manager.mode = AudioManager.MODE_NORMAL
    }

    private companion object {
        val HEADSETS = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLE_HEADSET,
        )
    }
}
