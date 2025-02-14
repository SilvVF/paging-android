package ios.silv.paging4

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import io.ktor.network.selector.ActorSelectorManager
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.flushIfNeeded
import io.ktor.utils.io.writeByte
import io.ktor.utils.io.writeByteArray
import io.ktor.utils.io.writeString
import io.ktor.utils.io.writeStringUtf8
import ios.silv.cameraext.CameraComposeView
import ios.silv.cameraext.rememberCameraState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.time.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

const val CAMERA_SCREEN_ROUTE = "camera_screen"

fun NavController.navigateToCameraScreen() {
    navigate("camera_screen")
}

private val requiredPermission = arrayOf(
    Manifest.permission.CAMERA,
    Manifest.permission.RECORD_AUDIO
)

private const val HOST = "10.0.2.2"
private const val PORT = 9002

object NetTcp {

    private val selectorManager = ActorSelectorManager(Dispatchers.IO)

    val frameCh = Channel<ByteArray>(
        capacity = 10,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    @OptIn(DelicateCoroutinesApi::class)
    fun run() = GlobalScope.launch(Dispatchers.IO) {
        Log.d("TCP", "starting tcp conn")
        try {
            val socket = withTimeout(5000) {
                aSocket(selectorManager).tcp().connect(InetSocketAddress(HOST, PORT)) {
                    noDelay = true
                    keepAlive = true
                }
            }
            socket.use { sock ->
                Log.d("TCP", "connected")
                val sendChannel = sock.openWriteChannel(autoFlush = true)

                frameCh.receiveAsFlow().collect { frame ->
                    Log.i("TCP", "sending frame ${frame.size}")
                    sendChannel.writeByteArray(frame)
                    // TODO: this is for testing the go server expects this as the end of a line
                    sendChannel.writeString("\n")
                }
            }
        } catch (e: Exception) {
            Log.e("TCP", e.stackTraceToString())
            if (e is CancellationException) throw e
        }
    }
}


@Composable
fun CameraScreen(navController: NavController) {

    val context = LocalContext.current
    val permissionState = remember {
        mutableStateMapOf<String, Boolean>().apply {
            putAll(requiredPermission.zip(
                Array(requiredPermission.size) {
                    context.checkSelfPermission(requiredPermission[it]) == PackageManager.PERMISSION_GRANTED
                }
            ))
        }
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissions.forEach { (p, res) ->
            permissionState[p] = res
        }
    }

    val allGranted by remember(permissionState) {
        derivedStateOf { permissionState.all { it.value } }
    }

    if (allGranted) {
        CameraViewCustomLifecycle(
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(
                onClick = {
                    launcher.launch(requiredPermission)
                }
            ) {
                Text("Grant Permissions")
            }
            permissionState.forEach { (p, v) ->
                Text("Perm: $p, state: $v")
            }
        }
    }
}

@Composable
private fun CameraViewActivityLifecycle(
    modifier: Modifier = Modifier
) {
    val cameraState = rememberCameraState(NetTcp.frameCh)

    LaunchedEffect(Unit) {
        NetTcp.frameCh.receiveAsFlow().collect {
            Log.d("MainActivity.kt", "Received a frame size: ${it.size}")
        }
    }


    CameraComposeView(
        modifier = modifier.fillMaxSize(),
        cameraState = cameraState
    )
}

@Composable
private fun CameraViewCustomLifecycle(
    modifier: Modifier = Modifier
) {

    val lifecycleOwner = remember {
        object : LifecycleOwner {
            val lifecycleRegistry = LifecycleRegistry(this).apply {
                currentState = Lifecycle.State.CREATED
            }
            override val lifecycle: Lifecycle = lifecycleRegistry
        }
    }

    DisposableEffect(Unit) {
        lifecycleOwner.lifecycleRegistry.currentState = Lifecycle.State.STARTED

        onDispose {
            lifecycleOwner.lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        }
    }

    val cameraState = rememberCameraState(lifecycleOwner, NetTcp.frameCh)

    LaunchedEffect(Unit) {
        NetTcp.frameCh.receiveAsFlow().collect {
            Log.d("MainActivity.kt", "Received a frame size: ${it.size}")
        }
    }

    CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
        CameraComposeView(
            modifier = modifier,
            cameraState = cameraState
        )
    }
}