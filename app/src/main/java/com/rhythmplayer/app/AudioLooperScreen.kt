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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
    var isZoomedView by remember { mutableStateOf(false) }

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
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("تنظیم لوپ بدون مکث (Zero-Gap)", color = Color.White, fontSize = 20.sp)

        if (!isLoaded) {
            CircularProgressIndicator(color = Color.Cyan)
            Text("در حال پردازش و استخراج فایل در RAM...", color = Color.Gray, fontSize = 14.sp)
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isZoomedView) "نمای زوم شده (Focus)" else "نمای کامل فایل",
                    color = Color.LightGray,
                    fontSize = 14.sp
                )
                FilterChip(
                    selected = isZoomedView,
                    onClick = { isZoomedView = !isZoomedView },
                    label = { Text(if (isZoomedView) "نمایش کل فایل" else "زوم روی محدوده") }
                )
            }

            InteractiveWaveformDisplay(
                waveformData = waveformData,
                startMs = startMs,
                endMs = endMs,
                totalMs = engine.totalDurationMs,
                isZoomed = isZoomedView,
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
                Text("شروع: ${startMs}ms", color = Color.Green, fontSize = 13.sp)
                Text("پایان: ${endMs}ms", color = Color.Red, fontSize = 13.sp)
                Text("طول لوپ: ${endMs - startMs}ms", color = Color.Yellow, fontSize = 13.sp)
            }

            Divider(color = Color.DarkGray, thickness = 1.dp)

            FineTuneControls(
                title = "تنظیم دقیق شروع (Start)",
                color = Color.Green,
                onAdjust = { delta ->
                    val next = (startMs + delta).coerceIn(0L, endMs - 50L)
                    startMs = next
                    if (isPlaying) engine.playLoop(startMs, endMs)
                }
            )

            FineTuneControls(
                title = "تنظیم دقیق پایان (End)",
                color = Color.Red,
                onAdjust = { delta ->
                    val next = (endMs + delta).coerceIn(startMs + 50L, engine.totalDurationMs)
                    endMs = next
                    if (isPlaying) engine.playLoop(startMs, endMs)
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

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
                    .height(54.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (isPlaying) Color(0xFFE53935) else Color(0xFF00ACC1)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(if (isPlaying) "توقف پخش" else "پخش لوپ بدون مکث", color = Color.White, fontSize = 18.sp)
            }
        }
    }
}

@Composable
fun FineTuneControls(title: String, color: Color, onAdjust: (Long) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = color, fontSize = 13.sp)
        Spacer(modifier = Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = { onAdjust(-500) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C2C2C)), contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("-500ms", fontSize = 11.sp)
            }
            Button(onClick = { onAdjust(-50) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3D3D3D)), contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("-50ms", fontSize = 11.sp)
            }
            Button(onClick = { onAdjust(-5) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A4A4A)), contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("-5ms", fontSize = 11.sp)
            }
            Button(onClick = { onAdjust(5) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A4A4A)), contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("+5ms", fontSize = 11.sp)
            }
            Button(onClick = { onAdjust(50) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3D3D3D)), contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("+50ms", fontSize = 11.sp)
            }
            Button(onClick = { onAdjust(500) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C2C2C)), contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("+500ms", fontSize = 11.sp)
            }
        }
    }
}

