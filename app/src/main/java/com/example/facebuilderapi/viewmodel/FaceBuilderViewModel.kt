package com.example.facebuilderapi.viewmodel

import android.app.Application
import android.util.Log
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.facebuilderapi.data.repository.FaceBuilderRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed class AvatarCreationState {
    object Idle : AvatarCreationState()
    object Initializing : AvatarCreationState()
    data class Uploading(val progress: Int, val total: Int) : AvatarCreationState()
    object Processing : AvatarCreationState()
    data class Running(val progress: Float) : AvatarCreationState()
    data class Completed(val avatarId: String) : AvatarCreationState()
    data class Error(val message: String) : AvatarCreationState()
}

class FaceBuilderViewModel(application: Application) : AndroidViewModel(application) {
    
    private val repository = FaceBuilderRepository()
    
    private val _state = MutableStateFlow<AvatarCreationState>(AvatarCreationState.Idle)
    val state = _state.asStateFlow()

    private val _modelData = MutableStateFlow<java.nio.ByteBuffer?>(null)
    val modelData = _modelData.asStateFlow()

    private val _localModelData = MutableStateFlow<java.nio.ByteBuffer?>(null)
    val localModelData = _localModelData.asStateFlow()

    fun setLocalModelData(data: java.nio.ByteBuffer?) {
        _localModelData.value = data
    }

    fun loadModelForRendering(avatarId: String) {
        viewModelScope.launch {
            Log.d("ViewModel", "loadModelForRendering called with avatarId: $avatarId")
            // Handle local test case
            if (avatarId == "LOCAL_TEST_MODEL") {
                Log.d("ViewModel", "Loading local test model.")
                try {
                    val context = getApplication<Application>().applicationContext
                    val buffer = context.assets.open("my_model.glb").use { stream ->
                        java.nio.ByteBuffer.wrap(stream.readBytes())
                    }
                    _modelData.value = buffer
                    Log.d("ViewModel", "Local model loaded successfully.")
                } catch (e: Exception) {
                    Log.e("ViewModel", "Failed to load local model", e)
                    _state.value = AvatarCreationState.Error("Failed to load local model: ${e.message}")
                }
                return@launch
            }

            // Fetch from repository for remote model
            Log.d("ViewModel", "Fetching remote model data from repository.")
            val result = repository.getModelData(avatarId)
            result.onSuccess { byteArray ->
                Log.d("ViewModel", "Model data fetched successfully. Size: ${byteArray.size} bytes.")
                _modelData.value = java.nio.ByteBuffer.wrap(byteArray)
            }.onFailure { error ->
                Log.e("ViewModel", "Failed to fetch model data.", error)
                _state.value = AvatarCreationState.Error(error.message ?: "Failed to load model data")
            }
        }
    }
    
    fun createAvatar(context: Context, imageUris: List<Uri>) {
        viewModelScope.launch {
            try {
                // 1. Initialize
                _state.value = AvatarCreationState.Initializing
                val initResult = repository.initAvatar(imageUris.size)
                if (initResult.isFailure) {
                    _state.value = AvatarCreationState.Error(
                        initResult.exceptionOrNull()?.message ?: "Initialization failed"
                    )
                    return@launch
                }
                
                val initData = initResult.getOrNull()!!
                
                // 2. Upload images
                _state.value = AvatarCreationState.Uploading(1, imageUris.size)
                
                val uploadResult = repository.uploadImages(
                    context,
                    imageUris,
                    initData.imgUrls
                )
                if (uploadResult.isFailure) {
                    _state.value = AvatarCreationState.Error(
                        uploadResult.exceptionOrNull()?.message ?: "Upload failed"
                    )
                    return@launch
                }
                
                // 3. Start reconstruction
                _state.value = AvatarCreationState.Processing
                val createResult = repository.createAvatar(initData.avatarId)
                if (createResult.isFailure) {
                    _state.value = AvatarCreationState.Error(
                        createResult.exceptionOrNull()?.message ?: "Creation failed"
                    )
                    return@launch
                }
                
                // 4. Wait for completion
                val completionResult = repository.waitForCompletion(initData.avatarId) { progress ->
                    _state.value = AvatarCreationState.Running(progress)
                }
                
                if (completionResult.isSuccess) {
                    _state.value = AvatarCreationState.Completed(initData.avatarId)
                } else {
                    _state.value = AvatarCreationState.Error(
                        completionResult.exceptionOrNull()?.message ?: "Processing failed"
                    )
                }
                
            } catch (e: Exception) {
                _state.value = AvatarCreationState.Error(e.message ?: "Unknown error")
            }
        }
    }
    
    fun download3DModel(avatarId: String, outputFile: File, onComplete: (Result<File>) -> Unit) {
        viewModelScope.launch {
            val result = repository.download3DModel(avatarId, outputFile)
            onComplete(result)
        }
    }

    fun resetState() {
        _state.value = AvatarCreationState.Idle
    }

    fun downloadModel(url: String) {
        try {
            val context = getApplication<Application>().applicationContext
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle("My Avatar")
                .setDescription("Downloading 3D model...")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "my_avatar.glb")

            downloadManager.enqueue(request)

            Toast.makeText(context, "Download started...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            _state.value = AvatarCreationState.Error(e.message ?: "Failed to start download")
        }
    }
}