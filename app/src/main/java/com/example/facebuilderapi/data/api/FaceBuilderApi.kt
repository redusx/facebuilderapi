package com.example.facebuilderapi.data.api

import com.example.facebuilderapi.data.models.*
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

interface FaceBuilderApi {
    
    @Authenticated
    @Headers("Content-Type: application/json")
    @POST("avatar/init")
    suspend fun initAvatar(
        @Body request: RequestBody
    ): Response<AvatarInitResponse>
    
    @PUT
    suspend fun uploadImage(
        @Url url: String,
        @Body image: RequestBody
    ): Response<ResponseBody>
    
    @Authenticated
    @POST("avatar/{avatar_id}/create")
    suspend fun createAvatar(
        @Path("avatar_id") avatarId: String,
        @Body request: AvatarCreateRequest
    ): Response<ResponseBody>
    
    @Authenticated
    @GET("avatar/{avatar_id}/get_status")
    suspend fun getAvatarStatus(
        @Path("avatar_id") avatarId: String
    ): Response<AvatarStatusResponse>

    @Authenticated
    @GET("avatar/{avatar_id}/get_3d_model/neutral_with_blendshapes_glb")
    suspend fun getNeutralTexturedGlb(
        @Path("avatar_id") avatarId: String,
        @Query("texture") texture: Boolean = true
    ): Response<ResponseBody>

    @Authenticated
    @GET("avatar/{avatar_id}/get_3d_model/single_head")
    suspend fun get3DModel(
        @Path("avatar_id") avatarId: String,
        @Query("frame") frame: Int? = null,
        @Query("mesh_type") meshType: String = "glb",
        @Query("texture") texture: Boolean = true,
        @Query("resolution") resolution: String = "high_poly"
    ): Response<ResponseBody>
}
