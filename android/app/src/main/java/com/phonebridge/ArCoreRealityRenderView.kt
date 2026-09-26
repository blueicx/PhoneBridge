package com.phonebridge

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.media.Image
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Surface
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.google.ar.core.Anchor
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max

class ArCoreRealityRenderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GLSurfaceView(context, attrs), GLSurfaceView.Renderer {

    interface Listener {
        fun onTrackingSnapshot(snapshot: RealityTrackingSnapshot) {}
        fun onSessionStarted() {}
        fun onRuntimeFailure(reason: String) {}
        fun onQualityFallback(thermal: Boolean) {}
        fun onLocalCue(signal: RealityImageSignal) {}
        fun wantsRemoteFrame(): Boolean = false
        fun onRemoteFrame(planes: RealityImagePlanes) {}
        fun onAnchorPlacementResult(placed: Boolean) {}
        fun currentTemperatureCelsius(): Float? = null
    }

    @Volatile private var listener: Listener? = null
    @Volatile private var arSession: Session? = null
    @Volatile private var latestFrame: Frame? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessionCreationExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "PhoneBridge-ArCore-Session").apply { isDaemon = true }
    }
    private val callbackEpoch = RealityCallbackEpoch()
    private val sessionCreationPending = AtomicBoolean(false)
    private var sessionLifecycle: RealitySessionLifecycle? = null
    private var activityResumed = false
    private var surfaceResumed = false
    private var anchor: Anchor? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var displayRotation = Surface.ROTATION_0
    private var displayGeometryDirty = true
    private var cameraTextureId = 0
    private var cameraProgram = 0
    private var positionAttribute = -1
    private var textureAttribute = -1
    private var textureUniform = -1
    private var textureNamesSession: Session? = null
    private var textureCoordinatesReady = false
    private val vertices = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val textureCoordinates = FloatArray(8)
    private val vertexBuffer = directFloatBuffer(vertices.size)
    private val textureBuffer = directFloatBuffer(textureCoordinates.size)
    private val modelMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val viewModelMatrix = FloatArray(16)
    private val clipMatrix = FloatArray(16)
    // This selector is used only for its FPS/thermal policy; actual Mote poses
    // are projected from the session Anchor below, never from a synthetic pose.
    private val qualitySelector = RealityAnchorSelector(
        arCore = ArCoreAnchorProvider(ArCorePoseSource { null }),
        canvas = CanvasSensorAnchorProvider(),
    )
    private val runtimeFailureReported = AtomicBoolean(false)
    private val qualityFallbackReported = AtomicBoolean(false)
    private var lastLocalAnalysisAtMs = 0L
    private var lastSnapshotAtMs = 0L
    private var fpsWindowStartedAtMs = 0L
    private var framesInFpsWindow = 0
    private var measuredFps = 30f

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setZOrderMediaOverlay(false)
        preserveEGLContextOnPause = true
        setRenderer(this)
        renderMode = RENDERMODE_CONTINUOUSLY
        visibility = GONE
    }

    fun setListener(value: Listener?) {
        listener = value
    }

    /** Creates/configures the AR session off the UI thread; the GL view remains its sole owner. */
    fun createAndInstallSession(context: Context): Boolean {
        if (!sessionCreationPending.compareAndSet(false, true)) return false
        val generation = callbackEpoch.advance()
        return try {
            sessionCreationExecutor.execute {
                val session = runCatching {
                    check(ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        "camera permission is not granted"
                    }
                    Session(context).also { created ->
                        val config = Config(created).apply {
                            planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                        }
                        created.configure(config)
                    }
                }.getOrElse { _ ->
                    mainHandler.post {
                        if (!callbackEpoch.accepts(generation)) return@post
                        sessionCreationPending.set(false)
                        reportRuntimeFailure("ARCore 启动失败")
                    }
                    return@execute
                }
                mainHandler.post {
                    if (!callbackEpoch.accepts(generation) || visibility != VISIBLE) {
                        runCatching { session.close() }
                        return@post
                    }
                    sessionCreationPending.set(false)
                    if (installSession(session)) postCallback { it.onSessionStarted() }
                    else reportRuntimeFailure("ARCore Session 未能启动")
                }
            }
            true
        } catch (_: Exception) {
            sessionCreationPending.set(false)
            false
        }
    }

    /** The caller must release CameraX before installing this session. */
    private fun installSession(session: Session): Boolean {
        closeSession()
        runtimeFailureReported.set(false)
        qualityFallbackReported.set(false)
        qualitySelector.beginRealityEntry()
        lastLocalAnalysisAtMs = 0L
        latestFrame = null
        arSession = session
        sessionLifecycle = RealitySessionLifecycle(object : RealitySessionLifecyclePort {
            override fun resume() = session.resume()
            override fun pause() = session.pause()
            override fun close() = session.close()
        })
        visibility = VISIBLE
        return if (activityResumed) resumeArSession() else true
    }

    fun onHostResume() {
        activityResumed = true
        if (arSession != null) resumeArSession()
    }

    fun onHostPause() {
        activityResumed = false
        if (surfaceResumed) {
            super.onPause()
            surfaceResumed = false
        }
        sessionLifecycle?.pause()
    }

    fun closeSession() {
        callbackEpoch.advance()
        sessionCreationPending.set(false)
        if (surfaceResumed) {
            val detached = CountDownLatch(1)
            runCatching {
                queueEvent {
                    detachAnchorOnGlThread()
                    detached.countDown()
                }
                detached.await(350, TimeUnit.MILLISECONDS)
            }
            super.onPause()
            surfaceResumed = false
        } else {
            anchor = null
        }
        sessionLifecycle?.close()
        sessionLifecycle = null
        arSession = null
        latestFrame = null
        textureNamesSession = null
        visibility = GONE
    }

    fun shutdownSessionCreation() {
        closeSession()
        sessionCreationExecutor.shutdownNow()
    }

    fun requestMotePlacement(x: Float, y: Float) {
        if (visibility != VISIBLE || arSession == null) return
        queueEvent {
            val frame = latestFrame
            val cameraTracking = frame?.camera?.trackingState == TrackingState.TRACKING
            if (!cameraTracking || anchor != null || frame == null) {
                postCallback { it.onAnchorPlacementResult(false) }
                return@queueEvent
            }

            val hit = runCatching {
                frame.hitTest(x, y).firstOrNull { result ->
                    val plane = result.trackable as? Plane
                    plane?.trackingState == TrackingState.TRACKING && plane.isPoseInPolygon(result.hitPose)
                }
            }.getOrNull()
            val planeHit = hit != null
            if (!RealityPlanePlacementPolicy.canPlace(cameraTracking, anchor != null, planeHit)) {
                postCallback { it.onAnchorPlacementResult(false) }
                return@queueEvent
            }
            anchor = runCatching { hit?.createAnchor() }.getOrNull()
            val placed = anchor != null
            postCallback { it.onAnchorPlacementResult(placed) }
            publishTrackingSnapshot(frame)
        }
    }

    fun resetQualityLockoutAfterExplicitStart() {
        qualitySelector.clearThermalLockoutAfterExplicitStart()
        qualityFallbackReported.set(false)
    }

    private fun resumeArSession(): Boolean {
        val lifecycle = sessionLifecycle ?: return false
        if (!lifecycle.resume()) {
            reportRuntimeFailure("相机不可用")
            return false
        }
        if (!surfaceResumed) {
            super.onResume()
            surfaceResumed = true
        }
        requestRender()
        return true
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        try {
            cameraProgram = createCameraProgram()
            positionAttribute = GLES20.glGetAttribLocation(cameraProgram, "a_Position")
            textureAttribute = GLES20.glGetAttribLocation(cameraProgram, "a_TexCoord")
            textureUniform = GLES20.glGetUniformLocation(cameraProgram, "sTexture")
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            cameraTextureId = textures[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            textureNamesSession = null
            textureCoordinatesReady = false
        } catch (_: Exception) {
            reportRuntimeFailure("AR 画面初始化失败")
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width.coerceAtLeast(1)
        surfaceHeight = height.coerceAtLeast(1)
        displayRotation = currentDisplayRotation()
        displayGeometryDirty = true
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val session = arSession ?: return
        if (cameraTextureId == 0 || cameraProgram == 0) return
        try {
            if (textureNamesSession !== session) {
                session.setCameraTextureNames(intArrayOf(cameraTextureId))
                textureNamesSession = session
            }
            val rotationNow = currentDisplayRotation()
            if (displayGeometryDirty || rotationNow != displayRotation) {
                displayRotation = rotationNow
                session.setDisplayGeometry(displayRotation, surfaceWidth, surfaceHeight)
                displayGeometryDirty = false
            }
            val frame = session.update()
            latestFrame = frame
            drawCameraBackground(frame)
            val nowMs = SystemClock.elapsedRealtime()
            updateFps(nowMs)
            observeQuality(nowMs)
            if (nowMs - lastSnapshotAtMs >= 33L) {
                lastSnapshotAtMs = nowMs
                publishTrackingSnapshot(frame)
            }
            analyzeCurrentFrame(frame, nowMs)
        } catch (_: CameraNotAvailableException) {
            reportRuntimeFailure("AR 相机暂不可用")
        } catch (_: Exception) {
            reportRuntimeFailure("AR 跟踪已停止")
        }
    }

    private fun drawCameraBackground(frame: Frame) {
        if (!textureCoordinatesReady || frame.hasDisplayGeometryChanged()) {
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                vertices,
                Coordinates2d.TEXTURE_NORMALIZED,
                textureCoordinates,
            )
            textureCoordinatesReady = true
        }
        vertexBuffer.position(0)
        vertexBuffer.put(vertices).position(0)
        textureBuffer.position(0)
        textureBuffer.put(textureCoordinates).position(0)
        GLES20.glUseProgram(cameraProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES20.glUniform1i(textureUniform, 0)
        GLES20.glEnableVertexAttribArray(positionAttribute)
        GLES20.glVertexAttribPointer(positionAttribute, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(textureAttribute)
        GLES20.glVertexAttribPointer(textureAttribute, 2, GLES20.GL_FLOAT, false, 0, textureBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionAttribute)
        GLES20.glDisableVertexAttribArray(textureAttribute)
    }

    private fun updateFps(nowMs: Long) {
        if (fpsWindowStartedAtMs == 0L) {
            fpsWindowStartedAtMs = nowMs
            framesInFpsWindow = 1
            return
        }
        framesInFpsWindow += 1
        val elapsed = nowMs - fpsWindowStartedAtMs
        if (elapsed >= 1_000L) {
            measuredFps = (framesInFpsWindow * 1_000f / elapsed).coerceIn(0f, 120f)
            fpsWindowStartedAtMs = nowMs
            framesInFpsWindow = 0
        }
    }

    private fun observeQuality(nowMs: Long) {
        val temperature = listener?.currentTemperatureCelsius()?.takeIf { it.isFinite() } ?: 25f
        qualitySelector.update(
            RealityFrame(
                timestampMs = nowMs,
                bearingDegrees = 0f,
                pitchDegrees = 0f,
                rollDegrees = 0f,
                targetBearingDegrees = 0f,
                distanceBand = "mid",
                width = surfaceWidth,
                height = surfaceHeight,
                fps = measuredFps,
                temperatureCelsius = temperature,
            )
        )
        val reason = qualitySelector.fallbackReason ?: return
        if (qualityFallbackReported.compareAndSet(false, true)) {
            postCallback { it.onQualityFallback(reason == "thermal") }
        }
    }

    private fun publishTrackingSnapshot(frame: Frame) {
        val cameraTracking = frame.camera.trackingState == TrackingState.TRACKING
        val currentAnchor = anchor
        val anchorTracking = currentAnchor?.trackingState == TrackingState.TRACKING
        val pose = if (cameraTracking && anchorTracking) currentAnchor?.let { projectAnchor(frame, it) } else null
        val tracking = cameraTracking
        val status = if (tracking) RealityTrackingStatus.TRACKING else RealityTrackingStatus.SEARCHING
        val snapshot = RealityTrackingSnapshot(
            status = status,
            motePose = pose,
            fallbackReason = qualitySelector.fallbackReason,
            fps = measuredFps,
            anchorPlaced = currentAnchor != null,
        )
        postCallback { it.onTrackingSnapshot(snapshot) }
    }

    private fun projectAnchor(frame: Frame, currentAnchor: Anchor): AnchorPose? = runCatching {
        currentAnchor.pose.toMatrix(modelMatrix, 0)
        frame.camera.getViewMatrix(viewMatrix, 0)
        frame.camera.getProjectionMatrix(projectionMatrix, 0, .1f, 100f)
        Matrix.multiplyMM(viewModelMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(clipMatrix, 0, projectionMatrix, 0, viewModelMatrix, 0)
        val depth = -viewModelMatrix[14]
        RealityProjection.fromClipCoordinates(
            clipX = clipMatrix[12],
            clipY = clipMatrix[13],
            clipW = clipMatrix[15],
            width = surfaceWidth,
            height = surfaceHeight,
            scale = if (depth > 0f) (1.6f / depth).coerceIn(.45f, 1.3f) else 1f,
            tracking = frame.camera.trackingState == TrackingState.TRACKING && currentAnchor.trackingState == TrackingState.TRACKING,
            anchorPlaced = true,
        )
    }.getOrNull()

    private fun analyzeCurrentFrame(frame: Frame, nowMs: Long) {
        val target = listener ?: return
        val localDue = RealityFrameAnalyzer.shouldSampleLocalCue(nowMs, lastLocalAnalysisAtMs)
        val remoteDue = runCatching { target.wantsRemoteFrame() }.getOrDefault(false)
        if (!localDue && !remoteDue) return
        val image = runCatching { frame.acquireCameraImage() }.getOrNull() ?: return
        RealityFrameAnalyzer.withFrameClosed(image) {
            if (localDue) {
                lastLocalAnalysisAtMs = nowMs
                RealityFrameAnalyzer.sampleLuminance(image)?.let { signal ->
                    postCallback { it.onLocalCue(signal) }
                }
            }
            if (remoteDue) {
                RealityFrameAnalyzer.copyPlanes(image)?.let { planes ->
                    postCallback { it.onRemoteFrame(planes) }
                }
            }
        }
    }

    private fun detachAnchorOnGlThread() {
        runCatching { anchor?.detach() }
        anchor = null
    }

    private fun reportRuntimeFailure(reason: String) {
        if (!runtimeFailureReported.compareAndSet(false, true)) return
        postCallback { it.onRuntimeFailure(reason) }
    }

    private inline fun postCallback(crossinline callback: (Listener) -> Unit) {
        val current = listener ?: return
        val generation = callbackEpoch.current()
        mainHandler.post {
            if (listener === current && callbackEpoch.accepts(generation)) callback(current)
        }
    }

    private fun currentDisplayRotation(): Int =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            context.display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay?.rotation
                ?: Surface.ROTATION_0
        }

    private fun directFloatBuffer(size: Int): FloatBuffer =
        ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private fun createCameraProgram(): Int {
        val vertexShader = compileShader(
            GLES20.GL_VERTEX_SHADER,
            """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
              gl_Position = a_Position;
              v_TexCoord = a_TexCoord;
            }
            """.trimIndent(),
        )
        val fragmentShader = compileShader(
            GLES20.GL_FRAGMENT_SHADER,
            """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES sTexture;
            varying vec2 v_TexCoord;
            void main() {
              gl_FragColor = texture2D(sTexture, v_TexCoord);
            }
            """.trimIndent(),
        )
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val error = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw IllegalStateException(error)
        }
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val error = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw IllegalStateException(error)
        }
        return shader
    }
}
