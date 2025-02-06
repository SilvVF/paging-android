package ios.silv.paging4

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import ios.silv.page4.Pager
import ios.silv.page4.PagingConfig
import ios.silv.page4.PagingFactory
import ios.silv.page4.PagingState
import ios.silv.page4.get
import ios.silv.paging4.ui.theme.Paging4Theme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
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
            override suspend fun getNextPage(key: Int): Result<List<String>> {
                return Result.success(
                    (((key-1)*TEST_PAGE_SIZE+1)..(key*TEST_PAGE_SIZE)).map { it.toString() }
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
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val mainPresenter = MainPresenter(lifecycleScope)

        setContent {

            Paging4Theme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->

                    val pages by mainPresenter.data.collectAsState()

                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = innerPadding
                    ) {
                        items(pages.items, key = { it.itemKey() }) { item ->

                            val data = item.get() ?: return@items

                            Card(
                                modifier = Modifier.padding(8.dp).fillMaxWidth(),
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
                                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                                        text = "Page #${item.key} Item #${item.offset} data: $data",
                                        textAlign = TextAlign.Center,
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    Paging4Theme {
        Greeting("Android")
    }
}