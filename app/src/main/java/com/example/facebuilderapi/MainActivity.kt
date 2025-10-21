package com.example.facebuilderapi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.facebuilderapi.ui.screens.MainScreen
import com.example.facebuilderapi.ui.screens.ResultScreen
import com.example.facebuilderapi.ui.theme.FaceBuilderApiTheme
import com.example.facebuilderapi.viewmodel.FaceBuilderViewModel
import com.example.facebuilderapi.ui.screens.LocalModelViewerScreen


class MainActivity : ComponentActivity() {
    
    private val viewModel: FaceBuilderViewModel by viewModels()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FaceBuilderApiTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation(viewModel)
                }
            }
        }
    }
}

@Composable
fun AppNavigation(viewModel: FaceBuilderViewModel) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "main") {
        composable("main") {
            MainScreen(
                viewModel = viewModel,
                navController = navController,
                onNavigateToResult = { avatarId ->
                    navController.navigate("result/$avatarId")
                }
            )
        }
        composable(
            route = "result/{avatarId}",
            arguments = listOf(navArgument("avatarId") { type = NavType.StringType })
        ) { backStackEntry ->
            val avatarId = backStackEntry.arguments?.getString("avatarId") ?: ""
            ResultScreen(
                viewModel = viewModel,
                avatarId = avatarId
            )
        }
        composable("localModelViewer") {
            LocalModelViewerScreen(viewModel = viewModel)
        }
    }
}