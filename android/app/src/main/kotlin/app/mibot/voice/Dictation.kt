package app.mibot.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * Records what the person says so the hub can turn it into text for the message box. It keeps the
 * sound in memory as a WAV file: a spoken message is short.
 */
class Dictation(private val context: Context) {
    private var record: AudioRecord? = null
    private val sound = ByteArrayOutputStream()
    @Volatile private var running = false
    private var reader: Thread? = null

    @Volatile var level = 0f
        private set

    @SuppressLint("MissingPermission")
    fun start() {
        if (!hasMicrophone(context)) throw IllegalStateException("MiBot needs the microphone to take dictation.")
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE))
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("The microphone could not be opened.")
        }
        record = rec
        sound.reset()
        running = true
        rec.startRecording()
        reader = thread(name = "mibot-dictation") {
            val buf = ByteArray(RATE / 10 * 2)
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) {
                    level = loudness(buf, n)
                    // Five minutes is far more than a message; past that, stop keeping it.
                    if (sound.size() < RATE * 2 * 300) sound.write(buf, 0, n)
                } else if (n < 0) break
            }
        }
    }

    /** Stop and hand back the recording as a WAV file, or null when nothing was said. */
    fun finish(): ByteArray? {
        release()
        val pcm = sound.toByteArray()
        sound.reset()
        if (pcm.size < RATE / 2) return null // under a quarter of a second
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(RATE); putInt(RATE * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(pcm.size)
        }.array()
        return header + pcm
    }

    fun cancel() {
        release()
        sound.reset()
    }

    private fun release() {
        running = false
        runCatching { reader?.join(500) }
        reader = null
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        level = 0f
    }

    private companion object {
        const val RATE = 16_000
    }
}
