package io.terminus.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import io.terminus.app.map.MapSetup
import io.terminus.app.nav.TerminusNavHost
import io.terminus.app.ui.theme.TerminusTheme

/**
 * The single activity (ARCHITECTURE.md §1.2): everything is Compose, navigated by
 * [TerminusNavHost]. osmdroid is configured once before any map is created.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapSetup.init(this)
        setContent {
            TerminusTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    TerminusNavHost()
                }
            }
        }
    }
}