@Composable
fun InteractiveWaveformDisplay(
    waveformData: List<Float>,
    startMs: Long,
    endMs: Long,
    totalMs: Long,
    isZoomed: Boolean,
    onSeek: (Long, Long) -> Unit
) {
    var draggingHandle by remember { mutableStateOf<String?>(null) } // "start" or "end"

    val displayStartMs = if (isZoomed) startMs else 0L
    val displayEndMs = if (isZoomed) endMs else totalMs
    val displayDuration = max(1L, displayEndMs - displayStartMs)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp)
            .background(Color(0xFF1E1E1E), shape = RoundedCornerShape(8.dp))
            .pointerInput(startMs, endMs, totalMs, isZoomed) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val width = size.width.toFloat()
                        val startX = ((startMs - displayStartMs).toFloat() / displayDuration) * width
                        val endX = ((endMs - displayStartMs).toFloat() / displayDuration) * width

                        val touchX = offset.x
                        val startDist = Math.abs(touchX - startX)
                        val endDist = Math.abs(touchX - endX)

                        draggingHandle = if (startDist < endDist && startDist < 80f) {
                            "start"
                        } else if (endDist < 80f) {
                            "end"
                        } else if (touchX < startX) {
                            "start"
                        } else {
                            "end"
                        }
                    },
                    onDragEnd = { draggingHandle = null },
                    onDragCancel = { draggingHandle = null },
                    onDrag = { change, _ ->
                        val width = size.width.toFloat()
                        val touchX = change.position.x.coerceIn(0f, width)
                        val draggedMs = displayStartMs + ((touchX / width) * displayDuration).toLong()

                        if (draggingHandle == "start") {
                            val newStart = draggedMs.coerceIn(0L, endMs - 20L)
                            onSeek(newStart, endMs)
                        } else if (draggingHandle == "end") {
                            val newEnd = draggedMs.coerceIn(startMs + 20L, totalMs)
                            onSeek(startMs, newEnd)
                        }
                    }
                )
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (waveformData.isEmpty() || totalMs == 0L) return@Canvas

            val width = size.width
            val height = size.height
            val centerY = height / 2

            val startSampleIndex = ((displayStartMs.toFloat() / totalMs) * waveformData.size).toInt().coerceIn(0, waveformData.size - 1)
            val endSampleIndex = ((displayEndMs.toFloat() / totalMs) * waveformData.size).toInt().coerceIn(startSampleIndex + 1, waveformData.size)
            val visiblePeaks = waveformData.subList(startSampleIndex, endSampleIndex)

            if (visiblePeaks.isNotEmpty()) {
                val barWidth = width / visiblePeaks.size
                visiblePeaks.forEachIndexed { i, amplitude ->
                    val x = i * barWidth
                    val barHeight = amplitude * height
                    drawLine(
                        color = Color(0xFF555555),
                        start = Offset(x, centerY - barHeight / 2),
                        end = Offset(x, centerY + barHeight / 2),
                        strokeWidth = max(1f, barWidth)
                    )
                }
            }

            val startX = ((startMs - displayStartMs).toFloat() / displayDuration) * width
            val endX = ((endMs - displayStartMs).toFloat() / displayDuration) * width

            val activeLeft = startX.coerceIn(0f, width)
            val activeRight = endX.coerceIn(0f, width)
            if (activeRight > activeLeft) {
                drawRect(
                    color = Color.Cyan.copy(alpha = 0.25f),
                    topLeft = Offset(activeLeft, 0f),
                    size = Size(activeRight - activeLeft, height)
                )
            }

            if (startX in 0f..width) {
                drawLine(
                    color = Color.Green,
                    start = Offset(startX, 0f),
                    end = Offset(startX, height),
                    strokeWidth = 4.dp.toPx()
                )
            }

            if (endX in 0f..width) {
                drawLine(
                    color = Color.Red,
                    start = Offset(endX, 0f),
                    end = Offset(endX, height),
                    strokeWidth = 4.dp.toPx()
                )
            }
        }

        // دستگیره لمسی خط سبز (Start Handle)
        val displayStartX = ((startMs - displayStartMs).toFloat() / displayDuration)
        if (displayStartX in 0f..1f) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = (displayStartX * 300).dp - 14.dp, y = 4.dp)
                    .size(28.dp)
                    .background(Color.Green, shape = CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("S", color = Color.Black, fontSize = 12.sp)
            }
        }

        // دستگیره لمسی خط قرمز (End Handle)
        val displayEndX = ((endMs - displayStartMs).toFloat() / displayDuration)
        if (displayEndX in 0f..1f) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .offset(x = (displayEndX * 300).dp - 14.dp, y = (-4).dp)
                    .size(28.dp)
                    .background(Color.Red, shape = CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("E", color = Color.White, fontSize = 12.sp)
            }
        }
    }
}
