package dev.rubenmoya.traelo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.rubenmoya.traelo.ui.TraeloApp
import dev.rubenmoya.traelo.ui.TraeloTheme
import dev.rubenmoya.traelo.ui.TraeloViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TraeloTheme {
                val vm: TraeloViewModel = viewModel()
                TraeloApp(vm)
            }
        }
    }
}
