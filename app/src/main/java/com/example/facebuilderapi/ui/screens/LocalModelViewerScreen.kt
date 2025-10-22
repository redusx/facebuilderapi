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
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Transform
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
import com.example.facebuilderapi.ui.screens.FilamentView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalModelViewerScreen(viewModel: com.example.facebuilderapi.viewmodel.FaceBuilderViewModel) {
    val modelData by viewModel.localModelData.collectAsState()
    var attachmentUri by remember { mutableStateOf<Uri?>(null) }
    val context = LocalContext.current

    var mainModelRotation by remember { mutableStateOf(180f) }
    var isAttachmentRotationMode by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            val fileName = getFileName(context, it)
            if (fileName?.endsWith(".glb", ignoreCase = true) == true) {
                attachmentUri = it
            } else {
                Toast.makeText(context, "Unsupported file format. Please select a .glb file.", Toast.LENGTH_LONG).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Local Model Viewer") },
                actions = {
                    IconButton(onClick = { launcher.launch("*/*") }) {
                        Icon(Icons.Default.Add, contentDescription = "Add Attachment")
                    }
                    if (attachmentUri != null) {
                        IconButton(onClick = { 
                            isAttachmentRotationMode = !isAttachmentRotationMode
                            Log.d("LocalModelViewerScreen", "isAttachmentRotationMode toggled to: $isAttachmentRotationMode")
                        }) {
                            Icon(
                                imageVector = if (isAttachmentRotationMode) Icons.Default.RotateRight else Icons.Default.Transform,
                                contentDescription = if (isAttachmentRotationMode) "Rotate Attachment" else "Translate Attachment"
                            )
                        }
                    }
                }
            )
        }
    ) { paddingValues -> // Changed parameter name to paddingValues
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues) // Apply paddingValues here
        ) {
            if (modelData != null) {
                FilamentView(
                    modelData = modelData?.array(), // Convert ByteBuffer to ByteArray
                    attachmentUri = attachmentUri,
                    mainModelRotation = mainModelRotation,
                    isAttachmentRotationMode = rememberUpdatedState(isAttachmentRotationMode), // Pass as State<Boolean>
                    modifier = Modifier.weight(1f) // Apply weight here
                )
            } else {
                Text("Loading model...")
            }
            // Slider for main model rotation
            Slider(
                value = mainModelRotation,
                onValueChange = { mainModelRotation = it },
                valueRange = 0f..360f,
                steps = 0,
                // Removed horizontal and vertical padding from here
                modifier = Modifier.padding(bottom = 8.dp) // Keep some bottom padding
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
