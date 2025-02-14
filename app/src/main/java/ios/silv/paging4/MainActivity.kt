package ios.silv.paging4

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import ios.silv.paging4.ui.theme.Paging4Theme

class App: Application() {
    override fun onCreate() {
        super.onCreate()

        NetTcp.run()
    }
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            Paging4Theme {
                val navController = rememberNavController()

                NavHost(navController, SELECT_ROUTE) {
                    composable(SELECT_ROUTE) {
                        ScreenSelection(navController)
                    }
                    composable(PAGING_SCREEN_ROUTE) {
                        PagingScreen(navController)
                    }
                    composable(CAMERA_SCREEN_ROUTE) {
                        CameraScreen(navController)
                    }
                }
            }
        }
    }
}

private val routes = arrayOf(
    PAGING_SCREEN_ROUTE,
    CAMERA_SCREEN_ROUTE,
)

const val SELECT_ROUTE = "select_screen"

@Composable
fun ScreenSelection(navController: NavController) {
    Scaffold(Modifier.fillMaxSize()) { paddingValues ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(paddingValues),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            routes.forEach { route ->
                Button(
                    modifier = Modifier
                        .padding(12.dp)
                        .fillMaxWidth(0.5f),
                    onClick = { navController.navigate(route) }
                ) {
                    Text(route)
                }
            }
        }
    }
}