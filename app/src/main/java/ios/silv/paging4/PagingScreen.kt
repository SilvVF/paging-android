@file:OptIn(ExperimentalMaterial3Api::class)

package ios.silv.paging4

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import ios.silv.page4.Pager
import ios.silv.page4.PagingConfig
import ios.silv.page4.PagingFactory
import ios.silv.page4.PagingState
import ios.silv.page4.get
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.random.Random
import kotlin.random.nextInt
import kotlin.time.TimeSource

const val TEST_PAGE_SIZE = 50

object SavedState {
    val pager = Pager(
        config = PagingConfig(
            timeSource = TimeSource.Monotonic,
            prefetchDistance = 2
        )
    ) {
        object : PagingFactory<Int, String> {

            private val random = Random(LocalDateTime.now().toEpochSecond(ZoneOffset.UTC))

            override suspend fun getNextPage(key: Int): Result<List<String>> {

                delay(2000)

                if (random.nextBoolean()) {
                    error("random was true")
                }

                return Result.success(
                    (((key - 1) * TEST_PAGE_SIZE + 1)..(key * TEST_PAGE_SIZE)).map { it.toString() }
                )
            }

            override suspend fun getPrevKey(key: Int?): Int {
                return (key?.minus(1) ?: 0).coerceAtLeast(1)
            }

            override suspend fun getNextKey(key: Int?): Int {
                return key?.plus(1) ?: 1
            }
        }
    }
}

class MainPresenter(
    viewModelScope: CoroutineScope
) {
    val data = SavedState.pager.pagerFlow
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            PagingState.Refreshing.Loading()
        )

    fun refresh() {
        SavedState.pager.refresh()
    }

    fun retry(page: Int) {
        SavedState.pager.retry(page)
    }
}

const val PAGING_SCREEN_ROUTE = "paging_screen"

fun NavController.navigateToPagingScreen() {
    navigate(PAGING_SCREEN_ROUTE)
}

@Composable
fun PagingScreen(
    navController: NavController
) {
    val scope = rememberCoroutineScope()
    val mainPresenter = remember(scope) { MainPresenter(scope) }

    val pagingState by mainPresenter.data.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(pagingState::class.toString())
                }
            )
        },
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->

        if (
            pagingState is PagingState.Refreshing.Error ||
            pagingState is PagingState.Fetching.Error && pagingState.items.isEmpty()
        ) {
            Box(Modifier.fillMaxSize()) {
                Text("$pagingState", Modifier.align(Alignment.Center))
                Button(
                    onClick = mainPresenter::refresh,
                    Modifier.align(Alignment.Center)
                ) {
                    Text("Refresh")
                }
            }
            return@Scaffold
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = innerPadding
        ) {
            items(pagingState.items, key = { it.itemKey() }) { item ->

                val data = item.get() ?: return@items

                Card(
                    modifier = Modifier
                        .padding(8.dp)
                        .fillMaxWidth(),
                    colors = CardDefaults.cardColors().copy(
                        containerColor = remember {
                            val random = Random(item.key)
                            Color(
                                red = random.nextInt(0..255),
                                green = random.nextInt(0..255),
                                blue = random.nextInt(0..255),
                                alpha = 20
                            )
                        }
                    )
                ) {
                    Column {
                        Text(
                            modifier = Modifier
                                .padding(12.dp)
                                .fillMaxWidth(),
                            text = "Page #${item.key} Item #${item.offset} data: $data",
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
            if (pagingState is PagingState.Fetching.Error) {
                item {
                    Button(
                        onClick = {
                            mainPresenter.retry(pagingState.items.last().key + 1)
                        }
                    ) {
                        Text("Retry")
                    }
                }
            }
        }
    }
}