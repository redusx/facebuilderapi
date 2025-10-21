package com.example.facebuilderapi.ui.screens

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import com.example.facebuilderapi.viewmodel.AvatarCreationState
import com.example.facebuilderapi.viewmodel.FaceBuilderViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: FaceBuilderViewModel,
    navController: androidx.navigation.NavController,
    onNavigateToResult: (String) -> Unit
) {
    val uiState by viewModel.state.collectAsState()
    val context = LocalContext.current

    // Navigate when the avatar creation is complete.
    LaunchedEffect(uiState) {
        val state = uiState
        if (state is AvatarCreationState.Completed) {
            onNavigateToResult(state.avatarId)
        }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents(),
        onResult = { uris -> viewModel.createAvatar(context, uris) } // Simplified: select and start
    )

    val localModelPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri: Uri? ->
            uri?.let {
                try {
                    context.contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    val inputStream = context.contentResolver.openInputStream(it)
                    val buffer = inputStream?.use { stream ->
                        java.nio.ByteBuffer.wrap(stream.readBytes())
                    }
                    viewModel.setLocalModelData(buffer)
                    navController.navigate("localModelViewer")
                } catch (e: Exception) {
                    Log.e("MainScreen", "Failed to load model", e)
                }
            }
        }
    )

    Scaffold(
        topBar = { TopAppBar(title = { Text("FaceBuilder API Demo") }) }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                when (val state = uiState) {
                    is AvatarCreationState.Idle -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Button(onClick = { imagePickerLauncher.launch("image/jpeg") }) {
                                Text("Select Images & Generate")
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            OutlinedButton(onClick = { onNavigateToResult("LOCAL_TEST_MODEL") }) {
                                Text("Test Local Renderer")
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            OutlinedButton(onClick = { localModelPickerLauncher.launch(arrayOf("*/*")) }) {
                                Text("Load Local Model")
                            }
                        }
                    }
                    is AvatarCreationState.Initializing -> {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Initializing...")
                    }
                    is AvatarCreationState.Uploading -> {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Uploading image ${state.progress} of ${state.total}...")
                    }
                    is AvatarCreationState.Processing -> {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Processing avatar...")
                    }
                    is AvatarCreationState.Running -> {
                        LinearProgressIndicator(progress = state.progress)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Building model: ${(state.progress * 100).toInt()}% ")
                    }
                    is AvatarCreationState.Error -> {
                        Text("An error occurred: ${state.message}", color = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { viewModel.resetState() }) {
                            Text("Try Again")
                        }
                    }
                    is AvatarCreationState.Completed -> {
                        Text("Avatar Ready! Navigating...")
                    }
                }
            }
        }
    }
}

