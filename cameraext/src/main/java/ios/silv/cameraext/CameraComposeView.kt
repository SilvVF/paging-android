package ios.silv.cameraext

import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapabilities
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Label
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContentProviderCompat.requireContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

@Composable
@ReadOnlyComposable
fun ProvidableCompositionLocal<Context>.requireActivityContext(): Context = when (this.current) {
    is ComponentActivity -> this.current
    is ContextWrapper -> this.requireActivityContext()
    else -> error("context was not part of an activity")
}

private const val TAG = "CameraComposeView.kt"

data class CameraCapability(val camSelector: CameraSelector, val qualities: List<Quality>)

fun Quality.getNameString() :String {
    return when (this) {
        Quality.UHD -> "QUALITY_UHD(2160p)"
        Quality.FHD -> "QUALITY_FHD(1080p)"
        Quality.HD -> "QUALITY_HD(720p)"
        Quality.SD -> "QUALITY_SD(480p)"
        else -> throw IllegalArgumentException("Quality $this is NOT supported")
    }
}

private fun loadCameraCapabilities(
    lifecycleOwner: LifecycleOwner,
    context: Context,
    loaded: (CameraCapability) -> Unit
): Deferred<Unit> = lifecycleOwner.lifecycleScope.async {
    lifecycleOwner.repeatOnLifecycle(Lifecycle.State.CREATED) {
        val provider = ProcessCameraProvider.awaitInstance(context)

        provider.unbindAll()
        for (camSelector in arrayOf(
            CameraSelector.DEFAULT_BACK_CAMERA,
            CameraSelector.DEFAULT_FRONT_CAMERA
        )) {
            try {
                // just get the camera.cameraInfo to query capabilities
                // we are not binding anything here.
                if (provider.hasCamera(camSelector)) {
                    val camera = provider.bindToLifecycle(lifecycleOwner, camSelector)
                    val capabilities = Recorder.getVideoCapabilities(camera.cameraInfo)

                    capabilities.getSupportedQualities(DynamicRange.UNSPECIFIED)
                        .filter { quality ->
                            listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD)
                                .contains(quality)
                        }.also {
                            loaded(CameraCapability(camSelector, it))
                        }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Camera Face $camSelector is not supported")
            }
        }
    }
}

sealed interface CameraUiEvent {
    data class ChangeQuality(val idx: Int): CameraUiEvent
}

class CameraStateHolder(
    val context: Context,
    val lifecycleOwner: LifecycleOwner,
    private val lifecycleScope: LifecycleCoroutineScope = lifecycleOwner.lifecycleScope
) {

    lateinit var cameraProvider: ProcessCameraProvider
    var enumerationDeferred: Deferred<Unit>? = null
    var cameraIndex by mutableIntStateOf(0)
    var qualityIndex by mutableIntStateOf(0)

    var uiEnabled by mutableStateOf(false)

    val cameraCapabilities = mutableStateListOf<CameraCapability>()

    val executor by lazy { ContextCompat.getMainExecutor(context) }

    init {
        enumerationDeferred = loadCameraCapabilities(lifecycleOwner, context) { capabilities ->
            cameraCapabilities.add(capabilities)
        }
    }

    fun handleEvent(event: CameraUiEvent) {
        if (!uiEnabled) return
        when(event) {
            is CameraUiEvent.ChangeQuality -> cameraIndex = event.idx
        }
    }
}


@Composable
fun CameraComposeView(
    modifier: Modifier = Modifier
) {

    val context = LocalContext.requireActivityContext()
    val lifecycleOwner = LocalLifecycleOwner.current


    val cameraState = remember { CameraStateHolder(context, lifecycleOwner) }

    LaunchedEffect(Unit) {
        lifecycleOwner.lifecycleScope.launch {
            if (cameraState.enumerationDeferred != null) {
                cameraState.enumerationDeferred!!.await()
                cameraState.enumerationDeferred = null
            }
            initializeQualitySectionsUI()

            bindCaptureUsecase()
        }
    }

    Box {
        AndroidView(
            modifier = modifier.fillMaxSize(),
            factory = { context ->
                PreviewView(context)
            },
            update = { view ->

            }
        )
    }
}

@Composable
private fun CameraControls(
    state: CameraStateHolder
) {

    val selectorStrings by remember {
        derivedStateOf {
            state.cameraCapabilities[state.cameraIndex].qualities.map {
                it.getNameString()
            }
        }
    }

    LazyColumn {
        itemsIndexed(selectorStrings) { i, quality ->
            FilterChip(
                selected = (i == state.qualityIndex),
                onClick = {
                    state.handleEvent(CameraUiEvent.ChangeQuality(i))
                    // TODO: reset camera with selected quality
                    // https://github.com/android/camera-samples/blob/e691ae0a501ef1c2a70c77f9b13850df93aef0e4/CameraXVideo/app/src/main/java/com/example/android/camerax/video/fragments/CaptureFragment.kt#L517
                },
                label = { Text(quality) }
            )
        }
    }
}