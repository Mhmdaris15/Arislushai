package app.arislushai

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Streams a 16 kHz mono PCM16 WAV file as float samples in [-1, 1].
 * Convert other formats first, e.g.:
 *   ffmpeg -i lecture.m4a -ac 1 -ar 16000 -c:a pcm_s16le input.wav
 */
class WavReader(file: File) : Closeable {
    private val input = BufferedInputStream(file.inputStream(), 1 shl 16)
    var totalSamples: Long = 0
        private set
    private var remainingBytes: Long = 0

    init {
        val riff = ByteArray(12)
        require(input.readNBytes(riff, 0, 12) == 12 && String(riff, 0, 4) == "RIFF") { "Not a RIFF/WAV file" }
        var formatOk = false
        while (true) {
            val hdr = ByteArray(8)
            require(input.readNBytes(hdr, 0, 8) == 8) { "No data chunk found" }
            val id = String(hdr, 0, 4)
            val size = ByteBuffer.wrap(hdr, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
            if (id == "fmt ") {
                val fmt = ByteArray(size.toInt())
                require(input.readNBytes(fmt, 0, fmt.size) == fmt.size) { "Truncated fmt chunk" }
                val b = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                val tag = b.short.toInt() and 0xFFFF
                val channels = b.short.toInt()
                val rate = b.int
                b.int; b.short
                val bits = b.short.toInt()
                require(tag == 1 && channels == 1 && rate == 16000 && bits == 16) {
                    "Need PCM16 mono 16000 Hz (got tag=$tag ch=$channels rate=$rate bits=$bits). Convert with ffmpeg."
                }
                formatOk = true
            } else if (id == "data") {
                require(formatOk) { "data chunk before fmt chunk" }
                remainingBytes = size
                totalSamples = size / 2
                break
            } else {
                input.skipNBytes(size + (size and 1L))
            }
        }
    }

    /** Returns up to [count] samples, or null at end of data. */
    fun read(count: Int): FloatArray? {
        if (remainingBytes <= 0) return null
        val want = minOf(count.toLong() * 2, remainingBytes).toInt()
        val bytes = ByteArray(want)
        val got = input.readNBytes(bytes, 0, want)
        if (got <= 1) return null
        remainingBytes -= got
        val bb = ByteBuffer.wrap(bytes, 0, got - (got % 2)).order(ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(got / 2)
        for (i in out.indices) out[i] = bb.short / 32768f
        return out
    }

    override fun close() = input.close()
}

/** Writes PCM16 mono 16 kHz audio and patches the WAV header on close. */
class WavWriter(file: File) : Closeable {
    private val raf = RandomAccessFile(file, "rw")
    private var dataBytes = 0L

    init {
        raf.setLength(0)
        raf.write(ByteArray(44))
    }

    fun write(samples: FloatArray, count: Int = samples.size) {
        val bb = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) {
            bb.putShort((samples[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        }
        raf.write(bb.array())
        dataBytes += count * 2L
    }

    override fun close() {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt((36 + dataBytes).toInt()).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(dataBytes.toInt())
        raf.seek(0)
        raf.write(h.array())
        raf.close()
    }
}
