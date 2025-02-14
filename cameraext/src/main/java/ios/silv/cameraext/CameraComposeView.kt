package ios.silv.cameraext

import android.content.Context
import android.content.ContextWrapper
import android.media.MediaCodec
import android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM
import android.media.MediaCodec.BufferInfo
import android.media.MediaCodec.CONFIGURE_FLAG_ENCODE
import android.media.MediaCodec.INFO_TRY_AGAIN_LATER
import android.media.MediaCodec.createEncoderByType
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

private const val TAG = "CameraComposeView.kt"

@Composable
@ReadOnlyComposable
internal fun ProvidableCompositionLocal<Context>.activityContext() =
    this.current.requireActivityContext()

internal tailrec fun Context.requireActivityContext(): Context = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.requireActivityContext()
    else -> error("context was not part of an activity")
}

data class CameraCapability(val camSelector: CameraSelector, val qualities: List<Quality>)

internal fun Quality.getNameString(): String {
    return when (this) {
        Quality.UHD -> "QUALITY_UHD(2160p)"
        Quality.FHD -> "QUALITY_FHD(1080p)"
        Quality.HD -> "QUALITY_HD(720p)"
        Quality.SD -> "QUALITY_SD(480p)"
        else -> throw IllegalArgumentException("Quality $this is NOT supported")
    }
}

internal fun Quality.getAspectRatioStrategy(): AspectRatioStrategy {
    val hdQualities = arrayOf(Quality.UHD, Quality.FHD, Quality.HD)
    return when {
        hdQualities.contains(this) -> AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
        this == Quality.SD -> AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
        else -> AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
    }
}

internal fun Quality.getAspectRatioString(portraitMode: Boolean): String {
    val hdQualities = arrayOf(Quality.UHD, Quality.FHD, Quality.HD)
    val ratio = when {
        hdQualities.contains(this) -> Pair(16, 9)
        this == Quality.SD -> Pair(4, 3)
        else -> throw UnsupportedOperationException()
    }

    return if (portraitMode) "V,${ratio.second}:${ratio.first}"
    else "H,${ratio.first}:${ratio.second}"
}


