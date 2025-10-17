package com.example.facebuilderapi.data.api

/**
 * A custom annotation to mark Retrofit API methods that require authentication.
 * The AuthInterceptor will look for this annotation to decide whether to add the
 * Authorization header or not.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Authenticated
