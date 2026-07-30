package com.rhythmplayer.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

class GaplessLoopEngine(private val context: Context) {

    private var audioTrack: AudioTrack? = null
    private var pcmData: ByteArray? = null
    private var sampleRate: Int = 44100
    private var channelCount: Int = 2
    var totalDurationMs: Long = 0
        private set

    suspend fun decodeAudioFile(uri: Uri, onWaveformReady: (List<Float>) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            var trackIndex = -1
            var format: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }

            if (trackIndex < 0 || format == null) return@withContext false

            extractor.selectTrack(trackIndex)
            sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val durationUs = format.getLong(MediaFormat.KEY_DURATION)
            totalDurationMs = durationUs / 1000

            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val byteList = mutableListOf<Byte>()
            val bufferInfo = MediaCodec.BufferInfo()
            var isEOS = false

            val waveformPeaks = mutableListOf<Float>()
            var sampleCounter = 0
            var maxAmplitude = 0f

            while (!isEOS) {
                val inIndex = codec.dequeueInputBuffer(10000)
                if (inIndex >= 0) {
                    val buffer = codec.getInputBuffer(inIndex)
                    if (buffer != null) {
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isEOS = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
                if (outIndex >= 0) {
                    val buffer = codec.getOutputBuffer(outIndex)
                    if (buffer != null && bufferInfo.size > 0) {
                        val chunk = ByteArray(bufferInfo.size)
                        buffer.get(chunk)
                        buffer.clear()
                        byteList.addAll(chunk.toList())

                        val shortBuffer = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        while (shortBuffer.hasRemaining()) {
                            val sample = Math.abs(shortBuffer.get().toInt())
                            if (sample > maxAmplitude) maxAmplitude = sample.toFloat()
                            sampleCounter++
                            if (sampleCounter >= sampleRate / 50) {
                                waveformPeaks.add(maxAmplitude / 32768f)
                                maxAmplitude = 0f
                                sampleCounter = 0
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                }
            }

            codec.stop()
            codec.release()
            extractor.release()

            pcmData = byteList.toByteArray()
            withContext(Dispatchers.Main) {
                onWaveformReady(waveformPeaks)
            }
            return@withContext true
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext false
        }
    }

    fun playLoop(startMs: Long, endMs: Long) {
        stop()
        val data = pcmData ?: return

        val bytesPerSample = 2 * channelCount
        val bytesPerSecond = sampleRate * bytesPerSample

        val startByte = max(0, ((startMs * bytesPerSecond) / 1000).toInt() / bytesPerSample * bytesPerSample)
        val endByte = min(data.size, ((endMs * bytesPerSecond) / 1000).toInt() / bytesPerSample * bytesPerSample)

        if (endByte <= startByte) return

        val length = endByte - startByte
        val slicedData = ByteArray(length)
        System.arraycopy(data, startByte, slicedData, 0, length)

        applyCrossfade(slicedData, sampleRate, channelCount)

        val channelConfig = if (channelCount == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO

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
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .build()
            )
            .setBufferSizeInBytes(length)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        audioTrack?.let { track ->
            track.write(slicedData, 0, length)
            track.setLoopPoints(0, length / bytesPerSample, -1)
            track.play()
        }
    }

    fun stop() {
        audioTrack?.let {
            if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                it.stop()
            }
            it.release()
        }
        audioTrack = null
    }

    private fun applyCrossfade(data: ByteArray, sampleRate: Int, channels: Int) {
        val fadeSamples = (sampleRate * 0.002).toInt()
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val totalSamples = buffer.capacity() / channels

        for (i in 0 until fadeSamples) {
            val factor = i.toFloat() / fadeSamples
            for (c in 0 until channels) {
                val indexStart = (i * channels) + c
                if (indexStart < buffer.capacity()) {
                    buffer.put(indexStart, (buffer.get(indexStart) * factor).toInt().toShort())
                }
                val indexEnd = ((totalSamples - 1 - i) * channels) + c
                if (indexEnd >= 0 && indexEnd < buffer.capacity()) {
                    buffer.put(indexEnd, (buffer.get(indexEnd) * factor).toInt().toShort())
                }
            }
        }
    }
}

@Composable
fun AudioLooperScreen(audioUri: Uri) {
    val context = LocalContext.current
    val engine = remember { GaplessLoopEngine(context) }

    var isLoaded by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var waveformData by remember { mutableStateOf<List<Float>>(emptyList()) }

    var startMs by remember { mutableLongStateOf(0L) }
    var endMs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(audioUri) {
        isLoaded = false
        val success = engine.decodeAudioFile(audioUri) { peaks ->
            waveformData = peaks
        }
        if (success) {
            startMs = 0L
            endMs = engine.totalDurationMs
            isLoaded = true
        }
    }

    DisposableEffect(Unit) {
        onDispose { engine.stop() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("تنظیم لوپ بدون مکث (Zero-Gap)", color = Color.White, fontSize = 20.sp)

        if (!isLoaded) {
            CircularProgressIndicator(color = Color.Cyan)
            Text("در حال پردازش و استخراج فایل در RAM...", color = Color.Gray, fontSize = 14.sp)
        } else {
            WaveformDisplay(
                waveformData = waveformData,
                startMs = startMs,
                endMs = endMs,
                totalMs = engine.totalDurationMs,
                onSeek = { newStart, newEnd ->
                    startMs = newStart
                    endMs = newEnd
                    if (isPlaying) engine.playLoop(startMs, endMs)
                }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("شروع: ${startMs}ms", color = Color.Green, fontSize = 14.sp)
                Text("پایان: ${endMs}ms", color = Color.Red, fontSize = 14.sp)
                Text("طول لوپ: ${endMs - startMs}ms", color = Color.Yellow, fontSize = 14.sp)
            }

            HorizontalDivider(color = Color.DarkGray)

            FineTuneControls(
                title = "فاین‌تیون نقطه شروع (Start)",
                color = Color.Green,
                onAdjust = { delta ->
                    val next = (startMs + delta).coerceIn(0L, endMs - 50L)
                    startMs = next
                    if (isPlaying) engine.playLoop(startMs, endMs)
                }
            )

            FineTuneControls(
                title = "فاین‌تیون نقطه پایان (End)",
                color = Color.Red,
                onAdjust = { delta ->
                    val next = (endMs + delta).coerceIn(startMs + 50L, engine.totalDurationMs)
                    endMs = next
                    if (isPlaying) engine.playLoop(startMs, endMs)
                }
            )

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = {
                    if (isPlaying) {
                        engine.stop()
                        isPlaying = false
                    } else {
                        engine.playLoop(startMs, endMs)
                        isPlaying = true
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (isPlaying) Color.Red else Color.Cyan),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(if (isPlaying) "توقف پخش" else "پخش لوپ بدون مکث", color = Color.Black, fontSize = 18.sp)
            }
        }
    }
}

@Composable
fun FineTuneControls(title: String, color: Color, onAdjust: (Long) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = color, fontSize = 14.sp)
        Spacer(modifier = Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onAdjust(-100) }, colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)) {
                Text("-100ms")
            }
            Button(onClick = { onAdjust(-10) }, colors = ButtonDefaults.buttonColors(containerColor = Color.Gray)) {
                Text("-10ms")
            }
            Button(onClick = { onAdjust(10) }, colors = ButtonDefaults.buttonColors(containerColor = Color.Gray)) {
                Text("+10ms")
            }
            Button(onClick = { onAdjust(100) }, colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)) {
                Text("+100ms")
            }
        }
    }
}

