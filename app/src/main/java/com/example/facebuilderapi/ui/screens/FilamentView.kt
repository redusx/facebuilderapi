package com.example.facebuilderapi.ui.screens

import android.content.Context
import android.net.Uri
import android.opengl.Matrix
import android.util.Log
import android.view.Choreographer
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
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
    attachmentUri: Uri?,
    mainModelRotation: Float,
    isAttachmentRotationMode: State<Boolean>,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val modelViewer = remember { ModelViewer(context) }

    // Log recomposition and current state
    Log.d("FilamentView", "FilamentView recomposed. mainModelRotation: $mainModelRotation, isAttachmentRotationMode: ${isAttachmentRotationMode.value}")

    LaunchedEffect(modelData) {
        modelData?.let {
            modelViewer.loadModelGlb(ByteBuffer.wrap(it))
        }
    }

    LaunchedEffect(attachmentUri) {
        attachmentUri?.let {
            context.contentResolver.openInputStream(it)?.use { inputStream ->
                val byteBuffer = ByteBuffer.wrap(inputStream.readBytes())
                modelViewer.loadAttachmentModel(byteBuffer)
            }
        }
    }

    // Update main model rotation
    LaunchedEffect(mainModelRotation) {
        modelViewer.setMainModelRotation(mainModelRotation)
    }

    // Update attachment rotation mode
    LaunchedEffect(isAttachmentRotationMode.value) {
        modelViewer.isAttachmentRotationMode.value = isAttachmentRotationMode.value
        modelViewer.setupTouchEvents() // Recreate listener with updated state
        Log.d("FilamentView", "isAttachmentRotationMode updated to: ${isAttachmentRotationMode.value}")
    }

    DisposableEffect(Unit) {
        onDispose {
            modelViewer.destroy()
        }
    }

    AndroidView(factory = { modelViewer.surfaceView }, modifier = modifier)
}

class ModelViewer(context: Context) : android.view.SurfaceHolder.Callback {

    companion object {
        init {
            Utils.init()
            com.google.android.filament.Filament.init()
        }
    }

    val surfaceView: SurfaceView = SurfaceView(context)
    private val choreographer: Choreographer = Choreographer.getInstance()
    private val frameCallback: Choreographer.FrameCallback = Choreographer.FrameCallback { frameTimeNanos ->
        choreographer.postFrameCallback(this.frameCallback)
        render(frameTimeNanos)
    }

    private val engine: Engine = Engine.create()
    private val materialProvider: MaterialProvider = UbershaderProvider(engine)
    private val entityManager = EntityManager.get()
    private val assetLoader: AssetLoader = AssetLoader(engine, materialProvider, entityManager)
    private val resourceLoader: ResourceLoader = ResourceLoader(engine)
    private val renderer = engine.createRenderer()
    private val view = engine.createView()
    private val camera = engine.createCamera(engine.entityManager.create())
    private val scene = engine.createScene()
    private var swapChain: com.google.android.filament.SwapChain? = null

    private var modelAsset: FilamentAsset? = null
    private var attachmentAsset: FilamentAsset? = null
    private var lastX = 0f
    private var lastY = 0f

    private var cameraDistance: Float = 10.0f
    private val scaleDetector: ScaleGestureDetector

