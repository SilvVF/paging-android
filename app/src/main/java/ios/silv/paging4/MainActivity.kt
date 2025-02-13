package ios.silv.paging4

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import ios.silv.page4.Pager
import ios.silv.page4.PagingConfig
import ios.silv.page4.PagingFactory
import ios.silv.page4.PagingState
import ios.silv.page4.get
import ios.silv.paging4.ui.theme.Paging4Theme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.random.Random
import kotlin.random.nextInt
import kotlin.time.TimeSource


class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
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