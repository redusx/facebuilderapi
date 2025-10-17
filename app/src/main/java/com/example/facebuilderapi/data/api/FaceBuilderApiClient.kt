package com.example.facebuilderapi.data.api

import com.example.facebuilderapi.data.models.AvatarStatusDeserializer
import com.example.facebuilderapi.data.models.AvatarStatusResponse
import com.google.gson.GsonBuilder
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Invocation
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object FaceBuilderApiClient {
    
    private const val BASE_URL = "https://br7ls2mdjpzkmchaeuwlp6hf540zimfq.lambda-url.us-east-1.on.aws/"
    private const val API_KEY = "5bfc56d965fed789d2a5e64a901c0270cd2b83d1b14f18d7bd57c37bd8c4db"
    
    private val authInterceptor = Interceptor { chain ->
        val originalRequest = chain.request()

        // Find the Invocation object from the request tags
        val invocation = originalRequest.tag(Invocation::class.java)
            ?: return@Interceptor chain.proceed(originalRequest)

        // Check if the invoked method has the @Authenticated annotation
        val shouldAttachAuth = invocation.method().isAnnotationPresent(Authenticated::class.java)

        if (shouldAttachAuth) {
            val newRequest = originalRequest.newBuilder()
                .addHeader("Authorization", "Bearer $API_KEY")
                .addHeader("Accept", "application/json")
                .build()
            chain.proceed(newRequest)
        } else {
            // For non-annotated methods, proceed with the original request
            chain.proceed(originalRequest)
        }
    }
    
    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }
    
    private val okHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .addInterceptor(authInterceptor)
        .addInterceptor(loggingInterceptor)
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    
    private val gson = GsonBuilder()
        .registerTypeAdapter(AvatarStatusResponse::class.java, AvatarStatusDeserializer())
        .create()
    
    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create(gson))
        .build()
    
    val api: FaceBuilderApi = retrofit.create(FaceBuilderApi::class.java)
}
