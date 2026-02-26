package com.example.facebuilderapi.util

import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal GLB (Binary glTF 2.0) parser. Extracts vertex positions at specific indices and
 * node-level extras metadata. No external library needed — uses Gson (already in project via
 * Retrofit).
 */
class GlbParser(glbData: ByteArray) {

    companion object {
        private const val TAG = "GlbParser"
        private const val GLB_MAGIC = 0x46546C67 // "glTF"
        private const val CHUNK_TYPE_JSON = 0x4E4F534A // JSON
        private const val CHUNK_TYPE_BIN = 0x004E4942 // BIN
        private const val COMPONENT_TYPE_FLOAT = 5126
    }

    private val jsonObject: JsonObject
    private val binData: ByteBuffer

    init {
        val buffer = ByteBuffer.wrap(glbData).order(ByteOrder.LITTLE_ENDIAN)

        // --- Header (12 bytes) ---
        val magic = buffer.getInt()
        require(magic == GLB_MAGIC) { "Not a valid GLB file" }
        buffer.getInt() // version
        buffer.getInt() // totalLength

        // --- JSON chunk ---
        val jsonChunkLength = buffer.getInt()
        val jsonChunkType = buffer.getInt()
        require(jsonChunkType == CHUNK_TYPE_JSON) { "First chunk must be JSON" }
        val jsonBytes = ByteArray(jsonChunkLength)
        buffer.get(jsonBytes)
        jsonObject =
                JsonParser()
                        .parse(String(jsonBytes, Charsets.UTF_8).trimEnd('\u0000', ' '))
                        .asJsonObject

        // --- BIN chunk ---
        val binChunkLength = buffer.getInt()
        val binChunkType = buffer.getInt()
        require(binChunkType == CHUNK_TYPE_BIN) { "Second chunk must be BIN" }
        val binBytes = ByteArray(binChunkLength)
        buffer.get(binBytes)
        binData = ByteBuffer.wrap(binBytes).order(ByteOrder.LITTLE_ENDIAN)

        Log.d(TAG, "GLB parsed: JSON=$jsonChunkLength bytes, BIN=$binChunkLength bytes")
    }

    /** Returns the raw JSON nodes array as a string for diagnostics. */
    fun dumpNodesJson(): String {
        val nodes = jsonObject.getAsJsonArray("nodes") ?: return "no nodes"
        val sb = StringBuilder()
        for (i in 0 until nodes.size()) {
            val node = nodes[i].asJsonObject
            val name = node.get("name")?.asString ?: "unnamed"
            val extras = if (node.has("extras")) node.get("extras").toString() else "none"
            sb.appendLine("Node[$i] name='$name' extras=$extras")
        }
        return sb.toString()
    }

    /**
     * Reads the 3D position (x,y,z) of a vertex at [vertexIndex] from mesh 0, primitive 0, POSITION
     * attribute.
     */
    fun getVertexPosition(vertexIndex: Int): FloatArray {
        val meshes = jsonObject.getAsJsonArray("meshes")
        val mesh0 = meshes[0].asJsonObject
        val primitives = mesh0.getAsJsonArray("primitives")
        Log.d(TAG, "Mesh 0 has ${primitives.size()} primitive(s)")

        val primitive = primitives[0].asJsonObject
        val posAccessorIdx = primitive.getAsJsonObject("attributes").get("POSITION").asInt

        val accessor = jsonObject.getAsJsonArray("accessors")[posAccessorIdx].asJsonObject
        val bufferViewIdx = accessor.get("bufferView").asInt
        val accByteOffset = if (accessor.has("byteOffset")) accessor.get("byteOffset").asInt else 0
        val count = accessor.get("count").asInt
        Log.d(
                TAG,
                "POSITION accessor: idx=$posAccessorIdx, count=$count, bufferView=$bufferViewIdx, accByteOffset=$accByteOffset"
        )
        require(vertexIndex.compareTo(count) < 0) {
            "Vertex $vertexIndex out of range (count=$count)"
        }
        require(accessor.get("componentType").asInt == COMPONENT_TYPE_FLOAT)

        val bufferView = jsonObject.getAsJsonArray("bufferViews")[bufferViewIdx].asJsonObject
        val bvByteOffset =
                if (bufferView.has("byteOffset")) bufferView.get("byteOffset").asInt else 0
        val bvByteStride =
                if (bufferView.has("byteStride")) bufferView.get("byteStride").asInt
                else 12 // VEC3 float = 12
        Log.d(TAG, "BufferView: byteOffset=$bvByteOffset, byteStride=$bvByteStride")

        val offset = bvByteOffset + accByteOffset + (vertexIndex * bvByteStride)
        Log.d(TAG, "Reading vertex $vertexIndex at byte offset $offset")
        return floatArrayOf(
                binData.getFloat(offset),
                binData.getFloat(offset + 4),
                binData.getFloat(offset + 8)
        )
    }

