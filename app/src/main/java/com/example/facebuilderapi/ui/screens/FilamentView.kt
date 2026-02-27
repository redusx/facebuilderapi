package com.example.facebuilderapi.ui.screens

import android.content.Context
import android.opengl.Matrix
import android.util.Log
import android.view.Choreographer
import android.view.ScaleGestureDetector
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.example.facebuilderapi.util.*
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.MaterialProvider
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import com.google.android.filament.utils.KTX1Loader
import com.google.android.filament.utils.Utils
import java.nio.ByteBuffer

@Composable
fun FilamentView(
        modelData: ByteArray?,
        glassesData: ByteArray?,
        mainModelRotation: Float,
        glassesScale: Float = 1.0f,
        modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val viewer = remember { ModelViewer(context) }

    LaunchedEffect(modelData) { modelData?.let { viewer.loadHeadModel(ByteBuffer.wrap(it)) } }
    LaunchedEffect(glassesData) { glassesData?.let { viewer.loadGlasses(it) } }
    LaunchedEffect(mainModelRotation) { viewer.setRotation(mainModelRotation) }
    LaunchedEffect(glassesScale) { viewer.setGlassesScale(glassesScale) }

    DisposableEffect(Unit) { onDispose { viewer.destroy() } }

    AndroidView(factory = { viewer.surfaceView }, modifier = modifier)
}

/**
 * Filament-based 3D viewer with automatic glasses alignment.
 *
 * Alignment strategy:
 * - Both head and glasses anchors are computed in WORLD-SPACE (with node TRS applied).
 * - The alignment matrix maps glasses-world → head-world.
 * - The glasses root entity is PARENTED under the head root entity.
 * - Since parented, Filament chains: headTransform * glassesRoot * childNodeTRS * meshVertex.
 * - We set glassesRoot = alignmentMatrix so that childNodeTRS * v → world-space, then
 * alignmentMatrix maps that to head-local space, and headTransform places it in scene.
 */
class ModelViewer(context: Context) : android.view.SurfaceHolder.Callback {

    companion object {
        private const val TAG = "ModelViewer"
        init {
            Utils.init()
            com.google.android.filament.Filament.init()
        }
    }

    val surfaceView: SurfaceView = SurfaceView(context)

    // Filament core
    private val engine: Engine = Engine.create()
    private val materialProvider: MaterialProvider = UbershaderProvider(engine)
    private val assetLoader: AssetLoader =
            AssetLoader(engine, materialProvider, EntityManager.get())
    private val resourceLoader: ResourceLoader = ResourceLoader(engine)
    private val renderer = engine.createRenderer()
    private val filamentView = engine.createView()
    private val camera = engine.createCamera(engine.entityManager.create())
    private val scene = engine.createScene()
    private var swapChain: com.google.android.filament.SwapChain? = null

    // Frame loop
    private val choreographer: Choreographer = Choreographer.getInstance()
    private lateinit var frameCallback: Choreographer.FrameCallback

    // Model state
    private var headAsset: FilamentAsset? = null
    private var glassesAsset: FilamentAsset? = null
    private var headRawData: ByteArray? = null
    private var initialHeadTransform: FloatArray? = null
    private var cameraDistance: Float = 10f

    // Zoom
    private val scaleDetector: ScaleGestureDetector

    init {
        frameCallback =
                Choreographer.FrameCallback { nanos ->
                    choreographer.postFrameCallback(frameCallback)
                    render(nanos)
                }
        surfaceView.holder.addCallback(this)
        filamentView.camera = camera
        filamentView.scene = scene

        // IBL
        val iblBuffer =
                context.assets.open("venetian_crossroads_2k_ibl.ktx").use {
                    ByteBuffer.wrap(it.readBytes())
                }
        val ibl = KTX1Loader.createIndirectLight(engine, iblBuffer)
        scene.indirectLight = ibl.indirectLight
        scene.indirectLight!!.intensity = 30_000f

        // Skybox
        val skyboxBuffer =
                context.assets.open("venetian_crossroads_2k_skybox.ktx").use {
                    ByteBuffer.wrap(it.readBytes())
                }
        val skybox = KTX1Loader.createSkybox(engine, skyboxBuffer)
        scene.skybox = skybox.skybox

        // Pinch-to-zoom
        scaleDetector =
                ScaleGestureDetector(
                        context,
                        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                            override fun onScale(detector: ScaleGestureDetector): Boolean {
                                cameraDistance /= detector.scaleFactor
                                cameraDistance = cameraDistance.coerceIn(0.5f, 30.0f)
                                updateCamera()
                                return true
                            }
                        }
                )

