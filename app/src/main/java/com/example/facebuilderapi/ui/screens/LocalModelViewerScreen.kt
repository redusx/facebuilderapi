package com.example.facebuilderapi.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalModelViewerScreen(viewModel: com.example.facebuilderapi.viewmodel.FaceBuilderViewModel) {
    val modelData by viewModel.localModelData.collectAsState()
    val glassesData by viewModel.glassesData.collectAsState()
    val context = LocalContext.current

    var mainModelRotation by remember { mutableStateOf(180f) }
    var glassesScale by remember { mutableStateOf(1.0f) } // Manual scale multiplier

    val glassesPickerLauncher =
            rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
                uri?.let {
                    try {
                        val fileName = getFileName(context, it)
                        if (fileName?.endsWith(".glb", ignoreCase = true) != true) {
                            Toast.makeText(context, "Please select a .glb file.", Toast.LENGTH_LONG)
                                    .show()
                            return@rememberLauncherForActivityResult
                        }
                        val inputStream = context.contentResolver.openInputStream(it)
                        val buffer =
                                inputStream?.use { stream ->
                                    java.nio.ByteBuffer.wrap(stream.readBytes())
                                }
                        viewModel.setGlassesData(buffer)
                        glassesScale = 1.0f // Reset scale when new glasses loaded
                        Log.d("LocalModelViewerScreen", "Glasses model loaded: $fileName")
                    } catch (e: Exception) {
                        Log.e("LocalModelViewerScreen", "Failed to load glasses model", e)
                        Toast.makeText(
                                        context,
                                        "Failed to load glasses: ${e.message}",
                                        Toast.LENGTH_LONG
                                )
                                .show()
                    }
                }
            }

    Scaffold(
            topBar = {
                TopAppBar(
                        title = { Text("Local Model Viewer") },
                        actions = {
                            IconButton(onClick = { glassesPickerLauncher.launch(arrayOf("*/*")) }) {
                                Icon(Icons.Default.Add, contentDescription = "Add Glasses")
                            }
                        }
                )
            }
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            val cachedModelArray = remember(modelData) { modelData?.array() }
            val cachedGlassesArray =
                    remember(glassesData) {
                        glassesData?.let {
                            val arr = ByteArray(it.remaining())
                            it.duplicate().get(arr)
                            arr
                        }
                    }

            if (cachedModelArray != null) {
                FilamentView(
                        modelData = cachedModelArray,
                        glassesData = cachedGlassesArray,
                        mainModelRotation = mainModelRotation,
                        glassesScale = glassesScale,
                        modifier = Modifier.weight(1f)
                )
            } else {
                Text("Loading model...")
            }

            // Glasses Scale Controls
            if (glassesData != null) {
                androidx.compose.foundation.layout.Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Text("Gözlük Boyutu: ", modifier = Modifier.weight(1f))
                    androidx.compose.material3.Button(onClick = { glassesScale -= 0.05f }) {
                        Text("-")
                    }
                    Text(
                            text = String.format("%.2fx", glassesScale),
                            modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    androidx.compose.material3.Button(onClick = { glassesScale += 0.05f }) {
                        Text("+")
                    }
                }
            }

            // Slider for main model rotation
            Slider(
                    value = mainModelRotation,
                    onValueChange = { mainModelRotation = it },
                    valueRange = 0f..360f,
                    steps = 0,
                    modifier = Modifier.padding(bottom = 8.dp)
            )
        }
    }
}

private fun getFileName(context: Context, uri: Uri): String? {
    var result: String? = null
    if (uri.scheme == "content") {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        try {
            if (cursor != null && cursor.moveToFirst()) {
                val displayNameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (displayNameIndex != -1) {
                    result = cursor.getString(displayNameIndex)
                }
            }
        } finally {
            cursor?.close()
        }
    }
    if (result == null) {
        result = uri.path
        val cut = result?.lastIndexOf('/')
        if (cut != -1) {
            if (cut != null) {
                result = result.substring(cut + 1)
            }
        }
    }
    return result
}
