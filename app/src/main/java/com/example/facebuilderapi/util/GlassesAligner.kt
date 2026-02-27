package com.example.facebuilderapi.util

import android.opengl.Matrix
import android.util.Log
import kotlin.math.sqrt

/**
 * Computes the 4x4 transform matrix to align a glasses model onto a head model using
 * landmark-to-anchor point correspondence.
 *
 * Algorithm:
 * 1. Scale: headIPD / glassesIPD (uniform)
 * 2. Rotation: align glasses coordinate frame to head coordinate frame
 * 3. Translation: move glasses nose_bridge to head nose_bridge vertex position
 */
object GlassesAligner {

    private const val TAG = "GlassesAligner"

    /**
     * Computes the alignment transform: glasses-local → head-local space. Returns a 4x4
     * column-major matrix suitable for Android's Matrix / Filament.
     */
    fun computeAlignmentTransform(
            head: FaceLandmarks,
            glasses: GlassesAnchors,
            manualScale: Float = 1.0f
    ): FloatArray {

        // Debug: log all input positions
        Log.d(TAG, "=== ALIGNMENT DEBUG ===")
        Log.d(TAG, "Head noseBridge: ${head.noseBridge.contentToString()}")
        Log.d(TAG, "Head leftEyeInner: ${head.leftEyeInner.contentToString()}")
        Log.d(TAG, "Head rightEyeInner: ${head.rightEyeInner.contentToString()}")
        Log.d(TAG, "Head leftEarTragus: ${head.leftEarTragus.contentToString()}")
        Log.d(TAG, "Head rightEarTragus: ${head.rightEarTragus.contentToString()}")
        Log.d(TAG, "Glasses noseBridge: ${glasses.noseBridge.contentToString()}")
        Log.d(TAG, "Glasses leftLensCenter: ${glasses.leftLensCenter.contentToString()}")
        Log.d(TAG, "Glasses rightLensCenter: ${glasses.rightLensCenter.contentToString()}")
        Log.d(TAG, "Glasses leftEarTip: ${glasses.leftEarTip.contentToString()}")
        Log.d(TAG, "Glasses rightEarTip: ${glasses.rightEarTip.contentToString()}")

        // --- 1. SCALE ---
        // The user's lens center vertices are asymmetric (Y=3.0 vs 1.3). Using them for scale/axes
        // ruins everything.
        // The ear tips are perfectly symmetric! We use the distance between ears for scale.
        val headEarDist = distance(head.leftEarTragus, head.rightEarTragus)
        val glassesEarDist = distance(glasses.leftEarTip, glasses.rightEarTip)
        // Apply 0.85x multiplier because ear tips on glasses are usually wider than ear tragus on
        // head
        val scale =
                (if (glassesEarDist > 0.0001f) headEarDist / glassesEarDist else 1f) *
                        0.85f *
                        manualScale
        Log.d(TAG, "EarDist: head=$headEarDist, glasses=$glassesEarDist, scale=$scale")

        // --- 1.5. FIX BROKEN NOSE BRIDGE METADATA ---
        // The user's nose_bridge_vertex is completely wrong (it's at X=0, but glasses are at X=17).
        // It causes a 90-degree rotation and bad translation. We dynamically calculate the nose
        // bridge
        // as the midpoint between the two lenses.
        val gNoseBridge = mid(glasses.leftLensCenter, glasses.rightLensCenter)
        Log.d(TAG, "Computed real glasses nose bridge: ${gNoseBridge.contentToString()}")

        // --- 2. COORDINATE FRAMES ---
        // Head frame (using ear tragus for the X axis to be stable)
        val headX = normalize(sub(head.rightEarTragus, head.leftEarTragus)) // right
        val headEarMid = mid(head.leftEarTragus, head.rightEarTragus)
        val headFwd = normalize(sub(head.noseBridge, headEarMid)) // forward
        val headY = normalize(cross(headFwd, headX)) // up
        val headZ = normalize(cross(headX, headY)) // re-ortho forward
        Log.d(TAG, "Head frame X(right): ${headX.contentToString()}")
        Log.d(TAG, "Head frame Y(up): ${headY.contentToString()}")
        Log.d(TAG, "Head frame Z(fwd): ${headZ.contentToString()}")

        // Glasses frame (using ear tips for X axis since they are symmetric)
        val gX = normalize(sub(glasses.rightEarTip, glasses.leftEarTip)) // right
        val gEarMid = mid(glasses.leftEarTip, glasses.rightEarTip)
        // Use exactly the computed nose bridge instead of the broken metadata one
        val gFwd = normalize(sub(gNoseBridge, gEarMid)) // forward
        val gY = normalize(cross(gFwd, gX)) // up
        val gZ = normalize(cross(gX, gY)) // re-ortho forward
        Log.d(TAG, "Glasses frame X(right): ${gX.contentToString()}")
        Log.d(TAG, "Glasses frame Y(up): ${gY.contentToString()}")
        Log.d(TAG, "Glasses frame Z(fwd): ${gZ.contentToString()}")

        // --- 3. ROTATION: R = HeadFrame * GlassesFrame^T ---
        val hMat = frameToMatrix(headX, headY, headZ)
        val gtMat = transposeFrameToMatrix(gX, gY, gZ)
        val rotMat = FloatArray(16)
        Matrix.multiplyMM(rotMat, 0, hMat, 0, gtMat, 0)

        // --- 4. COMBINE: T(head_nose) * R * S * T(-glasses_nose) ---
        // Step A: Translate glasses so the COMPUTED nose bridge moves to origin
        val toOrigin = FloatArray(16)
        Matrix.setIdentityM(toOrigin, 0)
        toOrigin[12] = -gNoseBridge[0]
        toOrigin[13] = -gNoseBridge[1]
        toOrigin[14] = -gNoseBridge[2]

        // Step B: Scale
        val scaleMat = FloatArray(16)
        Matrix.setIdentityM(scaleMat, 0)
        scaleMat[0] = scale
        scaleMat[5] = scale
        scaleMat[10] = scale

        // Step C: S * T(-glasses_nose)
        val stMat = FloatArray(16)
        Matrix.multiplyMM(stMat, 0, scaleMat, 0, toOrigin, 0)

        // Step D: R * S * T(-glasses_nose)
        val rstMat = FloatArray(16)
        Matrix.multiplyMM(rstMat, 0, rotMat, 0, stMat, 0)

        // Step E: T(head_nose) * R * S * T(-glasses_nose)
        val transMat = FloatArray(16)
        Matrix.setIdentityM(transMat, 0)

        // To fix the "1-2cm right shift", we don't just put it on the head.noseBridge X.
        // We explicitly align the geometric center of the glasses to the geometric center of the
        // head.
        // The head's center on the X-axis is headEarMid[0].
        // So we set the target X to be headEarMid[0], but Y and Z stay locked to the noseBridge.
        transMat[12] = headEarMid[0]
        transMat[13] = head.noseBridge[1]
        transMat[14] = head.noseBridge[2]

        val result = FloatArray(16)
        Matrix.multiplyMM(result, 0, transMat, 0, rstMat, 0)

        Log.d(TAG, "Final transform: [${result.joinToString()}]")
        Log.d(TAG, "=== END ALIGNMENT ===")
        return result
    }