    /**
     * Reads glasses anchor metadata from the first node that has an "anchors" field in its glTF
     * extras. Supports two formats:
     * 1. Vertex-index-based (preferred): {"nose_bridge_vertex":123, "left_lens_center_vertex":456,
     * ...}
     * 2. Coordinate-based (legacy): {"nose_bridge":[x,y,z], "left_lens_center":[x,y,z], ...}
     * Vertex-index format reads positions from the mesh data, avoiding coordinate system issues.
     */
    fun getGlassesAnchors(): GlassesAnchors? {
        val nodes = jsonObject.getAsJsonArray("nodes") ?: return null
        Log.d(TAG, "Scanning ${nodes.size()} nodes for glasses anchors...")

        for (i in 0 until nodes.size()) {
            val node = nodes[i].asJsonObject
            if (!node.has("extras")) continue

            val extrasElement = node.get("extras")
            if (!extrasElement.isJsonObject) {
                Log.d(TAG, "Node[$i] extras is not a JSON object: $extrasElement")
                continue
            }

            val extras = extrasElement.asJsonObject
            Log.d(TAG, "Node[$i] '${node.get("name")?.asString}' extras keys: ${extras.keySet()}")

            if (!extras.has("anchors")) continue

            val anchorsElement = extras.get("anchors")
            Log.d(
                    TAG,
                    "Node[$i] anchors type: ${anchorsElement.javaClass.simpleName}, value: $anchorsElement"
            )

            val anchorsObj =
                    try {
                        if (anchorsElement.isJsonObject) {
                            anchorsElement.asJsonObject
                        } else if (anchorsElement.isJsonPrimitive &&
                                        anchorsElement.asJsonPrimitive.isString
                        ) {
                            JsonParser().parse(anchorsElement.asString).asJsonObject
                        } else {
                            Log.e(TAG, "anchors is neither object nor string: $anchorsElement")
                            continue
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse anchors JSON: $anchorsElement", e)
                        continue
                    }

            Log.d(TAG, "Anchors keys: ${anchorsObj.keySet()}")

            // Vertex-index-based anchors (preferred)
            if (anchorsObj.has("nose_bridge_vertex")) {
                Log.d(TAG, "Using vertex-index-based anchors")
                return try {
                    GlassesAnchors(
                            noseBridge =
                                    getVertexPosition(anchorsObj.get("nose_bridge_vertex").asInt),
                            leftLensCenter =
                                    getVertexPosition(
                                            anchorsObj.get("left_lens_center_vertex").asInt
                                    ),
                            rightLensCenter =
                                    getVertexPosition(
                                            anchorsObj.get("right_lens_center_vertex").asInt
                                    ),
                            leftEarTip =
                                    getVertexPosition(anchorsObj.get("left_ear_tip_vertex").asInt),
                            rightEarTip =
                                    getVertexPosition(anchorsObj.get("right_ear_tip_vertex").asInt)
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to read anchor vertex positions", e)
                    null
                }
            }

            // Coordinate-based anchors (legacy)
            if (anchorsObj.has("nose_bridge")) {
                Log.d(TAG, "Using coordinate-based anchors (legacy)")
                return GlassesAnchors(
                        noseBridge = parseVec3(anchorsObj, "nose_bridge"),
                        leftLensCenter = parseVec3(anchorsObj, "left_lens_center"),
                        rightLensCenter = parseVec3(anchorsObj, "right_lens_center"),
                        leftEarTip = parseVec3(anchorsObj, "left_ear_tip"),
                        rightEarTip = parseVec3(anchorsObj, "right_ear_tip")
                )
            }

            Log.w(TAG, "anchors object has no recognized keys: ${anchorsObj.keySet()}")
        }
        Log.e(TAG, "No node found with valid 'anchors' in extras")
        return null
    }

    private fun parseVec3(obj: JsonObject, key: String): FloatArray {
        val arr = obj.getAsJsonArray(key)
        return FloatArray(3) { arr[it].asFloat }
    }
}
