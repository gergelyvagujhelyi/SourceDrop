package dev.sourcedrop.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.sourcedrop.app.ui.navigation.SourceDropNavHost
import dev.sourcedrop.app.ui.theme.SourceDropTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    /** The pending [OpenRequest], cleared by the nav host once it has acted on it. */
    private val openRequest = MutableStateFlow<OpenRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as SourceDropApplication).container

        // On recreation (rotation etc.) the original intent was already handled.
        if (savedInstanceState == null) {
            openRequest.value = OpenRequest.from(intent)
        }

        setContent {
            SourceDropTheme {
                SourceDropNavHost(container = container, openRequest = openRequest)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        OpenRequest.from(intent)?.let { openRequest.value = it }
    }
}
