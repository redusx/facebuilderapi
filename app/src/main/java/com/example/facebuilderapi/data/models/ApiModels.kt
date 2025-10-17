package com.example.facebuilderapi.data.models

import com.google.gson.annotations.SerializedName

// region Init & Create
data class AvatarInitRequest(
    @SerializedName("img_count")
    val imgCount: Int
)

data class AvatarInitResponse(
    @SerializedName("avatar_id")
    val avatarId: String,
    @SerializedName("img_urls")
    val imgUrls: List<String>
)

data class AvatarCreateRequest(
    @SerializedName("focal_length_type")
    val focalLengthType: String = "estimate_common",
    @SerializedName("focal_length_values")
    val focalLengthValues: List<Float>? = null,
    @SerializedName("expressions_enabled")
    val expressionsEnabled: Boolean = false
)
// endregion

// region Status

/**
 * Represents the full response from the /get_status endpoint.
 * The `data` field is polymorphic and will be deserialized into one of the
 * subtypes of [AvatarStatusData] based on the `status` string.
 */
data class AvatarStatusResponse(
    @SerializedName("status")
    val status: String,
    @SerializedName("data")
    val data: AvatarStatusData
)

/**
 * A sealed class representing the different possible structures of the `data` object
 * in the status response.
 */
sealed class AvatarStatusData {
    data class Preparing(
        @SerializedName("execution_started")
        val executionStarted: Boolean
    ) : AvatarStatusData()

    data class NotStarted(
        @SerializedName("img_urls")
        val imgUrls: List<String>
    ) : AvatarStatusData()

    data class Running(
        @SerializedName("progress")
        val progress: Float,
        @SerializedName("img_urls")
        val imgUrls: List<String>
    ) : AvatarStatusData()

    data class Failed(
        @SerializedName("error_message")
        val errorMessage: String,
        @SerializedName("img_urls")
        val imgUrls: List<String>
    ) : AvatarStatusData()

    data class Completed(
        @SerializedName("img_urls")
        val imgUrls: List<String>,
        @SerializedName("texture_url")
        val textureUrl: String,
        @SerializedName("has_per_frame_model")
        val hasPerFrameModel: Boolean,
        @SerializedName("focal_length_type")
        val focalLengthType: String
    ) : AvatarStatusData()

    // A default case for any unknown status types
    object Unknown : AvatarStatusData()
}

// endregion
