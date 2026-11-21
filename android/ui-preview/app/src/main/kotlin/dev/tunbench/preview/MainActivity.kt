package dev.tunbench.preview

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import dev.tunbench.preview.model.ControlState
import dev.tunbench.preview.theme.TunBenchTheme
import dev.tunbench.preview.ui.AppShell
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state = remember { ControlState.sample() }
            LaunchedEffect(state.running) {
                while (state.running) {
                    delay(1_000)
                    state.tick()
                }
            }
            TunBenchTheme { AppShell(state) }
        }
    }
}