@Composable
fun WaveformDisplay(
    waveformData: List<Float>,
    startMs: Long,
    endMs: Long,
    totalMs: Long,
    onSeek: (Long, Long) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .background(Color(0xFF1E1E1E), shape = RoundedCornerShape(8.dp))
            .pointerInput(totalMs) {
                detectTapGestures { offset ->
                    val fraction = offset.x / size.width
                    val clickedMs = (fraction * totalMs).toLong()
                    if (Math.abs(clickedMs - startMs) < Math.abs(clickedMs - endMs)) {
                        onSeek(clickedMs.coerceAtMost(endMs - 50), endMs)
                    } else {
                        onSeek(startMs, clickedMs.coerceAtLeast(startMs + 50))
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (waveformData.isEmpty() || totalMs == 0L) return@Canvas

            val width = size.width
            val height = size.height
            val centerY = height / 2
            val barWidth = width / waveformData.size

            waveformData.forEachIndexed { i, amplitude ->
                val x = i * barWidth
                val barHeight = amplitude * height
                drawLine(
                    color = Color.DarkGray,
                    start = Offset(x, centerY - barHeight / 2),
                    end = Offset(x, centerY + barHeight / 2),
                    strokeWidth = barWidth
                )
            }

            val startX = (startMs.toFloat() / totalMs) * width
            val endX = (endMs.toFloat() / totalMs) * width

            drawRect(
                color = Color.Cyan.copy(alpha = 0.2f),
                topLeft = Offset(startX, 0f),
                size = Size(endX - startX, height)
            )

            drawLine(
                color = Color.Green,
                start = Offset(startX, 0f),
                end = Offset(startX, height),
                strokeWidth = 4.dp.toPx()
            )

            drawLine(
                color = Color.Red,
                start = Offset(endX, 0f),
                end = Offset(endX, height),
                strokeWidth = 4.dp.toPx()
            )
        }
    }
}