    var isAttachmentRotationMode: MutableState<Boolean> = mutableStateOf(false)
    private var initialMainModelTransform: FloatArray? = null
    private var initialAttachmentTransform: FloatArray? = null

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            cameraDistance /= detector.scaleFactor
            cameraDistance = cameraDistance.coerceIn(1.0f, 20.0f)
            updateCameraTransform()
            return true
        }
    }

    init {
        surfaceView.holder.addCallback(this)
        scaleDetector = ScaleGestureDetector(context, ScaleListener())
        view.camera = camera
        view.scene = scene

        val iblBuffer = context.assets.open("venetian_crossroads_2k_ibl.ktx").use { ByteBuffer.wrap(it.readBytes()) }
        val ibl = KTX1Loader.createIndirectLight(engine, iblBuffer)
        scene.indirectLight = ibl.indirectLight
        scene.indirectLight!!.intensity = 30_000.0f

        val skyboxBuffer = context.assets.open("venetian_crossroads_2k_skybox.ktx").use { ByteBuffer.wrap(it.readBytes()) }
        val skybox = KTX1Loader.createSkybox(engine, skyboxBuffer)
        scene.skybox = skybox.skybox

        setupTouchEvents()
    }

    override fun surfaceCreated(holder: android.view.SurfaceHolder) {
        swapChain = engine.createSwapChain(holder.surface)
        choreographer.postFrameCallback(frameCallback)
    }

    override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, width: Int, height: Int) {
        view.viewport = com.google.android.filament.Viewport(0, 0, width, height)
        val aspect = width.toDouble() / height.toDouble()
        camera.setProjection(45.0, aspect, 0.1, 1000.0, com.google.android.filament.Camera.Fov.VERTICAL)
    }

    override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
        choreographer.removeFrameCallback(frameCallback)
        swapChain?.let {
            engine.destroySwapChain(it)
            engine.flushAndWait()
            swapChain = null
        }
    }

    fun loadModelGlb(buffer: ByteBuffer) {
        modelAsset?.let {
            scene.removeEntities(it.entities)
            assetLoader.destroyAsset(it)
        }
        modelAsset = assetLoader.createAsset(buffer)?.also {
            resourceLoader.loadResources(it)
            it.releaseSourceData()
            scene.addEntities(it.entities)
            frameCamera(it)
            // Store initial transform
            val tm = engine.transformManager
            val root = tm.getInstance(it.root)
            initialMainModelTransform = FloatArray(16)
            tm.getTransform(root, initialMainModelTransform!!)
            setMainModelRotation(180f) // Set initial rotation to 0 degrees (slider middle)
            Log.d("ModelViewer", "initialMainModelTransform set after loading model.")
        }
    }

    fun loadAttachmentModel(buffer: ByteBuffer) {
        Log.d("ModelViewer", "loadAttachmentModel called.")
        attachmentAsset?.let {
            scene.removeEntities(it.entities)
            assetLoader.destroyAsset(it)
        }
        attachmentAsset = assetLoader.createAsset(buffer)?.also {
            resourceLoader.loadResources(it)
            it.releaseSourceData()
            scene.addEntities(it.entities)

            // Initial positioning and scaling for the attachment
            modelAsset?.let { mainAsset ->
                val mainBBox = mainAsset.boundingBox
                val attachmentBBox = it.boundingBox

                val mainCenter = mainBBox.center.let { floatArrayOf(it[0], it[1], it[2]) }
                val mainHalfExtent = mainBBox.halfExtent.let { floatArrayOf(it[0], it[1], it[2]) }
                val attachmentCenter = attachmentBBox.center.let { floatArrayOf(it[0], it[1], it[2]) }
                val attachmentHalfExtent = attachmentBBox.halfExtent.let { floatArrayOf(it[0], it[1], it[2]) }

                val tm = engine.transformManager
                val root = tm.getInstance(it.root)
                val transform = FloatArray(16)
                Matrix.setIdentityM(transform, 0)

                // Calculate scale factor to fit attachment relative to main model
                // This is a heuristic, adjust as needed
                val scaleFactor = (mainHalfExtent[0] * 0.5f) / attachmentHalfExtent[0].coerceAtLeast(0.001f)
                Matrix.scaleM(transform, 0, scaleFactor, scaleFactor, scaleFactor)

                // Translate to be in front of the main model, slightly above its center
                Matrix.translateM(transform, 0, mainCenter[0] - attachmentCenter[0], mainCenter[1] - attachmentCenter[1] + (mainHalfExtent[1] * 0.5f), mainCenter[2] - attachmentCenter[2] + (mainHalfExtent[2] * 1.2f))

                tm.setTransform(root, transform)
                // Store initial transform for attachment
                initialAttachmentTransform = FloatArray(16)
                tm.getTransform(root, initialAttachmentTransform!!)
            }
        }
    }

    fun setMainModelRotation(rotationDegrees: Float) {
        Log.d("ModelViewer", "setMainModelRotation called with: $rotationDegrees degrees")
        modelAsset?.let { asset ->
            // Map slider value (0-360) to model rotation (-180 to 180)
            val actualModelRotation = rotationDegrees - 180f
            val tm = engine.transformManager
            val root = tm.getInstance(asset.root)

            initialMainModelTransform?.let { initialTransform ->
                val rotationMatrix = FloatArray(16)
                Matrix.setIdentityM(rotationMatrix, 0)
                Matrix.rotateM(rotationMatrix, 0, actualModelRotation, 0f, 1f, 0f) // Y-axis rotation

                val finalTransform = FloatArray(16)
                Matrix.multiplyMM(finalTransform, 0, rotationMatrix, 0, initialTransform, 0)
                tm.setTransform(root, finalTransform)
            }
        }
    }

    private fun rotateAttachment(deltaX: Float, deltaY: Float) {
        attachmentAsset?.let { asset ->
            val tm = engine.transformManager
            val root = tm.getInstance(asset.root)
            val currentTransform = FloatArray(16)
            tm.getTransform(root, currentTransform)

            val attachmentBBox = asset.boundingBox
            val attachmentCenter = attachmentBBox.center.let { floatArrayOf(it[0], it[1], it[2]) }

            // 1. Translate to origin (relative to current position)
            val toOrigin = FloatArray(16)
            Matrix.setIdentityM(toOrigin, 0)
            Matrix.translateM(toOrigin, 0, -attachmentCenter[0], -attachmentCenter[1], -attachmentCenter[2])

            // 2. Apply rotation
            val rotationMatrix = FloatArray(16)
            Matrix.setIdentityM(rotationMatrix, 0)
            Matrix.rotateM(rotationMatrix, 0, deltaX * -0.1f, 0f, 1f, 0f) // Y-axis rotation
            Matrix.rotateM(rotationMatrix, 0, deltaY * -0.1f, 1f, 0f, 0f) // X-axis rotation

            // 3. Translate back from origin
            val fromOrigin = FloatArray(16)
            Matrix.setIdentityM(fromOrigin, 0)
            Matrix.translateM(fromOrigin, 0, attachmentCenter[0], attachmentCenter[1], attachmentCenter[2])

            // Combine transformations: current -> toOrigin -> rotate -> fromOrigin
            val temp1 = FloatArray(16)
            Matrix.multiplyMM(temp1, 0, rotationMatrix, 0, toOrigin, 0)
            val temp2 = FloatArray(16)
            Matrix.multiplyMM(temp2, 0, fromOrigin, 0, temp1, 0)
            val finalTransform = FloatArray(16)
            Matrix.multiplyMM(finalTransform, 0, currentTransform, 0, temp2, 0)

            tm.setTransform(root, finalTransform)
        }
    }

    private fun translateAttachment(deltaX: Float, deltaY: Float) {
        attachmentAsset?.let { asset ->
            val tm = engine.transformManager
            val root = tm.getInstance(asset.root)
            val current = FloatArray(16)
            tm.getTransform(root, current)

            val translation = FloatArray(16)
            Matrix.setIdentityM(translation, 0)
            Matrix.translateM(translation, 0, deltaX * 0.01f, -deltaY * 0.01f, 0f)

            val result = FloatArray(16)
            Matrix.multiplyMM(result, 0, translation, 0, current, 0)
            tm.setTransform(root, result)
        }
    }

    private fun frameCamera(asset: FilamentAsset) {
        val boundingBox = asset.boundingBox
        val center = boundingBox.center.let { floatArrayOf(it[0], it[1], it[2]) }
        val halfExtent = boundingBox.halfExtent.let { floatArrayOf(it[0], it[1], it[2]) }
        val maxExtent = halfExtent.maxOrNull() ?: 0.0f
        cameraDistance = (maxExtent * 2.5f) / kotlin.math.tan(Math.toRadians(45.0)).toFloat()
        updateCameraTransform()
    }

    private fun updateCameraTransform() {
        val asset = modelAsset ?: return
        val center = asset.boundingBox.center.let { floatArrayOf(it[0], it[1], it[2]) }
        val eye = floatArrayOf(center[0], center[1], center[2] + cameraDistance)
        val target = center
        val up = floatArrayOf(0.0f, 1.0f, 0.0f)

        camera.lookAt(
            eye[0].toDouble(), eye[1].toDouble(), eye[2].toDouble(),
            target[0].toDouble(), target[1].toDouble(), target[2].toDouble(),
            up[0].toDouble(), up[1].toDouble(), up[2].toDouble()
        )
    }

    private fun render(frameTimeNanos: Long) {
        val sc = swapChain ?: return
        if (renderer.beginFrame(sc, frameTimeNanos)) {
            renderer.render(view)
            renderer.endFrame()
        }
    }

    fun setupTouchEvents() {
        Log.d("ModelViewer", "setupTouchEvents called. Current isAttachmentRotationMode: ${this.isAttachmentRotationMode.value}")
        surfaceView.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            if (scaleDetector.isInProgress) {
                return@setOnTouchListener true
            }

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x
                    lastY = event.y
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.x - lastX
                    val deltaY = event.y - lastY
                    lastX = event.x
                    lastY = event.y

                    if (attachmentAsset != null) {
                        Log.d("ModelViewer", "onTouchEvent - isAttachmentRotationMode: ${this.isAttachmentRotationMode.value}")
                        if (this.isAttachmentRotationMode.value) {
                            rotateAttachment(deltaX, deltaY)
                        } else {
                            translateAttachment(deltaX, deltaY)
                        }
                    }
                }
            }
            true
        }
    }

    fun destroy() {
        surfaceView.holder.removeCallback(this)
        choreographer.removeFrameCallback(frameCallback)
        modelAsset?.let {
            scene.removeEntities(it.entities)
            assetLoader.destroyAsset(it)
        }
        attachmentAsset?.let {
            scene.removeEntities(it.entities)
            assetLoader.destroyAsset(it)
        }
        resourceLoader.destroy()
        assetLoader.destroy()
        materialProvider.destroy()
        engine.destroyRenderer(renderer)
        engine.destroyView(view)
        engine.destroyScene(scene)
        engine.entityManager.destroy(camera.entity) // Correctly destroy the camera's entity
        engine.destroy()
    }
}
