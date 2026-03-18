package dev.sourcedrop.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.sourcedrop.app.ui.navigation.SourceDropNavHost
import dev.sourcedrop.app.ui.theme.SourceDropTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as SourceDropApplication).container

        setContent {
            SourceDropTheme {
                SourceDropNavHost(container = container)
            }
        }
    }
}
