package ios.silv.paging4

import android.Manifest
import android.content.pm.PackageManager
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
import ios.silv.cameraext.CameraComposeView
import ios.silv.cameraext.rememberCameraState

const val CAMERA_SCREEN_ROUTE = "camera_screen"

fun NavController.navigateToCameraScreen() {
    navigate("camera_screen")
}

private val requiredPermission = arrayOf(
    Manifest.permission.CAMERA,
    Manifest.permission.RECORD_AUDIO
)

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
    val cameraState = rememberCameraState()

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


    val cameraState = rememberCameraState(lifecycleOwner)

    CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
        CameraComposeView(
            modifier = modifier,
            cameraState = cameraState
        )
    }
}