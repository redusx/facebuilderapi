package com.example.facebuilderapi.ui.screens

import android.content.Context
import android.net.Uri
import android.opengl.Matrix
import android.util.Log
import android.view.Choreographer
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.MaterialProvider
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import com.google.android.filament.utils.KTX1Loader
import com.google.android.filament.utils.Utils
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import com.google.android.filament.Camera
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.SwapChain
import com.google.android.filament.View
import com.google.android.filament.Viewport
import android.view.SurfaceHolder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalModelViewerScreen(viewModel: com.example.facebuilderapi.viewmodel.FaceBuilderViewModel) {
    val modelData by viewModel.localModelData.collectAsState()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Local Model Viewer") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (modelData != null) {
                FilamentView(modelData)
            } else {
                Text("Loading model...")
            }
        }
    }
}


