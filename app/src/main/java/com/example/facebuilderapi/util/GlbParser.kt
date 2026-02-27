package com.example.facebuilderapi.util

import android.opengl.Matrix as AndroidMatrix
import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal GLB (Binary glTF 2.0) parser. Extracts vertex positions at specific indices and
 * node-level extras metadata.
 */
class GlbParser(glbData: ByteArray) {

    companion object {
        private const val TAG = "GlbParser"
        private const val GLB_MAGIC = 0x46546C67
        private const val CHUNK_TYPE_JSON = 0x4E4F534A
        private const val CHUNK_TYPE_BIN = 0x004E4942
        private const val COMPONENT_TYPE_FLOAT = 5126
    }

    private val jsonObject: JsonObject
    private val binData: ByteBuffer

    init {
        val buffer = ByteBuffer.wrap(glbData).order(ByteOrder.LITTLE_ENDIAN)
        val magic = buffer.getInt()
        require(magic == GLB_MAGIC) { "Not a valid GLB file" }
        buffer.getInt() // version
        buffer.getInt() // totalLength

        val jsonChunkLength = buffer.getInt()
        val jsonChunkType = buffer.getInt()
        require(jsonChunkType == CHUNK_TYPE_JSON) { "First chunk must be JSON" }
        val jsonBytes = ByteArray(jsonChunkLength)
        buffer.get(jsonBytes)
        jsonObject =
                JsonParser()
                        .parse(String(jsonBytes, Charsets.UTF_8).trimEnd('\u0000', ' '))
                        .asJsonObject

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
     * Reads the 3D position (x,y,z) of a vertex at [vertexIndex] from mesh 0, primitive 0. Returns
     * raw mesh-local coordinates.
     */
    fun getVertexPosition(vertexIndex: Int): FloatArray {
        val meshes = jsonObject.getAsJsonArray("meshes")
        val mesh0 = meshes[0].asJsonObject
        val primitives = mesh0.getAsJsonArray("primitives")
        val primitive = primitives[0].asJsonObject
        val posAccessorIdx = primitive.getAsJsonObject("attributes").get("POSITION").asInt

        val accessor = jsonObject.getAsJsonArray("accessors")[posAccessorIdx].asJsonObject
        val bufferViewIdx = accessor.get("bufferView").asInt
        val accByteOffset = if (accessor.has("byteOffset")) accessor.get("byteOffset").asInt else 0
        val count = accessor.get("count").asInt
        require(vertexIndex < count) { "Vertex $vertexIndex out of range (count=$count)" }
        require(accessor.get("componentType").asInt == COMPONENT_TYPE_FLOAT)

        val bufferView = jsonObject.getAsJsonArray("bufferViews")[bufferViewIdx].asJsonObject
        val bvByteOffset =
                if (bufferView.has("byteOffset")) bufferView.get("byteOffset").asInt else 0
        val bvByteStride =
                if (bufferView.has("byteStride")) bufferView.get("byteStride").asInt else 12

        val offset = bvByteOffset + accByteOffset + (vertexIndex * bvByteStride)
        return floatArrayOf(
                binData.getFloat(offset),
                binData.getFloat(offset + 4),
                binData.getFloat(offset + 8)
        )
    }

    /**
     * Builds the 4x4 TRS matrix for the first node that references the given mesh index. Returns
     * identity if no transform is found.
     */
    fun getNodeTransformForMesh(meshIndex: Int = 0): FloatArray {
        val nodes = jsonObject.getAsJsonArray("nodes") ?: return identityMatrix()
        for (i in 0 until nodes.size()) {
            val node = nodes[i].asJsonObject
            if (node.has("mesh") && node.get("mesh").asInt == meshIndex) {
                return buildNodeMatrix(node)
            }
        }
        return identityMatrix()
    }

    /** Transforms a raw vertex position by the given 4x4 matrix → world-space. */
    fun getVertexPositionWorldSpace(vertexIndex: Int, nodeTransform: FloatArray): FloatArray {
        val local = getVertexPosition(vertexIndex)
        val input = floatArrayOf(local[0], local[1], local[2], 1f)
        val output = FloatArray(4)
        AndroidMatrix.multiplyMV(output, 0, nodeTransform, 0, input, 0)
        return floatArrayOf(output[0], output[1], output[2])
    }

    /**
     * Reads glasses anchor metadata from glTF extras. Returns world-space positions (with node TRS
     * applied) for vertex-index anchors.
     */
    fun getGlassesAnchors(): GlassesAnchors? {
        val nodes = jsonObject.getAsJsonArray("nodes") ?: return null
        val meshNodeTransform = getNodeTransformForMesh(0)
        Log.d(TAG, "Glasses mesh node TRS: ${meshNodeTransform.contentToString()}")

        for (i in 0 until nodes.size()) {
            val node = nodes[i].asJsonObject
            if (!node.has("extras")) continue
            val extrasElement = node.get("extras")
            if (!extrasElement.isJsonObject) continue
            val extras = extrasElement.asJsonObject
            if (!extras.has("anchors")) continue

            val anchorsElement = extras.get("anchors")
            val anchorsObj =
                    try {
                        if (anchorsElement.isJsonObject) {
                            anchorsElement.asJsonObject
                        } else if (anchorsElement.isJsonPrimitive &&
                                        anchorsElement.asJsonPrimitive.isString
                        ) {
                            JsonParser().parse(anchorsElement.asString).asJsonObject
                        } else continue
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse anchors JSON", e)
                        continue
                    }

            if (anchorsObj.has("nose_bridge_vertex")) {
                Log.d(TAG, "Using vertex-index-based anchors (world-space)")
                return try {
                    GlassesAnchors(
                            noseBridge =
                                    getVertexPositionWorldSpace(
                                            anchorsObj.get("nose_bridge_vertex").asInt,
                                            meshNodeTransform
                                    ),
                            leftLensCenter =
                                    getVertexPositionWorldSpace(
                                            anchorsObj.get("left_lens_center_vertex").asInt,
                                            meshNodeTransform
                                    ),
                            rightLensCenter =
                                    getVertexPositionWorldSpace(
                                            anchorsObj.get("right_lens_center_vertex").asInt,
                                            meshNodeTransform
                                    ),
                            leftEarTip =
                                    getVertexPositionWorldSpace(
                                            anchorsObj.get("left_ear_tip_vertex").asInt,
                                            meshNodeTransform
                                    ),
                            rightEarTip =
                                    getVertexPositionWorldSpace(
                                            anchorsObj.get("right_ear_tip_vertex").asInt,
                                            meshNodeTransform
                                    )
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to read anchor vertex positions", e)
                    null
                }
            }

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
        }
        Log.e(TAG, "No node found with valid 'anchors' in extras")
        return null
    }

    private fun buildNodeMatrix(node: JsonObject): FloatArray {
        val m = FloatArray(16)
        AndroidMatrix.setIdentityM(m, 0)

        if (node.has("matrix")) {
            val matArr = node.getAsJsonArray("matrix")
            for (i in 0 until 16) m[i] = matArr[i].asFloat
            return m
        }

        val t =
                if (node.has("translation")) {
                    val arr = node.getAsJsonArray("translation")
                    floatArrayOf(arr[0].asFloat, arr[1].asFloat, arr[2].asFloat)
                } else floatArrayOf(0f, 0f, 0f)

        val r =
                if (node.has("rotation")) {
                    val arr = node.getAsJsonArray("rotation")
                    floatArrayOf(arr[0].asFloat, arr[1].asFloat, arr[2].asFloat, arr[3].asFloat)
                } else floatArrayOf(0f, 0f, 0f, 1f)

        val s =
                if (node.has("scale")) {
                    val arr = node.getAsJsonArray("scale")
                    floatArrayOf(arr[0].asFloat, arr[1].asFloat, arr[2].asFloat)
                } else floatArrayOf(1f, 1f, 1f)

        // Quaternion → rotation matrix
        val qx = r[0]
        val qy = r[1]
        val qz = r[2]
        val qw = r[3]
        val rotMat = FloatArray(16)
        AndroidMatrix.setIdentityM(rotMat, 0)
        rotMat[0] = 1 - 2 * (qy * qy + qz * qz)
        rotMat[1] = 2 * (qx * qy + qz * qw)
        rotMat[2] = 2 * (qx * qz - qy * qw)
        rotMat[4] = 2 * (qx * qy - qz * qw)
        rotMat[5] = 1 - 2 * (qx * qx + qz * qz)
        rotMat[6] = 2 * (qy * qz + qx * qw)
        rotMat[8] = 2 * (qx * qz + qy * qw)
        rotMat[9] = 2 * (qy * qz - qx * qw)
        rotMat[10] = 1 - 2 * (qx * qx + qy * qy)

        // M = T * R * S
        val scaleMat = FloatArray(16)
        AndroidMatrix.setIdentityM(scaleMat, 0)
        scaleMat[0] = s[0]
        scaleMat[5] = s[1]
        scaleMat[10] = s[2]

        val rs = FloatArray(16)
        AndroidMatrix.multiplyMM(rs, 0, rotMat, 0, scaleMat, 0)

        val transMat = FloatArray(16)
        AndroidMatrix.setIdentityM(transMat, 0)
        transMat[12] = t[0]
        transMat[13] = t[1]
        transMat[14] = t[2]

        AndroidMatrix.multiplyMM(m, 0, transMat, 0, rs, 0)
        return m
    }

    private fun identityMatrix(): FloatArray {
        val m = FloatArray(16)
        AndroidMatrix.setIdentityM(m, 0)
        return m
    }

    private fun parseVec3(obj: JsonObject, key: String): FloatArray {
        val arr = obj.getAsJsonArray(key)
        return FloatArray(3) { arr[it].asFloat }
    }
}
