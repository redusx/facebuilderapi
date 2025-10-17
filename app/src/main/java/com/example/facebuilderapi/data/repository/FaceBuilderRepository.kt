package com.example.facebuilderapi.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.facebuilderapi.data.api.FaceBuilderApiClient
import com.example.facebuilderapi.data.models.AvatarCreateRequest
import com.example.facebuilderapi.data.models.AvatarInitRequest
import com.example.facebuilderapi.data.models.AvatarInitResponse
import com.example.facebuilderapi.data.models.AvatarStatusData
import com.example.facebuilderapi.data.models.AvatarStatusResponse
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream

class FaceBuilderRepository {
    
    private val api = FaceBuilderApiClient.api
    
    suspend fun initAvatar(imageCount: Int): Result<AvatarInitResponse> {
        return try {
            val initRequestData = AvatarInitRequest(imageCount)
            val jsonBody = Gson().toJson(initRequestData)
            // Convert to ByteArray to prevent OkHttp from adding a charset to the Content-Type
            val requestBody = jsonBody.toByteArray().toRequestBody("application/json".toMediaType())

            val response = api.initAvatar(requestBody)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Failed to init avatar: ${response.code()} - ${response.errorBody()?.string()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun uploadImages(
        context: Context,
        imageUris: List<Uri>,
        uploadUrls: List<String>
    ): Result<Unit> {
        return try {
            imageUris.forEachIndexed { index, uri ->
                val file = uriToFile(context, uri)
                val requestBody = file.asRequestBody("image/jpeg".toMediaType())
                
                val response = api.uploadImage(uploadUrls[index], requestBody)
                if (!response.isSuccessful) {
                    return Result.failure(Exception("Failed to upload image $index: ${response.code()}"))
                }
                file.delete() // Clean up the temporary file
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun createAvatar(
        avatarId: String,
        focalLengthType: String = "estimate_common",
        focalLengthValues: List<Float>? = null,
        expressionsEnabled: Boolean = false
    ): Result<Unit> {
        return try {
            val request = AvatarCreateRequest(
                focalLengthType = focalLengthType,
                focalLengthValues = focalLengthValues,
                expressionsEnabled = expressionsEnabled
            )
            val response = api.createAvatar(avatarId, request)
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to create avatar: ${response.code()} - ${response.errorBody()?.string()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun getAvatarStatus(avatarId: String): Result<AvatarStatusResponse> {
        return try {
            val response = api.getAvatarStatus(avatarId)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Failed to get status: ${response.code()} - ${response.errorBody()?.string()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun waitForCompletion(
        avatarId: String,
        onProgress: (Float) -> Unit = {}
    ): Result<AvatarStatusResponse> {
        while (true) {
            val statusResult = getAvatarStatus(avatarId)
            if (statusResult.isFailure) {
                return statusResult
            }
            
            val response = statusResult.getOrNull()!!
            when (val statusData = response.data) {
                is AvatarStatusData.Completed -> return Result.success(response)
                is AvatarStatusData.Failed -> return Result.failure(
                    Exception(statusData.errorMessage)
                )
                is AvatarStatusData.Running -> {
                    onProgress(statusData.progress)
                }
                // For NotStarted, Preparing, or Unknown, we just continue polling.
                else -> { /* Do nothing, just wait and poll again */ }
            }
            
            delay(3000) // Poll every 3 seconds
        }
    }
    
    suspend fun download3DModel(
        avatarId: String,
        outputFile: File,
        meshType: String = "glb",
        resolution: String = "high_poly"
    ): Result<File> {
        val maxAttempts = 10 // Poll for a maximum of 30 seconds
        var attempt = 0
        while (attempt < maxAttempts) {
            try {
                // Request texture=true
                val initialResponse = api.getNeutralTexturedGlb(avatarId = avatarId, texture = true)

                when (initialResponse.code()) {
                    302 -> { // Success: Found and redirecting
                        val location = initialResponse.headers()["Location"]
                        if (location.isNullOrEmpty()) {
                            return Result.failure(Exception("Redirect location is missing in 302 response."))
                        }

                        // Manually follow the redirect and write to file
                        withContext(Dispatchers.IO) {
                            java.net.URL(location).openStream().use { input ->
                                FileOutputStream(outputFile).use { output ->
                                    input.copyTo(output)
                                }
                            }
                        }
                        return Result.success(outputFile)
                    }
                    202 -> { // Not ready yet, wait and retry
                        Log.d("FaceBuilderRepository", "Download poll: Model not ready yet (202), retrying...")
                        delay(3000)
                    }
                    else -> { // Other error
                        return Result.failure(Exception("Failed to download 3D model: ${initialResponse.code()} - ${initialResponse.errorBody()?.string()}"))
                    }
                }
            } catch (e: Exception) {
                return Result.failure(e)
            }
            attempt++
        }
        return Result.failure(Exception("Failed to download 3D model after $maxAttempts attempts (timed out)."))
    }
    
    suspend fun getModelData(avatarId: String, meshType: String = "glb"): Result<ByteArray> {
        val maxAttempts = 10 // Poll for a maximum of 30 seconds (10 attempts * 3s delay)
        var attempt = 0
        while (attempt < maxAttempts) {
            try {
                val initialResponse = api.getNeutralTexturedGlb(avatarId = avatarId, texture = true)

                when (initialResponse.code()) {
                    302 -> { // Success: Found and redirecting
                        val location = initialResponse.headers()["Location"]
                        if (location.isNullOrEmpty()) {
                            return Result.failure(Exception("Redirect location is missing in 302 response."))
                        }
                        // Manually follow the redirect on an IO thread
                        val modelData = withContext(Dispatchers.IO) {
                            java.net.URL(location).openStream().use { it.readBytes() }
                        }
                        return Result.success(modelData)
                    }
                    202 -> { // Not ready yet, wait and retry
                        Log.d("FaceBuilderRepository", "Model not ready yet (202), retrying in 3 seconds...")
                        delay(3000)
                    }
                    else -> { // Other error
                        return Result.failure(Exception("Failed to get model data: ${initialResponse.code()} - ${initialResponse.errorBody()?.string()}"))
                    }
                }
            } catch (e: Exception) {
                return Result.failure(e)
            }
            attempt++
        }
        return Result.failure(Exception("Failed to get model data after $maxAttempts attempts (timed out)."))
    }

    /**
     * Creates a temporary file from a content URI to be used in the upload.
     */
    private fun uriToFile(context: Context, uri: Uri): File {
        val inputStream = context.contentResolver.openInputStream(uri)
        val file = File(context.cacheDir, "upload_${System.currentTimeMillis()}.jpg")
        inputStream?.use { input ->
            FileOutputStream(file).use { output ->
                input.copyTo(output)
            }
        }
        return file
    }
}
