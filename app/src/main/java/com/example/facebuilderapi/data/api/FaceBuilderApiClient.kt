package com.example.facebuilderapi.data.api

/**
 * This file intentionally contains no production API credentials.
 *
 * The original public endpoint and bearer token were removed before publishing.
 * If you need the client locally, configure these values in BuildConfig or local.properties:
 *   - BuildConfig.API_BASE_URL
 *   - BuildConfig.API_KEY
 */
object FaceBuilderApiClient {
    private const val BASE_URL = "https://example.invalid/"
    private const val API_KEY = ""

    val api: FaceBuilderApi
        get() = throw UnsupportedOperationException(
            "FaceBuilder API client is intentionally disabled in the public repository. " +
                "Set BuildConfig.API_BASE_URL and BuildConfig.API_KEY in a private/local build."
        )
}