    // --- Vector math helpers ---

    private fun sub(a: FloatArray, b: FloatArray) =
            floatArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])

    private fun mid(a: FloatArray, b: FloatArray) =
            floatArrayOf((a[0] + b[0]) / 2f, (a[1] + b[1]) / 2f, (a[2] + b[2]) / 2f)

    private fun cross(a: FloatArray, b: FloatArray) =
            floatArrayOf(
                    a[1] * b[2] - a[2] * b[1],
                    a[2] * b[0] - a[0] * b[2],
                    a[0] * b[1] - a[1] * b[0]
            )

    private fun normalize(v: FloatArray): FloatArray {
        val len = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        return if (len > 0.0001f) floatArrayOf(v[0] / len, v[1] / len, v[2] / len)
        else floatArrayOf(0f, 0f, 0f)
    }

    private fun distance(a: FloatArray, b: FloatArray): Float {
        val d = sub(a, b)
        return sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2])
    }

    /** Builds a 4x4 column-major rotation matrix from 3 basis vectors (columns). */
    private fun frameToMatrix(x: FloatArray, y: FloatArray, z: FloatArray): FloatArray {
        val m = FloatArray(16)
        Matrix.setIdentityM(m, 0)
        m[0] = x[0]
        m[1] = x[1]
        m[2] = x[2] // column 0
        m[4] = y[0]
        m[5] = y[1]
        m[6] = y[2] // column 1
        m[8] = z[0]
        m[9] = z[1]
        m[10] = z[2] // column 2
        return m
    }

    /** Builds a 4x4 column-major matrix that is the TRANSPOSE of the frame. */
    private fun transposeFrameToMatrix(x: FloatArray, y: FloatArray, z: FloatArray): FloatArray {
        val m = FloatArray(16)
        Matrix.setIdentityM(m, 0)
        // Transpose: rows of original become columns
        m[0] = x[0]
        m[1] = y[0]
        m[2] = z[0]
        m[4] = x[1]
        m[5] = y[1]
        m[6] = z[1]
        m[8] = x[2]
        m[9] = y[2]
        m[10] = z[2]
        return m
    }
}
