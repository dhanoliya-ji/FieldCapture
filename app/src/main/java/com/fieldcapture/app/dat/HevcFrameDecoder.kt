package com.fieldcapture.app.dat

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue

/**
 * Decodes compressed HEVC VideoFrames from the DAT SDK onto a preview Surface via MediaCodec.
 */
class HevcFrameDecoder {

    companion object {
        private const val TAG = "HevcFrameDecoder"
        private const val DATA_QUEUE_CAPACITY = 100
        private val BLOCKED_DECODERS = setOf("OMX.Exynos.hevc.dec", "c2.mtk.hevc.decoder")
    }

    private class DecoderFrame(
        val data: ByteBuffer,
        val presentationTimeUs: Long,
        val isKeyFrame: Boolean,
        val isConfigFrame: Boolean,
    ) {
        val flags: Int
            get() {
                var mask = 0
                if (isKeyFrame) mask = mask or MediaCodec.BUFFER_FLAG_KEY_FRAME
                if (isConfigFrame) mask = mask or MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                return mask
            }
    }

    @Volatile private var decoder: MediaCodec? = null
    @Volatile private var decoderThread: HandlerThread? = null
    @Volatile private var decoderHandler: Handler? = null
    @Volatile private var outputSurface: Surface? = null
    @Volatile private var mediaFormat: MediaFormat? = null
    @Volatile private var cachedConfig: ByteBuffer? = null
    @Volatile private var active = false

    private val incomingQueue = LinkedBlockingQueue<DecoderFrame>(DATA_QUEUE_CAPACITY)
    private val availableInputBuffers = ArrayDeque<Int>()
    private val feedRunnable = Runnable { feedInputBuffers() }

    fun start(width: Int, height: Int, surface: Surface) {
        outputSurface = surface
        mediaFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, width, height).apply {
            setInteger(MediaFormat.KEY_FRAME_RATE, 24)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BIT_RATE, 750_000)
        }
        try {
            val thread = HandlerThread("FieldCaptureHevcDecoder", Process.THREAD_PRIORITY_DISPLAY).apply {
                start()
            }
            decoderThread = thread
            val handler = Handler(thread.looper)
            decoderHandler = handler

            val codec = createHevcCodec()
            decoder = codec
            codec.setCallback(
                object : MediaCodec.Callback() {
                    override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                        availableInputBuffers.addLast(index)
                        feedInputBuffers()
                    }

                    override fun onOutputBufferAvailable(
                        codec: MediaCodec,
                        index: Int,
                        info: MediaCodec.BufferInfo,
                    ) {
                        try {
                            codec.releaseOutputBuffer(index, outputSurface != null)
                        } catch (_: IllegalStateException) {
                        }
                    }

                    override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                        Log.e(TAG, "MediaCodec error: ${e.message}")
                    }

                    override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {}
                },
                handler,
            )
            codec.configure(mediaFormat, surface, null, 0)
            codec.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start HEVC decoder", e)
            stop()
        }
    }

    fun decodeFrame(data: ByteArray, presentationTimeUs: Long) {
        if (data.isEmpty() || decoder == null) return
        var index = findStartCode(data, 0)
        while (index < data.size) {
            val next = findStartCode(data, index + 3)
            val nalEnd = if (next < data.size) next else data.size
            val nalLen = nalEnd - index
            if (nalLen > 4) {
                val nalType = (data[index + 3].toInt() and 0x7E) ushr 1
                val isConfig = nalType == 32 || nalType == 33 || nalType == 34
                val isKey = nalType in 16..21
                val slice = ByteBuffer.wrap(data, index, nalLen)
                if (isConfig) {
                    cachedConfig = cloneBuffer(ByteBuffer.wrap(data))
                } else if (isKey && !active) {
                    active = true
                    cachedConfig?.let { cfg ->
                        enqueueFrame(DecoderFrame(cloneBuffer(cfg), 0L, isKeyFrame = false, isConfigFrame = true))
                    }
                }
                if (active || isConfig) {
                    enqueueFrame(DecoderFrame(cloneBuffer(slice), presentationTimeUs, isKey, isConfig))
                }
            }
            index = next
        }
    }

    fun stop() {
        active = false
        outputSurface = null
        incomingQueue.clear()
        availableInputBuffers.clear()
        try {
            decoder?.stop()
        } catch (_: Exception) {
        }
        try {
            decoder?.release()
        } catch (_: Exception) {
        }
        decoder = null
        decoderThread?.quitSafely()
        decoderThread = null
        decoderHandler = null
        cachedConfig = null
    }

    private fun enqueueFrame(frame: DecoderFrame) {
        if (incomingQueue.remainingCapacity() == 0) {
            incomingQueue.poll()
        }
        incomingQueue.offer(frame)
        decoderHandler?.post(feedRunnable)
    }

    private fun feedInputBuffers() {
        val codec = decoder ?: return
        while (availableInputBuffers.isNotEmpty() && incomingQueue.isNotEmpty()) {
            val index = availableInputBuffers.removeFirst()
            val frame = incomingQueue.poll() ?: break
            try {
                val inputBuffer = codec.getInputBuffer(index) ?: continue
                inputBuffer.clear()
                val copySize = minOf(inputBuffer.remaining(), frame.data.remaining())
                val bytes = ByteArray(copySize)
                frame.data.get(bytes)
                inputBuffer.put(bytes)
                codec.queueInputBuffer(index, 0, copySize, frame.presentationTimeUs, frame.flags)
            } catch (_: Exception) {
            }
        }
    }

    private fun createHevcCodec(): MediaCodec {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val software = list.codecInfos.firstOrNull { info ->
            !info.isEncoder &&
                info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true) } &&
                info.isSoftwareOnly &&
                info.name !in BLOCKED_DECODERS
        }
        return if (software != null) {
            MediaCodec.createByCodecName(software.name)
        } else {
            MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC)
        }
    }

    private fun findStartCode(data: ByteArray, from: Int): Int {
        var i = from
        while (i + 3 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                return if (i > 0 && data[i - 1] == 0.toByte()) i - 1 else i
            }
            i++
        }
        return data.size
    }

    private fun cloneBuffer(src: ByteBuffer): ByteBuffer {
        val dup = src.duplicate()
        val copy = ByteBuffer.allocateDirect(dup.remaining())
        copy.put(dup)
        copy.flip()
        return copy
    }
}