private fun loadCameraCapabilities(
    lifecycleOwner: LifecycleOwner, context: Context, loaded: (CameraCapability) -> Unit
): Deferred<Unit> = lifecycleOwner.lifecycleScope.async {
    Log.d(TAG, "getting provider")
    val provider = ProcessCameraProvider.awaitInstance(context)
    provider.unbindAll()

    Log.d(TAG, "got provider and unbound usecases")

    for (camSelector in arrayOf(
        CameraSelector.DEFAULT_BACK_CAMERA, CameraSelector.DEFAULT_FRONT_CAMERA
    )) {
        try {
            // just get the camera.cameraInfo to query capabilities
            // we are not binding anything here.
            if (provider.hasCamera(camSelector)) {
                val camera = provider.bindToLifecycle(lifecycleOwner, camSelector)
                val capabilities = Recorder.getVideoCapabilities(camera.cameraInfo)

                capabilities.getSupportedQualities(DynamicRange.UNSPECIFIED).filter { quality ->
                    listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD).contains(quality)
                }.also {
                    Log.d(TAG, "loaded cam selector")
                    loaded(CameraCapability(camSelector, it))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Camera Face $camSelector is not supported")
        }
    }
}

sealed interface CameraUiEvent {
    data class ChangeQuality(val idx: Int) : CameraUiEvent
    data class ChangeCamera(val idx: Int) : CameraUiEvent
}

private const val VIDEO_AVC = "video/avc"
private const val CODEC_TIMEOUT_US = 10000L
private val defaultMediaFormatBuilder: MediaFormat.() -> Unit = {
    setInteger(
        MediaFormat.KEY_COLOR_FORMAT,
        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
    )
    setInteger(MediaFormat.KEY_BIT_RATE, 2000000)
    setInteger(MediaFormat.KEY_FRAME_RATE, 30)
    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
}

private fun createEncoder(
    width: Int = 1920,
    height: Int = 1080,
    flags: Int = CONFIGURE_FLAG_ENCODE,
    mimeType: String = VIDEO_AVC,
    builder: MediaFormat.() -> Unit = defaultMediaFormatBuilder
): MediaCodec {
    // https://en.wikipedia.org/wiki/Advanced_Video_Coding
    return createEncoderByType(mimeType).apply {
        configure(
            MediaFormat
                .createVideoFormat(mimeType, width, height)
                .apply(builder),
            /*surface =*/null,
            /*crypto =*/ null,
            /*flags =*/flags
        )
    }
}

// https://en.wikipedia.org/wiki/Y%E2%80%B2UV
private fun ImageProxy.toNV21(): ByteArray {
    val yPlane = planes[0].buffer
    val uPlane = planes[1].buffer
    val vPlane = planes[2].buffer

    val ySize = yPlane.remaining()
    val uvSize = uPlane.remaining() + vPlane.remaining()

    val nv21 = ByteArray(ySize + uvSize)

    yPlane.get(nv21, 0, ySize)
    vPlane.get(nv21, ySize, vPlane.remaining()) // V
    uPlane.get(nv21, ySize + vPlane.remaining(), uPlane.remaining()) // U

    return nv21
}

// https://stuff.mit.edu/afs/sipb/project/android/docs/reference/android/media/MediaCodec.html
private fun MediaCodec.encodeFrame(yuvData: ByteArray) {
    val inputBufferIndex = dequeueInputBuffer(CODEC_TIMEOUT_US)
    if (inputBufferIndex >= 0) {
        val inputBuffer = getInputBuffer(inputBufferIndex) ?: return
        inputBuffer.clear()
        inputBuffer.put(yuvData)
        queueInputBuffer(
            /*index */ inputBufferIndex,
            /*offset*/0,
            /*size*/yuvData.size,
            /*presentationTimeUs*/ System.nanoTime() / 1000,
            /*flags*/0
        )
    }
}

// https://github.com/lykhonis/MediaCodec/blob/master/app/src/main/java/com/vladli/android/mediacodec/VideoEncoder.java
private suspend fun MediaCodec.listenForEncodedFrames(frameCh: SendChannel<ByteArray>) = coroutineScope {
    val bufferInfo = BufferInfo()
    while(true) {
        try {
            val status = dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)
            when {
                status == INFO_TRY_AGAIN_LATER -> {
                    ensureActive()
                    delay(1)
                }
                status > 0 -> {
                    val outputBuffer = getOutputBuffer(status) ?: continue
                    val outData = ByteArray(bufferInfo.size)
                    outputBuffer.get(outData)
                    val eos = bufferInfo.flags and BUFFER_FLAG_END_OF_STREAM == BUFFER_FLAG_END_OF_STREAM

                    if (!eos) {
                        val result = frameCh.trySend(outData)
                        Log.i(TAG, "tried sending frame: $status success: ${result.isSuccess}")
                    }
                    releaseOutputBuffer(status, false)

                    if (eos) {
                        signalEndOfInputStream()
                        frameCh.close()
                        break
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        }
    }
}

class CameraState internal constructor(
    private val context: Context,
    internal val lifecycleOwner: LifecycleOwner,
    private val frameCh: SendChannel<ByteArray>
) {

    private val lifecycleScope: LifecycleCoroutineScope = lifecycleOwner.lifecycleScope
    private lateinit var captureView: PreviewView
    private lateinit var cameraProvider: ProcessCameraProvider
    internal val encoder = createEncoder()

    var deferredCapabilities: Deferred<Unit>? = null

    var cameraIndex by mutableIntStateOf(0)
    var qualityIndex by mutableIntStateOf(0)
    var uiEnabled by mutableStateOf(false)

    val cameraCapabilities = mutableStateListOf<CameraCapability>()

    private val mainThreadExecutor by lazy { ContextCompat.getMainExecutor(context) }

    init {
        deferredCapabilities = loadCameraCapabilities(lifecycleOwner, context) { capabilities ->
            cameraCapabilities.add(capabilities)
        }
    }

    internal fun startFrameListener() = lifecycleScope.launch {
        encoder.listenForEncodedFrames(frameCh)
    }

    internal suspend fun bindCaptureUseCase(previewView: PreviewView) {
        Log.d(TAG, "running bindCaptureUseCase")
        this.captureView = previewView

        deferredCapabilities?.await()

        cameraProvider = ProcessCameraProvider.awaitInstance(context)
        val cameraSelector = cameraCapabilities[cameraIndex].camSelector

        val quality = cameraCapabilities[cameraIndex].qualities[qualityIndex]
        val qualitySelector = QualitySelector.from(quality)
        Log.d(TAG, qualitySelector.toString())
        Log.d(TAG, cameraSelector.toString())

        val preview = Preview.Builder().setResolutionSelector(
            ResolutionSelector.Builder().setAspectRatioStrategy(
                quality.getAspectRatioStrategy()
            ).build()
        ).build()
            .apply {
                Log.d(TAG, "Set surfaceProvider")
                surfaceProvider = previewView.surfaceProvider
            }

        // https://developer.android.com/media/camera/camerax/analyze#operating-modes
        val analyzer = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        analyzer.setAnalyzer(mainThreadExecutor) { imageProxy ->
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            Log.i(
                TAG,
                "received a frame h:${imageProxy.height}, w:${imageProxy.width}, r:${rotationDegrees}"
            )
            val nv21 = imageProxy.toNV21()
            encoder.encodeFrame(nv21)
            imageProxy.close()
        }

        try {
            Log.d(TAG, "unbinding previous and binding to lifecycle")
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner, cameraSelector, preview, analyzer
            )
            Log.d(TAG, "unbound previous and bound to lifecycle")
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
            resetUiState()
        }
        uiEnabled = true
    }

    private fun resetUiState() {
        uiEnabled = true
        cameraIndex = 0
        qualityIndex = 0
    }

    fun handleEvent(event: CameraUiEvent) {
        Log.d(TAG, "handling event $event")
        when (event) {
            is CameraUiEvent.ChangeQuality -> {
                if (event.idx == qualityIndex || !uiEnabled) return

                qualityIndex = event.idx
                uiEnabled = false

                lifecycleScope.launch {
                    bindCaptureUseCase(captureView)
                }
            }

            is CameraUiEvent.ChangeCamera -> {
                if (cameraIndex == event.idx || !uiEnabled) return

                Log.d(TAG, "found capture view")
                cameraIndex = event.idx
                uiEnabled = false

                lifecycleScope.launch {
                    bindCaptureUseCase(captureView)
                }
            }
        }
    }
}


@Composable
fun rememberCameraState(
    frameCh: SendChannel<ByteArray>
): CameraState {

    val context = LocalContext.activityContext()
    val lifecycleOwner = LocalLifecycleOwner.current

    val chan by rememberUpdatedState(frameCh)

    return remember(context, lifecycleOwner, frameCh) {
        CameraState(context, lifecycleOwner, chan)
    }
}

@Composable
fun rememberCameraState(
    lifecycleOwner: LifecycleOwner,
    frameCh: SendChannel<ByteArray>,
): CameraState {

    val context = LocalContext.activityContext()

    val chan by rememberUpdatedState(frameCh)

    return remember(context, lifecycleOwner) {
        CameraState(context, lifecycleOwner, chan)
    }
}

@Composable
fun CameraComposeView(
    modifier: Modifier = Modifier,
    cameraState: CameraState
) {
    DisposableEffect(cameraState) {
        cameraState.encoder.start()
        val job = cameraState.startFrameListener()
        onDispose {
            job.cancel()
            cameraState.encoder.stop()
            cameraState.encoder.release()
        }
    }

    Box(
        modifier = modifier
    ) {
        AndroidView(
            modifier = Modifier.matchParentSize(),
            factory = { context ->
                PreviewView(context).also { view ->
                    cameraState.lifecycleOwner.lifecycleScope.launch {
                        cameraState.lifecycleOwner.repeatOnLifecycle(Lifecycle.State.CREATED) {
                            Log.d(TAG, "starting camera")

                            if (cameraState.deferredCapabilities != null) {
                                cameraState.deferredCapabilities!!.await()
                                cameraState.deferredCapabilities = null
                            }

                            Log.d(TAG, "finished loading capabilities")
                            cameraState.bindCaptureUseCase(view)
                        }
                    }
                }
            },
        )
        CameraControls(
            state = cameraState,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .fillMaxWidth()
                .height(200.dp)
        )
    }
}

@Composable
private fun CameraControls(
    modifier: Modifier, state: CameraState
) {

    val selectorStrings by remember {
        derivedStateOf {
            state.cameraCapabilities.getOrNull(state.cameraIndex)?.qualities?.map {
                it.getNameString()
            } ?: emptyList()
        }
    }

    Row(
        modifier = modifier
    ) {
        LazyColumn(
            modifier = Modifier
                .height(200.dp)
                .weight(1f)
        ) {
            itemsIndexed(selectorStrings) { i, quality ->
                FilterChip(selected = (i == state.qualityIndex),
                    enabled = state.uiEnabled,
                    onClick = {
                        state.handleEvent(CameraUiEvent.ChangeQuality(i))
                    },
                    label = { Text(quality) })
            }
        }
        Button(enabled = state.uiEnabled, onClick = {
            val idx = if (state.cameraIndex == 0) state.cameraCapabilities.lastIndex else 0
            state.handleEvent(CameraUiEvent.ChangeCamera(idx))
        }) {
            Text("flip")
        }
    }
}