        surfaceView.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            true
        }
    }

    // ─── Head Model ───────────────────────────────────────────

    fun loadHeadModel(buffer: ByteBuffer) {
        val bytes = ByteArray(buffer.remaining())
        buffer.duplicate().get(bytes)
        headRawData = bytes

        headAsset?.let {
            scene.removeEntities(it.entities)
            assetLoader.destroyAsset(it)
        }

        headAsset =
                assetLoader.createAsset(buffer)?.also {
                    resourceLoader.loadResources(it)
                    it.releaseSourceData()
                    scene.addEntities(it.entities)
                    frameCamera(it)

                    val tm = engine.transformManager
                    val root = tm.getInstance(it.root)
                    initialHeadTransform = FloatArray(16).also { arr -> tm.getTransform(root, arr) }
                    setRotation(180f)
                    Log.d(TAG, "Head model loaded.")
                }
    }

    // ─── Glasses (Parented Alignment) ─────────────────────────

    fun loadGlasses(glassesGlbData: ByteArray) {
        val rawHead =
                headRawData
                        ?: run {
                            Log.e(TAG, "Head model raw data not available")
                            return
                        }
        val head =
                headAsset
                        ?: run {
                            Log.e(TAG, "Head model not loaded")
                            return
                        }

        try {
            // 1. Parse head — world-space positions (head TRS is identity)
            val headParser = GlbParser(rawHead)
            val headNodeTRS = headParser.getNodeTransformForMesh(0)
            val landmarks =
                    FaceLandmarks(
                            noseBridge =
                                    headParser.getVertexPositionWorldSpace(
                                            HeadLandmarks.NOSE_BRIDGE,
                                            headNodeTRS
                                    ),
                            leftEyeInner =
                                    headParser.getVertexPositionWorldSpace(
                                            HeadLandmarks.LEFT_EYE_INNER,
                                            headNodeTRS
                                    ),
                            rightEyeInner =
                                    headParser.getVertexPositionWorldSpace(
                                            HeadLandmarks.RIGHT_EYE_INNER,
                                            headNodeTRS
                                    ),
                            leftEarTragus =
                                    headParser.getVertexPositionWorldSpace(
                                            HeadLandmarks.LEFT_EAR_TRAGUS,
                                            headNodeTRS
                                    ),
                            rightEarTragus =
                                    headParser.getVertexPositionWorldSpace(
                                            HeadLandmarks.RIGHT_EAR_TRAGUS,
                                            headNodeTRS
                                    )
                    )
            Log.d(TAG, "Head noseBridge: ${landmarks.noseBridge.contentToString()}")

            // 2. Parse glasses — world-space anchors (with node TRS applied)
            val glassesParser = GlbParser(glassesGlbData)
            Log.d(TAG, "Glasses nodes:\n${glassesParser.dumpNodesJson()}")

            val anchors = glassesParser.getGlassesAnchors()
            if (anchors == null) {
                Log.e(TAG, "No glasses anchor metadata found")
                return
            }
            Log.d(TAG, "Glasses noseBridge (world): ${anchors.noseBridge.contentToString()}")
            Log.d(TAG, "Glasses leftLens (world): ${anchors.leftLensCenter.contentToString()}")
            Log.d(TAG, "Glasses rightLens (world): ${anchors.rightLensCenter.contentToString()}")

            // 3. Compute alignment (world-space → world-space)
            val alignmentMatrix = GlassesAligner.computeAlignmentTransform(landmarks, anchors)

            // 4. Load glasses asset into Filament
            glassesAsset?.let {
                scene.removeEntities(it.entities)
                assetLoader.destroyAsset(it)
            }
            glassesAsset =
                    assetLoader.createAsset(ByteBuffer.wrap(glassesGlbData))?.also { asset ->
                        resourceLoader.loadResources(asset)
                        asset.releaseSourceData()
                        scene.addEntities(asset.entities)

                        val tm = engine.transformManager

                        // 5. PARENT glasses root under head root.
                        // This makes glasses automatically follow head rotation.
                        // Transform chain: headTransform * alignmentMatrix * childNodeTRS * v
                        // Since childNodeTRS * v = v_worldSpace, and
                        // alignmentMatrix * v_worldSpace = head_localSpace,
                        // headTransform places it correctly in the scene.
                        val headRootEntity = head.root
                        val glassesRootInst = tm.getInstance(asset.root)

                        // Set parent: glasses root becomes child of head root
                        tm.setParent(glassesRootInst, tm.getInstance(headRootEntity))

                        // 6. Set glasses root LOCAL transform = alignment matrix
                        // This is relative to head root, so glasses follow head rotation
                        cachedHeadLandmarks = landmarks
                        cachedGlassesAnchors = anchors
                        tm.setTransform(glassesRootInst, alignmentMatrix)

                        Log.d(TAG, "Glasses parented under head and aligned.")
                    }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load/align glasses", e)
        }
    }

    private var cachedHeadLandmarks: FaceLandmarks? = null
    private var cachedGlassesAnchors: GlassesAnchors? = null
    private var currentGlassesScale: Float = 1.0f

    // ─── Scale (Manual Controls) ──────────────────────────────

    fun setGlassesScale(scaleMultiplier: Float) {
        currentGlassesScale = scaleMultiplier
        val asset = glassesAsset ?: return
        val headLandmarks = cachedHeadLandmarks ?: return
        val glassesAnchors = cachedGlassesAnchors ?: return

        // Recalculate the entire alignment transform with the specific manual scale.
        // This ensures the glasses scale properly around their own nose bridge
        // without shifting out of place, unlike a naive matrix multiplication.
        val alignmentMatrix =
                GlassesAligner.computeAlignmentTransform(
                        headLandmarks,
                        glassesAnchors,
                        scaleMultiplier
                )

        val tm = engine.transformManager
        val inst = tm.getInstance(asset.root)
        tm.setTransform(inst, alignmentMatrix)
    }

    // ─── Rotation (Slider) ────────────────────────────────────

    fun setRotation(sliderDegrees: Float) {
        val headModel = headAsset ?: return
        val initial = initialHeadTransform ?: return
        val angle = sliderDegrees - 180f

        val rotMat = FloatArray(16)
        Matrix.setIdentityM(rotMat, 0)
        Matrix.rotateM(rotMat, 0, angle, 0f, 1f, 0f)

        val finalTransform = FloatArray(16)
        Matrix.multiplyMM(finalTransform, 0, rotMat, 0, initial, 0)

        val tm = engine.transformManager
        tm.setTransform(tm.getInstance(headModel.root), finalTransform)

        // Glasses follow automatically since they're parented under head root!
    }

    // ─── Camera ───────────────────────────────────────────────

    private fun frameCamera(asset: FilamentAsset) {
        val halfExtent = asset.boundingBox.halfExtent
        val maxExtent = halfExtent.maxOrNull() ?: 1f
        cameraDistance = (maxExtent * 2.5f) / kotlin.math.tan(Math.toRadians(45.0)).toFloat()
        updateCamera()
    }

    private fun updateCamera() {
        val center = headAsset?.boundingBox?.center ?: floatArrayOf(0f, 0f, 0f)
        camera.lookAt(
                center[0].toDouble(),
                center[1].toDouble(),
                (center[2] + cameraDistance).toDouble(),
                center[0].toDouble(),
                center[1].toDouble(),
                center[2].toDouble(),
                0.0,
                1.0,
                0.0
        )
    }

    // ─── Render Loop ──────────────────────────────────────────

    private fun render(@Suppress("UNUSED_PARAMETER") frameTimeNanos: Long) {
        val sc = swapChain ?: return
        if (renderer.beginFrame(sc, frameTimeNanos)) {
            renderer.render(filamentView)
            renderer.endFrame()
        }
    }

    // ─── Surface Callbacks ────────────────────────────────────

    override fun surfaceCreated(holder: android.view.SurfaceHolder) {
        swapChain = engine.createSwapChain(holder.surface)
        choreographer.postFrameCallback(frameCallback)
    }

    override fun surfaceChanged(
            holder: android.view.SurfaceHolder,
            format: Int,
            width: Int,
            height: Int
    ) {
        filamentView.viewport = com.google.android.filament.Viewport(0, 0, width, height)
        val aspect = width.toDouble() / height.toDouble()
        camera.setProjection(
                45.0,
                aspect,
                0.1,
                100.0,
                com.google.android.filament.Camera.Fov.VERTICAL
        )
    }

    override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
        choreographer.removeFrameCallback(frameCallback)
        swapChain?.let { engine.destroySwapChain(it) }
        swapChain = null
    }

    // ─── Cleanup ──────────────────────────────────────────────

    fun destroy() {
        surfaceView.holder.removeCallback(this)
        choreographer.removeFrameCallback(frameCallback)
        headAsset?.let {
            scene.removeEntities(it.entities)
            assetLoader.destroyAsset(it)
        }
        glassesAsset?.let {
            scene.removeEntities(it.entities)
            assetLoader.destroyAsset(it)
        }
        resourceLoader.destroy()
        assetLoader.destroy()
        materialProvider.destroy()
        engine.destroyRenderer(renderer)
        engine.destroyView(filamentView)
        engine.destroyScene(scene)
        engine.entityManager.destroy(camera.entity)
        engine.destroy()
    }
}
