package com.example.facebuilderapi.ui.screens

import android.content.Context
import android.net.Uri
import android.opengl.Matrix
import android.os.Environment
import android.util.Log
import android.view.Choreographer
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceView
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.example.facebuilderapi.viewmodel.AvatarCreationState
import com.example.facebuilderapi.viewmodel.FaceBuilderViewModel
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.MaterialProvider
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import com.google.android.filament.utils.KTX1Loader
import com.google.android.filament.utils.Utils
import java.nio.ByteBuffer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultScreen(
    viewModel: FaceBuilderViewModel,
    avatarId: String
) {
    val mainState by viewModel.state.collectAsState()
    val modelData by viewModel.modelData.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(avatarId) {
        viewModel.loadModelForRendering(avatarId)
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Avatar Result") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (mainState is AvatarCreationState.Error) {
                Text(
                    text = "Error loading model: ${(mainState as AvatarCreationState.Error).message}",
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (modelData == null) {
                        CircularProgressIndicator()
                    }
                }
                Button(onClick = {
                    val fileName = "avatar_$avatarId.glb"
                    val file = java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName)
                    viewModel.download3DModel(avatarId, file) { result ->
                        result.onSuccess {
                            Toast.makeText(context, "Model saved to Downloads folder", Toast.LENGTH_LONG).show()
                        }.onFailure {
                            Toast.makeText(context, "Download failed: ${it.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }) {
                    Text("Download Model")
                }
            }
        }
    }
}


