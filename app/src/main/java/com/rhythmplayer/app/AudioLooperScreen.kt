package com.rhythmplayer.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioLooperScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var audioBytes by remember { mutableStateOf<ByteArray?>(null) }
    var sampleRate by remember { mutableStateOf(44100) }
    var channels by remember { mutableStateOf(2) }
    
    var startPosition by remember { mutableStateOf(0f) }
    var endPosition by remember { mutableStateOf(1f) }
    var isPlaying by remember { mutableStateOf(false) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            coroutineScope.launch(Dispatchers.IO) {
                val data = loadPcmFromUri(context, it)
                if (data != null) {
                    withContext(Dispatchers.Main) {
                        audioBytes = data
                        startPosition = 0f
                        endPosition = 1f
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Audio Looper") }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Button(onClick = { filePickerLauncher.launch("audio/*") }) {
                Text("Select Audio File")
            }

            audioBytes?.let { bytes ->
                WaveformDisplay(
                    bytes = bytes,
                    startPos = startPosition,
                    endPos = endPosition,
                    onRangeChange = { newStart, newEnd ->
                        startPosition = newStart
                        endPosition = newEnd
                    }
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Button(
                        onClick = {
                            isPlaying = !isPlaying
                            if (isPlaying) {
                                coroutineScope.launch(Dispatchers.IO) {
                                    playLoop(bytes, sampleRate, channels, startPosition, endPosition) {
                                        isPlaying = false
                                    }
                                }
                            }
                        }
                    ) {
                        Text(if (isPlaying) "Stop" else "Play Loop")
                    }

                    Button(
                        onClick = {
                            coroutineScope.launch {
                                saveLoopToFile(context, bytes, startPosition, endPosition, sampleRate, channels)
                            }
                        }
                    ) {
                        Text("Save Loop")
                    }
                }
            } ?: run {
                Text("No audio file selected.")
            }
        }
    }
}

@Composable
fun WaveformDisplay(
    bytes: ByteArray,
    startPos: Float,
    endPos: Float,
    onRangeChange: (Float, Float) -> Unit
) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
            .background(Color.DarkGray)
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    val width = size.width.toFloat()
                    val touchX = change.position.x.coerceIn(0f, width)
                    val fraction = touchX / width

                    if (kotlin.math.abs(fraction - startPos) < kotlin.math.abs(fraction - endPos)) {
                        onRangeChange(min(fraction, endPos - 0.05f), endPos)
                    } else {
                        onRangeChange(startPos, max(fraction, startPos + 0.05f))
                    }
                }
            }
    ) {
        val width = size.width
        val height = size.height
        val startX = startPos * width
        val endX = endPos * width

        drawRect(
            color = Color.Yellow.copy(alpha = 0.3f),
            topLeft = Offset(startX, 0f),
            size = Size(endX - startX, height)
        )

        drawLine(
            color = Color.Green,
            start = Offset(startX, 0f),
            end = Offset(startX, height),
            strokeWidth = 4f
        )

        drawLine(
            color = Color.Red,
            start = Offset(endX, 0f),
            end = Offset(endX, height),
            strokeWidth = 4f
        )
    }
}

private fun loadPcmFromUri(context: Context, uri: Uri): ByteArray? {
    return try {
        val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
        inputStream?.readBytes()
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

private fun playLoop(
    bytes: ByteArray,
    sampleRate: Int,
    channels: Int,
    startPos: Float,
    endPos: Float,
    onComplete: () -> Unit
) {
    val totalFrames = bytes.size / 2
    val startByte = ((startPos * totalFrames).toInt() * 2).coerceIn(0, bytes.size)
    val endByte = ((endPos * totalFrames).toInt() * 2).coerceIn(startByte, bytes.size)
    val length = endByte - startByte

    if (length <= 0) {
        onComplete()
        return
    }

    val channelConfig = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
    val bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)

    val audioTrack = AudioTrack.Builder()
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
        .setBufferSizeInBytes(max(bufferSize, length))
        .setTransferMode(AudioTrack.MODE_STATIC)
        .build()

    audioTrack.write(bytes, startByte, length)
    audioTrack.play()
}

private suspend fun saveLoopToFile(
    context: Context,
    bytes: ByteArray,
    startPos: Float,
    endPos: Float,
    sampleRate: Int,
    channels: Int
) {
    withContext(Dispatchers.IO) {
        try {
            val totalFrames = bytes.size / 2
            val startByte = ((startPos * totalFrames).toInt() * 2).coerceIn(0, bytes.size)
            val endByte = ((endPos * totalFrames).toInt() * 2).coerceIn(startByte, bytes.size)
            val length = endByte - startByte

            val outputFile = File(context.cacheDir, "loop_output.pcm")
            FileOutputStream(outputFile).use { fos ->
                fos.write(bytes, startByte, length)
            }

            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Loop saved to cache!", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Failed to save loop", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
