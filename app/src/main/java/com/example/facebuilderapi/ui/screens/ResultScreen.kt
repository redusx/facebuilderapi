package com.example.facebuilderapi.ui.screens

import android.content.Context
import android.util.Log
import android.view.ScaleGestureDetector
import android.opengl.Matrix
import android.os.Environment
import android.view.Choreographer
import android.view.MotionEvent
import android.view.SurfaceView
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.example.facebuilderapi.viewmodel.AvatarCreationState
import com.example.facebuilderapi.viewmodel.FaceBuilderViewModel
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.gltfio.*
import com.google.android.filament.utils.KTX1Loader
import com.google.android.filament.utils.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.nio.ByteBuffer


// ----------------------
// ✅ UI KISMI (Compose)
// ----------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultScreen(
    viewModel: FaceBuilderViewModel,
    avatarId: String
) {
    val mainState by viewModel.state.collectAsState()
    val modelData by viewModel.modelData.collectAsState()
    val context = LocalContext.current

    // Trigger the model load when the screen is first displayed
    LaunchedEffect(avatarId) {
        viewModel.loadModelForRendering(avatarId)
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Avatar Result") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Display error if one occurs
            if (mainState is AvatarCreationState.Error) {
                Text(
                    text = "Error loading model: ${(mainState as AvatarCreationState.Error).message}",
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                // Display the model viewer or a progress indicator
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (modelData == null) {
                        CircularProgressIndicator()
                    } else {
                        FilamentView(modelData)
                    }
                }
                // The download button now uses the avatarId, not a full URL
                Button(onClick = { 
                    val fileName = "avatar_$avatarId.glb"
                    val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName)
                    viewModel.download3DModel(avatarId, file) { result ->
                        result.onSuccess {
                            Toast.makeText(context, "Model saved to Downloads folder", Toast.LENGTH_LONG).show()
                        }.onFailure {
                            Toast.makeText(context, "Download failed: ${it.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }) {
                    Text("Download Model")
                }
            }
        }
    }
}

@Composable
fun FilamentView(modelData: ByteBuffer?) {
    val context = LocalContext.current
    val modelViewer = remember(context) { ModelViewer(context) }

    LaunchedEffect(modelData) {
        modelData?.let {
            // The modelViewer now directly receives the data buffer
            modelViewer.loadModelGlb(it)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            modelViewer.destroy()
        }
    }

    AndroidView(factory = { modelViewer.surfaceView })
}


// ----------------------
// ✅ Filament ModelViewer
// ----------------------
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
    private var lastX = 0f

    // Zoom and camera state
    private var cameraDistance: Float = 10.0f
    private val scaleDetector: ScaleGestureDetector

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            cameraDistance /= detector.scaleFactor
            // Clamp the zoom level
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

        // Load environment lighting and skybox
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
        Log.d("ModelViewer", "Attempting to load model from buffer...")
        modelAsset?.let {
            scene.removeEntities(it.entities)
            assetLoader.destroyAsset(it)
            modelAsset = null
        }

        val asset = assetLoader.createAsset(buffer)
        if (asset == null) {
            Log.e("ModelViewer", "Failed to create asset from buffer. The model might be invalid or corrupted.")
            return
        }
        Log.d("ModelViewer", "Asset created successfully.")

        resourceLoader.loadResources(asset)
        asset.releaseSourceData()
        scene.addEntities(asset.entities)
        modelAsset = asset
        frameCamera(asset)
    }

    private fun frameCamera(asset: FilamentAsset) {
        val boundingBox = asset.boundingBox
        Log.d("ModelViewer", "Asset Bounding Box: $boundingBox")

        val center = boundingBox.center.let { floatArrayOf(it[0], it[1], it[2]) }
        val halfExtent = boundingBox.halfExtent.let { floatArrayOf(it[0], it[1], it[2]) }
        Log.d("ModelViewer", "Bounding Box Center: ${center.joinToString()}, Half Extent: ${halfExtent.joinToString()}")

        val maxExtent = halfExtent.maxOrNull() ?: 0.0f
        cameraDistance = (maxExtent * 2.5f) / kotlin.math.tan(Math.toRadians(45.0)).toFloat()
        Log.d("ModelViewer", "Initial Camera Distance: $cameraDistance")
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

    private fun setupTouchEvents() {
        surfaceView.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            if (!scaleDetector.isInProgress) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> lastX = event.x
                    MotionEvent.ACTION_MOVE -> {
                        val deltaX = event.x - lastX
                        lastX = event.x
                        modelAsset?.let { asset ->
                            val tm = engine.transformManager
                            val root = tm.getInstance(asset.root)
                            val current = FloatArray(16)
                            tm.getTransform(root, current)

                            val rotationDeg = deltaX * -0.1f
                            val rot = FloatArray(16)
                            Matrix.setRotateM(rot, 0, rotationDeg, 0f, 1f, 0f)

                            val result = FloatArray(16)
                            Matrix.multiplyMM(result, 0, current, 0, rot, 0)
                            tm.setTransform(root, result)
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
            modelAsset = null
        }
        engine.destroy()
    }
}