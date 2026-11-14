package com.hqrec.app

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class AudioMode { BOTH, INTERNAL, MIC, NONE }

data class RecConfig(
    val width: Int,
    val height: Int,
    val dpi: Int,
    val fps: Int,
    val bitrate: Int,
    val audioMode: AudioMode,
    val hevc: Boolean,
    val codecName: String
)

/**
 * Hardware MediaCodec (GPU/encoder chip) use karta hai, isliye CPU par load kam padta hai
 * aur low-RAM phones par bhi game lag nahi karta.
 * Video: Surface -> H.264/H.265 encoder. Audio: game sound (AudioPlaybackCapture) + mic -> AAC.
 */
class ScreenRecorder(
    private val ctx: Context,
    private val projection: MediaProjection,
    private val cfg: RecConfig
) {
    private class Pending(val kind: Int, val data: ByteBuffer, val info: MediaCodec.BufferInfo)

    private val sampleRate = 44100
    private val hasAudio = cfg.audioMode != AudioMode.NONE

    private var vEnc: MediaCodec? = null
    private var aEnc: MediaCodec? = null
    private var vd: VirtualDisplay? = null
    private var muxer: MediaMuxer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var uri: Uri? = null
    private var internalRec: AudioRecord? = null
    private var micRec: AudioRecord? = null

    private val lock = Object()
    private val pending = ArrayList<Pending>()
    private var vTrack = -1
    private var aTrack = -1
    private var muxStarted = false

    @Volatile private var running = false
    private var started = false
    private var startUs = 0L
    private var vThread: Thread? = null
    private var aThread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start() {
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "HQREC_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/HQRecorder")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("File bana nahi paye")
        pfd = resolver.openFileDescriptor(uri!!, "w")
        muxer = MediaMuxer(pfd!!.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        // ---------- VIDEO ----------
        val mime = if (cfg.hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        val vf = MediaFormat.createVideoFormat(mime, cfg.width, cfg.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, cfg.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, cfg.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            setInteger(MediaFormat.KEY_OPERATING_RATE, cfg.fps)
        }
        val venc = MediaCodec.createByCodecName(cfg.codecName)
        venc.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = venc.createInputSurface()
        venc.start()
        vEnc = venc

        // ---------- AUDIO ----------
        if (hasAudio) {
            val chStereo = AudioFormat.CHANNEL_IN_STEREO
            val enc = AudioFormat.ENCODING_PCM_16BIT
            if (cfg.audioMode == AudioMode.INTERNAL || cfg.audioMode == AudioMode.BOTH) {
                val pc = AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()
                val minB = AudioRecord.getMinBufferSize(sampleRate, chStereo, enc)
                internalRec = AudioRecord.Builder()
                    .setAudioFormat(
                        AudioFormat.Builder().setEncoding(enc).setSampleRate(sampleRate)
                            .setChannelMask(chStereo).build()
                    )
                    .setBufferSizeInBytes(maxOf(minB * 2, 16384))
                    .setAudioPlaybackCaptureConfig(pc)
                    .build()
            }
            if (cfg.audioMode == AudioMode.MIC || cfg.audioMode == AudioMode.BOTH) {
                val minB = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, enc)
                micRec = AudioRecord(
                    MediaRecorder.AudioSource.MIC, sampleRate,
                    AudioFormat.CHANNEL_IN_MONO, enc, maxOf(minB * 2, 8192)
                )
            }
            val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 2).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 192000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            val aenc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            aenc.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            aenc.start()
            aEnc = aenc
        }

        running = true
        started = true
        startUs = System.nanoTime() / 1000

        vd = projection.createVirtualDisplay(
            "HQRec", cfg.width, cfg.height, cfg.dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null
        )

        vThread = Thread({ try { videoLoop() } catch (_: Exception) {} }, "vid-drain").also { it.start() }
        if (hasAudio) {
            aThread = Thread({ try { audioLoop() } catch (_: Exception) {} }, "aud-loop").also { it.start() }
        }
    }

    // ---------------- muxer helpers ----------------
    private fun tryStartMuxer() {
        if (muxStarted || vTrack < 0 || (hasAudio && aTrack < 0)) return
        muxer!!.start()
        muxStarted = true
        for (p in pending) {
            muxer!!.writeSampleData(if (p.kind == 0) vTrack else aTrack, p.data, p.info)
        }
        pending.clear()
    }

    private fun writeSample(kind: Int, buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        synchronized(lock) {
            val m = muxer ?: return
            buf.position(info.offset)
            buf.limit(info.offset + info.size)
            if (muxStarted) {
                m.writeSampleData(if (kind == 0) vTrack else aTrack, buf, info)
            } else {
                val copy = ByteBuffer.allocate(info.size)
                copy.put(buf)
                copy.flip()
                val i = MediaCodec.BufferInfo()
                i.set(0, info.size, info.presentationTimeUs, info.flags)
                pending.add(Pending(kind, copy, i))
            }
        }
    }

    // ---------------- video ----------------
    private fun videoLoop() {
        val enc = vEnc ?: return
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = enc.dequeueOutputBuffer(info, 10000)
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                synchronized(lock) {
                    vTrack = muxer!!.addTrack(enc.outputFormat)
                    tryStartMuxer()
                }
            } else if (idx >= 0) {
                val buf = enc.getOutputBuffer(idx)
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                if (info.size > 0 && buf != null) {
                    info.presentationTimeUs = maxOf(0L, info.presentationTimeUs - startUs)
                    writeSample(0, buf, info)
                }
                enc.releaseOutputBuffer(idx, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        }
    }

    // ---------------- audio ----------------
    private fun readFully(rec: AudioRecord, arr: ShortArray, n: Int): Boolean {
        var off = 0
        while (off < n && running) {
            val r = rec.read(arr, off, n - off)
            if (r <= 0) return false
            off += r
        }
        return off == n
    }

    private fun drainAudio(enc: MediaCodec, info: MediaCodec.BufferInfo, timeoutUs: Long): Boolean {
        while (true) {
            val idx = enc.dequeueOutputBuffer(info, timeoutUs)
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                synchronized(lock) {
                    aTrack = muxer!!.addTrack(enc.outputFormat)
                    tryStartMuxer()
                }
            } else if (idx >= 0) {
                val buf = enc.getOutputBuffer(idx)
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                if (info.size > 0 && buf != null) writeSample(1, buf, info)
                enc.releaseOutputBuffer(idx, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
            } else {
                return false
            }
        }
    }

    private fun audioLoop() {
        val enc = aEnc ?: return
        val chunk = 1024
        val stereo = ShortArray(chunk * 2)
        val mono = ShortArray(chunk)
        val info = MediaCodec.BufferInfo()
        var frames = 0L
        var base = -1L
        internalRec?.startRecording()
        micRec?.startRecording()

        while (running) {
            val ir = internalRec
            val mr = micRec
            if (ir != null && !readFully(ir, stereo, chunk * 2)) break
            if (mr != null && !readFully(mr, mono, chunk)) break

            if (ir == null && mr != null) {
                for (i in 0 until chunk) { stereo[2 * i] = mono[i]; stereo[2 * i + 1] = mono[i] }
            } else if (ir != null && mr != null) {
                for (i in 0 until chunk) {
                    val m = mono[i].toInt()
                    stereo[2 * i] = (stereo[2 * i] + m).coerceIn(-32768, 32767).toShort()
                    stereo[2 * i + 1] = (stereo[2 * i + 1] + m).coerceIn(-32768, 32767).toShort()
                }
            }

            if (base < 0) base = System.nanoTime() / 1000 - startUs - chunk * 1_000_000L / sampleRate
            val pts = base + frames * 1_000_000L / sampleRate
            frames += chunk

            var tries = 0
            var idx = enc.dequeueInputBuffer(10000)
            while (idx < 0 && tries++ < 50) {
                drainAudio(enc, info, 0)
                idx = enc.dequeueInputBuffer(10000)
            }
            if (idx >= 0) {
                val ib = enc.getInputBuffer(idx)!!
                ib.clear()
                ib.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(stereo, 0, chunk * 2)
                enc.queueInputBuffer(idx, 0, chunk * 4, maxOf(0L, pts), 0)
            }
            drainAudio(enc, info, 0)
        }

        try { internalRec?.stop() } catch (_: Exception) {}
        try { micRec?.stop() } catch (_: Exception) {}

        // EOS bhejo
        var idx = enc.dequeueInputBuffer(100000)
        if (idx >= 0) {
            val pts = base.coerceAtLeast(0L) + frames * 1_000_000L / sampleRate
            enc.queueInputBuffer(idx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }
        val deadline = System.currentTimeMillis() + 2000
        while (System.currentTimeMillis() < deadline) {
            if (drainAudio(enc, info, 10000)) break
        }
    }

    // ---------------- stop ----------------
    fun stop() {
        if (!started) return
        started = false
        running = false
        try { vd?.release() } catch (_: Exception) {}
        try { vEnc?.signalEndOfInputStream() } catch (_: Exception) {}
        try { vThread?.join(3000) } catch (_: Exception) {}
        try { aThread?.join(3000) } catch (_: Exception) {}
        try { vEnc?.stop() } catch (_: Exception) {}
        try { vEnc?.release() } catch (_: Exception) {}
        try { aEnc?.stop() } catch (_: Exception) {}
        try { aEnc?.release() } catch (_: Exception) {}
        synchronized(lock) {
            try { if (muxStarted) muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            muxStarted = false
        }
        try { internalRec?.release() } catch (_: Exception) {}
        try { micRec?.release() } catch (_: Exception) {}
        try { pfd?.close() } catch (_: Exception) {}
        uri?.let {
            val v = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            try { ctx.contentResolver.update(it, v, null, null) } catch (_: Exception) {}
        }
    }
}
