package com.visura.cam

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.visura.cam.ui.viewfinder.VisuraColors
import com.visura.cam.ui.viewfinder.ViewfinderScreen
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val navController = rememberNavController()
            NavHost(
                navController = navController,
                startDestination = "viewfinder",
                modifier = Modifier
                    .fillMaxSize()
                    .background(VisuraColors.Background)
            ) {
                composable("viewfinder") { ViewfinderScreen() }
                // composable("gallery") { GalleryScreen() }
                // composable("settings") { SettingsScreen() }
                // composable("calibration") { CalibrationScreen() }
            }
        }
    }
}
