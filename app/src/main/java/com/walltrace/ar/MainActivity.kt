package com.walltrace.ar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.walltrace.ar.ui.ArScreen
import com.walltrace.ar.ui.HomeScreen
import com.walltrace.ar.ui.OverlayScreen
import com.walltrace.ar.ui.theme.WallTraceTheme
import com.walltrace.ar.ui.theme.WtColors

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        setContent {
            WallTraceTheme {
                WallTraceApp()
            }
        }
    }
}

@Composable
private fun WallTraceApp(viewModel: MainViewModel = viewModel()) {
    val screen by viewModel.screen.collectAsStateWithLifecycle()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(WtColors.Background)
    ) {
        when (screen) {
            Screen.HOME -> HomeScreen(viewModel)
            Screen.AR -> {
                BackHandler { viewModel.closeAr() }
                ArScreen(viewModel = viewModel, onBack = viewModel::closeAr, onUseOverlay = viewModel::useOverlayMode)
            }
            Screen.OVERLAY -> {
                BackHandler { viewModel.closeAr() }
                OverlayScreen(viewModel = viewModel, onBack = viewModel::closeAr)
            }
        }
    }
}
