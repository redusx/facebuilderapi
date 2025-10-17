package com.example.facebuilderapi.data.models

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.lang.reflect.Type

/**
 * A custom Gson deserializer for the polymorphic AvatarStatusResponse.
 * This class inspects the 'status' field of the JSON object to determine which
 * concrete subclass of [AvatarStatusData] to deserialize the 'data' field into.
 */
class AvatarStatusDeserializer : JsonDeserializer<AvatarStatusResponse> {
    override fun deserialize(
        json: JsonElement?,
        typeOfT: Type?,
        context: JsonDeserializationContext?
    ): AvatarStatusResponse {
        val jsonObject = json?.asJsonObject ?: throw IllegalArgumentException("JSON must be an object")
        
        val status = jsonObject.get("status").asString
        val dataObject = jsonObject.get("data").asJsonObject

        val data: AvatarStatusData = when (status) {
            "preparing" -> context!!.deserialize(dataObject, AvatarStatusData.Preparing::class.java)
            "not_started" -> context!!.deserialize(dataObject, AvatarStatusData.NotStarted::class.java)
            "running" -> context!!.deserialize(dataObject, AvatarStatusData.Running::class.java)
            "failed" -> context!!.deserialize(dataObject, AvatarStatusData.Failed::class.java)
            "completed" -> context!!.deserialize(dataObject, AvatarStatusData.Completed::class.java)
            else -> AvatarStatusData.Unknown
        }

        return AvatarStatusResponse(status, data)
    }
}
