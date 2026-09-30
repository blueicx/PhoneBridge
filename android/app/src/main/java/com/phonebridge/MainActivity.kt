package com.phonebridge

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageFormat
import android.content.res.ColorStateList
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.activity.OnBackPressedCallback
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.HapticFeedbackConstants
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.ar.core.ArCoreApk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.math.sin
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.time.LocalDate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlin.random.Random

class MainActivity : AppCompatActivity(), CompanionView.Listener, BridgeLink.Listener {

    companion object {
        private const val TAG = "PhoneBridge"
        private const val REQUEST_CAMERA_PERMISSION = 71
        private const val REQUEST_LOCATION_PERMISSION = 72
        private const val REQUEST_AUDIO_PERMISSION = 73
        private const val REQUEST_PRIVACY_EXPORT_FILE = 74
        private const val TYPE_FRAME = 1
        private const val TYPE_AUDIO = 2
        private const val TYPE_SPEAK = 5
        private const val MAX_AR_AVAILABILITY_POLLS = 20
        private const val MAX_AR_INSTALL_POLLS = 40
        private const val AR_AVAILABILITY_POLL_MS = 500L
        private const val AR_THERMAL_LIMIT_CELSIUS = 40f
    }

    private data class CockpitAttentionItem(
        val key: String,
        val id: String,
        val title: String,
        val summary: String,
        val severity: String,
        val status: String,
        val source: String,
        val relatedSessionId: String = "",
        val relatedTaskId: String = "",
        val relatedActionId: String = "",
        val updatedAtMs: Long = System.currentTimeMillis(),
        val offlineMirror: Boolean = false
    )

    private data class SessionPolicyUi(
        val available: Boolean = false,
        val supported: Boolean = false,
        val level: String = AutonomyLevel.OBSERVE,
        val continuousMic: Boolean = false,
        val allowedTools: List<String> = emptyList(),
        val expiresAtMs: Long? = null,
        val detail: String = "",
        val loading: Boolean = false
    )

    private lateinit var statusChip: TextView
    private lateinit var rootLayout: View
    private lateinit var heroPanel: View
    private lateinit var speechText: TextView
    private lateinit var focusSpeechLayer: android.widget.FrameLayout
    private lateinit var focusSpeechScroll: android.widget.ScrollView
    private lateinit var focusSpeechStack: android.view.ViewGroup
    private lateinit var focusChatInput: EditText
    private lateinit var focusPttButton: Button
    private lateinit var petLevelChip: TextView
    private lateinit var petEnergyChip: TextView
    private lateinit var petAffectionChip: TextView
    private lateinit var petGrowthChip: TextView
    private lateinit var companionView: CompanionView
    private lateinit var previewView: PreviewView
    private lateinit var realityLensView: RealityLensView
    private lateinit var realityRepositionButton: Button
    private lateinit var cameraStateOverlay: View
    private lateinit var cameraStateBadge: TextView
    private lateinit var cameraStateTitle: TextView
    private lateinit var cameraStateSubtitle: TextView
    private lateinit var linkMetric: TextView
    private lateinit var audioMetric: TextView
    private lateinit var fpsMetric: TextView
    private lateinit var taskMetric: TextView
    private lateinit var feedButton: Button
    private lateinit var playButton: Button
    private lateinit var strokeButton: Button
    private lateinit var cameraButton: Button
    private lateinit var lensButton: Button
    private lateinit var listenButton: Button
    private lateinit var serverButton: Button
    private lateinit var residentButton: Button
    private lateinit var focusExit: Button
    private lateinit var focusToolbar: View
    private lateinit var focusSnapshotText: TextView
    private lateinit var focusToolScroll: HorizontalScrollView
    private lateinit var focusToolsToggle: Button
    private lateinit var focusCameraButton: Button
    private lateinit var focusLensButton: Button
    private lateinit var focusListenButton: Button
    private lateinit var focusVoiceButton: Button
    private lateinit var focusMemoryButton: Button
    private lateinit var focusGameButton: Button
    private lateinit var focusRealityButton: Button
    private lateinit var focusExploreLogButton: Button
    private lateinit var focusCommandButton: Button
    private lateinit var focusStageButton: Button
    private lateinit var focusRoutinesButton: Button
    private lateinit var focusGoalsButton: Button
    private var goalDraftForUi: GoalBoardDraft? = null
    private var goalBoardRefresh: (() -> Unit)? = null
    private lateinit var ambientSoundController: AmbientSoundController
    private var stagePreferences = CompanionStagePreferences()
    private var stageDecorations = emptyList<StageDecoration>()
    private var stageMessage: String? = null
    private var stageMessageExpiresAtMs = 0L
    private lateinit var pttButton: Button
    private lateinit var panelTabs: MaterialButtonToggleGroup
    private lateinit var consolePanel: android.view.View
    private lateinit var tasksPanel: android.view.View
    private lateinit var sensorsPanel: android.view.View
    private lateinit var codexPanel: android.view.View
    private lateinit var chatPanel: android.view.View
    private lateinit var voiceButton: Button
    private lateinit var memoryButton: Button
    private lateinit var offlineApiButton: Button
    private lateinit var logRecycler: RecyclerView
    private lateinit var taskRecycler: RecyclerView
    private lateinit var commandInput: EditText
    private lateinit var sendCommand: Button
    private lateinit var sensorText: TextView
    private lateinit var codexTaskSpinner: android.widget.Spinner
    private lateinit var modelSpinner: android.widget.Spinner
    private lateinit var chatInput: EditText
    private lateinit var chatRememberSwitch: SwitchMaterial
    private lateinit var sendChatButton: Button
    private lateinit var codexDetail: TextView
    private lateinit var selectedTaskDetail: TextView
    private lateinit var selectedTaskTitle: TextView
    private lateinit var selectedTaskPercent: TextView
    private lateinit var selectedTaskProgress: ProgressBar
    private lateinit var titleText: TextView
    private lateinit var selectedTaskMeta: TextView
    private lateinit var codexTaskAdapter: android.widget.ArrayAdapter<String>
    private lateinit var modelAdapter: android.widget.ArrayAdapter<String>
    private lateinit var chatRecycler: RecyclerView
    private lateinit var aiSpacePanel: View
    private lateinit var aiSpaceStatus: TextView
    private lateinit var aiAuthorizationStatus: TextView
    private lateinit var aiConversationText: TextView
    private lateinit var aiTaskText: TextView
    private lateinit var aiTaskStart: Button
    private lateinit var aiTaskPause: Button
    private lateinit var aiTaskContinue: Button
    private lateinit var aiTaskRetry: Button
    private lateinit var aiTaskCancel: Button
    private lateinit var aiTaskArchive: Button
    private lateinit var aiPolicyStatus: TextView
    private lateinit var aiPolicyDetail: TextView
    private lateinit var aiPolicyLevelButton: Button
    private lateinit var aiContinuousMicSwitch: SwitchMaterial
    private lateinit var aiPolicyRefresh: Button
    private lateinit var aiPolicyExpiry: TextView
    private lateinit var aiSessionSpinner: android.widget.Spinner
    private lateinit var aiProviderInput: EditText
    private lateinit var aiModelInput: EditText
    private lateinit var aiInput: EditText
    private lateinit var aiRememberSwitch: SwitchMaterial
    private lateinit var aiAuthorize: Button
    private lateinit var attentionStateChip: TextView
    private lateinit var attentionList: android.widget.LinearLayout
    private lateinit var cockpitSummaryStatus: TextView
    private lateinit var cockpitSummaryToggle: Button
    private lateinit var cockpitSummaryBody: android.widget.LinearLayout
    private lateinit var currentTaskSummary: TextView
    private lateinit var currentTaskMetaSummary: TextView
    private lateinit var recentResultSummary: TextView
    private lateinit var capabilityHealthSummary: TextView
    private lateinit var aiSessionAdapter: android.widget.ArrayAdapter<String>
    private val aiSessionIds = mutableListOf<String>()
    private val aiSessionLabels = mutableListOf<String>()
    private var aiSelectedSessionId = ""
    private var aiToolAuthorized = false
    private var aiAuthorizedUntilMs: Long? = null
    private var aiStreamingId = ""
    private var aiStreamingText = ""
    private var aiHighlightedTaskId = ""
    private var aiPolicyState = SessionPolicyUi()
    private var aiPolicyListenerMuted = false
    private val aiRenderedMessageIds = mutableSetOf<String>()
    private val cockpitAttentionItems = linkedMapOf<String, CockpitAttentionItem>()
    private val attentionMirror = linkedMapOf<String, JSONObject>()
    private val workspaceTaskMirror = linkedMapOf<String, JSONObject>()
    private val automationRunMirror = linkedMapOf<String, JSONObject>()
    private val actionRunMirror = linkedMapOf<String, JSONObject>()
    private var moteRosterJson = JSONArray()
    private var moteRelationship = MoteRelationshipSummary()
    private var moteStateJson = JSONObject()
    private var moteStoryJson = JSONArray()
    private var moteDexDialog: AlertDialog? = null
    private var activeMoteMoment = MoteMoment.IDLE
    private var moteMomentExpiresAtMs = 0L
    private var companionSummary = CompanionSummary()
    private var workspaceRevision: Long = 0L
    private val timelineProjection = TimelineProjection()
    private val companionSessionRepository = CompanionSessionRepository(timelineProjection)
    private val immersiveShellCoordinator = ImmersiveShellCoordinator()
    private val workspaceEventGate = WorkspaceEventGate()
    private var cockpitUsesOfflineMirror = false
    private var cockpitSummaryExpanded = false
    private var workspaceEmergencyState: JSONObject? = null
    private var deviceHealthState = DeviceHealthState()
    private val workspaceClient = WorkspaceClient()
    private val workspaceRepository by lazy { WorkspaceRepository.get(this) }
    private val explorationLogStore = ExplorationLogStore()
    private val explorationLogRequestGate = ExplorationLogRequestGate()
    @Volatile private var explorationLogSnapshot = ExplorationLogSnapshot()
    private var explorationLogDialogRenderer: ((ExplorationLogSnapshot) -> Unit)? = null
    private var explorationLogDialog: AlertDialog? = null
    private var explorationLogDetailDialog: AlertDialog? = null
    private val appearanceButtons = mutableMapOf<PetAppearance, Button>()
    private val themeButtons = mutableMapOf<UiTheme, Button>()
    private lateinit var themeApplier: ThemeApplier
    private var activeTheme = UiTheme.AURORA_GLASS
    private val chatAdapter = ChatAdapter()
    private var pendingVoiceCommand = false
    private var voiceRetryAvailable = false
    private var voiceStatusText = ""
    private lateinit var secureTokenStore: SecureTokenStore

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val frameSequence = AtomicInteger(0)
    private val audioSequence = AtomicInteger(0)
    private val fpsCounter = AtomicInteger(0)
    private val cameraSession = AtomicInteger(0)
    @Volatile private var cameraStartPending = false
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val networkExecutor = Executors.newSingleThreadExecutor()
    private val speechExecutor = Executors.newSingleThreadExecutor()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val outboxSequenceLock = Any()
    private var telemetryJob: Job? = null

    @Volatile private var cameraRunning = false
    @Volatile private var lastFrameAt = 0L
    @Volatile private var continuousListening = false
    @Volatile private var pttActive = false
    @Volatile private var micRunning = false
    @Volatile private var desiredServerUrl: String? = null
    @Volatile private var autoReconnect = false
    @Volatile private var destroyed = false
    @Volatile private var speaking = false
    @Volatile private var immersiveMode = false
    private var realityLensActive = false
    private var realityHistoryReadOnly = false
    private lateinit var arCoreRenderView: ArCoreRealityRenderView
    private var realityArEntryId: Long? = null
    private var arCoreInstallFlow: ArCoreInstallFlow? = null
    private var arCoreAvailabilityPolls = 0
    private var arCoreInstallPolls = 0
    private var awaitingArCoreCameraPermission = false
    private var cancelledRealityCameraPermission = false
    @Volatile private var latestArCameraTemperature: TimedTemperature? = null
    @Volatile private var latestTelemetrySampledAtMs = 0L
    private var lastArRemoteFrameAt = 0L
    private val arFrameUploadPending = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var pairingScanActive = false
    @Volatile private var pairingScanOwnsCamera = false
    @Volatile private var pairingScanLastFrameAt = 0L
    private val realityCaptureController = RealityCaptureController()
    private val realityThermalHandler = Handler(Looper.getMainLooper())
    private val realityThermalMonitor = object : Runnable {
        override fun run() {
            if (destroyed) return
            val temperature = currentRealityTemperatureCelsius()
            latestArCameraTemperature = temperature?.let {
                TimedTemperature(it, SystemClock.elapsedRealtime())
            }
            realityCaptureController.observeTemperature(SystemClock.elapsedRealtime(), temperature)
            val capture = realityCaptureController.snapshot()
            if (realityLensActive && !capture.thermalLockout &&
                temperature != null && temperature >= AR_THERMAL_LIMIT_CELSIUS
            ) {
                fallbackFromArCore("设备偏热，镜头已暂停；仍可手动探索", thermal = true, disableCamera = true)
            }
            if (realityLensActive || realityCaptureController.snapshot().thermalLockout) {
                realityThermalHandler.postDelayed(this, 2_000L)
            }
        }
    }
    @Volatile private var lastLocalCueAt = 0L
    private val realityLocationSampler by lazy { RealityLocationSampler(this) }
    private val realityExplorationCoordinator = RealityExplorationCoordinator()
    private var realityRegion: String? = null
    private var realityAnchorRepositioning = false
    private var normalPreviewParams: androidx.constraintlayout.widget.ConstraintLayout.LayoutParams? = null
    private var focusToolsExpanded = false
    private var normalHeroParams: androidx.constraintlayout.widget.ConstraintLayout.LayoutParams? = null
    private lateinit var focusBackCallback: OnBackPressedCallback
    private var pendingAutoCommand: String? = null
    private var pendingDeepLink: String? = null
    private var pendingPrivacyArchive: String? = null
    private val processedPrivacyDeletionIds = linkedSetOf<String>()
    private val pendingPrivacyDeletionCallbacks = linkedMapOf<String, MutableList<() -> Unit>>()
    private var streamingChatId = ""
    private val streamingText = StringBuilder()
    private var activeChatRequestId = ""
    private var pendingChatText = ""
    private var pendingChatRemember = true
    private var lastFailedChatText = ""
    private var lastFailedChatRemember = true
    private var offlineChatFuture: java.util.concurrent.Future<*>? = null
    private val cancelledChatRequestIds = mutableSetOf<String>()
    private var lastSpokenReply = ""
    private var pendingAutoCare: String? = null
    private var latestTelemetry: TelemetrySample? = null
    private var lastPublishedSensorState = -1
    private var lastCodexTaskId = ""
    private var lastCodexProgress = -1

    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    private var speakerTrack: AudioTrack? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraPreview: Preview? = null
    private var useFrontCamera = false
    private val activeTasks = LinkedHashMap<String, TaskItem>()
    private val exitAppReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BridgeService.ACTION_EXIT_APP) runOnUiThread { finishAffinity() }
        }
    }
    private val voiceStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BridgeService.ACTION_VOICE_STATE) return
            renderVoiceState(
                running = intent.getBooleanExtra(BridgeService.EXTRA_VOICE_RUNNING, false),
                listening = intent.getBooleanExtra(BridgeService.EXTRA_VOICE_LISTENING, false),
                speaking = intent.getBooleanExtra(BridgeService.EXTRA_VOICE_SPEAKING, false),
                status = intent.getStringExtra(BridgeService.EXTRA_VOICE_STATUS).orEmpty(),
                retryAvailable = intent.getBooleanExtra(BridgeService.EXTRA_VOICE_RETRY, false)
            )
        }
    }
    private val logAdapter = LogAdapter()
    private val taskAdapter = TaskAdapter()
    private val codexTaskLabels = mutableListOf<String>()
    private val codexTaskIds = mutableListOf<String>()
    private val modelLabels = mutableListOf<String>()
    private val modelProviderIds = mutableListOf<String>()
    private val modelNames = mutableListOf<String>()

    private var pet = PetState()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        secureTokenStore = SecureTokenStore(this)
        secureTokenStore.migrateLegacy(getSharedPreferences("phonebridge", Context.MODE_PRIVATE))
        workspaceRevision = getSharedPreferences("workspace_meta", Context.MODE_PRIVATE).getLong("revision", 0L)
        workspaceEventGate.markResynchronized(workspaceRevision)
        ContextCompat.registerReceiver(
            this,
            exitAppReceiver,
            IntentFilter(BridgeService.ACTION_EXIT_APP),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this,
            voiceStateReceiver,
            IntentFilter(BridgeService.ACTION_VOICE_STATE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        bindViews()
        ambientSoundController = AmbientSoundController(applicationContext)
        stagePreferences = readCompanionStagePreferences()
        stageDecorations = readCompanionStageDecorations()
        WindowCompat.setDecorFitsSystemWindows(window, true)
        focusBackCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                if (aiSpacePanel.visibility == View.VISIBLE) {
                    closeAiSpace()
                    return
                }
                if (dismissFocusKeyboard()) return
                if (immersiveMode && immersiveShellCoordinator.onBack()) {
                    val shellState = immersiveShellCoordinator.state.value
                    if (realityLensActive && shellState.surface == ImmersiveSurface.COMPANION) {
                        exitRealityLens()
                    }
                    focusToolsExpanded = shellState.drawerOpen
                    renderFocusTools()
                    return
                }
                if (realityLensActive) {
                    exitRealityLens()
                    return
                }
                exitFocusMode()
            }
        }
        onBackPressedDispatcher.addCallback(this, focusBackCallback)
        setupThemes()
        applyCompanionStagePreferences(updateVoiceService = false)
        loadPet()
        setupInteractions()
        setupPanels()

        intent?.getStringExtra("auto_care")?.takeIf { it.isNotBlank() }?.let {
            pendingAutoCare = it
        }
        intent?.getStringExtra("server_url")?.takeIf { it.isNotBlank() }?.let { url ->
            saveServer(url)
        }
        pendingAutoCommand = intent?.getStringExtra("auto_command")?.takeIf { it.isNotBlank() }
        pendingDeepLink = intent?.dataString?.takeIf { it.isNotBlank() }
        intent?.getStringExtra("access_token")?.let { saveAccessToken(it) }
        val autoConnect = intent?.getStringExtra("server_url")?.isNotBlank() == true ||
                getSharedPreferences("phonebridge", Context.MODE_PRIVATE).getBoolean("auto_connect", true)

        DeviceCommandBus.setReceiver { action -> runOnUiThread { applyDeviceCommand(action) } }
        startResident()
        if (autoConnect) connectSavedServer()
        renderPet()
        startTelemetryLoop()
        pendingAutoCare?.let { kind -> interact(kind) }
        pendingAutoCare = null
        rootLayout.post { enterAdaptiveImmersiveMode() }
    }

    @Deprecated("Deprecated in Android, retained for ACTION_CREATE_DOCUMENT compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PRIVACY_EXPORT_FILE) return
        val archive = pendingPrivacyArchive
        pendingPrivacyArchive = null
        val uri = data?.data
        if (resultCode != RESULT_OK || archive.isNullOrBlank() || uri == null) {
            Toast.makeText(this, "已取消导出；未在应用中保留档案", Toast.LENGTH_SHORT).show()
            return
        }
        networkExecutor.execute {
            val result = runCatching {
                val bytes = archive.toByteArray(Charsets.UTF_8)
                try {
                    contentResolver.openOutputStream(uri)?.use { output -> output.write(bytes) }
                        ?: error("无法写入所选位置")
                } finally {
                    bytes.fill(0)
                }
            }
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (result.isSuccess) "加密档案已保存；请妥善保管口令" else "保存失败：${result.exceptionOrNull()?.message ?: "未知错误"}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun bindViews() {
        statusChip = findViewById(R.id.statusChip)
        titleText = findViewById(R.id.titleText)
        rootLayout = findViewById(R.id.rootLayout)
        heroPanel = findViewById(R.id.heroPanel)
        speechText = findViewById(R.id.speechText)
        focusSpeechLayer = findViewById(R.id.focusSpeechLayer)
        focusSpeechScroll = findViewById(R.id.focusSpeechScroll)
        focusSpeechStack = findViewById(R.id.focusSpeechStack)
        focusChatInput = findViewById(R.id.focusChatInput)
        focusPttButton = findViewById(R.id.focusPttButton)
        petLevelChip = findViewById(R.id.petLevelChip)
        petEnergyChip = findViewById(R.id.petEnergyChip)
        petAffectionChip = findViewById(R.id.petAffectionChip)
        petGrowthChip = findViewById(R.id.petGrowthChip)
        companionView = findViewById(R.id.companionView)
        previewView = findViewById(R.id.previewView)
        cameraStateOverlay = findViewById(R.id.cameraStateOverlay)
        cameraStateBadge = findViewById(R.id.cameraStateBadge)
        cameraStateTitle = findViewById(R.id.cameraStateTitle)
        cameraStateSubtitle = findViewById(R.id.cameraStateSubtitle)
        findViewById<View>(R.id.previewFrame).clipToOutline = true
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        previewView.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(18).toFloat())
            }
        }
        previewView.clipToOutline = true
        realityLensView = findViewById(R.id.realityLensView)
        arCoreRenderView = findViewById(R.id.arCoreRenderView)
        realityRepositionButton = findViewById(R.id.realityRepositionButton)
        arCoreRenderView.setListener(object : ArCoreRealityRenderView.Listener {
            override fun onTrackingSnapshot(snapshot: RealityTrackingSnapshot) {
                if (realityLensActive) realityLensView.setRealityTrackingSnapshot(snapshot)
            }

            override fun onSessionStarted() {
                if (!realityLensActive || realityCaptureController.snapshot().owner != RealityCameraOwner.ARCORE) return
                realityRepositionButton.text = "重新放置"
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                renderCameraHeroState()
                renderFocusTools()
                renderPet()
                publishSensorState(force = true)
                setStatus("现实镜头已启动 · 轻触空白处放置 Mote")
            }

            override fun onRuntimeFailure(reason: String) {
                fallbackFromArCore(reason, thermal = false)
            }

            override fun onQualityFallback(thermal: Boolean) {
                fallbackFromArCore(
                    if (thermal) "设备偏热，镜头已暂停；仍可手动探索" else "画面负载偏高，已暂停 AR；仍可手动探索",
                    thermal = thermal,
                    disableCamera = true,
                )
            }

            override fun onLocalCue(signal: RealityImageSignal) {
                val hints = RealityCueAnalyzer.analyze(signal)
                if (realityLensActive) realityLensView.setLocalCueHints(hints.types)
            }

            override fun wantsRemoteFrame(): Boolean = shouldUploadArRemoteFrame()

            override fun onRemoteFrame(planes: RealityImagePlanes) {
                encodeAndUploadArFrame(planes)
            }

            override fun onAnchorPlacementResult(placed: Boolean) {
                if (!realityLensActive) return
                if (placed) {
                    realityAnchorRepositioning = false
                    realityLensView.setAnchorRepositioning(false)
                    realityRepositionButton.text = "重新放置"
                    say("Mote 已放到这个平面上。")
                } else {
                    setStatus("还没找到可用平面；原位置保持不变，请缓慢移动镜头后重试")
                }
            }

            override fun currentTemperatureCelsius(): Float? =
                currentCachedRealityTemperatureCelsius(SystemClock.elapsedRealtime())
        })
        realityLensView.contentDescription = "现实镜头，三个可探索线索"
        realityLensView.setDiscovered(loadDiscoveredRealityNodes())
        realityLensView.setPetState(pet)
        realityLensView.setListener(object : RealityLensView.Listener {
            override fun onNodeTapped(node: RealityLensView.LensNode) {
                handleRealityNode(node)
            }
            override fun onPetTapped(pet: PetState) {
                if (realityHistoryReadOnly) setStatus("历史记录只读，不能重复互动或领奖") else handleRealityPetTapped()
            }
            override fun onBlankAreaTapped(x: Float, y: Float) {
                if (realityHistoryReadOnly) setStatus("历史 Reality 记录仅供查看")
                else arCoreRenderView.requestMotePlacement(x, y, replaceExisting = realityAnchorRepositioning)
            }
        })
        normalPreviewParams = findViewById<View>(R.id.previewFrame).layoutParams as?
            androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        linkMetric = findViewById(R.id.linkMetric)
        audioMetric = findViewById(R.id.audioMetric)
        fpsMetric = findViewById(R.id.fpsMetric)
        taskMetric = findViewById(R.id.taskMetric)
        feedButton = findViewById(R.id.feedButton)
        playButton = findViewById(R.id.playButton)
        strokeButton = findViewById(R.id.strokeButton)
        cameraButton = findViewById(R.id.cameraButton)
        lensButton = findViewById(R.id.lensButton)
        listenButton = findViewById(R.id.listenButton)
        serverButton = findViewById(R.id.serverButton)
        residentButton = findViewById(R.id.residentButton)
        focusExit = findViewById(R.id.focusExit)
        focusToolbar = findViewById(R.id.focusToolbar)
        focusSnapshotText = findViewById(R.id.focusSnapshotText)
        focusToolScroll = findViewById(R.id.focusToolScroll)
        focusToolsToggle = findViewById(R.id.focusToolsToggle)
        focusCameraButton = findViewById(R.id.focusCameraButton)
        focusLensButton = findViewById(R.id.focusLensButton)
        focusListenButton = findViewById(R.id.focusListenButton)
        focusVoiceButton = findViewById(R.id.focusVoiceButton)
        focusMemoryButton = findViewById(R.id.focusMemoryButton)
        focusGameButton = findViewById(R.id.focusGameButton)
        focusRealityButton = findViewById(R.id.focusRealityButton)
        focusExploreLogButton = findViewById(R.id.focusExploreLogButton)
        focusCommandButton = findViewById(R.id.focusCommandButton)
        focusStageButton = findViewById(R.id.focusStageButton)
        focusRoutinesButton = findViewById(R.id.focusRoutinesButton)
        focusGoalsButton = findViewById(R.id.focusGoalsButton)
        realityRepositionButton.setOnClickListener {
            if (!realityLensActive) return@setOnClickListener
            if (realityHistoryReadOnly) {
                setStatus("历史 Reality 记录仅供查看")
                return@setOnClickListener
            }
            if (realityCaptureController.snapshot().owner == RealityCameraOwner.ARCORE) {
                realityAnchorRepositioning = !realityAnchorRepositioning
                realityLensView.setAnchorRepositioning(realityAnchorRepositioning)
                realityRepositionButton.text = if (realityAnchorRepositioning) "轻触新位置" else "重新放置"
                setStatus(if (realityAnchorRepositioning) "轻触新的可见平面，成功后才替换旧锚点" else "已取消锚点调整")
            } else {
                realityLensView.recalibrateSpatialSensors()
                setStatus("方向已重新校准 · 仍可手动探索")
            }
        }
        pttButton = findViewById(R.id.pttButton)
        panelTabs = findViewById(R.id.panelTabs)
        consolePanel = findViewById(R.id.consolePanel)
        tasksPanel = findViewById(R.id.tasksPanel)
        sensorsPanel = findViewById(R.id.sensorsPanel)
        codexPanel = findViewById(R.id.codexPanel)
        chatPanel = findViewById(R.id.chatPanel)
        logRecycler = findViewById(R.id.logRecycler)
        taskRecycler = findViewById(R.id.taskRecycler)
        commandInput = findViewById(R.id.commandInput)
        sendCommand = findViewById(R.id.sendCommand)
        sensorText = findViewById(R.id.sensorText)
        codexTaskSpinner = findViewById(R.id.codexTaskSpinner)
        modelSpinner = findViewById(R.id.modelSpinner)
        chatInput = findViewById(R.id.chatInput)
        chatRememberSwitch = findViewById(R.id.chatRememberSwitch)
        sendChatButton = findViewById(R.id.sendChat)
        chatRememberSwitch.isChecked = getSharedPreferences("mote_chat", Context.MODE_PRIVATE)
            .getBoolean("remember_this_turn", true)
        chatRememberSwitch.setOnCheckedChangeListener { _, checked ->
            getSharedPreferences("mote_chat", Context.MODE_PRIVATE).edit()
                .putBoolean("remember_this_turn", checked)
                .apply()
        }
        codexDetail = findViewById(R.id.codexDetail)
        selectedTaskDetail = findViewById(R.id.selectedTaskDetail)
        selectedTaskTitle = findViewById(R.id.selectedTaskTitle)
        selectedTaskPercent = findViewById(R.id.selectedTaskPercent)
        selectedTaskProgress = findViewById(R.id.selectedTaskProgress)
        selectedTaskMeta = findViewById(R.id.selectedTaskMeta)
        chatRecycler = findViewById(R.id.chatRecycler)
        aiSpacePanel = findViewById(R.id.aiSpacePanel)
        aiSpaceStatus = findViewById(R.id.aiSpaceStatus)
        aiAuthorizationStatus = findViewById(R.id.aiAuthorizationStatus)
        aiConversationText = findViewById(R.id.aiConversationText)
        aiTaskText = findViewById(R.id.aiTaskText)
        aiTaskStart = findViewById(R.id.aiTaskStart)
        aiTaskPause = findViewById(R.id.aiTaskPause)
        aiTaskContinue = findViewById(R.id.aiTaskContinue)
        aiTaskRetry = findViewById(R.id.aiTaskRetry)
        aiTaskCancel = findViewById(R.id.aiTaskCancel)
        aiTaskArchive = findViewById(R.id.aiTaskArchive)
        aiPolicyStatus = findViewById(R.id.aiPolicyStatus)
        aiPolicyDetail = findViewById(R.id.aiPolicyDetail)
        aiPolicyLevelButton = findViewById(R.id.aiPolicyLevelButton)
        aiContinuousMicSwitch = findViewById(R.id.aiContinuousMicSwitch)
        aiPolicyRefresh = findViewById(R.id.aiPolicyRefresh)
        aiPolicyExpiry = findViewById(R.id.aiPolicyExpiry)
        aiSessionSpinner = findViewById(R.id.aiSessionSpinner)
        aiProviderInput = findViewById(R.id.aiProviderInput)
        aiModelInput = findViewById(R.id.aiModelInput)
        aiInput = findViewById(R.id.aiInput)
        aiRememberSwitch = findViewById(R.id.aiRememberSwitch)
        aiAuthorize = findViewById(R.id.aiAuthorize)
        attentionStateChip = findViewById(R.id.attentionStateChip)
        attentionList = findViewById(R.id.attentionList)
        cockpitSummaryStatus = findViewById(R.id.cockpitSummaryStatus)
        cockpitSummaryToggle = findViewById(R.id.cockpitSummaryToggle)
        cockpitSummaryBody = findViewById(R.id.cockpitSummaryBody)
        currentTaskSummary = findViewById(R.id.currentTaskSummary)
        currentTaskMetaSummary = findViewById(R.id.currentTaskMetaSummary)
        recentResultSummary = findViewById(R.id.recentResultSummary)
        capabilityHealthSummary = findViewById(R.id.capabilityHealthSummary)
        aiSessionAdapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, aiSessionLabels)
        aiSessionSpinner.adapter = aiSessionAdapter
        voiceButton = findViewById(R.id.voiceButton)
        memoryButton = findViewById(R.id.memoryButton)
        offlineApiButton = findViewById(R.id.offlineApiButton)
        companionView.setListener(this)
        findViewById<Button>(R.id.quickHelp).setOnClickListener { runQuick("help") }
        findViewById<Button>(R.id.quickStatus).setOnClickListener { runQuick("status") }
        findViewById<Button>(R.id.quickProcesses).setOnClickListener { runQuick("ps") }
        findViewById<Button>(R.id.quickDisk).setOnClickListener { runQuick("disk") }
        findViewById<Button>(R.id.quickNetwork).setOnClickListener { runQuick("net") }
        findViewById<Button>(R.id.quickScreenshot).setOnClickListener { runQuick("screenshot") }
        findViewById<Button>(R.id.quickCamera).setOnClickListener { runQuick("camera") }
        findViewById<Button>(R.id.quickSysinfo).setOnClickListener { runQuick("sysinfo") }
        findViewById<Button>(R.id.quickPing).setOnClickListener { runPing() }
        findViewById<Button>(R.id.quickSay).setOnClickListener { speakFromConsole() }
        findViewById<Button>(R.id.quickRefresh).setOnClickListener {
            requestSnapshot()
            logAdapter.add("info", "已请求状态刷新。")
        }
        codexTaskAdapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, codexTaskLabels)
        modelAdapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, modelLabels)
        codexTaskSpinner.adapter = codexTaskAdapter
        modelSpinner.adapter = modelAdapter
        renderCameraHeroState()
        renderAiAuthorizationStatus()
        renderAiPolicyState()
        renderAttentionCenter()
        renderCockpitSummary()
    }

    private fun isCameraActive(): Boolean =
        cameraRunning || realityCaptureController.snapshot().owner == RealityCameraOwner.ARCORE

    private fun renderCameraHeroState() {
        val overlayVisible = !cameraRunning || cameraStartPending
        previewView.alpha = if (cameraRunning && !cameraStartPending) 1f else 0f
        previewView.visibility = if (cameraRunning && !cameraStartPending) View.VISIBLE else View.INVISIBLE
        if (realityLensActive) {
            cameraStateOverlay.visibility = View.GONE
            return
        }
        cameraStateOverlay.visibility = if (overlayVisible) View.VISIBLE else View.GONE
        when {
            cameraStartPending -> {
                cameraStateBadge.text = getString(R.string.camera_collapsed_badge)
                cameraStateTitle.text = getString(R.string.camera_starting_title)
                cameraStateSubtitle.text = getString(R.string.camera_starting_summary)
            }
            cameraRunning -> {
                cameraStateBadge.text = getString(R.string.camera_active_badge)
                cameraStateTitle.text = getString(R.string.camera_active_title)
                cameraStateSubtitle.text = getString(R.string.camera_active_summary)
            }
            else -> {
                cameraStateBadge.text = getString(R.string.camera_collapsed_badge)
                cameraStateTitle.text = getString(R.string.camera_collapsed_title)
                cameraStateSubtitle.text = getString(R.string.camera_collapsed_summary)
            }
        }
    }

    private fun renderAiAuthorizationStatus() {
        val remainingMs = aiAuthorizedUntilMs?.minus(System.currentTimeMillis())
        val stillAuthorized = remainingMs != null && remainingMs > 0L
        if (!stillAuthorized && aiAuthorizedUntilMs != null) aiToolAuthorized = false
        val authorized = aiToolAuthorized || stillAuthorized
        aiToolAuthorized = authorized
        aiAuthorize.text = if (authorized) "撤销授权" else "授权工具"
        aiAuthorizationStatus.text = if (authorized) {
            "工具已授权 · 剩余 ${formatRemaining(remainingMs)}"
        } else {
            "工具未授权"
        }
        aiAuthorizationStatus.setTextColor(
            when {
                authorized -> 0xFF8FF0C4.toInt()
                aiPolicyState.loading -> 0xFFFFC86B.toInt()
                else -> 0xFFFFC86B.toInt()
            }
        )
    }

    private fun renderAiPolicyState() {
        val level = aiPolicyState.level.ifBlank { AutonomyLevel.OBSERVE }
        aiPolicyLevelButton.text = "自主权：$level"
        aiPolicyStatus.text = when {
            aiPolicyState.loading -> getString(R.string.ai_policy_status_loading)
            !aiPolicyState.supported -> "当前节点尚未返回 session policy"
            aiPolicyState.available -> "Policy 已同步 · $level"
            else -> "Policy 可见性降级"
        }
        aiPolicyDetail.text = when {
            aiPolicyState.loading -> getString(R.string.ai_policy_detail_loading)
            aiPolicyState.detail.isNotBlank() -> aiPolicyState.detail
            aiPolicyState.allowedTools.isNotEmpty() ->
                "允许工具：${aiPolicyState.allowedTools.take(3).joinToString("、")}"
            else -> "暂未收到 allowedTools；会保留现有会话和授权流程。"
        }
        aiPolicyExpiry.text = "授权剩余时间：${formatRemaining(aiAuthorizedUntilMs?.minus(System.currentTimeMillis()))}"
        aiPolicyListenerMuted = true
        aiContinuousMicSwitch.isEnabled = aiPolicyState.supported
        aiContinuousMicSwitch.isChecked = aiPolicyState.continuousMic
        aiPolicyLevelButton.isEnabled = aiPolicyState.supported
        aiPolicyRefresh.isEnabled = true
        aiPolicyListenerMuted = false
    }

    private fun formatRemaining(remainingMs: Long?): String {
        if (remainingMs == null) return "暂无"
        if (remainingMs <= 0L) return "已到期"
        val totalMinutes = (remainingMs / 60_000L).toInt()
        val seconds = ((remainingMs / 1_000L) % 60L).toInt()
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours > 0 -> "${hours}小时${minutes}分"
            totalMinutes > 0 -> "${totalMinutes}分"
            else -> "${seconds}秒"
        }
    }

    private fun parseEpochMs(value: Any?): Long? = when (value) {
        null -> null
        is Number -> value.toLong()
        is String -> when {
            value.isBlank() -> null
            value.all { it.isDigit() } -> value.toLongOrNull()
            else -> runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
        }
        else -> null
    }

    private fun jsonStringList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        val values = mutableListOf<String>()
        for (index in 0 until array.length()) {
            array.optString(index).takeIf { it.isNotBlank() }?.let(values::add)
        }
        return values
    }

    private fun attentionRank(item: CockpitAttentionItem): Int {
        val severity = when (item.severity.lowercase()) {
            "critical" -> 5
            "high", "failed", "blocked" -> 4
            "medium", "warning", "running" -> 3
            "low", "queued" -> 2
            else -> 1
        }
        val statusBoost = when (item.status.lowercase()) {
            "open", "pending", "running", "blocked", "failed" -> 2
            "acknowledged", "read", "snoozed" -> 1
            else -> 0
        }
        return severity * 10 + statusBoost
    }

    private fun attentionColors(item: CockpitAttentionItem): Pair<Int, Int> = when (item.severity.lowercase()) {
        "critical", "high", "failed", "blocked" -> 0xFFFFC7C7.toInt() to 0xFF5A2A28.toInt()
        "medium", "warning", "running" -> 0xFFFFD98A.toInt() to 0xFF5B4220.toInt()
        else -> 0xFFA5F3CB.toInt() to 0xFF24453A.toInt()
    }

    private fun renderAttentionCenter() {
        attentionList.removeAllViews()
        val ranked = cockpitAttentionItems.values
            .sortedWith(compareByDescending<CockpitAttentionItem> { attentionRank(it) }.thenByDescending { it.updatedAtMs })
            .take(4)
        val activeCount = ranked.count {
            !it.status.equals("resolved", true) &&
                !it.status.equals("dismissed", true) &&
                !it.status.equals("ignored", true)
        }
        attentionStateChip.text = when {
            cockpitUsesOfflineMirror -> getString(R.string.attention_state_offline)
            activeCount <= 0 -> getString(R.string.attention_state_empty)
            else -> "${activeCount} 条提醒"
        }
        val chipColor = when {
            ranked.any { it.severity.equals("high", true) || it.severity.equals("critical", true) } -> 0xFFFFC86B.toInt()
            cockpitUsesOfflineMirror -> 0xFF87A495.toInt()
            else -> 0xFF8FF0C4.toInt()
        }
        attentionStateChip.setTextColor(chipColor)
        if (ranked.isEmpty()) {
            val empty = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                background = resources.getDrawable(R.drawable.bg_chip, theme)
                backgroundTintList = ColorStateList.valueOf(0x141F3129)
                setPadding(dp(12), dp(12), dp(12), dp(12))
            }
            empty.addView(TextView(this).apply {
                text = getString(R.string.attention_empty_title)
                setTextColor(0xFFEAF7F1.toInt())
                textSize = 12f
            })
            empty.addView(TextView(this).apply {
                text = if (cockpitUsesOfflineMirror) {
                    "本地 Room 镜像里还没有 attention 节点。"
                } else {
                    getString(R.string.attention_empty_summary)
                }
                setTextColor(0xFF87A495.toInt())
                textSize = 11f
                setPadding(0, dp(4), 0, 0)
            })
            attentionList.addView(empty)
            return
        }
        ranked.forEachIndexed { index, item ->
            if (index > 0) {
                attentionList.addView(View(this).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(8)
                    )
                })
            }
            attentionList.addView(buildAttentionItemView(item))
        }
    }

    private fun buildAttentionItemView(item: CockpitAttentionItem): View {
        val (textColor, strokeColor) = attentionColors(item)
        val background = android.graphics.drawable.GradientDrawable().apply {
            setColor(ColorUtils.setAlphaComponent(0xFF0D1B15.toInt(), if (item.offlineMirror) 214 else 238))
            setStroke(dp(1), strokeColor)
            cornerRadius = dp(18).toFloat()
        }
        return android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            this.background = background
            isClickable = true
            isFocusable = true
            contentDescription = "${item.title}，${item.summary}"
            setOnClickListener { openAttentionInAiSpace(item) }

            addView(android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    text = item.title
                    setTextColor(0xFFF4FAF6.toInt())
                    textSize = 13f
                    maxLines = 2
                })
                addView(TextView(context).apply {
                    this.background = resources.getDrawable(R.drawable.bg_chip, theme)
                    backgroundTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(strokeColor, 96))
                    text = item.severity.uppercase(Locale.getDefault())
                    setTextColor(textColor)
                    textSize = 10f
                    gravity = android.view.Gravity.CENTER
                    setPadding(dp(10), dp(4), dp(10), dp(4))
                })
            })

            addView(TextView(context).apply {
                text = item.summary.ifBlank { "暂无更多描述。" }
                setTextColor(0xFFD8EEE2.toInt())
                textSize = 12f
                setPadding(0, dp(6), 0, 0)
            })

            addView(TextView(context).apply {
                text = buildList {
                    add(sourceLabel(item.source))
                    add(statusLabel(item.status))
                    if (item.offlineMirror) add("离线镜像")
                    item.relatedTaskId.takeIf { it.isNotBlank() }?.let { add("任务 ${it.takeLast(6)}") }
                }.joinToString(" · ")
                setTextColor(0xFF87A495.toInt())
                textSize = 10.5f
                setPadding(0, dp(6), 0, 0)
            })

            addView(android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                setPadding(0, dp(10), 0, 0)
                addView(buildAttentionAction(getString(R.string.attention_action_open), 0xFF8FF0C4.toInt()) {
                    openAttentionInAiSpace(item)
                })
                addView(buildAttentionAction(getString(R.string.attention_action_ack), 0xFFA7C5B6.toInt()) {
                    handleAttentionAction(item, "acknowledged")
                })
                addView(buildAttentionAction(getString(R.string.attention_action_resolve), 0xFF8FF0C4.toInt()) {
                    handleAttentionAction(item, "resolved")
                })
                addView(buildAttentionAction(getString(R.string.attention_action_ignore), 0xFFFFC86B.toInt()) {
                    handleAttentionAction(item, AttentionStatus.DISMISSED)
                })
            })
        }
    }

    private fun buildAttentionAction(label: String, tint: Int, onClick: () -> Unit): TextView =
        TextView(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) }
            background = resources.getDrawable(R.drawable.bg_chip, theme)
            backgroundTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(tint, 36))
            text = label
            gravity = android.view.Gravity.CENTER
            setTextColor(tint)
            textSize = 10.5f
            setPadding(dp(10), dp(5), dp(10), dp(5))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun sourceLabel(source: String): String = when (source.lowercase()) {
        "task" -> "任务"
        "automation" -> "自动化"
        "action" -> "动作"
        "emergency" -> "急停"
        "proactive" -> "主动提醒"
        else -> source.ifBlank { "工作区" }
    }

    private fun statusLabel(status: String): String = when (status.lowercase()) {
        "open" -> "待处理"
        "pending" -> "待确认"
        "needs_confirmation" -> "待确认"
        "running" -> "执行中"
        "blocked" -> "受阻"
        "acknowledged", "read" -> "已读"
        "resolved", "succeeded", "done" -> "已完成"
        "ignored", "dismissed" -> "已忽略"
        "cancelled" -> "已取消"
        "archived" -> "已归档"
        "failed", "error" -> "失败"
        else -> status.ifBlank { "未知" }
    }

    private fun renderCockpitSummary() {
        cockpitSummaryBody.visibility = if (cockpitSummaryExpanded) View.VISIBLE else View.GONE
        cockpitSummaryToggle.text = getString(if (cockpitSummaryExpanded) R.string.cockpit_collapse else R.string.cockpit_expand)
        val workspaceTasks = workspaceTaskMirror.values.toList()
        val activeWorkspaceTask = workspaceTasks
            .sortedByDescending { parseEpochMs(it.opt("updatedAt")) ?: 0L }
            .firstOrNull {
                val state = it.optString("state", it.optString("status", "pending"))
                state.equals("pending", true) || state.equals("running", true) || state.equals("needs_confirmation", true)
            }
        val latestResult = workspaceTasks
            .sortedByDescending { parseEpochMs(it.opt("updatedAt")) ?: 0L }
            .firstOrNull {
                val state = it.optString("state", it.optString("status", "pending"))
                state.equals("failed", true) || state.equals("error", true) ||
                    state.equals("succeeded", true) || state.equals("done", true)
            }
        val activeCount = cockpitAttentionItems.values.count {
            !it.status.equals("resolved", true) &&
                !it.status.equals("dismissed", true) &&
                !it.status.equals("ignored", true)
        }
        cockpitSummaryStatus.text = if (!cockpitUsesOfflineMirror && companionSummary.generatedAt > 0L) {
            companionSummary.compactStatus()
        } else {
            buildList {
                add(if (cockpitUsesOfflineMirror) "离线镜像" else "链路 ${deviceHealthLabel(deviceHealthState.overall.name)}")
                add("${activeCount} 条提醒")
                add(if (activeWorkspaceTask == null) "无进行中任务" else "有进行中任务")
                add("Mote Lv.${moteRelationship.level}")
            }.joinToString(" · ")
        }
        currentTaskSummary.text = if (activeWorkspaceTask == null) {
            getString(R.string.current_task_empty)
        } else {
            "当前任务：${activeWorkspaceTask.optString("title", "未命名任务")} · ${statusLabel(activeWorkspaceTask.optString("state", "pending"))} · ${activeWorkspaceTask.optInt("progress", 0)}%"
        }
        currentTaskMetaSummary.text = if (activeWorkspaceTask == null) {
            getString(R.string.current_task_meta_empty)
        } else {
            buildList {
                add("来源 ${activeWorkspaceTask.optString("source", "workspace")}")
                activeWorkspaceTask.optJSONObject("metadata")?.optString("sessionId")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { add("会话 ${it.takeLast(6)}") }
                add("更新 ${activeWorkspaceTask.optString("updatedAt").take(19)}")
            }.joinToString(" · ")
        }
        recentResultSummary.text = if (latestResult == null) {
            getString(R.string.recent_result_empty)
        } else {
            "最近结果：${latestResult.optString("title", "未命名任务")} · ${statusLabel(latestResult.optString("state", "pending"))}\n${latestResult.optString("error").ifBlank { latestResult.optString("detail").take(96) }}"
        }
        capabilityHealthSummary.text = listOf(
            "连接：${deviceHealthLabel(deviceHealthState.bridge.name.lowercase(Locale.ROOT))}",
            "节点：${deviceHealthLabel(deviceHealthState.node.name)} · 模型：${deviceHealthLabel(deviceHealthState.model.name)}",
            "授权：${deviceHealthLabel(deviceHealthState.authorization.name)} · 工具会话 ${formatRemaining(aiAuthorizedUntilMs?.minus(System.currentTimeMillis()))}",
            "相机：${deviceHealthLabel(deviceHealthState.camera.name)}",
            "麦克风：${deviceHealthLabel(deviceHealthState.microphone.name)} · ${when {
                pttActive -> "按住说话"
                continuousListening -> "持续监听"
                micRunning -> "占用中"
                else -> "关闭"
            }}",
            "任务：${deviceHealthLabel(deviceHealthState.tasks.name)} · 自动化：${automationSummaryLabel()}",
            "事件积压：${deviceHealthState.outboxPending} · ACK：${deviceHealthState.lastAckAt?.toString() ?: "暂无"}",
            deviceHealthState.lastError?.takeIf { it.isNotBlank() }?.let { "最近错误：$it" } ?: "重连次数：${deviceHealthState.reconnectAttempt}",
        ).joinToString("\n")
    }

    private fun deviceHealthLabel(value: String): String = when (value.lowercase(Locale.ROOT)) {
        "online", "active" -> "正常"
        "connecting" -> "连接中"
        "retrying" -> "重连中"
        "degraded" -> "降级"
        "expired", "auth_failed" -> "令牌失效"
        "paused" -> "已暂停"
        "error" -> "异常"
        "inactive", "disconnected" -> "离线"
        else -> "未知"
    }

    private fun automationSummaryLabel(): String {
        if (workspaceEmergencyState?.optBoolean("active") == true) return "急停中"
        val recent = automationRunMirror.values.maxByOrNull { parseEpochMs(it.opt("completedAt")) ?: parseEpochMs(it.opt("createdAt")) ?: 0L }
        return when {
            recent == null && cockpitUsesOfflineMirror -> "离线镜像"
            recent == null -> "暂无"
            else -> "${recent.optString("status", "queued")} · ${recent.optString("automationId").takeLast(6)}"
        }
    }

    private fun refreshCockpitSnapshot() {
        if (!BridgeLink.isOnline) {
            loadLocalCockpitSnapshot()
            return
        }
        workspaceRequest(
            "/api/workspace",
            onSuccess = { json -> applyWorkspaceSnapshot(json) },
            onError = {
                Log.w(TAG, "workspace snapshot unavailable: $it")
                loadLocalCockpitSnapshot()
            }
        )
    }

    private fun loadLocalCockpitSnapshot() {
        cockpitUsesOfflineMirror = true
        appScope.launch(Dispatchers.IO) {
            val sessions = workspaceRepository.sessions()
            val tasks = workspaceRepository.tasks()
            val attention = workspaceRepository.attentionItems()
            val actionRuns = workspaceRepository.actionRuns()
            val cachedMoteRoster = getSharedPreferences("mote_roster", Context.MODE_PRIVATE).getString("roster", null)
            val cachedMoteState = getSharedPreferences("mote_roster", Context.MODE_PRIVATE).getString("state", null)
            val attentionJson = JSONArray().apply {
                attention.forEach { put(JSONObject(it.toJson())) }
            }
            withContext(Dispatchers.Main) {
                if (!cachedMoteRoster.isNullOrBlank()) handleMoteSnapshot(JSONObject().put("roster", JSONArray(cachedMoteRoster)).put("state", JSONObject(cachedMoteState ?: "{}")))
                workspaceTaskMirror.clear()
                automationRunMirror.clear()
                actionRunMirror.clear()
                attentionMirror.clear()
                tasks.forEach { task ->
                    workspaceTaskMirror[task.id] = JSONObject()
                        .put("id", task.id)
                        .put("source", task.source)
                        .put("title", task.title)
                        .put("state", task.state)
                        .put("progress", task.progress)
                        .put("detail", task.detail)
                        .put("error", task.error)
                        .put("updatedAt", task.updatedAt)
                }
                attention.forEach { item ->
                    val key = item.dedupeKey?.takeIf { it.isNotBlank() } ?: "attention:${item.id}"
                    attentionMirror[key] = JSONObject(item.toJson())
                }
                actionRuns.forEach { run ->
                    actionRunMirror[run.id] = JSONObject(run.toJson())
                }
                companionSessionRepository.applyTimelineSnapshot(
                    TimelineSnapshot(
                        revision = workspaceRevision,
                        tasks = tasks.map { task ->
                            TimelineTask(
                                id = task.id,
                                title = task.title,
                                state = task.state,
                                progress = task.progress,
                                detail = task.detail,
                                source = task.source,
                                recentResult = task.error,
                                createdAt = task.createdAt,
                                updatedAt = task.updatedAt
                            )
                        },
                        attention = attention.map { item ->
                            TimelineAttention(
                                id = item.id,
                                source = item.source,
                                severity = item.severity,
                                status = item.status,
                                title = item.title,
                                summary = item.summary,
                                relatedSessionId = item.relatedSessionId,
                                relatedTaskId = item.relatedTaskId,
                                dedupeKey = item.dedupeKey,
                                createdAt = item.createdAt,
                                updatedAt = item.updatedAt
                            )
                        }
                    ),
                    workspaceRevision
                )
                companionSessionRepository.markOffline("使用本地镜像")
                if (aiSelectedSessionId.isBlank()) {
                    aiSelectedSessionId = sessions.firstOrNull()?.id.orEmpty()
                }
                rebuildAttentionItems(attentionJson)
                renderAttentionCenter()
                renderCockpitSummary()
                renderCompanionSessionSnapshot()
            }
        }
    }

    private fun applyWorkspaceSnapshot(json: JSONObject) {
        cockpitUsesOfflineMirror = false
        handleMoteSnapshot(json.optJSONObject("motes"))
        json.optJSONObject("deviceHealth")?.let { deviceHealthState = parseDeviceHealthJson(it) }
        workspaceEmergencyState = json.optJSONObject("emergencyStop")
        workspaceTaskMirror.clear()
        automationRunMirror.clear()
        actionRunMirror.clear()
        attentionMirror.clear()
        val tasks = json.optJSONArray("tasks") ?: JSONArray()
        for (index in 0 until tasks.length()) {
            tasks.optJSONObject(index)?.optString("id")?.takeIf { it.isNotBlank() }?.let { id ->
                val task = tasks.getJSONObject(index)
                workspaceTaskMirror[id] = task
                persistWorkspaceTask(task)
            }
        }
        val automationRuns = json.optJSONArray("automationRuns") ?: json.optJSONArray("runs") ?: JSONArray()
        for (index in 0 until automationRuns.length()) {
            automationRuns.optJSONObject(index)?.optString("id")?.takeIf { it.isNotBlank() }?.let { id ->
                automationRunMirror[id] = automationRuns.getJSONObject(index)
            }
        }
        val actionRuns = json.optJSONArray("actionRuns") ?: JSONArray()
        for (index in 0 until actionRuns.length()) {
            actionRuns.optJSONObject(index)?.optString("id")?.takeIf { it.isNotBlank() }?.let { id ->
                val actionRun = actionRuns.getJSONObject(index)
                actionRunMirror[id] = actionRun
                persistActionRun(actionRun)
            }
        }
                val attention = json.optJSONArray("attention")
        if (attention != null) {
            for (index in 0 until attention.length()) {
                attention.optJSONObject(index)?.let { item ->
                    val key = item.optString("dedupeKey").ifBlank { "attention:${item.optString("id", index.toString())}" }
                    attentionMirror[key] = item
                    persistAttention(item)
                }
            }
        }
        val policies = json.optJSONArray("policies")
        if (policies != null) {
            for (index in 0 until policies.length()) {
                policies.optJSONObject(index)?.let { policy ->
                    persistPolicy(policy, policy.optString("scopeType", "session"), policy.optString("targetId", policy.optString("scopeId")))
                }
            }
        }
        rebuildAttentionItems(attention)
        renderAttentionCenter()
        renderCockpitSummary()
    }

    private fun rebuildAttentionItems(explicitAttention: JSONArray? = null) {
        val merged = linkedMapOf<String, CockpitAttentionItem>()
        if (explicitAttention != null) {
            attentionMirror.clear()
            for (index in 0 until explicitAttention.length()) {
                explicitAttention.optJSONObject(index)?.let { item ->
                    val key = item.optString("dedupeKey").ifBlank { "attention:${item.optString("id", index.toString())}" }
                    attentionMirror[key] = item
                }
            }
        }
        attentionMirror.values.forEach { json ->
            upsertAttentionItem(
                merged,
                CockpitAttentionItem(
                    key = json.optString("dedupeKey").ifBlank { "attention:${json.optString("id")}" },
                    id = json.optString("id"),
                    title = json.optString("title", "注意力节点"),
                    summary = json.optString("summary", json.optString("detail")),
                    severity = json.optString("severity", "medium"),
                    status = json.optString("status", if (json.optBoolean("read")) "read" else "open"),
                    source = json.optString("source", "attention"),
                    relatedSessionId = json.optString("relatedSessionId"),
                    relatedTaskId = json.optString("relatedTaskId"),
                    relatedActionId = json.optString("relatedActionId"),
                    updatedAtMs = parseEpochMs(json.opt("updatedAt")) ?: System.currentTimeMillis(),
                    offlineMirror = cockpitUsesOfflineMirror
                )
            )
        }
        workspaceEmergencyState?.takeIf { it.optBoolean("active") }?.let { state ->
            upsertAttentionItem(
                merged,
                CockpitAttentionItem(
                    key = "emergency:global",
                    id = "emergency:global",
                    title = "AI 工具已急停",
                    summary = state.optString("reason", "manual").ifBlank { "手动急停" },
                    severity = "high",
                    status = "open",
                    source = "emergency",
                    updatedAtMs = parseEpochMs(state.opt("updatedAt")) ?: System.currentTimeMillis()
                )
            )
        }
        workspaceTaskMirror.values.forEach { task ->
            attentionFromTask(task)?.let { upsertAttentionItem(merged, it) }
        }
        automationRunMirror.values.forEach { run ->
            attentionFromAutomationRun(run)?.let { upsertAttentionItem(merged, it) }
        }
        actionRunMirror.values.forEach { run ->
            attentionFromActionRun(run)?.let { upsertAttentionItem(merged, it) }
        }
        cockpitAttentionItems.clear()
        cockpitAttentionItems.putAll(merged)
    }

    private fun upsertAttentionItem(
        target: MutableMap<String, CockpitAttentionItem>,
        item: CockpitAttentionItem
    ) {
        val current = target[item.key]
        if (current == null || item.updatedAtMs >= current.updatedAtMs) {
            target[item.key] = item
        }
    }

    private fun attentionFromTask(task: JSONObject): CockpitAttentionItem? {
        val id = task.optString("id")
        if (id.isBlank()) return null
        val state = task.optString("state", task.optString("status", "pending"))
        val metadata = task.optJSONObject("metadata")
        val severity = when {
            state.equals("failed", true) || state.equals("error", true) -> "high"
            state.equals("running", true) -> "medium"
            state.equals("pending", true) -> "low"
            state.equals("succeeded", true) || state.equals("done", true) -> "low"
            else -> "medium"
        }
        val status = when {
            state.equals("failed", true) || state.equals("error", true) -> "open"
            state.equals("running", true) -> "running"
            state.equals("pending", true) -> "pending"
            state.equals("succeeded", true) || state.equals("done", true) -> "resolved"
            else -> state
        }
        val title = when {
            state.equals("failed", true) || state.equals("error", true) -> "任务异常 · ${task.optString("title", "未命名任务")}"
            state.equals("succeeded", true) || state.equals("done", true) -> "任务结果 · ${task.optString("title", "未命名任务")}"
            else -> task.optString("title", "未命名任务")
        }
        val summary = task.optString("error").ifBlank { task.optString("detail").take(92) }
        return CockpitAttentionItem(
            key = "task:$id",
            id = id,
            title = title,
            summary = summary,
            severity = severity,
            status = status,
            source = "task",
            relatedSessionId = metadata?.optString("sessionId").orEmpty(),
            relatedTaskId = id,
            updatedAtMs = parseEpochMs(task.opt("updatedAt")) ?: System.currentTimeMillis(),
            offlineMirror = cockpitUsesOfflineMirror
        )
    }

    private fun attentionFromAutomationRun(run: JSONObject): CockpitAttentionItem? {
        val id = run.optString("id")
        if (id.isBlank()) return null
        val status = run.optString("status", "queued")
        val severity = when {
            status.equals("failed", true) -> "high"
            status.equals("queued", true) -> "low"
            else -> "medium"
        }
        return CockpitAttentionItem(
            key = "automation:$id",
            id = id,
            title = "自动化 ${statusLabel(status)}",
            summary = "自动化 ${run.optString("automationId").takeLast(6)}",
            severity = severity,
            status = when {
                status.equals("queued", true) -> "pending"
                status.equals("succeeded", true) -> "resolved"
                status.equals("cancelled", true) -> "ignored"
                else -> status
            },
            source = "automation",
            updatedAtMs = parseEpochMs(run.opt("completedAt")) ?: parseEpochMs(run.opt("createdAt")) ?: System.currentTimeMillis(),
            offlineMirror = cockpitUsesOfflineMirror
        )
    }

    private fun attentionFromActionRun(run: JSONObject): CockpitAttentionItem? {
        val id = run.optString("id")
        if (id.isBlank()) return null
        val state = run.optString("state", "queued")
        val severity = when {
            state.equals("failed", true) || state.equals("blocked", true) -> "high"
            state.equals("running", true) -> "medium"
            else -> "low"
        }
        return CockpitAttentionItem(
            key = "action:$id",
            id = id,
            title = "动作 ${statusLabel(state)} · ${run.optString("toolId", "tool")}",
            summary = run.optString("error").ifBlank { run.optString("argsSummary").take(92) },
            severity = severity,
            status = when {
                state.equals("succeeded", true) -> "resolved"
                state.equals("cancelled", true) -> "ignored"
                else -> state
            },
            source = "action",
            relatedSessionId = run.optString("sessionId"),
            relatedTaskId = run.optString("taskId"),
            relatedActionId = id,
            updatedAtMs = parseEpochMs(run.opt("endedAt")) ?: parseEpochMs(run.opt("startedAt")) ?: parseEpochMs(run.opt("createdAt")) ?: System.currentTimeMillis(),
            offlineMirror = cockpitUsesOfflineMirror
        )
    }

    private fun handleAttentionAction(item: CockpitAttentionItem, nextStatus: String) {
        cockpitAttentionItems[item.key] = item.copy(
            status = nextStatus,
            updatedAtMs = System.currentTimeMillis()
        )
        renderAttentionCenter()
        renderCockpitSummary()
        if (!BridgeLink.isOnline || item.id.isBlank()) return
        val payload = JSONObject().put("read", true)
        if (!nextStatus.equals("acknowledged", true)) payload.put("status", nextStatus)
        workspaceRequest(
            "/api/attention/${android.net.Uri.encode(item.id)}",
            "PATCH",
            payload,
            onError = { Log.w(TAG, "attention patch unavailable: $it") }
        )
    }

    private fun openAttentionInAiSpace(item: CockpitAttentionItem) {
        aiSelectedSessionId = item.relatedSessionId.ifBlank { aiSelectedSessionId }
        aiHighlightedTaskId = item.relatedTaskId
        openAiSpace()
        if (item.relatedTaskId.isNotBlank()) {
            aiSpaceStatus.text = "已定位关联任务 ${item.relatedTaskId.takeLast(6)}"
            refreshAiTasks()
        }
    }

    private fun showPolicyLevelChooser() {
        if (aiSelectedSessionId.isBlank()) return
        val levels = arrayOf(AutonomyLevel.OBSERVE, AutonomyLevel.REVERSIBLE)
        val selected = levels.indexOf(aiPolicyState.level.ifBlank { AutonomyLevel.OBSERVE }).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("选择自主权级别")
            .setSingleChoiceItems(levels, selected) { dialog, which ->
                dialog.dismiss()
                patchSessionPolicy(JSONObject().put("level", levels[which]), status = "自主权级别已更新")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun refreshSessionPolicy() {
        if (aiSelectedSessionId.isBlank()) {
            aiPolicyState = SessionPolicyUi(
                supported = false,
                detail = "当前没有选中会话，无法读取 policy。"
            )
            renderAiPolicyState()
            return
        }
        aiPolicyState = aiPolicyState.copy(loading = true)
        renderAiPolicyState()
        workspaceRequest(
            "/api/workspace/sessions/${android.net.Uri.encode(aiSelectedSessionId)}/policy",
            onSuccess = { json ->
                val policy = json.optJSONObject("policy")
                if (policy == null) {
                    aiPolicyState = SessionPolicyUi(
                        supported = false,
                        detail = "节点未返回 policy 对象。"
                    )
                } else {
                    aiPolicyState = SessionPolicyUi(
                        available = true,
                        supported = true,
                        level = policy.optString("level", AutonomyLevel.OBSERVE),
                        continuousMic = policy.optBoolean("continuousMic", false),
                        allowedTools = jsonStringList(policy.optJSONArray("allowedTools")),
                        expiresAtMs = parseEpochMs(policy.opt("expiresAt")),
                        detail = if (policy.optJSONArray("confirmationRules")?.length() ?: 0 > 0) {
                            "确认规则 ${policy.optJSONArray("confirmationRules")?.length()} 条"
                        } else {
                            "会话级策略已返回。"
                        },
                        loading = false
                    )
                }
                renderAiPolicyState()
            },
            onError = {
                aiPolicyState = SessionPolicyUi(
                    supported = false,
                    detail = "Node 尚未返回 /policy 路由：$it",
                    loading = false
                )
                renderAiPolicyState()
            }
        )
    }

    private fun patchSessionPolicy(
        payload: JSONObject,
        revertContinuousMic: Boolean? = null,
        status: String
    ) {
        if (aiSelectedSessionId.isBlank()) return
        workspaceRequest(
            "/api/workspace/sessions/${android.net.Uri.encode(aiSelectedSessionId)}/policy",
            "PATCH",
            payload,
            onSuccess = { json ->
                json.optJSONObject("policy")?.let { policy ->
                    aiPolicyState = SessionPolicyUi(
                        available = true,
                        supported = true,
                        level = policy.optString("level", aiPolicyState.level),
                        continuousMic = policy.optBoolean("continuousMic", aiPolicyState.continuousMic),
                        allowedTools = jsonStringList(policy.optJSONArray("allowedTools")),
                        expiresAtMs = parseEpochMs(policy.opt("expiresAt")),
                        detail = status
                    )
                }
                renderAiPolicyState()
            },
            onError = {
                revertContinuousMic?.let { expected ->
                    aiPolicyListenerMuted = true
                    aiContinuousMicSwitch.isChecked = expected
                    aiPolicyListenerMuted = false
                }
                aiPolicyState = aiPolicyState.copy(
                    supported = false,
                    detail = "Policy 更新失败：$it"
                )
                renderAiPolicyState()
            }
        )
    }

    private fun setupThemes() {
        themeApplier = ThemeApplier(this)
        activeTheme = UiTheme.load(this)
        themeButtons[UiTheme.AURORA_GLASS] = findViewById(R.id.themeGlass)
        themeButtons[UiTheme.LIQUID_MOTION] = findViewById(R.id.themeLiquid)
        themeButtons[UiTheme.CIRCUIT_NOIR] = findViewById(R.id.themeNoir)
        themeButtons.forEach { (theme, button) ->
            button.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                selectTheme(theme)
            }
        }
        applyTheme(activeTheme, persist = false)
    }

    private fun selectTheme(theme: UiTheme) {
        if (theme == activeTheme) return
        activeTheme = theme
        applyTheme(theme, persist = true)
        say("换到${theme.label}界面了。")
    }

    private fun applyTheme(theme: UiTheme, persist: Boolean) {
        themeApplier.apply(theme)
        companionView.setPalette(theme.accent, theme.secondary)
        renderAppearanceSelection()
        if (persist) UiTheme.save(this, theme)
    }

    private fun setupInteractions() {
        feedButton.setOnClickListener { interact("feed") }
        playButton.setOnClickListener { interact("play") }
        strokeButton.setOnClickListener { interact("stroke") }
        cameraButton.setOnClickListener { toggleCameraFromUi() }
        lensButton.setOnClickListener { flipLens() }
        listenButton.setOnClickListener { toggleListening() }
        serverButton.setOnClickListener { askForServer() }
        residentButton.setOnClickListener { toggleResident() }
        findViewById<Button>(R.id.focusButton).setOnClickListener { enterFocusMode() }
        findViewById<Button>(R.id.aiSpaceButton).setOnClickListener { openAiSpace() }
        findViewById<Button>(R.id.moteDexButton).setOnClickListener { showMoteDexDialog() }
        findViewById<Button>(R.id.focusAiSpaceButton).setOnClickListener { openAiSpace() }
        findViewById<Button>(R.id.aiSpaceClose).setOnClickListener { closeAiSpace() }
        findViewById<Button>(R.id.aiNewSession).setOnClickListener { createAiSession() }
        findViewById<Button>(R.id.aiSend).setOnClickListener { submitAiMessage() }
        aiTaskStart.setOnClickListener { performAiTaskAction("start") }
        aiTaskPause.setOnClickListener { performAiTaskAction("pause") }
        aiTaskContinue.setOnClickListener { performAiTaskAction("continue") }
        aiTaskRetry.setOnClickListener { performAiTaskAction("retry") }
        aiTaskCancel.setOnClickListener { performAiTaskAction("cancel") }
        aiTaskArchive.setOnClickListener { performAiTaskAction("archive") }
        findViewById<Button>(R.id.aiEmergencyStop).setOnClickListener { emergencyStopAiTools() }
        aiAuthorize.setOnClickListener { toggleAiAuthorization() }
        aiPolicyLevelButton.setOnClickListener { showPolicyLevelChooser() }
        aiPolicyRefresh.setOnClickListener { refreshSessionPolicy() }
        aiContinuousMicSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (aiPolicyListenerMuted || aiSelectedSessionId.isBlank()) return@setOnCheckedChangeListener
            patchSessionPolicy(
                JSONObject().put("continuousMic", isChecked),
                revertContinuousMic = !isChecked,
                status = "continuousMic 已更新"
            )
        }
        aiSessionSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                aiSessionIds.getOrNull(position)?.let { if (it != aiSelectedSessionId) selectAiSession(it) }
            }
        }
        titleText.setOnLongClickListener { openAiSpace(); true }
        focusExit.setOnClickListener {
            if (realityLensActive) exitRealityLens() else exitFocusMode()
        }
        focusToolsToggle.setOnClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            immersiveShellCoordinator.toggleDrawer()
            focusToolsExpanded = immersiveShellCoordinator.state.value.drawerOpen
            renderFocusTools()
        }
        focusCameraButton.setOnClickListener { toggleCameraFromUi() }
        focusLensButton.setOnClickListener { flipLens() }
        focusListenButton.setOnClickListener { toggleListening() }
        focusVoiceButton.setOnClickListener { toggleContinuousVoice() }
        focusMemoryButton.setOnClickListener { showMemoryDialog() }
        focusGameButton.setOnClickListener { showSignalGameDialog() }
        focusRealityButton.setOnClickListener { enterRealityLens() }
        focusExploreLogButton.setOnClickListener { showExplorationLogDialog() }
        focusCommandButton.setOnClickListener {
            exitFocusMode()
            commandInput.requestFocus()
            say("回到工作台，可以直接输入指令。")
        }
        focusStageButton.setOnClickListener { showCompanionStageSettings() }
        focusRoutinesButton.setOnClickListener { showDailyRoutineDialog() }
        focusGoalsButton.setOnClickListener { showGoalBoardDialog() }
        cockpitSummaryToggle.setOnClickListener {
            cockpitSummaryExpanded = !cockpitSummaryExpanded
            renderCockpitSummary()
        }
        findViewById<Button>(R.id.focusSendButton).setOnClickListener { submitFocusChat() }
        focusChatInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                submitFocusChat()
                true
            } else {
                false
            }
        }
        sendCommand.setOnClickListener { submitCommand() }
        findViewById<Button>(R.id.refreshTasks).setOnClickListener { requestSnapshot() }
        findViewById<Button>(R.id.refreshSensors).setOnClickListener { refreshTelemetry(forceUiUpdate = true) }
        findViewById<Button>(R.id.selectCodexTask).setOnClickListener { selectCodexTask() }
        findViewById<Button>(R.id.applyModel).setOnClickListener { applySelectedModel() }
        findViewById<Button>(R.id.refreshCodex).setOnClickListener { requestSnapshot() }
        sendChatButton.setOnClickListener { submitChat() }
        renderChatActionButton()
        voiceButton.setOnClickListener { toggleContinuousVoice() }
        renderVoiceState(BridgeService.isVoiceChatRunning, listening = false, speaking = false)
        memoryButton.setOnClickListener { showMemoryDialog() }
        memoryButton.setOnLongClickListener {
            showHandoffDialog()
            true
        }
        petGrowthChip.setOnClickListener { showGrowthDialog() }
        offlineApiButton.setOnClickListener { showOfflineApiDialog() }
        appearanceButtons[PetAppearance.MOTE] = findViewById(R.id.appearanceMote)
        appearanceButtons[PetAppearance.SPRITE] = findViewById(R.id.appearanceSprite)
        appearanceButtons[PetAppearance.GHOST] = findViewById(R.id.appearanceGhost)
        appearanceButtons[PetAppearance.CIRCUIT] = findViewById(R.id.appearanceCircuit)
        appearanceButtons[PetAppearance.CLOUD_WHALE] = findViewById(R.id.appearanceCloudWhale)
        appearanceButtons[PetAppearance.RIMURU] = findViewById(R.id.appearanceRimuru)
        appearanceButtons.forEach { (appearance, button) ->
            button.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                chooseAppearance(appearance)
            }
        }
        renderAppearanceSelection()

        panelTabs.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            consolePanel.visibility = if (checkedId == R.id.consoleTab) View.VISIBLE else View.GONE
            tasksPanel.visibility = if (checkedId == R.id.tasksTab) View.VISIBLE else View.GONE
            sensorsPanel.visibility = if (checkedId == R.id.sensorsTab) View.VISIBLE else View.GONE
            codexPanel.visibility = if (checkedId == R.id.codexTab) View.VISIBLE else View.GONE
            chatPanel.visibility = if (checkedId == R.id.chatTab) View.VISIBLE else View.GONE
        }

        bindHoldToTalk(pttButton)
        bindHoldToTalk(focusPttButton)
        commandInput.setOnEditorActionListener { _, _, _ ->
            submitCommand()
            true
        }
    }

    private fun bindHoldToTalk(view: View) {
        view.setOnTouchListener { touchView, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchView.isPressed = true
                    touchView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    setPtt(true)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    touchView.isPressed = false
                    touchView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    setPtt(false)
                    true
                }
                else -> false
            }
        }
    }

    private fun submitFocusChat() {
        val text = focusChatInput.text.toString().trim()
        if (text.isEmpty()) return
        focusChatInput.setText("")
        sendChatMessage(text)
    }

    private fun showPetBubble(text: String) {
        if (!immersiveMode || text.isBlank()) return
        layoutFocusSpeechOverlay()
        val mouthPoint = focusMouthPoint() ?: return
        val journeySeed = (0..4).random()
        val bubble = TextView(this).apply {
            this.text = text.take(220)
            textSize = 12f
            setTextColor(0xFFEAFFF5.toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.argb(216, 12, 32, 24))
                cornerRadius = dp(15).toFloat()
            }
            val horizontal = dp(13)
            val vertical = dp(9)
            setPadding(horizontal, vertical, horizontal, vertical)
            elevation = dp(10).toFloat()
            maxWidth = (resources.displayMetrics.widthPixels * .74f).toInt()
        }
        focusSpeechLayer.addView(
            bubble,
            android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        while (focusSpeechLayer.childCount > 3) {
            focusSpeechLayer.removeViewAt(0)
        }
        bubble.post {
            val layerWidth = focusSpeechLayer.width
            val drift = dp(journeySeed - 2).toFloat()
            val startX = (mouthPoint.first - bubble.width * .5f + drift)
                .coerceIn(dp(10).toFloat(), (layerWidth - bubble.width - dp(10)).coerceAtLeast(dp(10)).toFloat())
            val startY = (mouthPoint.second - bubble.height)
                .coerceAtLeast(dp(8).toFloat())
            bubble.x = startX
            bubble.y = startY
            bubble.pivotX = bubble.width * .5f
            bubble.pivotY = bubble.height.toFloat()
            bubble.scaleX = .84f
            bubble.scaleY = .84f

            val travel = dp(148 + journeySeed * 15).toFloat()
            val rise = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 3800L
                interpolator = android.view.animation.AnimationUtils.loadInterpolator(
                    this@MainActivity,
                    android.R.interpolator.accelerate_decelerate
                )
                addUpdateListener { animator ->
                    val value = animator.animatedValue as Float
                    bubble.translationY = dp(7) * (1f - value) - travel * value
                    bubble.translationX = drift * .55f * sin(Math.PI * value.toDouble()).toFloat()
                    bubble.alpha = if (value < .09f) {
                        value / .09f
                    } else {
                        (((1f - value) / .58f).coerceIn(0f, 1f))
                    }
                    val grow = (value / .10f).coerceIn(0f, 1f)
                    bubble.scaleX = .84f + grow * .16f
                    bubble.scaleY = .84f + grow * .16f
                }
                addListener(object : android.animation.Animator.AnimatorListener {
                    override fun onAnimationStart(animation: android.animation.Animator) = Unit
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        (bubble.parent as? android.view.ViewGroup)?.removeView(bubble)
                    }
                    override fun onAnimationCancel(animation: android.animation.Animator) = Unit
                    override fun onAnimationRepeat(animation: android.animation.Animator) = Unit
                })
            }
            bubble.tag = rise
            rise.start()
        }
    }

    private fun layoutFocusSpeechOverlay() {
        if (!immersiveMode) return
        val params = focusSpeechLayer.layoutParams as?
            androidx.constraintlayout.widget.ConstraintLayout.LayoutParams ?: return
        if (params.topMargin != 0) {
            params.topMargin = 0
            focusSpeechLayer.layoutParams = params
        }
    }

    private fun focusMouthPoint(): Pair<Float, Float>? {
        if (!immersiveMode) return null
        val stageView = companionView
        val stageWidth = stageView.width.toFloat()
        val stageHeight = stageView.height.toFloat()
        if (stageWidth <= 0f || stageHeight <= 0f) return null

        val radius = minOf(stageWidth, stageHeight) * .215f
        val mouthY = stageView.top + stageHeight * .52f + when (pet.appearance) {
            PetAppearance.GHOST -> -radius * .34f
            PetAppearance.RIMURU -> radius * .16f
            PetAppearance.CLOUD_WHALE -> radius * .20f
            else -> radius * .30f
        }
        return Pair(stageView.left + stageWidth * .5f, mouthY)
    }

    private fun chooseAppearance(value: PetAppearance) {
        pet = pet.copy(appearance = value)
        savePet()
        companionView.update(pet)
        companionView.poke()
        renderAppearanceSelection()
        if (BridgeLink.isOnline) {
            workspaceRequest("/api/motes/active", "PATCH", JSONObject().put("id", value.name.lowercase(Locale.ROOT)))
        }
        say("换成了${value.label}形态。")
    }

    private fun renderAppearanceSelection() {
        if (appearanceButtons.isEmpty()) return
        appearanceButtons.forEach { (appearance, button) ->
            val selected = pet.appearance == appearance
            button.backgroundTintList = ColorStateList.valueOf(
                if (selected) ColorUtils.setAlphaComponent(activeTheme.accent, 72) else activeTheme.buttonFill
            )
            button.setTextColor(if (selected) activeTheme.textPrimary else activeTheme.textSecondary)
            button.isSelected = selected
        }
    }

    private fun Int.dpPx() = (this * resources.displayMetrics.density).toInt()

    private fun Int.toVisibility(): Int = if (this != FrameLayout.LayoutParams.WRAP_CONTENT) View.GONE else View.VISIBLE

    private fun setupPanels() {
        logRecycler.layoutManager = LinearLayoutManager(this)
        logRecycler.adapter = logAdapter
        taskRecycler.layoutManager = LinearLayoutManager(this)
        taskRecycler.adapter = taskAdapter
        chatRecycler.layoutManager = LinearLayoutManager(this)
        chatRecycler.adapter = chatAdapter
        loadChatHistory()
        logAdapter.add("info", "Mote 已唤醒；输入 help 查看指令。")
    }

    private fun loadChatHistory() {
        val raw = getSharedPreferences("mote_chat", Context.MODE_PRIVATE)
            .getString("history", null) ?: return
        runCatching {
            val array = JSONArray(raw)
            val items = mutableListOf<ChatMessage>()
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                items.add(
                    ChatMessage(
                        role = item.optString("role"),
                        text = item.optString("text"),
                        time = item.optString("time"),
                    )
                )
            }
            chatAdapter.load(items)
        }.onFailure { Log.w(TAG, "chat history load failed", it) }
    }

    private fun appendChat(role: String, text: String, streaming: Boolean = false, time: String = currentTime()) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        chatAdapter.upsertStreaming(ChatMessage(role, clean, time, streaming))
        chatRecycler.scrollToPosition(chatAdapter.itemCount - 1)
        persistChatHistory()
    }

    private fun syncChatHistory(messages: List<ChatMessage>) {
        val visibleMessages = if (streamingChatId.isNotEmpty() && streamingText.isNotBlank()) {
            messages + ChatMessage(
                role = "assistant",
                text = streamingText.toString(),
                time = currentTime(),
                streaming = true
            )
        } else {
            messages
        }
        chatAdapter.load(visibleMessages)
        chatRecycler.scrollToPosition(chatAdapter.itemCount - 1)
        persistChatHistory(visibleMessages.dropLast(if (streamingChatId.isNotEmpty()) 1 else 0))
    }

    private fun persistChatHistory(messages: List<ChatMessage>? = null) {
        val values = messages ?: chatAdapterCurrentMessages()
        val array = JSONArray()
        values.takeLast(120).forEach { item ->
            array.put(JSONObject().put("role", item.role).put("text", item.text).put("time", item.time))
        }
        getSharedPreferences("mote_chat", Context.MODE_PRIVATE).edit()
            .putString("history", array.toString())
            .apply()
    }

    private fun chatAdapterCurrentMessages(): List<ChatMessage> =
        (0 until chatAdapter.itemCount).mapNotNull { chatAdapter.messageAt(it) }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LOCATION_PERMISSION) {
            updateRealityLocation()
            return
        }
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                if (cancelledRealityCameraPermission) {
                    cancelledRealityCameraPermission = false
                    awaitingArCoreCameraPermission = false
                    setStatus("现实镜头请求已取消 · 未开启相机")
                    return
                }
                setStatus(if (pairingScanActive) "相机已准备 · 正在本机扫码" else "相机权限已准备")
                if (awaitingArCoreCameraPermission && realityLensActive) {
                    awaitingArCoreCameraPermission = false
                    beginArCoreForCurrentEntry()
                } else {
                    startCamera()
                }
            } else {
                cancelledRealityCameraPermission = false
                awaitingArCoreCameraPermission = false
                val pairingDenied = pairingScanActive
                pairingScanActive = false
                pairingScanOwnsCamera = false
                if (realityLensActive) {
                    realityLensView.setRealityTrackingSnapshot(
                        RealityTrackingSnapshot(RealityTrackingStatus.FALLBACK, fallbackReason = "相机权限关闭，可继续手动探索")
                    )
                }
                setStatus(if (realityLensActive) "相机权限关闭 · 仍可手动探索" else "相机保持关闭")
                if (pairingDenied) Toast.makeText(this, "扫码需要相机权限；未读取或上传任何画面", Toast.LENGTH_LONG).show()
                else say("需要看见时，再把镜头交给我。")
            }
            return
        }
        if (requestCode == REQUEST_AUDIO_PERMISSION) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                setStatus("麦克风权限已准备")
                if (continuousListening || pttActive) startMicrophone()
            } else {
                setStatus("麦克风保持关闭")
                pttActive = false
                continuousListening = false
                say("不打开麦克风也可以继续文字陪伴。")
            }
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startCameraOrReportPermissions() {
        val lockout = realityCaptureController.snapshot()
        if (lockout.thermalLockout) {
            val allowed = realityCaptureController.clearThermalLockoutAfterExplicitStart(
                nowMs = SystemClock.elapsedRealtime(),
                temperatureCelsius = currentRealityTemperatureCelsius(),
                entryId = lockout.entryId.takeIf { realityLensActive && lockout.surface == RealityCaptureSurface.REALITY },
            )
            if (!allowed) {
                setStatus("设备需先低于 38°C 并稳定冷却 60 秒，随后再手动开启镜头")
                return
            }
            arCoreRenderView.resetQualityLockoutAfterExplicitStart()
        }
        if (!hasCameraPermission()) {
            awaitingArCoreCameraPermission = false
            cancelledRealityCameraPermission = false
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.CAMERA),
                REQUEST_CAMERA_PERMISSION
            )
            return
        }
        if (realityLensActive) {
            val capture = realityCaptureController.snapshot()
            if (capture.owner == RealityCameraOwner.ARCORE) return
            if (capture.thermalLockout) {
                val allowed = realityCaptureController.clearThermalLockoutAfterExplicitStart(
                    nowMs = SystemClock.elapsedRealtime(),
                    temperatureCelsius = currentRealityTemperatureCelsius(),
                    entryId = capture.entryId.takeIf { capture.surface == RealityCaptureSurface.REALITY },
                )
                if (!allowed) {
                    setStatus("设备需先低于 38°C 并稳定冷却 60 秒，随后再手动开启镜头")
                    return
                }
                arCoreRenderView.resetQualityLockoutAfterExplicitStart()
            }
        } else if (realityCaptureController.snapshot().thermalLockout) {
            val allowed = realityCaptureController.clearThermalLockoutAfterExplicitStart(
                nowMs = SystemClock.elapsedRealtime(),
                temperatureCelsius = currentRealityTemperatureCelsius(),
            )
            if (!allowed) {
                setStatus("设备需先低于 38°C 并稳定冷却 60 秒，再手动开启镜头")
                return
            }
            arCoreRenderView.resetQualityLockoutAfterExplicitStart()
        }
        val ownerState = realityCaptureController.snapshot()
        if (realityLensActive && ownerState.owner == RealityCameraOwner.ARCORE) return
        if (realityLensActive && ownerState.owner == RealityCameraOwner.NONE &&
            ownerState.arAttempted && ownerState.fallbackReason == null
        ) {
            cancelPendingArCoreForCameraX(ownerState.entryId)
        }
        startCamera()
    }

    private fun cancelPendingArCoreForCameraX(entryId: Long) {
        if (!isCurrentArEntry(entryId)) return
        clearPendingArCoreInstall()
        arCoreInstallFlow = null
        arCoreRenderView.closeSession()
        arCoreRenderView.visibility = View.GONE
        realityCaptureController.markFallback(entryId, "已切换普通镜头", thermal = false)
        realityLensView.setRealityTrackingSnapshot(
            RealityTrackingSnapshot(RealityTrackingStatus.FALLBACK, fallbackReason = "普通镜头模式")
        )
    }

    private fun toggleCameraFromUi() {
        val owner = realityCaptureController.snapshot().owner
        if (cameraRunning || (realityLensActive && owner == RealityCameraOwner.ARCORE)) stopCamera()
        else startCameraOrReportPermissions()
    }

    private fun currentRealityTemperatureCelsius(): Float? {
        val nowMs = SystemClock.elapsedRealtime()
        return RealityTemperaturePolicy.maximumFresh(
            nowMs = nowMs,
            currentCelsius = DeviceTelemetry.currentBatteryTemperatureCelsius(this),
            cached = cachedTelemetryTemperatures(),
        )
    }

    private fun currentCachedRealityTemperatureCelsius(nowMs: Long): Float? =
        RealityTemperaturePolicy.maximumFresh(
            nowMs = nowMs,
            currentCelsius = null,
            cached = cachedTelemetryTemperatures() + listOfNotNull(latestArCameraTemperature),
        )

    private fun cachedTelemetryTemperatures(): List<TimedTemperature> {
        val sampleAtMs = latestTelemetrySampledAtMs
        val telemetry = latestTelemetry ?: return emptyList()
        if (sampleAtMs <= 0L) return emptyList()
        return listOfNotNull(
            telemetry.batteryTemperature.takeIf { it.isFinite() && it > 0f },
            telemetry.thermalCelsius.takeIf { it.isFinite() && it > 0f },
        ).map { TimedTemperature(it, sampleAtMs) }
    }

    private fun beginArCoreForCurrentEntry() {
        if (!realityLensActive) return
        if (cameraRunning || cameraStartPending) {
            realityLensView.setRealityTrackingSnapshot(
                RealityTrackingSnapshot(RealityTrackingStatus.FALLBACK, fallbackReason = "普通镜头模式")
            )
            return
        }
        val current = realityCaptureController.snapshot()
        if (current.owner == RealityCameraOwner.CAMERAX) {
            realityLensView.setRealityTrackingSnapshot(
                RealityTrackingSnapshot(RealityTrackingStatus.FALLBACK, fallbackReason = "普通镜头模式")
            )
            return
        }
        if (current.owner == RealityCameraOwner.ARCORE) return
        if (current.thermalLockout) {
            val reason = current.fallbackReason ?: "设备温度过高"
            realityLensView.setRealityTrackingSnapshot(
                RealityTrackingSnapshot(RealityTrackingStatus.FALLBACK, fallbackReason = "$reason · 仍可手动探索")
            )
            setStatus(reason)
            return
        }
        if ((currentRealityTemperatureCelsius() ?: 0f) >= AR_THERMAL_LIMIT_CELSIUS) {
            realityArEntryId = current.entryId
            fallbackFromArCore("设备偏热，镜头已暂停；仍可手动探索", thermal = true)
            return
        }
        if (!hasCameraPermission()) {
            awaitingArCoreCameraPermission = true
            cancelledRealityCameraPermission = false
            realityLensView.setRealityTrackingSnapshot(
                RealityTrackingSnapshot(RealityTrackingStatus.STARTING, fallbackReason = "等待相机权限")
            )
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.CAMERA),
                REQUEST_CAMERA_PERMISSION,
            )
            return
        }
        val entryId = realityCaptureController.beginArCoreAttempt() ?: return
        realityArEntryId = entryId
        if ((currentRealityTemperatureCelsius() ?: 0f) >= AR_THERMAL_LIMIT_CELSIUS) {
            fallbackFromArCore("设备偏热，镜头已暂停；仍可手动探索", thermal = true)
            return
        }
        arCoreInstallFlow = ArCoreInstallFlow()
        val installReturnPending = getSharedPreferences("reality_arcore", Context.MODE_PRIVATE)
            .getBoolean("install_return_pending", false)
        if (installReturnPending) arCoreInstallFlow = ArCoreInstallFlow(initialInstallUiPending = true)
        arCoreAvailabilityPolls = 0
        arCoreRenderView.visibility = View.VISIBLE
        previewView.visibility = View.INVISIBLE
        realityLensView.setRealityTrackingSnapshot(RealityTrackingSnapshot(RealityTrackingStatus.CHECKING))
        if (installReturnPending) handleArCoreInstallResume() else checkArCoreAvailability(entryId)
    }

    private fun checkArCoreAvailability(entryId: Long) {
        if (!isCurrentArEntry(entryId)) return
        val availability = runCatching { ArCoreApk.getInstance().checkAvailability(this) }.getOrNull()
        val state = when (availability) {
            ArCoreApk.Availability.SUPPORTED_INSTALLED -> ArCoreAvailabilityState.INSTALLED
            ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED,
            ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD -> ArCoreAvailabilityState.NEEDS_INSTALL
            ArCoreApk.Availability.UNKNOWN_CHECKING -> ArCoreAvailabilityState.CHECKING
            else -> ArCoreAvailabilityState.UNSUPPORTED
        }
        when (arCoreInstallFlow?.evaluate(state) ?: ArCoreInstallAction.FALLBACK) {
            ArCoreInstallAction.WAIT -> {
                if (arCoreAvailabilityPolls++ >= MAX_AR_AVAILABILITY_POLLS) {
                    fallbackFromArCore("AR 服务检查超时，已切换兼容镜头", thermal = false)
                } else {
                    realityLensView.setRealityTrackingSnapshot(RealityTrackingSnapshot(RealityTrackingStatus.CHECKING))
                    rootLayout.postDelayed({ checkArCoreAvailability(entryId) }, AR_AVAILABILITY_POLL_MS)
                }
            }
            ArCoreInstallAction.START_SESSION -> startArCoreSession(entryId)
            ArCoreInstallAction.REQUEST_USER_CONFIRMATION -> requestArCoreInstall(entryId)
            ArCoreInstallAction.FALLBACK -> fallbackFromArCore("此设备暂不支持 ARCore，已切换兼容镜头", thermal = false)
            ArCoreInstallAction.CHECK_WITHOUT_PROMPT -> Unit
        }
    }

    private fun requestArCoreInstall(entryId: Long) {
        if (!isCurrentArEntry(entryId)) return
        try {
            when (ArCoreApk.getInstance().requestInstall(this, true)) {
                ArCoreApk.InstallStatus.INSTALLED -> {
                    arCoreInstallFlow?.onUserInstallResult(installed = true, installUiRequested = false)
                    clearPendingArCoreInstall()
                    startArCoreSession(entryId)
                }
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    arCoreInstallFlow?.onUserInstallResult(installed = false, installUiRequested = true)
                    getSharedPreferences("reality_arcore", Context.MODE_PRIVATE).edit()
                        .putBoolean("install_return_pending", true)
                        .commit()
                    realityLensView.setRealityTrackingSnapshot(RealityTrackingSnapshot(RealityTrackingStatus.INSTALLING))
                    setStatus("等待 Google Play 服务安装 ARCore")
                }
            }
        } catch (_: Exception) {
            clearPendingArCoreInstall()
            arCoreInstallFlow?.onUserInstallResult(installed = false, installUiRequested = false)
            fallbackFromArCore("ARCore 安装未完成，已切换兼容镜头", thermal = false)
        }
    }

    private fun handleArCoreInstallResume() {
        val flow = arCoreInstallFlow ?: return
        if (flow.onActivityResumed() != ArCoreInstallAction.CHECK_WITHOUT_PROMPT) return
        val entryId = realityArEntryId ?: return
        if (!isCurrentArEntry(entryId)) return
        try {
            when (ArCoreApk.getInstance().requestInstall(this, false)) {
                ArCoreApk.InstallStatus.INSTALLED -> {
                    flow.onSilentInstallResult(installed = true)
                    clearPendingArCoreInstall()
                    startArCoreSession(entryId)
                }
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    arCoreInstallPolls = 0
                    pollArCoreInstall(entryId)
                }
            }
        } catch (_: Exception) {
            clearPendingArCoreInstall()
            flow.onSilentInstallResult(installed = false)
            fallbackFromArCore("ARCore 未安装，已切换兼容镜头", thermal = false)
        }
    }

    private fun pollArCoreInstall(entryId: Long) {
        if (!isCurrentArEntry(entryId)) return
        val state = runCatching { ArCoreApk.getInstance().checkAvailability(this) }.getOrNull()
        when (state) {
            ArCoreApk.Availability.SUPPORTED_INSTALLED -> {
                arCoreInstallFlow?.onSilentInstallResult(installed = true)
                clearPendingArCoreInstall()
                startArCoreSession(entryId)
            }
            ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> {
                arCoreInstallFlow?.onSilentInstallResult(installed = false)
                clearPendingArCoreInstall()
                fallbackFromArCore("此设备暂不支持 ARCore，已切换兼容镜头", thermal = false)
            }
            else -> if (arCoreInstallPolls++ >= MAX_AR_INSTALL_POLLS) {
                arCoreInstallFlow?.onSilentInstallResult(installed = false)
                clearPendingArCoreInstall()
                fallbackFromArCore("ARCore 安装超时，已切换兼容镜头", thermal = false)
            } else {
                rootLayout.postDelayed({ pollArCoreInstall(entryId) }, AR_AVAILABILITY_POLL_MS)
            }
        }
    }

    private fun startArCoreSession(entryId: Long) {
        if (!isCurrentArEntry(entryId)) return
        if (realityCaptureController.snapshot().thermalLockout) {
            fallbackFromArCore("设备温度过高，镜头已暂停；仍可手动探索", thermal = true, disableCamera = true)
            return
        }
        if (!realityCaptureController.claimArCore(entryId)) {
            clearPendingArCoreInstall()
            return
        }
        arCoreRenderView.visibility = View.VISIBLE
        previewView.visibility = View.INVISIBLE
        realityLensView.setRealityTrackingSnapshot(RealityTrackingSnapshot(RealityTrackingStatus.STARTING))
        if (!arCoreRenderView.createAndInstallSession(this)) {
            fallbackFromArCore("ARCore 启动失败，已切换兼容镜头", thermal = false)
        }
    }

    private fun isCurrentArEntry(entryId: Long): Boolean =
        realityLensActive && realityArEntryId == entryId && realityCaptureController.isCurrentRealityEntry(entryId)

    private fun clearPendingArCoreInstall() {
        getSharedPreferences("reality_arcore", Context.MODE_PRIVATE).edit()
            .remove("install_return_pending")
            .apply()
    }

    private fun fallbackFromArCore(reason: String, thermal: Boolean, disableCamera: Boolean = thermal) {
        val entryId = realityArEntryId ?: return
        if (!isCurrentArEntry(entryId)) return
        val capture = realityCaptureController.snapshot()
        if (capture.owner == RealityCameraOwner.CAMERAX && !thermal) return
        if (capture.owner == RealityCameraOwner.NONE && capture.fallbackReason != null && !thermal) return
        clearPendingArCoreInstall()
        if (capture.owner == RealityCameraOwner.ARCORE) arCoreRenderView.closeSession()
        arCoreRenderView.visibility = View.GONE
        if (capture.owner == RealityCameraOwner.CAMERAX) {
            cameraSession.incrementAndGet()
            cameraRunning = false
            cameraStartPending = false
            cameraPreview = null
            runCatching { cameraProvider?.unbindAll() }
        }
        if (capture.owner != RealityCameraOwner.NONE) realityCaptureController.releaseCamera(capture.owner, entryId)
        if (!realityCaptureController.markFallback(entryId, reason, thermal)) return
        realityAnchorRepositioning = false
        realityLensView.setAnchorRepositioning(false)
        realityRepositionButton.text = "重新校准"
        previewView.visibility = View.INVISIBLE
        previewView.alpha = 0f
        realityLensView.setRealityTrackingSnapshot(
            RealityTrackingSnapshot(RealityTrackingStatus.FALLBACK, fallbackReason = reason)
        )
        renderCameraHeroState()
        renderFocusTools()
        renderPet()
        if (disableCamera) {
            cameraSession.incrementAndGet()
            cameraRunning = false
            cameraStartPending = false
            runCatching { cameraProvider?.unbindAll() }
            realityCaptureController.releaseCamera(RealityCameraOwner.CAMERAX, entryId)
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            setStatus(reason)
        } else if (hasCameraPermission()) {
            startCamera()
            setStatus(reason)
        } else {
            startCameraOrReportPermissions()
            setStatus(reason)
        }
        publishSensorState(force = true)
    }

    private fun stopArCoreForManualMode() {
        val entryId = realityArEntryId ?: return
        arCoreRenderView.closeSession()
        arCoreRenderView.visibility = View.GONE
        realityAnchorRepositioning = false
        realityLensView.setAnchorRepositioning(false)
        realityRepositionButton.text = "重新校准"
        realityCaptureController.releaseCamera(RealityCameraOwner.ARCORE, entryId)
        realityCaptureController.markFallback(entryId, "相机已由用户关闭", thermal = false)
        realityLensView.setRealityTrackingSnapshot(
            RealityTrackingSnapshot(RealityTrackingStatus.FALLBACK, fallbackReason = "镜头已关闭 · 可继续手动探索")
        )
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        renderCameraHeroState()
        renderFocusTools()
        renderPet()
        publishSensorState(force = true)
        setStatus("镜头已关闭 · 可继续手动探索")
    }

    private fun shouldUploadArRemoteFrame(): Boolean {
        if (!realityLensActive || !BridgeLink.isOnline || arFrameUploadPending.get()) return false
        if (!getSharedPreferences("phonebridge_privacy", Context.MODE_PRIVATE)
                .getBoolean("allow_remote_camera_upload", false)
        ) return false
        val now = SystemClock.elapsedRealtime()
        val temperature = currentCachedRealityTemperatureCelsius(now)
        val battery = latestTelemetry?.batteryPercent
        if ((temperature ?: 0f) >= 40f || (battery != null && battery <= 10)) return false
        val interval = if ((temperature ?: 0f) >= 38f || (battery != null && battery <= 20)) 240L else 120L
        if (now - lastArRemoteFrameAt < interval) return false
        lastArRemoteFrameAt = now
        return true
    }

    private fun encodeAndUploadArFrame(planes: RealityImagePlanes) {
        if (!BridgeLink.isOnline || !realityLensActive) return
        if (!arFrameUploadPending.compareAndSet(false, true)) return
        runCatching {
            networkExecutor.execute {
                try {
                    if (!realityLensActive || !BridgeLink.isOnline || !getSharedPreferences("phonebridge_privacy", Context.MODE_PRIVATE)
                            .getBoolean("allow_remote_camera_upload", false)
                    ) return@execute
                    val bounded = RealityFrameAnalyzer.toBoundedNv21(planes) ?: return@execute
                    val jpeg = ByteArrayOutputStream()
                    if (YuvImage(bounded.data, ImageFormat.NV21, bounded.width, bounded.height, null)
                            .compressToJpeg(Rect(0, 0, bounded.width, bounded.height), 55, jpeg)
                    ) sendBinary(TYPE_FRAME, jpeg.toByteArray())
                } catch (error: Exception) {
                    Log.w(TAG, "AR frame encode failed", error)
                } finally {
                    arFrameUploadPending.set(false)
                }
            }
        }.onFailure {
            arFrameUploadPending.set(false)
            Log.w(TAG, "AR frame enqueue failed", it)
        }
    }

    private fun requestAudioPermissionIfNeeded(): Boolean {
        if (hasAudioPermission()) return true
        ActivityCompat.requestPermissions(
            this,
            arrayOf(android.Manifest.permission.RECORD_AUDIO),
            REQUEST_AUDIO_PERMISSION
        )
        return false
    }

    private fun updateRealityLocation() {
        val decision = realityLocationSampler.sample()
        realityRegion = decision.region
        realityExplorationCoordinator.setRegion(decision.region)
        realityLensView.setCoarseRegion(decision.region)
        val message = when (decision.mode) {
            RealityLocationMode.COARSE_REGION -> "现实区域已切换到 ${decision.region}"
            RealityLocationMode.CAMERA_ONLY -> "现实镜头仅使用相机：${decision.reason}"
        }
        logAdapter.add(if (decision.mode == RealityLocationMode.COARSE_REGION) "info" else "warn", message)
        if (realityLensActive) setStatus(message)
        if (decision.region != null && BridgeLink.isOnline) {
            workspaceRequest(
                "/api/reality/events?region=${android.net.Uri.encode(decision.region)}",
                onSuccess = { json ->
                    val events = mutableListOf<RealityEvent>()
                    val rawEvents = json.optJSONArray("events") ?: JSONArray()
                    for (index in 0 until rawEvents.length()) {
                        val item = rawEvents.optJSONObject(index) ?: continue
                        events += RealityEvent(
                            id = item.optString("id"),
                            region = item.optString("region", decision.region),
                            kind = item.optString("kind", "mote"),
                            clueType = RealityClueProtocol.canonicalType(item.optString("clueType")),
                            bearing = RealityRegion.normalizeBearing(item.optInt("bearing", 0)),
                            distanceBand = item.optString("distanceBand", "mid"),
                            expiresAt = item.optLong("expiresAt", 0L),
                            seed = item.optString("seed")
                        )
                    }
                    realityExplorationCoordinator.replaceEvents(events)
                    realityLensView.setNearbyEvents(events)
                    val count = events.size
                    logAdapter.add("info", "附近现实事件已刷新：$count 个（仅粗区域）")
                }
            )
        }
    }

    private fun requestRealityLocationIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            updateRealityLocation()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.ACCESS_COARSE_LOCATION),
                REQUEST_LOCATION_PERMISSION
            )
        }
    }

    private fun applyDeviceCommand(action: String) {
        Log.i(TAG, "Device command received: $action listening=$continuousListening ptt=$pttActive mic=$micRunning")
        when (action) {
            "camera_on" -> {
                if (!(realityLensActive && realityCaptureController.snapshot().owner == RealityCameraOwner.ARCORE)) {
                    startCameraOrReportPermissions()
                }
            }
            "camera_off" -> stopCamera()
            "camera_front" -> if (!useFrontCamera) flipLens()
            "camera_back" -> if (useFrontCamera) flipLens()
            "listen_on" -> if (!continuousListening || !micRunning) toggleListening()
            "listen_off" -> when {
                continuousListening -> toggleListening()
                micRunning || pttActive -> stopMicrophone(true)
            }
        }
    }

    private fun startCamera() {
        if (cameraRunning || cameraStartPending) return
        val capture = realityCaptureController.snapshot()
        if (capture.owner == RealityCameraOwner.ARCORE || capture.thermalLockout) return
        arCoreRenderView.visibility = View.GONE
        val realityEntryId = capture.entryId.takeIf { realityLensActive && capture.surface == RealityCaptureSurface.REALITY }
        val session = cameraSession.incrementAndGet()
        cameraStartPending = true
        renderCameraHeroState()
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                if (session != cameraSession.get() || cameraRunning ||
                    (realityEntryId != null && !realityCaptureController.isCurrentRealityEntry(realityEntryId))
                ) return@addListener
                val provider = future.get()
                cameraProvider = provider
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(720, 540))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(analysisExecutor, ::sendCameraFrame) }
                val useCases = mutableListOf<androidx.camera.core.UseCase>(analysis)
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    cameraPreview = preview
                    useCases.add(preview)
                } else {
                    cameraPreview = null
                }
                provider.unbindAll()
                val selector = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                if (!realityCaptureController.claimCameraX(realityEntryId)) return@addListener
                provider.bindToLifecycle(BridgeService.lifecycleOwner, selector, *useCases.toTypedArray())
                if (session != cameraSession.get()) {
                    runCatching { provider.unbindAll() }
                    realityCaptureController.releaseCamera(RealityCameraOwner.CAMERAX, realityEntryId)
                    return@addListener
                }
                cameraRunning = true
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                cameraButton.text = getString(R.string.stop_camera)
                renderCameraHeroState()
                renderPet()
                publishSensorState(force = true)
                setStatus(
                    when {
                        pairingScanActive -> "扫码中 · 画面仅在本机识别"
                        !BridgeLink.isOnline -> "眼睛开启 · 未连节点"
                        else -> "眼睛开启 · 在线"
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "camera failed", e)
                stopCamera()
                setStatus("眼睛启动失败：${e.rootMessage()}")
            } finally {
                cameraStartPending = false
                renderCameraHeroState()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        val capture = realityCaptureController.snapshot()
        if (realityLensActive && capture.owner == RealityCameraOwner.ARCORE) {
            stopArCoreForManualMode()
            return
        }
        pairingScanActive = false
        pairingScanOwnsCamera = false
        cameraRunning = false
        cameraSession.incrementAndGet()
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        cameraPreview = null
        runCatching { cameraProvider?.unbindAll() }
        realityCaptureController.releaseCamera(
            RealityCameraOwner.CAMERAX,
            capture.entryId.takeIf { realityLensActive && capture.surface == RealityCaptureSurface.REALITY },
        )
        if (realityLensActive) {
            realityLensView.setRealityTrackingSnapshot(
                RealityTrackingSnapshot(RealityTrackingStatus.FALLBACK, fallbackReason = "镜头已关闭 · 可继续手动探索")
            )
        }
        previewView.visibility = View.INVISIBLE
        previewView.alpha = 0f
        cameraButton.text = getString(R.string.start_camera)
        renderCameraHeroState()
        renderPet()
        publishSensorState(force = true)
        setStatus(if (!BridgeLink.isOnline) "眼睛关闭 · 离线" else "眼睛关闭 · 在线")
    }

    private fun flipLens() {
        useFrontCamera = !useFrontCamera
        if (!cameraRunning) return
        cameraRunning = false
        cameraSession.incrementAndGet()
        runCatching { cameraProvider?.unbindAll() }
        startCamera()
        say(if (useFrontCamera) "换成前眼了。" else "换成后眼了。")
    }

    private fun enterRealityLens(readOnlyHistory: ExplorationLogEntry? = null) {
        if (!immersiveMode) return
        if (realityLensActive) {
            readOnlyHistory?.let(::showRealityHistory)
            return
        }
        realityHistoryReadOnly = readOnlyHistory != null
        val restoreCameraOnExit = cameraRunning
        cameraSession.incrementAndGet()
        cameraStartPending = false
        cameraRunning = false
        runCatching { cameraProvider?.unbindAll() }
        realityCaptureController.releaseCamera(RealityCameraOwner.CAMERAX)
        arCoreRenderView.closeSession()
        arCoreRenderView.visibility = View.GONE
        previewView.visibility = View.INVISIBLE
        previewView.alpha = 0f
        realityLensActive = true
        immersiveShellCoordinator.setSurface(ImmersiveSurface.REALITY)
        immersiveShellCoordinator.closeDrawer()
        focusToolsExpanded = false
        saveImmersiveSurface(ImmersiveSurface.REALITY)
        realityArEntryId = null
        arCoreInstallFlow = null
        awaitingArCoreCameraPermission = false
        realityCaptureController.enterReality(restoreCameraOnExit)

        val frame = findViewById<View>(R.id.previewFrame)
        normalPreviewParams = frame.layoutParams as?
            androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        val lensParams = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            (heroPanel.height - dp(96)).coerceAtLeast(dp(220))
        ).apply {
            val parent = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            startToStart = parent
            endToEnd = parent
            topToTop = parent
            bottomToBottom = parent
            setMargins(dp(12), dp(40), dp(12), dp(54))
        }
        frame.layoutParams = lensParams

        listOf(
            R.id.focusToolbar,
            R.id.focusSpeechLayer,
            R.id.focusSpeechScroll,
            R.id.focusInputRow
        ).forEach { id -> findViewById<View>(id)?.visibility = View.GONE }
        realityLensView.setPetState(pet)
        realityLensView.setHistoricalRecord(readOnlyHistory?.let { ExplorationLogPresentation.from(it) }?.let {
            "历史${it.clueLabel}线索 · ${it.statusLabel} · ${it.regionLabel} · 只读"
        })
        realityLensView.setRealityTrackingSnapshot(RealityTrackingSnapshot(RealityTrackingStatus.CHECKING))
        realityLensView.visibility = View.VISIBLE
        realityAnchorRepositioning = false
        realityLensView.setAnchorRepositioning(false)
        realityRepositionButton.text = "重新校准"
        realityRepositionButton.visibility = if (realityHistoryReadOnly) View.GONE else View.VISIBLE
        if (!realityHistoryReadOnly) requestRealityLocationIfNeeded()
        realityThermalHandler.removeCallbacks(realityThermalMonitor)
        realityThermalHandler.post(realityThermalMonitor)
        renderCameraHeroState()
        renderPet()
        publishSensorState(force = true)
        renderFocusTools()
        findViewById<View>(R.id.previewFrame).post {
            if (realityLensActive) beginArCoreForCurrentEntry()
        }
        setMoteMoment(MoteMoment.EXPLORATION)
        say(MoteCharacterizationEngine.resolve(pet.appearance, MoteMoment.EXPLORATION, moteRelationship.level).line)
    }

    private fun exitRealityLens() {
        if (!realityLensActive) return
        val currentCapture = realityCaptureController.snapshot()
        val oldEntryId = currentCapture.entryId
        when (currentCapture.owner) {
            RealityCameraOwner.ARCORE -> {
                arCoreRenderView.closeSession()
                arCoreRenderView.visibility = View.GONE
                realityCaptureController.releaseCamera(RealityCameraOwner.ARCORE, oldEntryId)
            }
            RealityCameraOwner.CAMERAX -> {
                cameraSession.incrementAndGet()
                cameraRunning = false
                cameraStartPending = false
                cameraPreview = null
                runCatching { cameraProvider?.unbindAll() }
                realityCaptureController.releaseCamera(RealityCameraOwner.CAMERAX, oldEntryId)
            }
            RealityCameraOwner.NONE -> arCoreRenderView.closeSession()
        }
        realityLensActive = false
        realityHistoryReadOnly = false
        realityLensView.setHistoricalRecord(null)
        val captureState = realityCaptureController.exitReality()
        realityArEntryId = null
        arCoreInstallFlow = null
        clearPendingArCoreInstall()
        if (awaitingArCoreCameraPermission) cancelledRealityCameraPermission = true
        awaitingArCoreCameraPermission = false
        if (!captureState.keepCameraOnExit) window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        publishSensorState(force = true)
        immersiveShellCoordinator.setSurface(ImmersiveSurface.COMPANION)
        saveImmersiveSurface(ImmersiveSurface.COMPANION)

        val frame = findViewById<View>(R.id.previewFrame)
        normalPreviewParams?.let {
            frame.layoutParams = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(it)
        }
        realityLensView.visibility = View.GONE
        realityRepositionButton.visibility = View.GONE
        realityAnchorRepositioning = false
        realityLensView.setAnchorRepositioning(false)
        realityLensView.setCoarseRegion(null)
        realityLensView.setNearbyEvents(emptyList())
        realityLensView.setLocalCueHints(emptySet())
        realityLensView.setRealityTrackingSnapshot(RealityTrackingSnapshot(RealityTrackingStatus.STOPPED))
        realityRegion = null
        renderCameraHeroState()
        if (immersiveMode) {
            focusToolbar.visibility = View.VISIBLE
            focusSpeechLayer.visibility = View.VISIBLE
            focusSpeechScroll.visibility = View.VISIBLE
            findViewById<View>(R.id.focusInputRow).visibility = View.VISIBLE
            focusSpeechLayer.post { layoutFocusSpeechOverlay() }
        }
        renderFocusTools()

        if (captureState.keepCameraOnExit) {
            frame.post { if (!realityLensActive) startCameraOrReportPermissions() }
        }
        say("退出现实镜头。")
    }

    private fun handleRealityNode(node: RealityLensView.LensNode) {
        if (realityHistoryReadOnly) {
            setStatus("正在只读查看历史记录；不会重复提交线索或发放奖励")
            return
        }
        val discovered = loadDiscoveredRealityNodes()
        if (node.id in discovered) {
            say("今天已经收集过${node.title}。${node.detail}")
            return
        }

        val online = BridgeLink.isOnline
        val event = if (online) realityExplorationCoordinator.eventForClue(node.id) else null
        val submission = realityExplorationCoordinator.submitClue(node.id, online = online)
        if (submission.duplicate) {
            say("这个线索正在同步，稍等一下。")
            return
        }

        val observation = RealityEncounterPolicy.observationPrompt(
            appearance = pet.appearance,
            clueType = node.id,
            relationshipLevel = moteRelationship.level,
        )
        if (event != null && event.id == submission.eventId) {
            workspaceRequest(
                "/api/reality/events/${android.net.Uri.encode(event.id)}/start",
                "POST",
                JSONObject().put("region", event.region),
                onSuccess = { response ->
                    val receipt = response.optJSONObject("receipt")
                    if (receipt != null) {
                        realityExplorationCoordinator.acknowledge(submission.eventId, accepted = true)
                        confirmRealityClueLocally(node.id)
                        loadRealityRewardReceipt(submission.eventId)
                    } else {
                        showRealityEncounterChoices(node, submission, observation, null)
                    }
                },
                onError = { error ->
                    showRealityEncounterChoices(node, submission, observation, "遭遇暂不可用（$error），仍可手动完成观察。")
                },
            )
        } else {
            showRealityEncounterChoices(
                node,
                submission,
                observation,
                if (submission.offline) "离线线索会先暂存，恢复连接后再确认奖励。" else "当前没有有效区域遭遇，可继续手动观察。",
            )
        }
    }

    private fun showRealityHistory(entry: ExplorationLogEntry) {
        val presentation = ExplorationLogPresentation.from(entry)
        if (!presentation.canOpenReality || explorationLogSnapshot.entries.none {
                it.eventId == entry.eventId && it.status == ExplorationLogStatus.CONFIRMED
            }) {
            Toast.makeText(this, "这条记录已不可用或尚未确认奖励", Toast.LENGTH_SHORT).show()
            return
        }
        explorationLogDialog?.dismiss()
        realityHistoryReadOnly = true
        val label = "历史${presentation.clueLabel}线索 · ${presentation.statusLabel} · ${presentation.regionLabel} · 只读"
        if (realityLensActive) {
            realityLensView.setHistoricalRecord(label)
            realityRepositionButton.visibility = View.GONE
            setStatus("只读查看历史收据 · 不会再次提交或领奖")
        } else {
            enterRealityLens(entry)
            setStatus("只读查看历史收据 · 不会再次提交或领奖")
        }
    }

    private fun showExplorationLogDialog() {
        explorationLogDialog?.dismiss()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(4))
        }
        val status = TextView(this).apply {
            setTextColor(Color.parseColor("#A5F3CB"))
            textSize = 12f
            setPadding(0, 0, 0, dp(8))
        }
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = android.widget.ScrollView(this).apply {
            addView(rows, android.view.ViewGroup.LayoutParams(-1, -2))
            layoutParams = LinearLayout.LayoutParams(-1, dp(360))
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
        }
        val refresh = Button(this).apply { text = "刷新" }
        val more = Button(this).apply { text = "加载更多" }
        controls.addView(refresh, LinearLayout.LayoutParams(0, dp(44), 1f))
        controls.addView(more, LinearLayout.LayoutParams(0, dp(44), 1f))
        content.addView(status)
        content.addView(scroll)
        content.addView(controls)

        val renderer: (ExplorationLogSnapshot) -> Unit = { snapshot ->
            status.text = when {
                snapshot.entries.isEmpty() && !BridgeLink.isOnline -> "离线 · 暂无本机待同步记录；连接恢复后会继续同步"
                snapshot.entries.isEmpty() -> "暂无探索记录 · 相机或位置权限不会影响查看"
                else -> "${snapshot.entries.size} 条 · 已确认收据显示实际奖励；待同步与拒绝记录不发奖"
            }
            rows.removeAllViews()
            snapshot.entries.forEach { entry ->
                val presentation = ExplorationLogPresentation.from(entry)
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(9), dp(12), dp(9))
                    setBackgroundColor(Color.parseColor("#10201A"))
                    isClickable = true
                    isFocusable = true
                }
                val heading = TextView(this).apply {
                    text = "${presentation.clueLabel}线索 · ${presentation.statusLabel}"
                    setTextColor(Color.parseColor(if (entry.status == ExplorationLogStatus.CONFIRMED) "#8FF0C4" else "#FFC86B"))
                    textSize = 14f
                }
                val summary = TextView(this).apply {
                    text = "${presentation.regionLabel} · ${formatExplorationTime(entry.occurredAt)}"
                    setTextColor(Color.parseColor("#A9BEB3"))
                    textSize = 11f
                }
                row.addView(heading)
                row.addView(summary)
                row.setOnClickListener { showExplorationLogDetail(entry) }
                rows.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
            }
            more.visibility = if (snapshot.nextCursor != null) View.VISIBLE else View.GONE
            more.isEnabled = snapshot.nextCursor != null
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("探索记录")
            .setView(content)
            .setNegativeButton("关闭", null)
            .create()
        explorationLogDialog = dialog
        explorationLogDialogRenderer = renderer
        dialog.setOnDismissListener {
            if (explorationLogDialogRenderer === renderer) explorationLogDialogRenderer = null
            if (explorationLogDialog === dialog) explorationLogDialog = null
        }
        refresh.setOnClickListener {
            explorationLogStore.reset()
            explorationLogSnapshot = ExplorationLogSnapshot()
            renderer(explorationLogSnapshot)
            refreshExplorationLog()
        }
        more.setOnClickListener {
            val cursor = explorationLogSnapshot.nextCursor ?: return@setOnClickListener
            more.isEnabled = false
            refreshExplorationLog(cursor = cursor)
        }
        dialog.show()
        renderer(explorationLogSnapshot)
        refreshExplorationLog()
    }

    private fun showExplorationLogDetail(entry: ExplorationLogEntry) {
        explorationLogDetailDialog?.dismiss()
        val presentation = ExplorationLogPresentation.from(entry)
        val message = buildString {
            append(presentation.detailText)
            append("\n")
            append(formatExplorationTime(entry.occurredAt))
            if (!presentation.canOpenReality) append("\n\n此记录仅供查看；不会重新提交线索或发放奖励。")
        }
        val builder = AlertDialog.Builder(this)
            .setTitle("${presentation.clueLabel}线索详情")
            .setMessage(message)
        if (presentation.canOpenReality) {
            builder.setPositiveButton("在 Reality 中只读查看") { _, _ -> showRealityHistory(entry) }
                .setNegativeButton("关闭", null)
        } else {
            builder.setPositiveButton("知道了", null)
        }
        val dialog = builder.create()
        explorationLogDetailDialog = dialog
        dialog.setOnDismissListener { if (explorationLogDetailDialog === dialog) explorationLogDetailDialog = null }
        dialog.show()
    }

    private fun formatExplorationTime(timestamp: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date(timestamp))

    private fun showRealityEncounterChoices(
        node: RealityLensView.LensNode,
        submission: RealityClueSubmission,
        observation: String,
        statusMessage: String?,
    ) {
        val actions = listOf("observe", "soothe", "dodge", "skill")
        val labels = listOf("仔细观察", "与 Mote 共鸣", "绕开干扰", "使用 Mote 特长")
        val message = buildString {
            append(observation)
            if (!statusMessage.isNullOrBlank()) append("\n\n$statusMessage")
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("${node.title} · 现实遭遇")
            .setMessage(message)
            .setItems(labels.toTypedArray()) { _, index ->
                val action = RealityEncounterPolicy.wireAction(actions.getOrNull(index)) ?: return@setItems
                queueRealityClueChoice(node, submission, action)
            }
            .setOnCancelListener {
                realityExplorationCoordinator.acknowledge(submission.eventId, accepted = false)
                say("好，我们先不结算这条线索。")
            }
            .show()
    }

    private fun queueRealityClueChoice(node: RealityLensView.LensNode, submission: RealityClueSubmission, action: String) {
        val payload = JSONObject()
            .put("eventId", submission.eventId)
            .put("clueType", submission.clueType)
            .put("region", submission.region)
            .put("offline", submission.offline)
            .put("nodeId", node.id)
            .put("actions", JSONArray().put(action))
        if (submission.offline) payload.put("activityAt", System.currentTimeMillis())
        enqueueWorkspaceEvent(
            WorkspaceEventTypes.MOTE_EXPLORATION,
            payload
        )
        realityLensView.showPendingClue(node.title)
        logAdapter.add("info", "现实遭遇已选择 $action，等待业务确认：${node.title}")
        say(if (submission.offline) "${node.title}已暂存，联网后确认奖励。" else "我记下你的选择了，正在结算这次发现。")
    }

    private fun confirmRealityClueLocally(nodeId: String) {
        val discovered = loadDiscoveredRealityNodes().toMutableSet()
        if (discovered.add(nodeId)) {
            saveDiscoveredRealityNodes(discovered)
            if (::realityLensView.isInitialized && realityLensActive) realityLensView.setDiscovered(discovered)
        }
    }

    private fun loadRealityRewardReceipt(eventId: String) {
        workspaceRequest(
            "/api/reality/receipts/${android.net.Uri.encode(eventId)}",
            onSuccess = { response ->
                val realityReceipt = response.optJSONObject("realityReceipt")
                val growthReceipt = response.optJSONObject("growthReceipt")
                val reward = realityReceipt?.optJSONObject("reward")
                val itemName = reward?.optJSONObject("item")?.optString("name")?.takeIf { it.isNotBlank() }
                val xp = reward?.optInt("xp") ?: growthReceipt?.optJSONObject("reward")?.optInt("xp") ?: 0
                val label = itemName ?: if (xp > 0) "经验 +$xp" else "线索已确认"
                if (::realityLensView.isInitialized && realityLensActive) realityLensView.showEncounterReward(label)
                say(if (itemName != null) "发现了$itemName，已经放进背包。" else "这条现实线索已经确认。")
            },
            onError = { error ->
                if (realityLensActive) setStatus("线索已确认；奖励详情暂时读取失败：$error")
            },
        )
    }

    private fun handleRealityPetTapped() {
        pet = pet.copy(
            affection = (pet.affection + 2).coerceAtMost(100),
            experience = pet.experience + 1
        )
        val levelUpMessage = checkLevelUp()
        savePet()
        renderPet()
        say(buildString {
            append("抓到我啦！一起在现实中探险吧！")
            if (levelUpMessage.isNotBlank()) append(" $levelUpMessage")
        })
        logAdapter.add("success", "现实互动：在现实世界中触碰了 Mote（好感+2，经验+1）")
        sendJson(
            JSONObject()
                .put("type", "pet")
                .put("action", "reality_ar_pet")
                .put("reward", 1)
                .put("state", petJson())
        )
    }

    private fun loadDiscoveredRealityNodes(): Set<String> =
        run {
            val prefs = getSharedPreferences("reality_lens", Context.MODE_PRIVATE)
            val saved = prefs.getStringSet("discovered", emptySet())?.toSet().orEmpty()
            RealityDiscoveryProgress.forDate(saved, realityActivityDate())
        }

    private fun saveDiscoveredRealityNodes(ids: Set<String>) {
        getSharedPreferences("reality_lens", Context.MODE_PRIVATE).edit()
            .putStringSet("discovered", RealityDiscoveryProgress.encode(ids, realityActivityDate()))
            .apply()
    }

    private fun realityActivityDate(): String = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply {
        timeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai")
    }.format(Date())

    private fun sendCameraFrame(image: ImageProxy) {
        var bitmap: Bitmap? = null
        try {
            if (!cameraRunning) return
            val now = SystemClock.elapsedRealtime()
            if (pairingScanActive) {
                if (now - pairingScanLastFrameAt >= 500L) {
                    pairingScanLastFrameAt = now
                    val plane = image.planes.firstOrNull()
                    val crop = image.cropRect
                    val pixels = plane?.let {
                        PairingQrDecoder.copyLuminancePlane(
                            source = it.buffer,
                            width = crop.width(),
                            height = crop.height(),
                            rowStride = it.rowStride,
                            pixelStride = it.pixelStride,
                            cropLeft = crop.left,
                            cropTop = crop.top,
                        )
                    }
                    val payload = pixels?.let { PairingQrDecoder.decode(it, crop.width(), crop.height()) }
                    if (payload != null && pairingScanActive) {
                        pairingScanActive = false
                        val stopOwnedCamera = pairingScanOwnsCamera
                        pairingScanOwnsCamera = false
                        runOnUiThread {
                            if (stopOwnedCamera) stopCamera()
                            receivePairingQr(payload)
                        }
                    }
                }
                return
            }
            val allowRemoteUpload = getSharedPreferences("phonebridge_privacy", Context.MODE_PRIVATE)
                .getBoolean("allow_remote_camera_upload", false)
            val shouldAnalyzeLocally = realityLensActive && now - lastLocalCueAt >= 240L
            if (!shouldAnalyzeLocally && (!allowRemoteUpload || !BridgeLink.isOnline)) return
            bitmap = imageToBitmap(image)
            if (shouldAnalyzeLocally) {
                lastLocalCueAt = now
                val hints = RealityCueAnalyzer.analyze(sampleBitmapSignal(bitmap!!))
                runOnUiThread { if (realityLensActive) realityLensView.setLocalCueHints(hints.types) }
            }
            if (!allowRemoteUpload || !BridgeLink.isOnline) return
            val sample = latestTelemetry
            val hot = sample != null && sample.batteryTemperature >= 42f
            val veryHot = sample != null && sample.batteryTemperature >= 44f
            val lowBattery = sample != null && sample.batteryPercent <= 20
            val frameInterval = when {
                veryHot -> 240L
                hot || lowBattery -> 165L
                else -> 80L
            }
            if (now - lastFrameAt < frameInterval) return
            lastFrameAt = now
            fpsCounter.incrementAndGet()
            val output = ByteArrayOutputStream()
            bitmap!!.compress(Bitmap.CompressFormat.JPEG, if (hot || lowBattery) 46 else 58, output)
            sendBinary(TYPE_FRAME, output.toByteArray())
        } catch (e: Exception) {
            Log.w(TAG, "frame failed", e)
        } finally {
            bitmap?.recycle()
            image.close()
        }
    }

    private fun sampleBitmapSignal(bitmap: Bitmap): RealityImageSignal {
        val step = maxOf(4, min(bitmap.width, bitmap.height) / 24)
        var totalLuma = 0f
        var lumaSamples = 0
        var edgeTotal = 0f
        var edgeSamples = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                val luma = (Color.red(pixel) * .2126f + Color.green(pixel) * .7152f + Color.blue(pixel) * .0722f) / 255f
                totalLuma += luma
                lumaSamples += 1
                if (x + step < bitmap.width) {
                    val next = bitmap.getPixel(x + step, y)
                    val nextLuma = (Color.red(next) * .2126f + Color.green(next) * .7152f + Color.blue(next) * .0722f) / 255f
                    edgeTotal += kotlin.math.abs(luma - nextLuma)
                    edgeSamples += 1
                }
                x += step
            }
            y += step
        }
        return RealityImageSignal(
            averageLuma = if (lumaSamples == 0) .5f else totalLuma / lumaSamples,
            edgeScore = if (edgeSamples == 0) 0f else edgeTotal / edgeSamples,
        )
    }

    private fun imageToBitmap(image: ImageProxy): Bitmap {
        val source = image.toBitmap()
        val degrees = image.imageInfo.rotationDegrees.toFloat()
        if (degrees == 0f) return source
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true).also {
            if (it !== source) source.recycle()
        }
    }

    private fun saveServer(url: String) {
        getSharedPreferences("phonebridge", Context.MODE_PRIVATE).edit()
            .putString("server", url)
            .putBoolean("auto_connect", true)
            .apply()
    }

    private fun saveAccessToken(token: String) {
        secureTokenStore.put(token)
    }

    private fun savedAccessToken(): String = secureTokenStore.get()

    private fun authorizedUrl(url: String): String {
        val token = savedAccessToken()
        if (token.isBlank()) return url
        val separator = if (url.contains('?')) '&' else '?'
        return "$url${separator}token=${android.net.Uri.encode(token)}"
    }

    private fun savedServer(): String =
        getSharedPreferences("phonebridge", Context.MODE_PRIVATE)
            .getString("server", "ws://127.0.0.1:9503") ?: "ws://127.0.0.1:9503"

    private fun connectSavedServer() {
        val raw = savedServer().trim()
        val url = when {
            raw.startsWith("ws://") || raw.startsWith("wss://") -> raw
            raw.contains(":") -> "ws://$raw"
            else -> "ws://$raw:9503"
        }
        desiredServerUrl = url
        autoReconnect = true
        setStatus("寻找节点")
        BridgeLink.connect(
            url,
            savedAccessToken(),
            this,
            getSharedPreferences("phonebridge", Context.MODE_PRIVATE).getString("server_fingerprint", null)
        )
    }

    override fun onBridgeOpen() {
        companionSessionRepository.markOnline()
        runOnUiThread {
            setStatus("在线")
            renderCompanionSessionSnapshot()
            say("链接稳定，我能看见了。")
        }
        publishSensorState(force = true)
        sendJson(JSONObject().put("type", "hello").put("pet", petJson()))
        requestSnapshot()
        refreshStageHabitat()
        reconcilePrivacyMigration {
            reconcilePrivacyDeletionHistory {
                flushWorkspaceOutbox()
                refreshExplorationLog()
                drainChatOutbox()
                pendingAutoCommand?.let { command ->
                    sendJson(JSONObject().put("type", "command").put("text", command))
                    runOnUiThread { logAdapter.add("info", "自动指令：$command") }
                    pendingAutoCommand = null
                }
                if (continuousListening || pttActive) startMicrophone()
            }
        }
    }

    override fun onBridgeState(state: DeviceHealthState) {
        deviceHealthState = state
        if (state.bridge == BridgePhase.ONLINE) {
            companionSessionRepository.markOnline()
        } else if (state.bridge == BridgePhase.DISCONNECTED || state.bridge == BridgePhase.AUTH_FAILED) {
            companionSessionRepository.markOffline(state.lastError)
        } else {
            companionSessionRepository.markRecovering()
        }
        val payload = JSONObject()
            .put("source", "android")
            .put("state", deviceHealthJson(state))
        enqueueWorkspaceEvent(WorkspaceEventTypes.DEVICE_STATE, payload)
        runOnUiThread {
            when (state.bridge) {
                BridgePhase.CONNECTING -> setStatus("连接中")
                BridgePhase.RETRYING -> setStatus("重连中")
                BridgePhase.AUTH_FAILED -> setStatus("令牌失效")
                BridgePhase.DISCONNECTED -> setStatus("休眠中")
                BridgePhase.ONLINE -> if (!isCameraActive()) setStatus("在线")
            }
            renderCockpitSummary()
            renderCompanionSessionSnapshot()
        }
    }

    override fun onBridgeAudio(data: okio.ByteString) {
        if (data.size > 2 && data[0] == TYPE_SPEAK.toByte()) {
            speechExecutor.execute { playSpeech(data.substring(2).toByteArray()) }
        }
    }

    override fun onBridgeText(text: String) {
        handleServerJson(text)
    }

    override fun onBridgeLost(reason: String) {
        companionSessionRepository.markOffline(reason)
        runOnUiThread { if (!realityLensActive) stopCamera() }
        cleanupConnection(false)
        runOnUiThread {
            setStatus("重连中")
            renderCompanionSessionSnapshot()
            logAdapter.add("warn", "节点断开：$reason")
        }
    }

    private fun disconnect() {
        autoReconnect = false
        desiredServerUrl = null
        BridgeLink.disconnect(permanent = true)
        cleanupConnection(true)
        setStatus("休眠中")
    }

    private fun cleanupConnection(stopAudio: Boolean = true) {
        if (stopAudio) stopMicrophone(true)
        runOnUiThread {
            renderPet()
            if (!stopAudio) startMicrophone()
        }
    }

    private fun sendJson(json: JSONObject): Boolean = BridgeLink.send(json)

    private fun publishSensorState(force: Boolean = false) {
        val audioActive = micRunning || continuousListening || pttActive
        val cameraActive = isCameraActive()
        val state = (if (cameraActive) 2 else 0) or (if (audioActive) 1 else 0)
        if (!force && state == lastPublishedSensorState) return
        lastPublishedSensorState = state
        sendJson(
            JSONObject()
                .put("type", "sensor_state")
                .put("camera", cameraActive)
                .put("audio", audioActive)
        )
    }

    private fun sendBinary(type: Int, payload: ByteArray) {
        val seq = if (type == TYPE_FRAME) frameSequence.incrementAndGet() and 0xff else audioSequence.incrementAndGet() and 0xff
        BridgeLink.send(type, seq, payload)
    }

    private fun requestSnapshot() {
        val since = getSharedPreferences("workspace_meta", Context.MODE_PRIVATE).getLong("revision", 0L)
        sendJson(JSONObject().put("type", "snapshot").put("since", since))
        refreshCockpitSnapshot()
    }

    private fun deviceHealthJson(state: DeviceHealthState): JSONObject = JSONObject()
        .put("bridge", state.bridge.name.lowercase(Locale.ROOT))
        .put("node", state.node.name.lowercase(Locale.ROOT))
        .put("camera", state.camera.name.lowercase(Locale.ROOT))
        .put("microphone", state.microphone.name.lowercase(Locale.ROOT))
        .put("model", state.model.name.lowercase(Locale.ROOT))
        .put("authorization", state.authorization.name.lowercase(Locale.ROOT))
        .put("tasks", state.tasks.name.lowercase(Locale.ROOT))
        .put("automation", state.automation.name.lowercase(Locale.ROOT))
        .put("outbox", state.outbox.name.lowercase(Locale.ROOT))
        .put("outboxPending", state.outboxPending)
        .put("reconnectAttempt", state.reconnectAttempt)
        .put("nextRetryAt", state.nextRetryAt)
        .put("targetUrl", state.targetUrl)
        .put("lastError", state.lastError)
        .put("lastAckAt", state.lastAckAt)
        .put("updatedAt", state.updatedAt)
        .put("overall", state.overall.name.lowercase(Locale.ROOT))

    private fun parseDeviceHealthJson(json: JSONObject): DeviceHealthState {
        fun status(key: String, fallback: HealthStatus): HealthStatus = runCatching {
            HealthStatus.valueOf(json.optString(key).uppercase(Locale.ROOT))
        }.getOrDefault(fallback)
        val bridge = runCatching {
            BridgePhase.valueOf(json.optString("bridge").uppercase(Locale.ROOT))
        }.getOrDefault(BridgePhase.DISCONNECTED)
        return DeviceHealthState(
            bridge = bridge,
            node = status("node", HealthStatus.UNKNOWN),
            camera = status("camera", HealthStatus.INACTIVE),
            microphone = status("microphone", HealthStatus.INACTIVE),
            model = status("model", HealthStatus.UNKNOWN),
            authorization = status("authorization", HealthStatus.UNKNOWN),
            tasks = status("tasks", HealthStatus.UNKNOWN),
            automation = status("automation", HealthStatus.UNKNOWN),
            outbox = status("outbox", HealthStatus.UNKNOWN),
            outboxPending = json.optInt("outboxPending", 0).coerceAtLeast(0),
            reconnectAttempt = json.optInt("reconnectAttempt", 0).coerceAtLeast(0),
            nextRetryAt = json.optLong("nextRetryAt").takeIf { it > 0L },
            targetUrl = json.optString("targetUrl").takeIf { it.isNotBlank() },
            lastError = json.optString("lastError").takeIf { it.isNotBlank() },
            lastAckAt = json.optLong("lastAckAt").takeIf { it > 0L },
            updatedAt = json.optLong("updatedAt", System.currentTimeMillis()),
        )
    }

    private fun submitCommand() {
        val text = commandInput.text.toString().trim()
        if (text.isEmpty()) return
        commandInput.setText("")
        logAdapter.add("info", "> $text")
        when {
            text.equals("/help", true) || text.equals("help", true) -> {
                logAdapter.add("info", "本地：/clear /feed /play /camera /listen /flip")
                logAdapter.add("info", "节点：help status tasks ps disk net ping host screenshot sysinfo say 文字")
            }
            text.equals("/clear", true) -> logAdapter.add("info", "")
            text.equals("/feed", true) -> interact("feed")
            text.equals("/play", true) -> interact("play")
            text.equals("/camera", true) -> if (isCameraActive()) stopCamera() else startCameraOrReportPermissions()
        text.equals("/flip", true) -> flipLens()
        text.equals("/listen", true) -> toggleListening()
        text.equals("/memory", true) -> showMemoryDialog()
            else -> {
                val sent = sendJson(JSONObject().put("type", "command").put("text", text))
                if (!sent) logAdapter.add("error", "节点离线，命令未发送。")
            }
        }
    }

    private fun runQuick(command: String) {
        logAdapter.add("info", "> $command")
        val sent = sendJson(JSONObject().put("type", "command").put("text", command))
        if (!sent) logAdapter.add("error", "节点离线，命令未发送。")
    }

    private fun runPing() {
        val candidate = commandInput.text.toString().trim()
        val explicit = Regex("^ping\\s+([\\w.-]+)$", RegexOption.IGNORE_CASE)
            .find(candidate)?.groupValues?.get(1)
        val host = explicit
            ?: candidate.takeIf { it.matches(Regex("[\\w.-]+")) && it.contains('.') }
            ?: "223.5.5.5"
        commandInput.setText("")
        runQuick("ping $host")
    }

    private fun speakFromConsole() {
        val words = commandInput.text.toString()
            .removePrefix("/say")
            .removePrefix("say")
            .trim()
            .ifBlank { "我在这里，随时可以聊。" }
        commandInput.setText("")
        runQuick("say $words")
    }

    private fun workspaceRequest(
        path: String,
        method: String = "GET",
        payload: JSONObject? = null,
        onSuccess: (JSONObject) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val server = savedServer()
        val token = savedAccessToken()
        val certificateFingerprint = BridgeLink.httpAccess()
            ?.takeIf { access ->
                endpointIdentity(access.serverUrl) == endpointIdentity(server) && access.token == token
            }
            ?.certificateFingerprint
        networkExecutor.execute {
            val result = workspaceClient.request(server, token, path, method, payload, certificateFingerprint)
            runOnUiThread {
                result.onSuccess(onSuccess).onFailure { onError(it.message ?: "节点请求失败") }
            }
        }
    }

    private fun refreshExplorationLog(
        cursor: String? = null,
        restartAfterRevisionChange: Boolean = true
    ) {
        val requestGeneration = explorationLogRequestGate.begin(cursor)
        appScope.launch(Dispatchers.IO) {
            val revision = workspaceRepository.privacyRevision("progress")
            if (revision == null) {
                withContext(Dispatchers.Main) {
                    if (!explorationLogRequestGate.isCurrent(requestGeneration)) return@withContext
                    explorationLogRequestGate.reset()
                    explorationLogStore.reset()
                    explorationLogSnapshot = ExplorationLogSnapshot()
                    explorationLogDialogRenderer?.invoke(explorationLogSnapshot)
                }
                return@launch
            }
            val outbox = workspaceRepository.explorationLogOutbox()
            withContext(Dispatchers.Main) {
                if (destroyed || !explorationLogRequestGate.isCurrent(requestGeneration)) return@withContext
                fun applyLocalOnly() {
                    explorationLogSnapshot = explorationLogStore.applyPage(
                        ExplorationLogPage(emptyList(), null),
                        outbox,
                        revision
                    )
                    explorationLogDialogRenderer?.invoke(explorationLogSnapshot)
                }

                if (!BridgeLink.isOnline) {
                    applyLocalOnly()
                    return@withContext
                }
                workspaceRequest(
                    path = workspaceClient.realityLogPath(cursor = cursor, limit = 50),
                    onSuccess = { json ->
                        if (explorationLogRequestGate.isCurrent(requestGeneration)) {
                            val snapshot = explorationLogStore.applyPage(
                                ExplorationLogParser.parsePage(json),
                                outbox,
                                revision
                            )
                            explorationLogSnapshot = snapshot
                            explorationLogDialogRenderer?.invoke(snapshot)
                            if (snapshot.requiresRefreshFromStart && restartAfterRevisionChange) {
                                refreshExplorationLog(cursor = null, restartAfterRevisionChange = false)
                            }
                        }
                    },
                    onError = {
                        if (explorationLogRequestGate.isCurrent(requestGeneration)) {
                            applyLocalOnly()
                            if (cursor != null && restartAfterRevisionChange) {
                                refreshExplorationLog(cursor = null, restartAfterRevisionChange = false)
                            }
                        }
                    }
                )
            }
        }
    }

    private fun endpointIdentity(raw: String): String = raw.trim()
        .substringAfter("://", raw.trim())
        .substringBefore('/')
        .trimEnd('/')
        .lowercase(Locale.ROOT)

    private fun enqueueWorkspaceEvent(type: String, payload: JSONObject) {
        val sequence = nextWorkspaceSequence()
        val event = WorkspaceEvent(
            origin = "phone-${android.os.Build.MODEL}",
            sequence = sequence,
            type = type,
            payload = payload.toString()
        )
        appScope.launch(Dispatchers.IO) {
            val inserted = workspaceRepository.enqueue(event)
            withContext(Dispatchers.Main) {
                scheduleOutboxSync()
                if (inserted && type == WorkspaceEventTypes.MOTE_EXPLORATION) refreshExplorationLog()
            }
        }
    }

    private fun nextWorkspaceSequence(): Long = synchronized(outboxSequenceLock) {
        val meta = getSharedPreferences("workspace_meta", Context.MODE_PRIVATE)
        val sequence = meta.getLong("sequence", 0L) + 1L
        check(meta.edit().putLong("sequence", sequence).commit()) { "无法保存离线事件序号" }
        sequence
    }

    private fun flushWorkspaceOutbox() {
        scheduleOutboxSync()
    }

    override fun onWorkspaceAck(
        eventId: String,
        accepted: Boolean,
        status: String?,
        businessStatus: String?,
        reason: String?,
        resultRevision: Long?
    ) {
        if (eventId.isBlank()) return
        val businessAccepted = WorkspaceBusinessAckPolicy.isAccepted(accepted, status, businessStatus)
        appScope.launch(Dispatchers.IO) {
            val event = workspaceRepository.outboxEvent(eventId)
            workspaceRepository.acknowledge(
                eventId = eventId,
                accepted = businessAccepted,
                businessStatus = businessStatus ?: if (businessAccepted) "accepted" else "rejected",
                reason = reason ?: status,
                resultRevision = resultRevision
            )
            if (event?.type == WorkspaceEventTypes.MOTE_EXPLORATION) {
                val cluePayload = runCatching { JSONObject(event.payload) }.getOrNull()
                val clueEventId = cluePayload?.optString("eventId").orEmpty()
                if (clueEventId.isNotBlank()) {
                    withContext(Dispatchers.Main) {
                        realityExplorationCoordinator.acknowledge(clueEventId, accepted = businessAccepted)
                        if (businessAccepted) {
                            val nodeId = cluePayload?.optString("nodeId").orEmpty()
                            if (nodeId.isNotBlank()) {
                                val discovered = loadDiscoveredRealityNodes().toMutableSet()
                                if (discovered.add(nodeId)) {
                                    saveDiscoveredRealityNodes(discovered)
                                    if (::realityLensView.isInitialized && realityLensActive) realityLensView.setDiscovered(discovered)
                                    pet = pet.copy(experience = pet.experience + 4)
                                    checkLevelUp()
                                    savePet()
                                    renderPet()
                                    sendJson(
                                        JSONObject()
                                            .put("type", "pet")
                                            .put("action", "reality_lens")
                                            .put("node", nodeId)
                                            .put("reward", 4)
                                            .put("state", petJson())
                                    )
                                }
                            }
                            loadRealityRewardReceipt(clueEventId)
                        }
                        refreshExplorationLog()
                    }
                }
            }
            withContext(Dispatchers.Main) {
                val message = if (businessAccepted) "同步已确认" else "同步被拒绝：${reason ?: status ?: "节点未接受"}"
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openAiSpace() {
        aiSpacePanel.visibility = View.VISIBLE
        aiSpaceStatus.text = if (BridgeLink.isOnline) "节点在线 · 会话、工具与任务" else "节点离线 · 可查看本地空间，远端操作将排队提示"
        loadAiSessions()
        refreshAiTasks()
        refreshCockpitSnapshot()
    }

    private fun closeAiSpace() {
        aiSpacePanel.visibility = View.GONE
        aiStreamingId = ""
        aiStreamingText = ""
    }

    private fun loadAiSessions() {
        workspaceRequest("/api/workspace/sessions", onSuccess = { json ->
            val sessions = json.optJSONArray("sessions") ?: JSONArray()
            aiSessionIds.clear()
            aiSessionLabels.clear()
            val sessionJson = mutableListOf<JSONObject>()
            for (i in 0 until sessions.length()) {
                val session = sessions.optJSONObject(i) ?: continue
                val id = session.optString("id")
                if (id.isBlank()) continue
                aiSessionIds.add(id)
                aiSessionLabels.add(session.optString("title", "新会话"))
                sessionJson.add(session)
            }
            aiSessionAdapter.notifyDataSetChanged()
            if (aiSessionIds.isEmpty()) {
                createAiSession()
            } else {
                val selected = aiSelectedSessionId.takeIf { aiSessionIds.contains(it) } ?: aiSessionIds.first()
                aiSessionSpinner.setSelection(aiSessionIds.indexOf(selected), false)
                selectAiSession(selected)
            }
        }, onError = {
            aiSpaceStatus.text = "节点未连接，正在读取本地会话"
            appScope.launch(Dispatchers.IO) {
                val local = workspaceRepository.sessions()
                withContext(Dispatchers.Main) {
                    aiSessionIds.clear()
                    aiSessionLabels.clear()
                    local.forEach { session ->
                        aiSessionIds.add(session.id)
                        aiSessionLabels.add(session.title)
                    }
                    aiSessionAdapter.notifyDataSetChanged()
                    val selected = aiSelectedSessionId.takeIf { aiSessionIds.contains(it) } ?: aiSessionIds.firstOrNull()
                    if (selected != null) {
                        aiSelectedSessionId = selected
                        aiSessionSpinner.setSelection(aiSessionIds.indexOf(selected), false)
                        val sessionEntity = local.firstOrNull { it.id == selected }
                        aiAuthorizedUntilMs = sessionEntity?.armedUntil
                        aiToolAuthorized = (aiAuthorizedUntilMs ?: 0L) > System.currentTimeMillis()
                        val messages = workspaceRepository.messages(selected)
                        val json = JSONArray().apply {
                            messages.forEach { put(JSONObject().put("role", it.role).put("text", it.text)) }
                        }
                        renderAiConversation(json)
                        renderAiAuthorizationStatus()
                        aiPolicyState = SessionPolicyUi(
                            supported = false,
                            detail = "离线镜像可读，session policy 控制需要在线节点。"
                        )
                        renderAiPolicyState()
                    } else {
                        aiSpaceStatus.text = "节点离线 · 还没有本地会话"
                    }
                }
            }
        })
    }

    private fun createAiSession() {
        val title = "Mote · ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())}"
        workspaceRequest(
            "/api/workspace/sessions", "POST",
            JSONObject().put("title", title).put("providerId", "codex").put("model", ""),
            onSuccess = { json ->
                val session = json.optJSONObject("session") ?: return@workspaceRequest
                val id = session.optString("id")
                aiSessionIds.add(0, id)
                aiSessionLabels.add(0, session.optString("title", title))
                aiSessionAdapter.notifyDataSetChanged()
                aiSessionSpinner.setSelection(0, false)
                selectAiSession(id)
            },
            onError = { aiSpaceStatus.text = "新会话失败：$it" }
        )
    }

    private fun selectAiSession(sessionId: String) {
        aiSelectedSessionId = sessionId
        aiToolAuthorized = false
        workspaceRequest("/api/workspace/sessions/${android.net.Uri.encode(sessionId)}", onSuccess = { json ->
            val session = json.optJSONObject("session") ?: return@workspaceRequest
            aiProviderInput.setText(session.optString("providerId", "codex"))
            aiModelInput.setText(session.optString("model", ""))
            val messages = session.optJSONArray("messages") ?: JSONArray()
            renderAiConversation(messages)
            aiAuthorizedUntilMs = parseEpochMs(session.opt("armedUntil"))
            aiToolAuthorized = (aiAuthorizedUntilMs ?: 0L) > System.currentTimeMillis()
            renderAiAuthorizationStatus()
            refreshSessionPolicy()
            refreshAiTasks()
            appScope.launch(Dispatchers.IO) {
                workspaceRepository.saveSession(
                    WorkspaceSessionEntity(
                        id = session.optString("id"),
                        title = session.optString("title", "新会话"),
                        providerId = session.optString("providerId", "codex"),
                        model = session.optString("model", ""),
                        systemPrompt = session.optString("systemPrompt", ""),
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis(),
                        armedUntil = aiAuthorizedUntilMs
                    )
                )
            }
        }, onError = {
            aiSpaceStatus.text = "会话读取失败：$it"
            appScope.launch(Dispatchers.IO) {
                val localSession = workspaceRepository.sessions().firstOrNull { it.id == sessionId }
                val localMessages = workspaceRepository.messages(sessionId)
                withContext(Dispatchers.Main) {
                    aiProviderInput.setText(localSession?.providerId ?: "codex")
                    aiModelInput.setText(localSession?.model.orEmpty())
                    aiAuthorizedUntilMs = localSession?.armedUntil
                    aiToolAuthorized = (aiAuthorizedUntilMs ?: 0L) > System.currentTimeMillis()
                    renderAiAuthorizationStatus()
                    renderAiConversation(JSONArray().apply {
                        localMessages.forEach { put(JSONObject().put("role", it.role).put("text", it.text)) }
                    })
                    aiPolicyState = SessionPolicyUi(
                        supported = false,
                        detail = "会话已切到本地镜像；policy 控制待节点恢复。"
                    )
                    renderAiPolicyState()
                    refreshAiTasks()
                }
            }
        })
    }

    private fun saveAiSessionConfig() {
        if (aiSelectedSessionId.isBlank()) return
        workspaceRequest(
            "/api/workspace/sessions/${android.net.Uri.encode(aiSelectedSessionId)}", "PATCH",
            JSONObject().put("providerId", aiProviderInput.text.toString().trim().ifBlank { "codex" })
                .put("model", aiModelInput.text.toString().trim())
        )
    }

    private fun toggleAiAuthorization() {
        if (aiSelectedSessionId.isBlank()) return
        val suffix = if (aiToolAuthorized) "revoke" else "authorize"
        workspaceRequest(
            "/api/workspace/sessions/${android.net.Uri.encode(aiSelectedSessionId)}/$suffix", "POST",
            if (suffix == "authorize") JSONObject().put("durationMs", 15 * 60 * 1000) else null,
            onSuccess = { json ->
                aiAuthorizedUntilMs = when {
                    suffix == "authorize" -> parseEpochMs(json.optJSONObject("authorization")?.opt("armedUntil"))
                    else -> parseEpochMs(json.optJSONObject("session")?.opt("armedUntil"))
                }
                aiToolAuthorized = suffix == "authorize" && (aiAuthorizedUntilMs ?: 0L) > System.currentTimeMillis()
                renderAiAuthorizationStatus()
                renderAiPolicyState()
                renderCockpitSummary()
                renderPet()
            },
            onError = { aiAuthorizationStatus.text = "授权失败：$it" }
        )
    }

    private fun emergencyStopAiTools() {
        workspaceRequest(
            "/api/tools/emergency-stop", "POST", JSONObject().put("reason", "android_manual"),
            onSuccess = { json ->
                workspaceEmergencyState = json.optJSONObject("state")
                aiToolAuthorized = false
                aiAuthorizedUntilMs = null
                renderAiAuthorizationStatus()
                aiPolicyState = aiPolicyState.copy(detail = "已触发急停，等待手动恢复。")
                renderAiPolicyState()
                rebuildAttentionItems()
                renderAttentionCenter()
                renderCockpitSummary()
                say("工具已经停下了。")
            },
            onError = { aiAuthorizationStatus.text = "急停失败：$it" }
        )
    }

    private fun submitAiMessage() {
        val text = aiInput.text.toString().trim()
        if (text.isEmpty() || aiSelectedSessionId.isBlank()) return
        aiInput.setText("")
        val now = System.currentTimeMillis()
        val messageId = WorkspaceRepository.newId("message")
        aiRenderedMessageIds.add(messageId)
        val current = aiConversationText.text.toString().takeIf { it != "还没有消息。" }.orEmpty()
        aiConversationText.text = listOf(current, "我：$text").filter { it.isNotBlank() }.joinToString("\n")
        appScope.launch(Dispatchers.IO) {
            workspaceRepository.saveMessage(
                WorkspaceMessageEntity(
                    id = messageId,
                    sessionId = aiSelectedSessionId,
                    role = "user",
                    text = text,
                    createdAt = now
                )
            )
        }
        val remember = aiRememberSwitch.isChecked
        enqueueWorkspaceEvent(
            "workspace.message",
            JSONObject().put("sessionId", aiSelectedSessionId).put("messageId", messageId).put("role", "user").put("text", text)
        )
        saveAiSessionConfig()
        workspaceRequest(
            "/api/workspace/sessions/${android.net.Uri.encode(aiSelectedSessionId)}/messages", "POST",
            JSONObject().put("id", messageId).put("role", "user").put("text", text).put("runModel", true).put("remember", remember).put("memories", if (remember) JSONArray() else JSONArray()),
            onSuccess = { json ->
                val task = json.optJSONObject("task")
                if (task != null) aiTaskText.text = "任务：${task.optString("title")} · ${task.optString("state", "pending")}"
                aiSpaceStatus.text = "已提交 · 等待流式结果"
                refreshAiTasks()
            },
            onError = { aiSpaceStatus.text = "消息已保存在本地，节点未接收：$it" }
        )
    }

    private fun renderAiConversation(messages: JSONArray) {
        val lines = mutableListOf<String>()
        aiRenderedMessageIds.clear()
        for (i in 0 until messages.length()) {
            val message = messages.optJSONObject(i) ?: continue
            message.optString("id").takeIf { it.isNotBlank() }?.let { aiRenderedMessageIds.add(it) }
            val role = if (message.optString("role") == "assistant") "Mote" else "我"
            val text = message.optString("text", message.optString("content"))
            if (text.isNotBlank()) lines.add("$role：$text")
        }
        aiConversationText.text = lines.joinToString("\n\n").ifBlank { "还没有消息。" }
    }

    private fun refreshAiTasks() {
        workspaceRequest(
            "/api/tasks",
            onSuccess = { json ->
                val tasks = mutableListOf<JSONObject>()
                val array = json.optJSONArray("tasks") ?: JSONArray()
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.let(tasks::add)
                }
                renderAiTaskSummary(tasks, offline = false)
            },
            onError = {
                appScope.launch(Dispatchers.IO) {
                    val localTasks = workspaceRepository.tasks().map { task ->
                        JSONObject()
                            .put("id", task.id)
                            .put("source", task.source)
                            .put("title", task.title)
                            .put("state", task.state)
                            .put("progress", task.progress)
                            .put("detail", task.detail)
                            .put("error", task.error)
                            .put("updatedAt", task.updatedAt)
                    }
                    withContext(Dispatchers.Main) { renderAiTaskSummary(localTasks, offline = true) }
                }
            }
        )
    }

    private fun performAiTaskAction(action: String) {
        val taskId = aiHighlightedTaskId.trim()
        val request = CompanionTaskActionProtocol.create(
            taskId = taskId,
            action = action,
            idempotencyKey = WorkspaceRepository.newId("task-action")
        )
        if (request == null) {
            aiSpaceStatus.text = "先选择一个任务"
            return
        }
        if (!BridgeLink.isOnline) {
            aiSpaceStatus.text = "节点离线，任务操作未发送"
            return
        }
        aiSpaceStatus.text = "正在${actionLabel(action)}任务…"
        workspaceRequest(
            request.path,
            "POST",
            JSONObject().put("action", request.action).put("actor", "android").put("idempotencyKey", request.idempotencyKey),
            onSuccess = {
                aiSpaceStatus.text = "任务已${actionLabel(action)}"
                refreshAiTasks()
                refreshCockpitSnapshot()
            },
            onError = { error -> aiSpaceStatus.text = "任务${actionLabel(action)}失败：$error" }
        )
    }

    private fun actionLabel(action: String): String = when (action.lowercase(Locale.ROOT)) {
        "start" -> "开始"
        "pause" -> "暂停"
        "continue", "resume" -> "继续"
        "retry" -> "重试"
        "cancel" -> "取消"
        "archive" -> "归档"
        else -> action
    }

    private fun renderAiTaskSummary(tasks: List<JSONObject>, offline: Boolean) {
        val filtered = tasks
            .filter { task ->
                val metadata = task.optJSONObject("metadata")
                aiSelectedSessionId.isBlank() ||
                    metadata == null ||
                    metadata.optString("sessionId").isBlank() ||
                    metadata.optString("sessionId") == aiSelectedSessionId
            }
            .sortedWith(
                compareByDescending<JSONObject> { it.optString("id") == aiHighlightedTaskId }
                    .thenByDescending { parseEpochMs(it.opt("updatedAt")) ?: 0L }
            )
            .take(4)
        val lines = filtered.map { task ->
            val prefix = if (task.optString("id") == aiHighlightedTaskId) "当前任务" else "任务"
            "$prefix：${task.optString("title", "未命名任务")} · ${statusLabel(task.optString("state", "pending"))} · ${task.optInt("progress", 0)}%"
        }
        aiTaskText.text = when {
            lines.isEmpty() && offline -> "任务：离线镜像暂无"
            lines.isEmpty() -> "任务：暂无"
            offline -> "${lines.joinToString("\n")} · 离线镜像"
            else -> lines.joinToString("\n")
        }
        val selected = filtered.firstOrNull { it.optString("id") == aiHighlightedTaskId } ?: filtered.firstOrNull()
        if (selected == null) {
            aiTaskStart.isEnabled = false
            aiTaskPause.isEnabled = false
            aiTaskContinue.isEnabled = false
            aiTaskRetry.isEnabled = false
            aiTaskCancel.isEnabled = false
            aiTaskArchive.isEnabled = false
            return
        }
        aiHighlightedTaskId = selected.optString("id")
        val state = selected.optString("state", selected.optString("status", "pending")).lowercase(Locale.ROOT)
        val active = state in setOf("pending", "queued", "running", "paused", "needs_confirmation")
        aiTaskStart.isEnabled = state in setOf("pending", "queued")
        aiTaskPause.isEnabled = state == "running"
        aiTaskContinue.isEnabled = state == "paused"
        aiTaskRetry.isEnabled = state in setOf("failed", "cancelled", "error")
        aiTaskCancel.isEnabled = active
        aiTaskArchive.isEnabled = state in setOf("succeeded", "failed", "cancelled")
    }

    private fun applyLegacyTaskToCompanion(task: JSONObject) {
        val id = task.optString("id").trim()
        if (id.isBlank()) return
        val updatedAt = parseEpochMs(task.opt("updatedAt")) ?: System.currentTimeMillis()
        companionSessionRepository.applyEvent(
            TimelineEvent(
                eventId = "legacy-task:$id:$updatedAt:${task.optString("state", task.optString("status"))}",
                revision = workspaceRevision,
                timestamp = updatedAt,
                entityType = "task",
                entityId = id,
                entityVersion = task.optInt("entityVersion", 1).coerceAtLeast(1),
                payload = mapOf(
                    "title" to task.optString("title", "未命名任务"),
                    "state" to task.optString("state", task.optString("status", "pending")),
                    "progress" to task.optInt("progress", 0),
                    "detail" to task.optString("detail"),
                    "source" to task.optString("source", "conversation"),
                    "relatedSessionId" to task.optString("relatedSessionId").ifBlank { null },
                    "isPendingConfirmation" to (task.optString("state", task.optString("status")) == "needs_confirmation"),
                    "recentResult" to task.optString("result").ifBlank { task.optString("error") }
                )
            )
        )
    }

    private fun handleWorkspaceTaskEvent(task: JSONObject?) {
        if (task == null || task.optString("id").isBlank()) return
        if (task.optBoolean("deleted")) {
            val deletedTaskId = task.optString("id")
            runOnUiThread {
                workspaceTaskMirror.remove(deletedTaskId)
                appScope.launch(Dispatchers.IO) { workspaceRepository.deleteTask(deletedTaskId) }
                rebuildAttentionItems()
                renderAttentionCenter()
                renderCockpitSummary()
                refreshAiTasks()
            }
            return
        }
        applyLegacyTaskToCompanion(task)
        runOnUiThread {
            val taskId = task.optString("id")
            val previousState = workspaceTaskMirror[taskId]
                ?.let { it.optString("state", it.optString("status")) }
                ?.lowercase(Locale.ROOT)
                .orEmpty()
            val nextState = task.optString("state", task.optString("status")).lowercase(Locale.ROOT)
            val terminalStates = setOf("succeeded", "success", "completed", "done", "failed", "error")
            val taskFinished = previousState.isNotBlank() && previousState !in terminalStates && nextState in terminalStates
            workspaceTaskMirror[taskId] = task
            persistWorkspaceTask(task)
            rebuildAttentionItems()
            renderAttentionCenter()
            renderCockpitSummary()
            renderCompanionSessionSnapshot()
            refreshAiTasks()
            if (taskFinished) {
                setMoteMoment(MoteMoment.TASK)
                say(MoteCharacterizationEngine.resolve(pet.appearance, MoteMoment.TASK, moteRelationship.level).line)
            }
        }
    }

    private fun handleWorkspaceGoalEvent(event: JSONObject) {
        val operation = event.optString("operation", "upsert")
        val goalId = event.optString("id").ifBlank { event.optString("goalId") }
        if (operation == "delete") {
            if (goalId.isBlank()) return
            appScope.launch {
                withContext(Dispatchers.IO) { workspaceRepository.deleteGoal(goalId) }
                goalBoardRefresh?.invoke()
            }
            return
        }
        val rawGoal = event.optJSONObject("goal") ?: return
        val goal = runCatching {
            GoalBoardProtocol.parseSnapshot(JSONObject().put("goals", JSONArray().put(rawGoal)).toString()).goals.firstOrNull()
        }.getOrNull() ?: return
        appScope.launch {
            withContext(Dispatchers.IO) { workspaceRepository.saveGoalEvent(goal) }
            goalBoardRefresh?.invoke()
        }
    }

    private fun handleAttentionEvent(attention: JSONObject?) {
        if (attention == null || attention.optString("id").isBlank()) return
        val attentionUpdatedAt = parseEpochMs(attention.opt("updatedAt")) ?: System.currentTimeMillis()
        companionSessionRepository.applyEvent(
            TimelineEvent(
                eventId = "legacy-attention:${attention.optString("id")}:$attentionUpdatedAt:${attention.optString("status")}",
                revision = workspaceRevision,
                timestamp = attentionUpdatedAt,
                entityType = "attention",
                entityId = attention.optString("id"),
                entityVersion = attention.optInt("entityVersion", 1).coerceAtLeast(1),
                payload = mapOf(
                    "source" to attention.optString("source", "attention"),
                    "severity" to attention.optString("severity", "medium"),
                    "status" to attention.optString("status", "open"),
                    "title" to attention.optString("title", "注意力节点"),
                    "summary" to attention.optString("summary", attention.optString("detail")),
                    "relatedSessionId" to attention.optString("relatedSessionId").ifBlank { null },
                    "relatedTaskId" to attention.optString("relatedTaskId").ifBlank { null },
                    "dedupeKey" to attention.optString("dedupeKey").ifBlank { null }
                )
            )
        )
        val key = attention.optString("dedupeKey").ifBlank { "attention:${attention.optString("id")}" }
        val item = CockpitAttentionItem(
            key = key,
            id = attention.optString("id"),
            title = attention.optString("title", "注意力节点"),
            summary = attention.optString("summary", attention.optString("detail")),
            severity = attention.optString("severity", "medium"),
            status = attention.optString("status", "open"),
            source = attention.optString("source", "attention"),
            relatedSessionId = attention.optString("relatedSessionId"),
            relatedTaskId = attention.optString("relatedTaskId"),
            relatedActionId = attention.optString("relatedActionId"),
            updatedAtMs = parseEpochMs(attention.opt("updatedAt")) ?: System.currentTimeMillis()
        )
        runOnUiThread {
            val previous = cockpitAttentionItems[key]
            if (previous == null || item.updatedAtMs >= previous.updatedAtMs) {
                attentionMirror[key] = attention
                cockpitAttentionItems[key] = item
                persistAttention(attention)
                renderAttentionCenter()
                renderCockpitSummary()
                renderCompanionSessionSnapshot()
                if (item.status.equals("open", true) && item.source.equals("proactive", true)) {
                    speechText.text = "Mote：${item.summary}".takeLast(220)
                    companionView.speakPulse()
                }
            }
        }
    }

    private fun handleActionRunEvent(actionRun: JSONObject?) {
        if (actionRun == null || actionRun.optString("id").isBlank()) return
        runOnUiThread {
            actionRunMirror[actionRun.optString("id")] = actionRun
            persistActionRun(actionRun)
            rebuildAttentionItems()
            renderAttentionCenter()
            renderCockpitSummary()
            refreshAiTasks()
        }
    }

    private fun handlePolicyEvent(policy: JSONObject?) {
        if (policy == null) return
        val scopeType = policy.optString("scopeType", "session")
        val targetId = policy.optString("targetId", policy.optString("scopeId"))
        if (targetId.isBlank()) return
        runOnUiThread {
            persistPolicy(policy, scopeType, targetId)
            if (scopeType.equals("session", true) && targetId == aiSelectedSessionId) {
                refreshSessionPolicy()
            }
        }
    }

    private fun scheduleOutboxSync() {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = OneTimeWorkRequestBuilder<OutboxSyncWorker>().setConstraints(constraints).build()
        WorkManager.getInstance(this).enqueueUniqueWork("phonebridge-outbox-sync", ExistingWorkPolicy.KEEP, request)
    }

    private fun handleMoteSnapshot(snapshot: JSONObject?) {
        if (snapshot == null) return
        snapshot.optJSONObject("relationship")?.let { relationship ->
            runCatching { MoteRelationshipSummary.fromJson(relationship.toString()) }.onSuccess { moteRelationship = it }
        }
        MoteBehaviorOutput.fromWire(snapshot.optJSONObject("behavior"))?.let { behavior ->
            runOnUiThread { companionView.setBehaviorHint(behavior); realityLensView.setBehaviorHint(behavior) }
        }
        moteRosterJson = JSONArray((snapshot.optJSONArray("roster") ?: JSONArray()).toString())
        moteStateJson = JSONObject((snapshot.optJSONObject("state") ?: JSONObject()).toString())
        moteStoryJson = JSONArray((snapshot.optJSONArray("story") ?: moteStoryJson).toString())
        val confirmedEventIds = linkedSetOf<String>()
        val stateSeen = moteStateJson.optJSONObject("exploration")?.optJSONArray("seenEventIds")
        if (stateSeen != null) {
            for (index in 0 until stateSeen.length()) {
                stateSeen.optString(index).takeIf { it.isNotBlank() }?.let(confirmedEventIds::add)
            }
        }
        val growthSeen = snapshot.optJSONObject("growth")?.optJSONArray("seenEventIds")
        if (growthSeen != null) {
            for (index in 0 until growthSeen.length()) {
                growthSeen.optString(index).takeIf { it.isNotBlank() }?.let(confirmedEventIds::add)
            }
        }
        if (confirmedEventIds.isNotEmpty()) {
            realityExplorationCoordinator.restoreDiscovered(confirmedEventIds)
            confirmedEventIds.forEach { realityExplorationCoordinator.acknowledge(it, accepted = true) }
        }
        getSharedPreferences("mote_roster", Context.MODE_PRIVATE).edit()
            .putString("roster", moteRosterJson.toString())
            .putString("state", moteStateJson.toString())
            .putString("story", moteStoryJson.toString())
            .apply()
        val active = moteStateJson.optString("activeId")
        if (active.isNotBlank()) {
            val appearance = PetAppearance.fromWire(active)
            if (appearance != pet.appearance) {
                runOnUiThread {
                    pet = pet.copy(appearance = appearance)
                    savePet()
                    renderAppearanceSelection()
                    renderPet()
                }
            }
        }
    }

    private fun handleMoteRosterEvent(roster: JSONArray?, state: JSONObject?, growth: JSONObject? = null, story: JSONArray? = null) {
        handleMoteSnapshot(
            JSONObject()
                .put("roster", roster ?: moteRosterJson)
                .put("state", state ?: moteStateJson)
                .apply {
                    growth?.let { put("growth", it) }
                    story?.let { put("story", it) }
                }
        )
    }

    private fun handleEmergencyStopEvent(state: JSONObject?) {
        runOnUiThread {
            workspaceEmergencyState = state
            if (state?.optBoolean("active") == true) {
                aiToolAuthorized = false
                aiAuthorizedUntilMs = null
            }
            renderAiAuthorizationStatus()
            rebuildAttentionItems()
            renderAttentionCenter()
            renderCockpitSummary()
        }
    }

    private fun persistWorkspaceTask(task: JSONObject) {
        val createdAt = parseEpochMs(task.opt("createdAt")) ?: System.currentTimeMillis()
        val updatedAt = parseEpochMs(task.opt("updatedAt")) ?: createdAt
        val metadata = task.optJSONObject("metadata")
        val goalId = metadata?.optString("goalId").orEmpty().ifBlank { task.optString("goalId").ifBlank { null } }
        val milestoneId = metadata?.optString("milestoneId").orEmpty().ifBlank { task.optString("milestoneId").ifBlank { null } }
        appScope.launch(Dispatchers.IO) {
            workspaceRepository.saveTask(
                WorkspaceTaskEntity(
                    id = task.optString("id"),
                    source = task.optString("source", "conversation"),
                    title = task.optString("title", "未命名任务"),
                    state = task.optString("state", task.optString("status", "pending")),
                    progress = task.optInt("progress", 0).coerceIn(0, 100),
                    detail = task.optString("detail"),
                    error = task.optString("error").ifBlank { null },
                    retryCount = task.optInt("retryCount", 0),
                    artifactRefsJson = task.optJSONArray("artifactRefs")?.toString() ?: "[]",
                    createdAt = createdAt,
                    updatedAt = updatedAt,
                    goalId = goalId,
                    milestoneId = milestoneId
                )
            )
        }
    }

    private fun persistAttention(attention: JSONObject) {
        val item = runCatching { AttentionItem.fromJson(attention.toString()) }.getOrNull() ?: return
        appScope.launch(Dispatchers.IO) { workspaceRepository.saveAttentionItem(item) }
    }

    private fun persistActionRun(actionRun: JSONObject) {
        val run = runCatching { ActionRun.fromJson(actionRun.toString()) }.getOrNull() ?: return
        appScope.launch(Dispatchers.IO) { workspaceRepository.saveActionRun(run) }
    }

    private fun persistPolicy(policy: JSONObject, scopeType: String, targetId: String) {
        val normalized = JSONObject(policy.toString()).put("scopeType", scopeType).put("targetId", targetId)
        val model = runCatching { AutonomyPolicy.fromJson(normalized.toString()) }.getOrNull() ?: return
        appScope.launch(Dispatchers.IO) { workspaceRepository.savePolicy(model) }
    }

    private fun handleWorkspaceMessage(json: JSONObject) {
        val message = json.optJSONObject("message") ?: return
        val messageId = message.optString("id")
        val text = message.optString("text")
        if (text.isBlank()) return
        val sessionId = json.optString("sessionId")
        val createdAt = parseEpochMs(message.opt("createdAt")) ?: System.currentTimeMillis()
        appScope.launch(Dispatchers.IO) {
            workspaceRepository.saveMessage(
                WorkspaceMessageEntity(
                    id = messageId.ifBlank { WorkspaceRepository.newId("message") },
                    sessionId = sessionId,
                    role = message.optString("role", "assistant"),
                    text = text,
                    createdAt = createdAt,
                    streamId = message.optString("streamId").ifBlank { null }
                )
            )
        }
        if (sessionId != aiSelectedSessionId) return
        if (messageId.isNotBlank() && !aiRenderedMessageIds.add(messageId)) return
        val role = if (message.optString("role") == "assistant") "Mote" else "我"
        val existing = aiConversationText.text.toString().takeIf { it != "还没有消息。" }.orEmpty()
        val previous = if (role == "Mote" && aiStreamingText.isNotBlank()) {
            existing.substringBeforeLast("\n\nMote：${aiStreamingText}（生成中）", existing)
        } else existing
        aiConversationText.text = listOf(previous, "$role：$text").filter { it.isNotBlank() }.joinToString("\n\n")
        if (role == "Mote") {
            aiSpaceStatus.text = "结果已归档"
            aiStreamingText = ""
        }
    }

    private fun submitChat() {
        if (activeChatRequestId.isNotBlank()) {
            cancelChatTurn()
            return
        }
        val text = chatInput.text.toString().trim()
        if (text.isEmpty()) {
            retryLastChat()
            return
        }
        chatInput.setText("")
        sendChatMessage(text)
    }

    private fun renderChatActionButton() {
        sendChatButton.text = when {
            activeChatRequestId.isNotBlank() -> "停止"
            lastFailedChatText.isNotBlank() -> "重试"
            else -> getString(R.string.action_chat)
        }
        sendChatButton.contentDescription = when {
            activeChatRequestId.isNotBlank() -> "停止当前回复生成"
            lastFailedChatText.isNotBlank() -> "重试上一条消息"
            else -> getString(R.string.action_chat)
        }
    }

    private fun retryLastChat() {
        val text = lastFailedChatText
        if (text.isBlank()) return
        lastFailedChatText = ""
        sendChatMessage(text, rememberOverride = lastFailedChatRemember)
    }

    private fun cancelChatTurn() {
        val requestId = activeChatRequestId
        if (requestId.isBlank()) return
        cancelledChatRequestIds += requestId
        if (cancelledChatRequestIds.size > 64) cancelledChatRequestIds.remove(cancelledChatRequestIds.first())
        if (requestId.startsWith("offline_")) {
            offlineChatFuture?.cancel(true)
        } else {
            sendJson(JSONObject().put("type", "chat_cancel").put("requestId", requestId))
        }
        finishChatCancellation(requestId)
    }

    private fun finishChatCancellation(requestId: String) {
        if (requestId != activeChatRequestId) return
        val partial = streamingText.toString().trim()
        streamingChatId = ""
        streamingText.setLength(0)
        activeChatRequestId = ""
        pendingChatText = ""
        offlineChatFuture = null
        appendChat("assistant", partial.takeIf { it.isNotBlank() }?.plus("（已停止）") ?: "这一轮已停止。")
        renderChatActionButton()
    }

    private fun sendChatMessage(text: String, rememberOverride: Boolean? = null) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val remember = rememberOverride ?: chatRememberSwitch.isChecked
        val explicitFact = ChatMemoryPolicy.explicitFact(clean, remember)
        if (explicitFact != null) {
            val fact = explicitFact
            MoteMemory.add(this, fact).firstOrNull { it.text == fact }?.let(::syncMemoryToNode)
            appendChat("assistant", "已记住：$fact")
            logAdapter.add("success", "长期记忆已保存。")
            say("我记住了。")
            return
        }
        ChatMemoryPolicy.inferCandidate(clean, remember)?.let { candidate ->
            MoteMemory.addCandidate(this, candidate.text, candidate.source)
                .firstOrNull { it.text == candidate.text }
                ?.let(::syncMemoryToNode)
        }
        val relevantMemories = if (remember) MoteMemory.relevant(this, clean, 12) else emptyList()
        if (remember) MoteMemory.touch(this, relevantMemories)
        activeChatRequestId = "chat_${java.util.UUID.randomUUID()}"
        pendingChatText = clean
        pendingChatRemember = remember
        lastFailedChatText = ""
        renderChatActionButton()
        if (!BridgeLink.isOnline) {
            appendChat("user", clean)
            logAdapter.add("info", "你：$text")
            activeChatRequestId = "offline_${java.util.UUID.randomUUID()}"
            val offlineId = activeChatRequestId
            renderChatActionButton()
            offlineChatFuture = requestOfflineReply(
                clean,
                relevantMemories.map { it.text },
                turnId = offlineId,
                onReply = {
                    if (activeChatRequestId.startsWith("offline_")) {
                        activeChatRequestId = ""
                        pendingChatText = ""
                        offlineChatFuture = null
                        renderChatActionButton()
                    }
                },
                onError = { _ ->
                    if (activeChatRequestId.startsWith("offline_")) {
                        lastFailedChatText = clean
                        lastFailedChatRemember = remember
                        activeChatRequestId = ""
                        offlineChatFuture = null
                        renderChatActionButton()
                    }
                }
            )
            return
        }
        logAdapter.add("info", "你：$text")
        appendChat("user", clean)
        say("让我想想……")
        val memoryArray = JSONArray()
        relevantMemories.forEach { memoryArray.put(it.text) }
        if (!sendJson(
                JSONObject()
                    .put("type", "chat")
                    .put("requestId", activeChatRequestId)
                    .put("text", clean)
                    .put("memories", memoryArray)
                    .put("remember", remember)
            )
        ) {
            logAdapter.add("warn", "节点连接不可用，切换离线接口。")
            activeChatRequestId = "offline_${java.util.UUID.randomUUID()}"
            val offlineId = activeChatRequestId
            renderChatActionButton()
            offlineChatFuture = requestOfflineReply(
                clean,
                relevantMemories.map { it.text },
                turnId = offlineId,
                onReply = {
                    if (activeChatRequestId.startsWith("offline_")) {
                        activeChatRequestId = ""
                        pendingChatText = ""
                        offlineChatFuture = null
                        renderChatActionButton()
                    }
                },
                onError = {
                    if (activeChatRequestId.startsWith("offline_")) {
                        lastFailedChatText = clean
                        lastFailedChatRemember = remember
                        activeChatRequestId = ""
                        offlineChatFuture = null
                        renderChatActionButton()
                    }
                }
            )
        }
    }

    private fun showMemoryDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.memory_manager, null)
        val input = view.findViewById<EditText>(R.id.memoryInput)
        val recycler = view.findViewById<RecyclerView>(R.id.memoryRecycler)
        val adapter = MemoryAdapter {}
        val refresh = { adapter.submit(MoteMemory.load(this)) }
        adapter.setOnDelete { item ->
            MoteMemory.removeById(this, item.id)
            workspaceRequest("/api/memories/${android.net.Uri.encode(item.id)}", "DELETE", JSONObject())
            refresh()
        }
        adapter.setOnSelect { item -> showMemoryActions(item, refresh) }
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        refresh()
        val container = view

        AlertDialog.Builder(this)
            .setTitle(R.string.memory_dialog_title)
            .setView(container)
            .setPositiveButton("添加") { _, _ ->
                val value = input.text.toString().trim()
                if (value.isNotBlank()) {
                    MoteMemory.add(this, value, source = "user")
                        .firstOrNull { it.text == value }
                        ?.let(::syncMemoryToNode)
                    refresh()
                }
            }
            .setNeutralButton("隐私与数据") { _, _ -> showPrivacyCenter() }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showPrivacyCenter() {
        val categories = PrivacyDataPolicy.categories
        val labels = mapOf(
            "memories" to "长期记忆",
            "conversations" to "聊天、会话与交接内容",
            "tasks" to "任务与审计",
            "progress" to "Mote 成长与探索",
            "routines" to "日常与习惯",
            "goals" to "个人目标"
        )
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val overview = TextView(this).apply {
            text = "正在读取节点数据清单……"
            setTextColor(Color.WHITE)
            textSize = 14f
        }
        container.addView(overview, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        val migrationButton = Button(this).apply {
            text = "处理历史数据迁移确认"
            visibility = View.GONE
        }
        container.addView(migrationButton, LinearLayout.LayoutParams(-1, -2))
        val quarantineButton = Button(this).apply { text = "查看 / 加密导出本机隔离数据" }
        container.addView(quarantineButton, LinearLayout.LayoutParams(-1, -2))
        val selections = linkedMapOf<String, android.widget.CheckBox>()
        categories.forEach { category ->
            val check = android.widget.CheckBox(this).apply {
                text = labels[category] ?: category
                isChecked = true
                setTextColor(Color.WHITE)
            }
            selections[category] = check
            container.addView(check)
        }
        val passphrase = EditText(this).apply {
            hint = "导出口令（至少 12 位）"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        val passphraseAgain = EditText(this).apply {
            hint = "再次输入导出口令"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        container.addView(passphrase, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        container.addView(passphraseAgain, LinearLayout.LayoutParams(-1, -2))
        val scroll = android.widget.ScrollView(this).apply { addView(container) }
        val dialog = AlertDialog.Builder(this)
            .setTitle("隐私与数据")
            .setView(scroll)
            .setPositiveButton("导出加密档案", null)
            .setNeutralButton("删除所选数据", null)
            .setNegativeButton("关闭", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val selected = PrivacyDataPolicy.normalizeCategories(selections.filterValues { it.isChecked }.keys.toList())
                val password = passphrase.text.toString()
                val repeated = passphraseAgain.text.toString()
                if (selected == null) {
                    Toast.makeText(this, "至少选择一个数据类别", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (!PrivacyDataPolicy.isValidPassphrase(password, repeated)) {
                    Toast.makeText(this, "导出口令需为 12–1024 位且两次一致", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val payload = JSONObject()
                    .put("categories", JSONArray(selected))
                    .put("passphrase", password)
                passphrase.text?.clear()
                passphraseAgain.text?.clear()
                overview.text = "正在生成本机加密档案……"
                workspaceRequest(
                    "/api/privacy/export",
                    "POST",
                    payload,
                    onSuccess = { response ->
                        val encrypted = response.optJSONObject("archive")?.toString().orEmpty()
                        if (encrypted.isBlank()) {
                            overview.text = "节点未返回有效加密档案。"
                            return@workspaceRequest
                        }
                        pendingPrivacyArchive = encrypted
                        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "application/json"
                            putExtra(Intent.EXTRA_TITLE, "phonebridge-private-export.pbenc.json")
                        }
                        runCatching { startActivityForResult(intent, REQUEST_PRIVACY_EXPORT_FILE) }
                            .onFailure {
                                pendingPrivacyArchive = null
                                Toast.makeText(this, "无法打开系统文件选择器", Toast.LENGTH_LONG).show()
                            }
                    },
                    onError = { error ->
                        overview.text = "加密导出失败：$error"
                        Toast.makeText(this, "加密导出失败", Toast.LENGTH_LONG).show()
                    }
                )
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val selected = PrivacyDataPolicy.normalizeCategories(selections.filterValues { it.isChecked }.keys.toList())
                if (selected == null) {
                    Toast.makeText(this, "至少选择一个数据类别", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                showPrivacyDeleteConfirmation(selected, labels)
            }
        }
        dialog.show()
        migrationButton.setOnClickListener {
            dialog.dismiss()
            reconcilePrivacyMigration {
                flushWorkspaceOutbox()
                drainChatOutbox()
            }
        }
        quarantineButton.setOnClickListener { showPrivacyQuarantineReview() }
        workspaceRequest("/api/privacy/overview", onSuccess = { response ->
            val counts = response.optJSONObject("categories")
            val migration = response.optJSONObject("migration")
            val requiredCategories = buildSet {
                migration?.optJSONArray("requiredCategories")?.let { required ->
                    for (index in 0 until required.length()) required.optString(index).takeIf(String::isNotBlank)?.let(::add)
                }
            }
            val decisions = migration?.optJSONObject("decisions")
            val pendingMigration = requiredCategories.filter { category ->
                decisions?.optString(category)?.let { it == "clear" || it == "keep" } != true
            }
            migrationButton.visibility = if (pendingMigration.isEmpty()) View.GONE else View.VISIBLE
            val localPrivacyStates = categories.mapNotNull { category ->
                val item = counts?.optJSONObject(category) ?: return@mapNotNull null
                WorkspacePrivacyStateEntity(
                    category = category,
                    revision = item.optLong("revision", 0L).coerceAtLeast(0L),
                    migrationRequired = item.optBoolean("migrationRequired", category in requiredCategories),
                    decision = decisions?.optString(category)?.takeIf { it == "clear" || it == "keep" },
                    updatedAt = System.currentTimeMillis()
                )
            }
            appScope.launch(Dispatchers.IO) { workspaceRepository.observePrivacyStates(localPrivacyStates) }
            appScope.launch(Dispatchers.IO) {
                val localCounts = runCatching { workspaceRepository.localPrivacyCounts() }.getOrDefault(emptyMap())
                withContext(Dispatchers.Main) {
                    overview.text = buildString {
                        appendLine("节点数据概览 · 本机数据仅在此设备统计")
                        appendLine(if (pendingMigration.isEmpty()) "历史数据迁移：已确认" else "历史数据迁移：待确认 ${pendingMigration.joinToString("、")}；同步已暂停")
                        categories.forEach { category ->
                            val remoteCount = counts?.optJSONObject(category)?.optInt("count", 0) ?: 0
                            val local = localCounts[category] ?: PrivacyLocalCounts()
                            appendLine(
                                "${labels[category]}：节点 $remoteCount · 本机 ${local.roomRecords + local.preferencesRecords} " +
                                    "（Room ${local.roomRecords} / 设置缓存 ${local.preferencesRecords}） · " +
                                    "待同步 ${local.pendingOutbox}（隔离 ${local.quarantinedOutbox}）"
                            )
                        }
                        append("排除：访问令牌、Provider 密钥、原始画面、精确位置与连续轨迹。")
                    }
                }
            }
        }, onError = { error -> overview.text = "节点概览暂不可用：$error\n仍可在连接恢复后重试。" })
    }

    private fun showPrivacyQuarantineReview() {
        appScope.launch(Dispatchers.IO) {
            val stored = runCatching { PrivacyQuarantineStore.snapshot(this@MainActivity) }
                .getOrElse { error ->
                    withContext(Dispatchers.Main) {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("隔离数据不可用")
                            .setMessage("本机加密隔离区无法读取；内容未发送。${error.message.orEmpty()}")
                            .setPositiveButton("关闭", null)
                            .show()
                    }
                    return@launch
                }
            val quarantinedEvents = JSONObject()
            for (category in PrivacyDataPolicy.categories) {
                val events = workspaceRepository.quarantinedOutboxForCategory(category)
                if (events.isNotEmpty()) {
                    quarantinedEvents.put(category, JSONArray().apply {
                        events.forEach { event ->
                            put(JSONObject().put("eventId", event.eventId).put("type", event.type)
                                .put("createdAt", event.createdAt).put("payload", event.payload))
                        }
                    })
                }
            }
            val unclassified = workspaceRepository.unclassifiedQuarantinedOutbox()
            if (unclassified.isNotEmpty()) {
                quarantinedEvents.put("unclassified", JSONArray().apply {
                    unclassified.forEach { event ->
                        put(JSONObject().put("eventId", event.eventId).put("type", event.type)
                            .put("createdAt", event.createdAt).put("payload", event.payload))
                    }
                })
            }
            val archivePayload = JSONObject()
                .put("formatVersion", 1)
                .put("createdAt", System.currentTimeMillis())
                .put("deviceQuarantine", stored)
                .put("quarantinedOutbox", quarantinedEvents)
                .toString(2)
            val preview = buildString {
                appendLine("只读本机隔离内容；不会自动重放或上传。")
                appendLine("Keystore 隔离类别：${stored.keys().asSequence().toList().joinToString("、").ifBlank { "无" }}")
                appendLine("隔离的待同步事件类别：${quarantinedEvents.keys().asSequence().toList().joinToString("、").ifBlank { "无" }}")
                appendLine()
                append(archivePayload.take(4_000))
            }
            withContext(Dispatchers.Main) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("本机隔离数据 · 只读")
                    .setMessage(preview)
                    .setPositiveButton("加密导出") { _, _ -> showPrivacyQuarantineExport(archivePayload) }
                    .setNegativeButton("关闭", null)
                    .show()
            }
        }
    }

    private fun showPrivacyQuarantineExport(archivePayload: String) {
        val password = EditText(this).apply {
            hint = "导出口令（至少 12 位）"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        val repeated = EditText(this).apply {
            hint = "再次输入导出口令"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(password)
            addView(repeated)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("加密导出本机隔离数据")
            .setView(content)
            .setPositiveButton("加密并保存", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val passphrase = password.text.toString()
                val again = repeated.text.toString()
                if (!PrivacyDataPolicy.isValidPassphrase(passphrase, again)) {
                    Toast.makeText(this, "导出口令需为 12–1024 位且两次一致", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                password.text?.clear()
                repeated.text?.clear()
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).text = "正在加密…"
                appScope.launch(Dispatchers.Default) {
                    val encrypted = runCatching { PrivacyLocalArchiveCrypto.encrypt(archivePayload, passphrase).toJson() }
                    withContext(Dispatchers.Main) {
                        val value = encrypted.getOrElse { error ->
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).text = "加密并保存"
                            Toast.makeText(this@MainActivity, "加密失败：${error.message}", Toast.LENGTH_LONG).show()
                            return@withContext
                        }
                        pendingPrivacyArchive = value
                        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "application/json"
                            putExtra(Intent.EXTRA_TITLE, "phonebridge-local-quarantine.pbenc.json")
                        }
                        runCatching { startActivityForResult(intent, REQUEST_PRIVACY_EXPORT_FILE) }
                            .onFailure {
                                pendingPrivacyArchive = null
                                Toast.makeText(this@MainActivity, "无法打开系统文件选择器", Toast.LENGTH_LONG).show()
                            }
                        dialog.dismiss()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun showPrivacyDeleteConfirmation(selected: List<String>, labels: Map<String, String>) {
        var activeRequestId: String? = null
        val confirmation = EditText(this).apply {
            hint = PrivacyDataPolicy.DELETE_CONFIRMATION
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        val description = TextView(this).apply {
            text = "将永久删除：${selected.joinToString("、") { labels[it] ?: it }}。\n请输入完整确认语句：${PrivacyDataPolicy.DELETE_CONFIRMATION}"
            setTextColor(Color.WHITE)
            textSize = 14f
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(description)
            addView(confirmation, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("确认永久删除")
            .setView(content)
            .setPositiveButton("永久删除", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val phrase = confirmation.text.toString()
                val requestId = activeRequestId ?: "phone-${UUID.randomUUID()}".also { activeRequestId = it }
                if (!PrivacyDataPolicy.isValidDeleteConfirmation(phrase, requestId)) {
                    Toast.makeText(this, "确认语句不匹配，数据未删除", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val payload = JSONObject()
                    .put("requestId", requestId)
                    .put("categories", JSONArray(selected))
                    .put("confirmation", phrase)
                confirmation.text?.clear()
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                workspaceRequest(
                    "/api/privacy/delete",
                    "POST",
                    payload,
                    onSuccess = { response ->
                        val receipt = response.optJSONObject("receipt")
                        if (receipt?.optString("status") != "completed") {
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                            Toast.makeText(this, "删除未完成；可用同一请求重试", Toast.LENGTH_LONG).show()
                            return@workspaceRequest
                        }
                        activeRequestId = null
                        applyPrivacyDeletion(
                            selected,
                            requestId,
                            categoryRevisions = PrivacyRevisionWire.fromJson(receipt?.optJSONObject("categoryRevisions")?.toString())
                        )
                        dialog.dismiss()
                        Toast.makeText(this, "所选数据已删除，并清理本机缓存", Toast.LENGTH_LONG).show()
                    },
                    onError = { error ->
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        Toast.makeText(this, "删除失败：$error", Toast.LENGTH_LONG).show()
                    }
                )
            }
        }
        dialog.show()
    }

    private fun savedPrivacyDeletionIds(): Set<String> = getSharedPreferences("privacy_deletions", Context.MODE_PRIVATE)
        .getStringSet("applied_request_ids", emptySet())?.toSet().orEmpty()

    private fun markPrivacyDeletionApplied(requestId: String) {
        val preferences = getSharedPreferences("privacy_deletions", Context.MODE_PRIVATE)
        val ids = LinkedHashSet(preferences.getStringSet("applied_request_ids", emptySet()).orEmpty())
        ids.add(requestId)
        while (ids.size > 100) ids.remove(ids.first())
        preferences.edit().putStringSet("applied_request_ids", ids).apply()
    }

    private fun reconcilePrivacyMigration(onComplete: () -> Unit) {
        workspaceRequest("/api/privacy/overview", onSuccess = { response ->
            val migration = response.optJSONObject("migration")
            val required = buildList {
                migration?.optJSONArray("requiredCategories")?.let { values ->
                    for (index in 0 until values.length()) {
                        values.optString(index).takeIf { it in PrivacyDataPolicy.categories }?.let(::add)
                    }
                }
            }.distinct()
            val decisions = migration?.optJSONObject("decisions")
            val remoteDecisions = required.mapNotNull { category ->
                decisions?.optString(category)?.takeIf { it == "clear" || it == "keep" }?.let { category to it }
            }.toMap()
            val remoteCategories = response.optJSONObject("categories")
            val remoteStates = PrivacyDataPolicy.categories.mapNotNull { category ->
                val item = remoteCategories?.optJSONObject(category) ?: return@mapNotNull null
                WorkspacePrivacyStateEntity(
                    category = category,
                    revision = item.optLong("revision", 0L).coerceAtLeast(0L),
                    migrationRequired = item.optBoolean("migrationRequired", category in required && category !in remoteDecisions),
                    decision = remoteDecisions[category],
                    updatedAt = System.currentTimeMillis()
                )
            }
            val remoteCounts = remoteCategories
            appScope.launch(Dispatchers.IO) {
                try {
                    val previous = workspaceRepository.privacyStates().associateBy { it.category }
                    for ((category, decision) in remoteDecisions) {
                        val localDecision = previous[category]?.decision
                        check(PrivacyRevisionPolicy.canApplyRemoteMigrationDecision(localDecision, decision)) {
                            "$category 的本机与节点迁移选择冲突；本机数据未更改"
                        }
                        if (localDecision == decision) continue
                        applyLegacyPrivacyPreferences(category, decision)
                        check(workspaceRepository.applyLegacyPrivacyDecision(category, decision)) {
                            "本机 $category 迁移选择与节点不一致"
                        }
                    }
                    workspaceRepository.observePrivacyStates(remoteStates)
                    val current = workspaceRepository.privacyStates().associateBy { it.category }
                    withContext(Dispatchers.Main) {
                        resolvePrivacyMigrationCategories(
                            required = required,
                            serverDecisions = remoteDecisions,
                            states = current,
                            remoteCategories = remoteCounts,
                            index = 0,
                            onComplete = onComplete
                        )
                    }
                } catch (error: Exception) {
                    runOnUiThread { logAdapter.add("warn", "隐私迁移尚未同步：${error.message ?: "本机状态不可用"}") }
                }
            }
        }, onError = { error ->
            logAdapter.add("warn", "无法读取隐私迁移状态；同步保持暂停：$error")
        })
    }

    private fun resolvePrivacyMigrationCategories(
        required: List<String>,
        serverDecisions: Map<String, String>,
        states: Map<String, WorkspacePrivacyStateEntity>,
        remoteCategories: JSONObject?,
        index: Int,
        onComplete: () -> Unit
    ) {
        if (index >= required.size) {
            appScope.launch {
                val blocked = withContext(Dispatchers.IO) { workspaceRepository.privacySyncBlocked() }
                if (blocked) {
                    logAdapter.add("warn", "仍有历史数据待确认，自动同步已暂停。可在设置 → 隐私与数据继续处理。")
                } else onComplete()
            }
            return
        }
        val category = required[index]
        val serverDecision = serverDecisions[category]
        val localDecision = states[category]?.decision
        if (serverDecision != null) {
            if (localDecision != serverDecision) {
                logAdapter.add("error", "$category 的本机与节点迁移选择冲突；请检查隐私与数据状态。")
                return
            }
            resolvePrivacyMigrationCategories(required, serverDecisions, states, remoteCategories, index + 1, onComplete)
            return
        }
        val chosen = localDecision?.takeIf { it == "clear" || it == "keep" }
        if (chosen != null) {
            submitPrivacyMigrationDecision(category, chosen, required, serverDecisions, states, remoteCategories, index, onComplete)
            return
        }
        appScope.launch {
            val localCounts = withContext(Dispatchers.IO) { workspaceRepository.localPrivacyCounts()[category] ?: PrivacyLocalCounts() }
            val remoteCount = remoteCategories?.optJSONObject(category)?.optInt("count", 0) ?: 0
            val label = mapOf(
                "memories" to "长期记忆", "conversations" to "聊天与交接内容",
                "tasks" to "任务与审计", "progress" to "Mote 成长与探索"
            )[category] ?: category
            AlertDialog.Builder(this@MainActivity)
                .setTitle("历史数据迁移确认 · $label")
                .setMessage(
                    "旧版记录无法可靠还原类别修订。节点约 $remoteCount 条；本机 Room ${localCounts.roomRecords} 条、设置缓存 ${localCounts.preferencesRecords} 条、待同步 ${localCounts.pendingOutbox} 条。\n\n" +
                        "清除会删除此类别旧数据；保留会将旧待同步内容加密隔离，仅供查看/导出，绝不自动重放。期间同步保持暂停。"
                )
                .setPositiveButton("清除旧数据") { _, _ ->
                    submitPrivacyMigrationDecision(category, "clear", required, serverDecisions, states, remoteCategories, index, onComplete)
                }
                .setNeutralButton("保留并隔离") { _, _ ->
                    submitPrivacyMigrationDecision(category, "keep", required, serverDecisions, states, remoteCategories, index, onComplete)
                }
                .setNegativeButton("稍后") { _, _ -> logAdapter.add("info", "历史数据待确认；同步保持暂停。") }
                .setOnCancelListener { logAdapter.add("info", "历史数据待确认；同步保持暂停。") }
                .show()
        }
    }

    private fun submitPrivacyMigrationDecision(
        category: String,
        decision: String,
        required: List<String>,
        serverDecisions: Map<String, String>,
        states: Map<String, WorkspacePrivacyStateEntity>,
        remoteCategories: JSONObject?,
        index: Int,
        onComplete: () -> Unit
    ) {
        appScope.launch(Dispatchers.IO) {
            try {
                if (states[category]?.decision != decision) {
                    applyLegacyPrivacyPreferences(category, decision)
                    check(workspaceRepository.applyLegacyPrivacyDecision(category, decision)) {
                        "本机已有不同的数据迁移选择"
                    }
                }
                withContext(Dispatchers.Main) {
                    workspaceRequest(
                        path = "/api/privacy/migration/resolve",
                        method = "POST",
                        payload = JSONObject().put("category", category).put("decision", decision),
                        onSuccess = { response ->
                            val result = response.optJSONObject("migration")
                            val revision = result?.optLong("categoryRevision", -1L) ?: -1L
                            if (revision < 0L) {
                                logAdapter.add("error", "节点未返回有效迁移修订；同步仍暂停。")
                                return@workspaceRequest
                            }
                            appScope.launch(Dispatchers.IO) {
                                workspaceRepository.observePrivacyStates(
                                    listOf(WorkspacePrivacyStateEntity(
                                        category = category,
                                        revision = revision,
                                        migrationRequired = false,
                                        decision = decision,
                                        updatedAt = System.currentTimeMillis()
                                    ))
                                )
                                val updated = workspaceRepository.privacyStates().associateBy { it.category }
                                withContext(Dispatchers.Main) {
                                    val nextServerDecisions = serverDecisions + (category to decision)
                                    val nextStates = states + updated
                                    resolvePrivacyMigrationCategories(
                                        required, nextServerDecisions, nextStates, remoteCategories, index + 1, onComplete
                                    )
                                }
                            }
                        },
                        onError = { error -> logAdapter.add("error", "$category 迁移确认未送达；保留本机选择并等待重试：$error") }
                    )
                }
            } catch (error: Exception) {
                withContext(Dispatchers.Main) {
                    logAdapter.add("error", "$category 本机迁移处理失败；没有向节点确认：${error.message}")
                }
            }
        }
    }

    private fun applyLegacyPrivacyPreferences(category: String, decision: String) {
        val keep = decision == "keep"
        when (category) {
            "conversations" -> {
                val chats = ChatOutbox.load(this)
                val handoff = MoteHandoff.load(this)
                if (keep && (chats.isNotEmpty() || !handoff.isEmpty())) {
                    PrivacyQuarantineStore.replaceCategory(
                        this, category,
                        JSONObject().put("chatOutbox", JSONArray(chats)).put("handoff", MoteHandoff.toJson(handoff)).toString()
                    )
                } else if (!keep) PrivacyQuarantineStore.clearCategory(this, category)
                ChatOutbox.replace(this, emptyList())
                if (!handoff.isEmpty()) MoteHandoff.replace(this, HandoffState())
            }
            "memories" -> {
                val memories = MoteMemory.load(this)
                if (keep && memories.isNotEmpty()) {
                    val items = JSONArray()
                    memories.forEach { item ->
                        items.put(JSONObject().put("id", item.id).put("text", item.text).put("createdAtMs", item.createdAtMs)
                            .put("importance", item.importance).put("source", item.source).put("status", item.status)
                            .put("excludedFromRecall", item.excludedFromRecall).put("updatedAtMs", item.updatedAtMs))
                    }
                    PrivacyQuarantineStore.replaceCategory(this, category, JSONObject().put("items", items).toString())
                } else if (!keep) PrivacyQuarantineStore.clearCategory(this, category)
                MoteMemory.clear(this)
            }
            "progress" -> {
                val roster = getSharedPreferences("mote_roster", Context.MODE_PRIVATE)
                val pet = getSharedPreferences("mote_pet", Context.MODE_PRIVATE)
                if (keep) {
                    val snapshot = JSONObject().put("roster", JSONObject(roster.all)).put("pet", JSONObject(pet.all))
                    if (roster.all.isNotEmpty() || pet.all.isNotEmpty()) {
                        PrivacyQuarantineStore.replaceCategory(this, category, snapshot.toString())
                    }
                } else PrivacyQuarantineStore.clearCategory(this, category)
                roster.edit().clear().commit()
                pet.edit().clear().commit()
            }
        }
    }

    private fun reconcilePrivacyDeletionHistory(onComplete: () -> Unit) {
        workspaceRequest("/api/privacy/overview", onSuccess = { response ->
            val receipts = mutableListOf<JSONObject>()
            response.optJSONArray("recentDeletions")?.let { recent ->
                for (index in 0 until recent.length()) recent.optJSONObject(index)?.let(receipts::add)
            }
            if (receipts.isEmpty()) response.optJSONObject("latestDeletion")?.let(receipts::add)
            val completed = receipts.filter { it.optString("status") == "completed" && it.optString("requestId").isNotBlank() }
            val pendingIds = PrivacyDataPolicy.pendingDeletionIds(completed.map { it.optString("requestId") }, savedPrivacyDeletionIds())
            val byId = completed.associateBy { it.optString("requestId") }
            var index = 0
            lateinit var applyNext: () -> Unit
            applyNext = {
                if (index >= pendingIds.size) {
                    onComplete()
                } else {
                    val receipt = byId[pendingIds[index++]]
                    val raw = receipt?.optJSONArray("categories") ?: JSONArray()
                    val categories = buildList {
                        for (categoryIndex in 0 until raw.length()) raw.optString(categoryIndex).takeIf { it.isNotBlank() }?.let(::add)
                    }
                    val normalized = PrivacyDataPolicy.normalizeCategories(categories)
                    if (normalized == null) applyNext() else applyPrivacyDeletion(
                        normalized,
                        receipt?.optString("requestId"),
                        onComplete = applyNext,
                        categoryRevisions = PrivacyRevisionWire.fromJson(receipt?.optJSONObject("categoryRevisions")?.toString())
                    )
                }
            }
            applyNext()
        }, onError = { error -> logAdapter.add("warn", "隐私删除历史暂不可读；继续保持同步暂停：$error") })
    }

    private fun completePrivacyDeletion(requestId: String?, succeeded: Boolean, fallback: (() -> Unit)? = null) {
        val finish = {
            val callbacks = requestId?.let { id ->
                synchronized(this@MainActivity) {
                    processedPrivacyDeletionIds.remove(id)
                    if (succeeded) markPrivacyDeletionApplied(id)
                    pendingPrivacyDeletionCallbacks.remove(id).orEmpty().toList()
                }
            } ?: listOfNotNull(fallback)
            if (succeeded) callbacks.forEach { callback -> callback() }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) finish() else runOnUiThread(finish)
    }

    @Synchronized
    private fun applyPrivacyDeletion(
        rawCategories: List<String>,
        requestId: String? = null,
        onComplete: (() -> Unit)? = null,
        categoryRevisions: Map<String, Long> = emptyMap()
    ) {
        val categories = PrivacyDataPolicy.normalizeCategories(rawCategories) ?: return
        val linkedConversationTaskIds = if ("conversations" in categories) {
            (workspaceTaskMirror.values
                .filter { task ->
                    task.optString("source") == "conversation" || task.optString("relatedSessionId").isNotBlank() ||
                        task.optJSONObject("metadata")?.let { it.has("sessionId") || it.has("messageId") } == true
                }
                .map { it.optString("id") } + timelineProjection.tasks.value.values
                .filter { task -> task.source == "conversation" || !task.relatedSessionId.isNullOrBlank() }
                .map { it.id })
                .filterNot { it.isNullOrBlank() }
                .mapNotNull { it }
                .distinct()
        } else emptyList()
        val deletionId = requestId?.takeIf { it.isNotBlank() }
        if (deletionId != null) {
            if (deletionId in savedPrivacyDeletionIds()) {
                onComplete?.let { callback -> runOnUiThread { callback() } }
                return
            }
            if (!processedPrivacyDeletionIds.add(deletionId)) {
                onComplete?.let { pendingPrivacyDeletionCallbacks.getOrPut(deletionId) { mutableListOf() }.add(it) }
                return
            }
            pendingPrivacyDeletionCallbacks[deletionId] = mutableListOf<() -> Unit>().apply { onComplete?.let(::add) }
        }
        appScope.launch(Dispatchers.IO) {
            val localPurge = runCatching {
                workspaceRepository.applyPrivacyDeletion(categories, categoryRevisions, linkedConversationTaskIds)
            }
            withContext(Dispatchers.Main) {
                if (localPurge.isFailure) {
                    Toast.makeText(this@MainActivity, "节点已删除；本机镜像清理失败，请重启后重试同步", Toast.LENGTH_LONG).show()
                    completePrivacyDeletion(deletionId, succeeded = false, fallback = onComplete)
                    return@withContext
                }
                if ("memories" in categories) MoteMemory.clear(this@MainActivity)
                if ("conversations" in categories) {
                    ChatOutbox.replace(this@MainActivity, emptyList())
                    val oldHandoff = MoteHandoff.load(this@MainActivity)
                    MoteHandoff.replace(
                        this@MainActivity,
                        HandoffState(revision = oldHandoff.revision + 1L, updatedAtMs = System.currentTimeMillis(), updatedBy = "privacy-delete")
                    )
                    activeChatRequestId.takeIf { it.isNotBlank() }?.let { cancelledChatRequestIds.add(it) }
                    activeChatRequestId = ""
                    pendingChatText = ""
                    lastFailedChatText = ""
                    lastSpokenReply = ""
                    streamingChatId = ""
                    streamingText.setLength(0)
                    offlineChatFuture?.cancel(true)
                    offlineChatFuture = null
                    speechText.text = ""
                    focusSpeechStack.removeAllViews()
                }
                if ("progress" in categories) {
                    explorationLogDetailDialog?.dismiss()
                    explorationLogRequestGate.reset()
                    explorationLogStore.reset()
                    explorationLogSnapshot = ExplorationLogSnapshot()
                    explorationLogDialogRenderer?.invoke(explorationLogSnapshot)
                    realityHistoryReadOnly = false
                    realityLensView.setHistoricalRecord(null)
                    getSharedPreferences("mote_roster", Context.MODE_PRIVATE).edit().clear().apply()
                    getSharedPreferences("mote_pet", Context.MODE_PRIVATE).edit().clear().apply()
                    moteRosterJson = JSONArray()
                    moteStateJson = JSONObject()
                    moteStoryJson = JSONArray()
                    moteRelationship = MoteRelationshipSummary()
                    realityExplorationCoordinator.clearForPrivacyDeletion()
                    realityRegion = null
                    realityLensView.setCoarseRegion(null)
                    realityLensView.setNearbyEvents(emptyList())
                    pet = PetState()
                    savePet()
                    refreshExplorationLog()
                }
                if ("tasks" in categories) {
                    activeTasks.clear()
                    taskAdapter.submit(emptyList())
                    workspaceTaskMirror.clear()
                    attentionMirror.clear()
                    actionRunMirror.clear()
                    cockpitAttentionItems.clear()
                } else if ("conversations" in categories) {
                    val removedTaskIds = linkedConversationTaskIds.toSet()
                    removedTaskIds.forEach {
                        workspaceTaskMirror.remove(it)
                        activeTasks.remove(it)
                    }
                    attentionMirror.entries.removeAll { (_, item) ->
                        item.optString("relatedSessionId").isNotBlank() || removedTaskIds.contains(item.optString("relatedTaskId"))
                    }
                    actionRunMirror.entries.removeAll { (_, item) ->
                        item.optString("sessionId").isNotBlank() || removedTaskIds.contains(item.optString("taskId"))
                    }
                    cockpitAttentionItems.entries.removeAll { (_, item) ->
                        item.relatedSessionId.isNotBlank() || removedTaskIds.contains(item.relatedTaskId)
                    }
                    taskAdapter.submit(activeTasks.values.toList())
                }
                if ("tasks" in categories && "progress" !in categories) {
                    pet = pet.copy(successfulTasks = 0, failedTasks = 0, activeTasks = 0)
                    savePet()
                }
                if ("conversations" in categories) chatAdapter.load(emptyList())
                logAdapter.submit(emptyList())

                val previous = companionSessionRepository.snapshot.value
                val filtered = TimelineSnapshot(
                    revision = previous.revision,
                    tasks = when {
                        "tasks" in categories -> emptyList()
                        "conversations" in categories -> previous.tasks.filterNot {
                            it.source == "conversation" || !it.relatedSessionId.isNullOrBlank() || it.id in linkedConversationTaskIds
                        }
                        else -> previous.tasks
                    },
                    messages = if ("conversations" in categories) emptyList() else previous.messages,
                    attention = when {
                        "tasks" in categories -> emptyList()
                        "conversations" in categories -> previous.attention.filterNot {
                            !it.relatedSessionId.isNullOrBlank() || it.relatedTaskId in linkedConversationTaskIds
                        }
                        else -> previous.attention
                    },
                    mote = if ("progress" in categories) null else previous.mote,
                    health = previous.health,
                    autonomy = previous.autonomy
                )
                timelineProjection.applySnapshot(filtered)
                companionSummary = companionSummary.copy(
                    totalTasks = if ("tasks" in categories) 0 else companionSummary.totalTasks,
                    runningTasks = if ("tasks" in categories) 0 else companionSummary.runningTasks,
                    needsConfirmation = if ("tasks" in categories) 0 else companionSummary.needsConfirmation,
                    activeTaskId = if ("tasks" in categories) null else companionSummary.activeTaskId,
                    openAttention = if ("tasks" in categories) 0 else companionSummary.openAttention,
                    memoryCount = if ("memories" in categories) 0 else companionSummary.memoryCount,
                    moteLevel = if ("progress" in categories) 1 else companionSummary.moteLevel,
                    moteXp = if ("progress" in categories) 0 else companionSummary.moteXp,
                    growthLevel = if ("progress" in categories) 1 else companionSummary.growthLevel,
                    growthXp = if ("progress" in categories) 0 else companionSummary.growthXp,
                    growthDailyDate = if ("progress" in categories) "" else companionSummary.growthDailyDate,
                    growthDailyClues = if ("progress" in categories) emptyMap() else companionSummary.growthDailyClues,
                    growthDailyCompleted = if ("progress" in categories) false else companionSummary.growthDailyCompleted,
                    growthActiveBoostId = if ("progress" in categories) null else companionSummary.growthActiveBoostId,
                    realityRegion = if ("progress" in categories) "" else companionSummary.realityRegion,
                    realityEvents = if ("progress" in categories) 0 else companionSummary.realityEvents,
                    realityLevel = if ("progress" in categories) 1 else companionSummary.realityLevel,
                    realityXp = if ("progress" in categories) 0 else companionSummary.realityXp,
                    inventoryCount = if ("progress" in categories) 0 else companionSummary.inventoryCount,
                    moteId = if ("progress" in categories) "rimuru" else companionSummary.moteId,
                    moteName = if ("progress" in categories) "利姆鲁" else companionSummary.moteName,
                    gaze = if ("progress" in categories) "ambient" else companionSummary.gaze,
                    reminderStrength = if ("progress" in categories) 0f else companionSummary.reminderStrength
                )
                companionSessionRepository.applySummary(companionSummary)
                companionSessionRepository.applyTimelineSnapshot(filtered, filtered.revision)
                renderCompanionSessionSnapshot()
                renderCockpitSummary()
                renderPet()
                MoteWidget.refresh(this@MainActivity)
                completePrivacyDeletion(deletionId, succeeded = true, fallback = onComplete)
            }
        }
    }

    private fun showMemoryActions(item: MemoryItem, refresh: () -> Unit) {
        val actions = buildList {
            if (item.status == "candidate") add("确认这条候选")
            add("编辑内容")
            add(if (item.excludedFromRecall) "恢复允许提起" else "不再提起")
            add("删除记忆")
        }
        AlertDialog.Builder(this)
            .setTitle(if (item.status == "candidate") "待确认记忆" else "记忆管理")
            .setItems(actions.toTypedArray()) { _, selected ->
                when (actions[selected]) {
                    "确认这条候选" -> {
                        MoteMemory.confirmById(this, item.id)
                        workspaceRequest("/api/memories/${android.net.Uri.encode(item.id)}/confirm", "POST", JSONObject())
                        refresh()
                    }
                    "编辑内容" -> editMemory(item, refresh)
                    "不再提起" -> {
                        MoteMemory.setExcludedFromRecall(this, item.id, true)
                        syncMemoryPatch(item.id, JSONObject().put("excludedFromRecall", true))
                        refresh()
                    }
                    "恢复允许提起" -> {
                        MoteMemory.setExcludedFromRecall(this, item.id, false)
                        syncMemoryPatch(item.id, JSONObject().put("excludedFromRecall", false))
                        refresh()
                    }
                    "删除记忆" -> {
                        MoteMemory.removeById(this, item.id)
                        workspaceRequest("/api/memories/${android.net.Uri.encode(item.id)}", "DELETE", JSONObject())
                        refresh()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun editMemory(item: MemoryItem, refresh: () -> Unit) {
        val input = EditText(this).apply {
            setText(item.text)
            minLines = 2
            maxLines = 5
        }
        AlertDialog.Builder(this)
            .setTitle("编辑记忆")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotBlank()) {
                    MoteMemory.updateById(this, item.id, text)
                    syncMemoryPatch(item.id, JSONObject().put("text", text))
                    refresh()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun syncMemoryToNode(item: MemoryItem) {
        workspaceRequest(
            "/api/memories",
            "POST",
            JSONObject()
                .put("id", item.id)
                .put("text", item.text)
                .put("source", item.source)
                .put("status", item.status)
                .put("explicit", item.source != "auto_extract")
                .put("excludedFromRecall", item.excludedFromRecall)
        )
    }

    private fun syncMemoryPatch(id: String, patch: JSONObject) {
        workspaceRequest("/api/memories/${android.net.Uri.encode(id)}", "PATCH", patch)
    }

    private fun showHandoffDialog() {
        val current = MoteHandoff.load(this)
        val input = android.widget.EditText(this).apply {
            setText(MoteHandoff.editableDocument(current))
            minLines = 7
            gravity = android.view.Gravity.TOP
            setSingleLine(false)
        }
        AlertDialog.Builder(this)
            .setTitle("交接文档")
            .setMessage("手机与电脑共享；导出位置：Android/data/com.phonebridge/files/handoff/")
            .setView(input)
            .setPositiveButton("保存并同步") { _, _ ->
                val parsed = MoteHandoff.parse(input.text.toString(), current)
                if (parsed.isEmpty()) {
                    Toast.makeText(this, "交接文档为空", Toast.LENGTH_SHORT).show()
                } else {
                    MoteHandoff.save(this, parsed)
                    sendJson(JSONObject().put("type", "handoff_sync").put("state", MoteHandoff.toJson(parsed)))
                    logAdapter.add("success", "交接文档已保存并同步。")
                }
            }
            .setNeutralButton("拉取电脑端") { _, _ -> sendJson(JSONObject().put("type", "handoff_get")) }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showOfflineApiDialog() {
        val config = OfflineBrain.load(this)
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val enabled = SwitchMaterial(this).apply {
            text = "节点不可用时启用"
            isChecked = config.enabled
        }
        val urlInput = EditText(this).apply {
            hint = "Base URL（例如 https://api.../v1）"
            setText(config.baseUrl)
        }
        val keyInput = EditText(this).apply {
            hint = "API Key"
            setText(config.apiKey)
        }
        val modelInput = EditText(this).apply {
            hint = "模型名"
            setText(config.model)
        }
        val protocolInput = EditText(this).apply {
            hint = "协议：chat 或 responses"
            setText(config.protocol)
        }
        listOf(enabled, urlInput, keyInput, modelInput, protocolInput).forEach(container::addView)
        AlertDialog.Builder(this)
            .setTitle("离线模型接口")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val saved = OfflineApiConfig(
                    enabled = enabled.isChecked,
                    baseUrl = urlInput.text.toString().trim(),
                    apiKey = keyInput.text.toString().trim(),
                    model = modelInput.text.toString().trim(),
                    protocol = protocolInput.text.toString().trim().lowercase(Locale.US)
                )
                OfflineBrain.save(this, saved)
                logAdapter.add(if (saved.isReady) "success" else "warn", if (saved.isReady) "离线 API 已启用。" else "离线 API 未完整或已停用。")
            }
            .setNeutralButton("测试") { _, _ ->
                val testConfig = OfflineApiConfig(
                    enabled = true,
                    baseUrl = urlInput.text.toString().trim(),
                    apiKey = keyInput.text.toString().trim(),
                    model = modelInput.text.toString().trim(),
                    protocol = protocolInput.text.toString().trim().lowercase(Locale.US)
                )
                networkExecutor.execute {
                    val result = runCatching {
                        OfflineBrain.chat(
                            this@MainActivity,
                            "回复：连接正常",
                            configOverride = testConfig
                        ).getOrThrow()
                    }
                    runOnUiThread {
                        result.onSuccess {
                            logAdapter.add("success", "离线 API 连通。")
                        }.onFailure {
                            logAdapter.add("error", "离线 API 失败：${it.message}")
                        }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun requestOfflineReply(
        clean: String,
        memories: List<String>,
        turnId: String? = null,
        showUserMessage: Boolean = false,
        onReply: ((String) -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ): java.util.concurrent.Future<*> {
        if (showUserMessage) logAdapter.add("info", "你：$clean")
        return networkExecutor.submit {
            val history = chatAdapterCurrentMessages()
                .dropLast(1)
                .takeLast(12)
                .map { it.role to it.text }
            val result = OfflineBrain.chat(this, clean, memories, history)
            runOnUiThread {
                if (turnId != null && activeChatRequestId != turnId) return@runOnUiThread
                result.onSuccess { reply ->
                    appendChat("assistant", reply)
                    lastSpokenReply = reply.take(180)
                    say(reply)
                    onReply?.invoke(reply)
                }.onFailure { error ->
                    val message = if (OfflineBrain.load(this).isReady) {
                        "离线接口失败了：${error.message ?: "未知错误"}"
                    } else {
                        "还没连上电脑，也没有配置离线 API。点工具栏里的 API 填好地址、Key 和模型名。"
                    }
                    logAdapter.add("error", message)
                    appendChat("assistant", message)
                    say(message)
                    onError?.invoke(message)
                }
            }
        }
    }

    private fun startVoiceInput() {
        toggleContinuousVoice()
    }

    private fun toggleContinuousVoice() {
        if (BridgeService.isVoiceChatRunning) {
            val action = when {
                voiceRetryAvailable -> BridgeService.ACTION_VOICE_RETRY
                voiceStatusText == "语音处理中" || voiceStatusText == "正在播报回复" || voiceStatusText == "正在识别语音" -> BridgeService.ACTION_VOICE_INTERRUPT
                else -> BridgeService.ACTION_VOICE_STOP
            }
            startService(Intent(this, BridgeService::class.java).setAction(action))
            logAdapter.add("info", when (action) {
                BridgeService.ACTION_VOICE_RETRY -> "连续语音：重试上一轮"
                BridgeService.ACTION_VOICE_INTERRUPT -> "连续语音：打断当前回复，继续监听"
                else -> "连续语音：关闭中"
            })
        } else {
            startService(Intent(this, BridgeService::class.java).setAction(BridgeService.ACTION_VOICE_START))
            logAdapter.add("info", "连续语音：开启中，后台也会保持。")
        }
        renderVoiceState(running = !BridgeService.isVoiceChatRunning, listening = false, speaking = false)
    }

    private fun renderVoiceState(
        running: Boolean,
        listening: Boolean,
        speaking: Boolean = false,
        status: String = "",
        retryAvailable: Boolean = false
    ) {
        voiceRetryAvailable = retryAvailable
        voiceStatusText = status
        companionView.setVoiceState(listening, speaking)
        voiceButton.text = when {
            !running -> getString(R.string.action_voice)
            retryAvailable -> "重试"
            status == "语音处理中" || status == "正在播报回复" || status == "正在识别语音" -> "打断"
            else -> "停止"
        }
        voiceButton.alpha = if (running) 1f else .78f
        audioMetric.text = when {
            status.isNotBlank() && running -> "音频 $status"
            listening -> "音频 语音对话"
            pttActive -> "音频 对讲"
            continuousListening -> "音频 监听"
            micRunning -> "音频 开"
            else -> "音频 关"
        }
    }

    private fun drainChatOutbox() {
        appScope.launch {
            val blocked = withContext(Dispatchers.IO) {
                runCatching { workspaceRepository.privacySyncBlocked() }.getOrDefault(true)
            }
            if (blocked) return@launch
            ChatOutbox.drain(this@MainActivity).forEach { text ->
                logAdapter.add("info", "发送快捷回复：$text")
                appendChat("user", text)
                sendJson(JSONObject().put("type", "chat").put("text", text))
            }
        }
    }

    private fun selectCodexTask() {
        val index = codexTaskSpinner.selectedItemPosition
        if (index !in codexTaskIds.indices) return
        sendJson(JSONObject().put("type", "select_codex_task").put("id", codexTaskIds[index]))
        logAdapter.add("success", "已选定 Codex 任务。")
    }

    private fun applySelectedModel() {
        val index = modelSpinner.selectedItemPosition
        if (index !in modelProviderIds.indices) return
        sendJson(
            JSONObject()
                .put("type", "select_model")
                .put("providerId", modelProviderIds[index])
                .put("model", modelNames[index])
        )
        logAdapter.add("info", "正在应用模型：${modelLabels[index]}")
    }

    private fun timelineEventFromJson(event: JSONObject): TimelineEvent {
        val createdAt = event.optString("createdAt").ifBlank { event.optString("timestamp") }
        val timestamp = createdAt.toLongOrNull() ?: runCatching { Instant.parse(createdAt).toEpochMilli() }.getOrDefault(0L)
        val payload = event.optJSONObject("payload")?.let { jsonObject ->
            jsonObject.keys().asSequence().associateWith { key -> jsonObject.opt(key) }
        } ?: emptyMap()
        return TimelineEvent(
            eventId = event.optString("eventId"),
            revision = event.optLong("revision", 0L),
            timestamp = timestamp,
            entityType = event.optString("entity").ifBlank { event.optString("entityType") },
            entityId = event.optString("entityId"),
            entityVersion = event.optInt("entityVersion", 1).coerceAtLeast(1),
            operation = event.optString("operation", "update"),
            payload = payload,
            deleted = RealityClueProtocol.booleanField(event.opt("deleted"))
        )
    }

    private fun applyTimelineEvent(event: JSONObject) {
        companionSessionRepository.applyEvent(timelineEventFromJson(event))
        runOnUiThread { renderCompanionSessionSnapshot() }
    }

    private fun applyTimelineEvents(events: List<JSONObject>) {
        if (events.isEmpty()) return
        companionSessionRepository.applyEvents(events.map(::timelineEventFromJson))
        runOnUiThread { renderCompanionSessionSnapshot() }
    }

    private fun handleServerJson(text: String) {
        runCatching {
            val json = JSONObject(text)
            when (json.optString("type")) {
                "privacy.migration" -> runOnUiThread {
                    reconcilePrivacyMigration {
                        flushWorkspaceOutbox()
                        drainChatOutbox()
                    }
                }
                "privacy.deleted" -> {
                    val raw = json.optJSONArray("categories") ?: return@runCatching
                    val categories = buildList {
                        for (index in 0 until raw.length()) raw.optString(index).takeIf { it.isNotBlank() }?.let(::add)
                    }
                    runOnUiThread {
                        applyPrivacyDeletion(
                            categories,
                            json.optString("requestId"),
                            categoryRevisions = PrivacyRevisionWire.fromJson(json.optJSONObject("categoryRevisions")?.toString())
                        )
                    }
                }
                "log" -> {
                    val item = BridgeJson.log(json)
                    runOnUiThread { logAdapter.add(item.level.ifBlank { "info" }, item.message) }
                }
                "task" -> {
                    val item = BridgeJson.task(json)
                    runOnUiThread {
                        activeTasks[item.id] = item
                        when (item.status.lowercase()) {
                            "done" -> pet = pet.copy(
                                successfulTasks = pet.successfulTasks + 1,
                                experience = pet.experience + 4,
                                mood = PetMood.HAPPY
                            )
                            "error" -> pet = pet.copy(
                                failedTasks = pet.failedTasks + 1,
                                energy = (pet.energy - 4).coerceAtLeast(0),
                                mood = PetMood.ALERT
                            )
                        }
                        taskAdapter.submit(activeTasks.values.toList())
                        savePet()
                        MoteWidget.refresh(this@MainActivity)
                        renderPet()
                        if (item.status.equals("done", true)) logAdapter.add("success", "${item.title} 完成")
                    }
                }
                "snapshot" -> {
                    json.optJSONObject("companionSummary")?.let { summaryJson ->
                        companionSummary = CompanionSummaryParser.parse(summaryJson)
                        companionSessionRepository.applySummary(companionSummary)
                    }
                    val revision = json.optLong("eventRevision", json.optJSONObject("workspace")?.optLong("eventRevision", 0L) ?: 0L)
                    if (revision > workspaceRevision) {
                        workspaceRevision = revision
                        workspaceEventGate.markResynchronized(revision)
                        getSharedPreferences("workspace_meta", Context.MODE_PRIVATE).edit().putLong("revision", revision).apply()
                    }
                    json.optJSONObject("motes")?.let { handleMoteSnapshot(it) }
                    json.optJSONObject("workspace")?.let { workspace ->
                        runOnUiThread { applyWorkspaceSnapshot(workspace) }
                        workspace.optJSONObject("timeline")?.let { timelineJson ->
                            val model = TimelineParser.parse(
                                JSONObject()
                                    .put("revision", timelineJson.optLong("revision", 0L))
                                    .put("snapshot", timelineJson)
                                    .toString()
                            )
                            companionSessionRepository.applyTimelineSnapshot(model.snapshot, model.revision)
                            runOnUiThread { renderCompanionSessionSnapshot() }
                        }
                    }
                    val tasks = json.optJSONArray("tasks") ?: JSONArray()
                    val parsedTasks = LinkedHashMap<String, TaskItem>()
                    for (i in 0 until tasks.length()) {
                        val item = BridgeJson.task(tasks.getJSONObject(i))
                        if (!item.status.equals("done", true)) parsedTasks[item.id] = item
                    }
                    val logs = json.optJSONArray("logs") ?: JSONArray()
                    val parsedLogs = mutableListOf<LogItem>()
                    for (i in 0 until logs.length()) parsedLogs.add(BridgeJson.log(logs.getJSONObject(i)))
                    val remoteHandoff = json.optJSONObject("handoff")?.let { MoteHandoff.fromJson(it) }
                    val localHandoff = MoteHandoff.load(this)
                    val mergedHandoff = remoteHandoff?.let { MoteHandoff.merge(localHandoff, it) }
                    var handoffChanged = false
                    if (mergedHandoff != null && mergedHandoff != localHandoff) {
                        MoteHandoff.replace(this, mergedHandoff)
                        handoffChanged = true
                    }
                    val codex = json.optJSONObject("codex")
                    if (codex != null) {
                        codexTaskIds.clear()
                        codexTaskLabels.clear()
                        val tasks = codex.optJSONArray("tasks") ?: JSONArray()
                        for (i in 0 until tasks.length()) {
                            val task = tasks.getJSONObject(i)
                            codexTaskIds.add(task.optString("id"))
                            codexTaskLabels.add("${task.optString("updatedAt", "").take(19)}  ${task.optString("title")}")
                        }
                        modelProviderIds.clear()
                        modelNames.clear()
                        modelLabels.clear()
                        val providers = codex.optJSONArray("providers") ?: JSONArray()
                        for (i in 0 until providers.length()) {
                            val provider = providers.getJSONObject(i)
                            val providerId = provider.optString("id")
                            val providerName = provider.optString("name")
                            val models = provider.optJSONArray("models") ?: JSONArray()
                            for (j in 0 until models.length()) {
                                val model = models.getJSONObject(j)
                                modelProviderIds.add(providerId)
                                modelNames.add(model.optString("model"))
                                modelLabels.add("${providerName} / ${model.optString("displayName", model.optString("model"))}")
                            }
                        }
                    }
                    val chat = json.optJSONArray("chat") ?: JSONArray()
                    val selectedTask = codex?.optJSONObject("selectedTask")
                    val taskTimeline = mutableListOf<String>()
                    if (selectedTask != null) {
                        val timeline = selectedTask.optJSONArray("timeline") ?: JSONArray()
                        for (i in 0 until timeline.length()) {
                            val item = timeline.getJSONObject(i)
                            taskTimeline.add("${item.optString("time", "").take(19)} [${item.optString("kind")}] ${item.optString("text").take(220)}")
                        }
                    }
                    val serverMessages = (0 until chat.length()).map {
                        val item = chat.getJSONObject(it)
                        ChatMessage(
                            role = item.optString("role"),
                            text = item.optString("text"),
                            time = item.optString("time")
                        )
                    }
                    val latestAssistant = (0 until chat.length())
                        .map { chat.getJSONObject(it) }
                        .sortedByDescending { it.optLong("time", 0L) }
                        .firstOrNull { it.optString("role") == "assistant" }
                        ?.optString("text")
                    runOnUiThread {
                        activeTasks.clear()
                        activeTasks.putAll(parsedTasks)
                        taskAdapter.submit(activeTasks.values.toList())
                        logAdapter.submit(parsedLogs)
                        syncChatHistory(serverMessages)
                        if (handoffChanged) logAdapter.add("info", "已接收电脑端交接文档。")
                        codexTaskAdapter.notifyDataSetChanged()
                        modelAdapter.notifyDataSetChanged()
                        val currentModel = codex?.optString("currentModel", "")
                        val currentIndex = modelNames.indexOf(currentModel)
                        if (currentIndex >= 0) modelSpinner.setSelection(currentIndex, false)
                        if (selectedTask == null) {
                            selectedTaskTitle.text = getString(R.string.codex_task_loading)
                            selectedTaskPercent.text = "--"
                            selectedTaskProgress.progress = 0
                            selectedTaskMeta.text = "还没有选定 Codex 任务"
                        } else {
                            val progress = selectedTask.optInt("progress", 0).coerceIn(0, 100)
                            val status = selectedTask.optString("status", "unknown")
                            val codexTaskId = codex?.optString("selectedTaskId", "").orEmpty()
                            if (codexTaskId.isNotBlank() &&
                                (codexTaskId != lastCodexTaskId || progress != lastCodexProgress)
                            ) {
                                val sameTask = codexTaskId == lastCodexTaskId
                                val milestoneGain = if (sameTask) {
                                    ((progress / 25) - (lastCodexProgress.coerceAtLeast(0) / 25)).coerceAtLeast(0)
                                } else 0
                                val completionGain = if (lastCodexProgress < 100 && progress >= 100) 1 else 0
                                val reward = milestoneGain * 4 + completionGain * 8
                                if (reward > 0) {
                                    val previousLevel = pet.level
                                    pet = pet.copy(experience = pet.experience + reward)
                                    val levelUpMessage = checkLevelUp()
                                    savePet()
                                    sendJson(
                                        JSONObject()
                                            .put("type", "pet")
                                            .put("action", "codex_progress")
                                            .put("reward", reward)
                                            .put("state", petJson())
                                    )
                                    logAdapter.add("success", "Codex 进展 +$reward 经验。")
                                    if (levelUpMessage.isNotBlank()) say(levelUpMessage)
                                    if (previousLevel != pet.level) MoteWidget.refresh(this@MainActivity)
                                }
                                lastCodexTaskId = codexTaskId
                                lastCodexProgress = progress
                                saveCodexProgressMarker()
                            }
                            selectedTaskTitle.text = selectedTask.optString("title", "Codex 任务").ifBlank { "Codex 任务" }
                            selectedTaskPercent.text = "$progress%"
                            selectedTaskPercent.setTextColor(
                                when {
                                    status.equals("done", true) || progress >= 100 -> 0xFF8FF0C4.toInt()
                                    status.contains("fail", true) || status.equals("unavailable", true) -> 0xFFFF6B6B.toInt()
                                    else -> 0xFFFFD166.toInt()
                                }
                            )
                            selectedTaskProgress.progress = progress
                            selectedTaskMeta.text = listOf(
                                "状态 $status",
                                "事件 ${selectedTask.optInt("eventCount", 0)}",
                                "轮次 ${selectedTask.optInt("turnCount", 0)}",
                                "活动 ${selectedTask.optString("lastActivityAt", "").take(19)}"
                            ).joinToString("  ·  ")
                        }
                        selectedTaskDetail.text = when {
                            selectedTask == null -> getString(R.string.codex_task_loading)
                            taskTimeline.isEmpty() -> getString(R.string.codex_task_timeline_empty)
                            else -> taskTimeline.joinToString("\n")
                        }
                        codexDetail.text = listOf(
                            codex?.optString("currentProviderName", ""),
                            codex?.optString("currentModel", ""),
                            latestAssistant?.take(90) ?: "还没有对话回复。"
                        ).joinToString("\n")
                        val replyToSpeak = latestAssistant?.take(180)
                        if (!replyToSpeak.isNullOrBlank() && replyToSpeak != lastSpokenReply) {
                            lastSpokenReply = replyToSpeak
                            say(replyToSpeak)
                        }
                        renderPet()
                    }
                }
                "workspace.events" -> {
                    val revision = json.optLong("revision", 0L)
                    val events = json.optJSONArray("events") ?: JSONArray()
                    val timelineEvents = mutableListOf<JSONObject>()
                    for (index in 0 until events.length()) {
                        val event = events.optJSONObject(index) ?: continue
                        val eventRevision = event.optLong("revision", revision)
                        if (workspaceEventGate.accept(eventRevision, event.optString("eventId"))) {
                            if (event.optString("entity").isNotBlank() || event.optString("entityType").isNotBlank()) {
                                timelineEvents += event
                            } else {
                                val payload = event.optJSONObject("payload") ?: JSONObject()
                                payload.put("type", event.optString("type"))
                                handleServerJson(payload.toString())
                            }
                        }
                    }
                    applyTimelineEvents(timelineEvents)
                    if (workspaceEventGate.consumeGap()) {
                        sendJson(JSONObject().put("type", "snapshot").put("since", workspaceRevision))
                    } else if (workspaceEventGate.revision > workspaceRevision) {
                        workspaceRevision = workspaceEventGate.revision
                        getSharedPreferences("workspace_meta", Context.MODE_PRIVATE).edit().putLong("revision", workspaceRevision).apply()
                    }
                }
                "workspace.timeline" -> {
                    val event = json.optJSONObject("event") ?: return@runCatching
                    applyTimelineEvent(event)
                }
                "chat" -> {
                    if (json.optString("source") in setOf("voice", "notification")) return@runCatching
                    val role = json.optString("role")
                    val message = json.optString("text")
                    val time = json.optString("time", currentTime())
                    val requestId = json.optString("requestId")
                    runOnUiThread {
                        if (role == "assistant" && requestId.isNotBlank() && requestId == activeChatRequestId) {
                            activeChatRequestId = ""
                            pendingChatText = ""
                            offlineChatFuture = null
                            lastFailedChatText = ""
                            renderChatActionButton()
                        }
                        streamingChatId = ""
                        streamingText.setLength(0)
                        logAdapter.add(if (role == "assistant") "success" else "info", "$role：$message")
                        appendChat(role, message, false, time)
                        if (role == "assistant") {
                            lastSpokenReply = message.take(180)
                            say(lastSpokenReply)
                        }
                    }
                }
                "workspace.message" -> handleWorkspaceMessage(json)
                "workspace.task" -> handleWorkspaceTaskEvent(json.optJSONObject("task"))
                "workspace.goal" -> handleWorkspaceGoalEvent(json)
                "attention.upsert" -> handleAttentionEvent(json.optJSONObject("attention"))
                "action.run", "action.result" -> handleActionRunEvent(json.optJSONObject("actionRun"))
                "workspace.policy" -> handlePolicyEvent(json.optJSONObject("policy"))
                "autonomy.approval" -> handleAutonomyApprovalEvent(json.optJSONObject("approval"))
                "workspace.emergency_stop" -> handleEmergencyStopEvent(json.optJSONObject("state"))
                "mote.roster" -> handleMoteRosterEvent(json.optJSONArray("roster"), json.optJSONObject("state"), json.optJSONObject("growth"), json.optJSONArray("story"))
                "mote.profile" -> json.optJSONObject("profile")?.let { profile ->
                    handleMoteRosterEvent(null, JSONObject().put("activeId", profile.optString("id")))
                }
                "mote.exploration" -> handleMoteRosterEvent(null, json.optJSONObject("state"), json.optJSONObject("growth"))
                "mote.relationship" -> json.optJSONObject("relationship")?.let { handleMoteRelationshipEvent(it) }
                "mote.quest" -> runOnUiThread { speechText.text = "Mote：有新的陪伴任务" }
                "mote.story" -> {
                    json.optJSONArray("story")?.let { story ->
                        runOnUiThread {
                            moteStoryJson = JSONArray(story.toString())
                            getSharedPreferences("mote_roster", Context.MODE_PRIVATE).edit().putString("story", moteStoryJson.toString()).apply()
                            val newest = json.optJSONArray("newlyCompleted")?.optJSONObject(0)?.optString("title").orEmpty()
                            if (newest.isNotBlank()) {
                                speechText.text = "Mote：剧情完成 · $newest"
                                companionView.speakPulse()
                            }
                        }
                    }
                }
                "mote.behavior" -> MoteBehaviorOutput.fromWire(json.optJSONObject("behavior"))?.let { behavior ->
                    runOnUiThread {
                        companionView.setBehaviorHint(behavior)
                        realityLensView.setBehaviorHint(behavior)
                    }
                }
                "device.state" -> json.optJSONObject("state")?.let { state ->
                    runOnUiThread {
                        deviceHealthState = parseDeviceHealthJson(state)
                        renderCockpitSummary()
                    }
                }
                "proactive" -> {
                    val message = json.optString("message").trim()
                    if (message.isNotBlank()) runOnUiThread {
                        speechText.text = "Mote：$message".takeLast(220)
                        companionView.speakPulse()
                        logAdapter.add("info", "Mote 主动提醒：$message")
                    }
                }
                "handoff" -> {
                    val remote = json.optJSONObject("state")?.let { MoteHandoff.fromJson(it) } ?: return@runCatching
                    val merged = MoteHandoff.merge(MoteHandoff.load(this), remote)
                    val changed = merged != MoteHandoff.load(this)
                    if (changed) MoteHandoff.replace(this, merged)
                    runOnUiThread {
                        if (changed) logAdapter.add("success", "交接文档已更新：v${merged.revision}")
                    }
                }
                "chat_delta" -> {
                    if (json.optString("source") in setOf("voice", "notification")) return@runCatching
                    val id = json.optString("id")
                    val requestId = json.optString("requestId", id)
                    val text = json.optString("text")
                    if (!ChatStreamPolicy.shouldRenderDelta(requestId, activeChatRequestId, cancelledChatRequestIds)) return@runCatching
                    runOnUiThread {
                        if (!ChatStreamPolicy.shouldRenderDelta(requestId, activeChatRequestId, cancelledChatRequestIds)) return@runOnUiThread
                        if (streamingChatId != id) {
                            streamingChatId = id
                            streamingText.setLength(0)
                        }
                        streamingText.setLength(0)
                        streamingText.append(text)
                        val sessionId = json.optString("sessionId")
                        if (sessionId.isBlank()) appendChat("assistant", text, true, currentTime())
                        if (sessionId.isNotBlank() && sessionId == aiSelectedSessionId && aiSpacePanel.visibility == View.VISIBLE) {
                            aiStreamingId = id
                            aiStreamingText = text
                            val previous = aiConversationText.text.toString().substringBeforeLast("\n\nMote：", aiConversationText.text.toString())
                            aiConversationText.text = listOf(previous, "Mote：$text（生成中）").filter { it.isNotBlank() }.joinToString("\n\n")
                            aiSpaceStatus.text = "正在流式生成…"
                        }
                        speechText.text = "Mote：$text".takeLast(220)
                        companionView.speakPulse()
                    }
                }
                "chat_error" -> {
                    val requestId = json.optString("requestId")
                    if (requestId != activeChatRequestId) return@runCatching
                    runOnUiThread {
                        lastFailedChatText = pendingChatText
                        lastFailedChatRemember = pendingChatRemember
                        activeChatRequestId = ""
                        offlineChatFuture = null
                        streamingChatId = ""
                        streamingText.setLength(0)
                        appendChat("assistant", "回复暂时失败。可以点“重试”，也可以继续刚才的话题。")
                        logAdapter.add("warn", "聊天生成失败，可重试或继续对话。")
                        renderChatActionButton()
                    }
                }
                "chat_cancelled" -> {
                    val requestId = json.optString("requestId")
                    if (requestId != activeChatRequestId) return@runCatching
                    runOnUiThread { finishChatCancellation(requestId) }
                }
            }
        }.onFailure {
            Log.w(TAG, "bad server json", it)
        }
    }

    private fun setPtt(active: Boolean) {
        Log.i(TAG, "PTT $active websocket=$BridgeLink.isOnline micRunning=$micRunning")
        if (active && !requestAudioPermissionIfNeeded()) {
            say("需要麦克风权限才能按住说话。")
            return
        }
        val online = BridgeLink.isOnline
        pttButton.text = when {
            active && online -> getString(R.string.ptt_listening)
            active -> getString(R.string.ptt_offline)
            else -> getString(R.string.hold_to_talk)
        }
        pttButton.alpha = if (active) 1f else .92f
        if (active) {
            pttActive = true
            if (online) {
                say("我在听，说吧。")
                sendPttControl(true)
                startMicrophone()
            } else {
                say("还没连上电脑，先点“服务器”。")
                logAdapter.add("error", "对讲失败：链路离线。")
            }
        } else {
            pttActive = false
            if (online) {
                stopMicrophone(false)
                sendPttControl(false)
                if (continuousListening) startMicrophone()
            } else {
                say("刚才没有录进去：链路还是离线。")
            }
        }
        renderPet()
    }

    private fun sendPttControl(active: Boolean) {
        networkExecutor.execute {
            val base = desiredServerUrl
                ?.replaceFirst("wss://", "https://")
                ?.replaceFirst("ws://", "http://")
                ?.trimEnd('/') ?: return@execute
            val endpoint = authorizedUrl("$base/ptt/${if (active) "start" else "stop"}")
            try {
                client.newCall(
                    Request.Builder()
                        .url(endpoint)
                        .header("x-phonebridge-token", savedAccessToken())
                        .post(FormBody.Builder().build())
                        .build()
                )
                    .execute().use { response -> Log.i(TAG, "PTT HTTP ${response.code}") }
            } catch (e: Exception) {
                Log.w(TAG, "PTT HTTP failed", e)
            }
        }
    }

    private fun toggleListening() {
        if (!continuousListening && !requestAudioPermissionIfNeeded()) return
        continuousListening = !continuousListening
        listenButton.text = getString(if (continuousListening) R.string.continuous_listen else R.string.continuous_listen)
        listenButton.alpha = if (continuousListening) 1f else .68f
        if (continuousListening) {
            startMicrophone()
            say("耳朵打开了。")
        } else {
            stopMicrophone(false)
            say("耳朵休息了。")
        }
        renderPet()
    }

    @SuppressLint("MissingPermission")
    private fun startMicrophone() {
        if (!hasAudioPermission() || !BridgeLink.isOnline || micRunning) return
        val minBuffer = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            16000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, 4096)
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            logAdapter.add("error", "麦克风初始化失败。")
            return
        }
        audioRecord = record
        micRunning = true
        record.startRecording()
        val audioEffects = mutableListOf<AudioEffect>()
        if (AcousticEchoCanceler.isAvailable()) {
            AcousticEchoCanceler.create(record.audioSessionId)?.let {
                runCatching { it.enabled = true }
                audioEffects.add(it)
            }
        }
        if (NoiseSuppressor.isAvailable()) {
            NoiseSuppressor.create(record.audioSessionId)?.let {
                runCatching { it.enabled = true }
                audioEffects.add(it)
            }
        }
        audioThread = Thread {
            val buffer = ByteArray(1600)
            var chunksSent = 0
            try {
                while (micRunning) {
                    val count = record.read(buffer, 0, buffer.size)
                    if (!micRunning) break
                    if (count <= 0) continue
                    sendBinary(TYPE_AUDIO, buffer.copyOf(count))
                    chunksSent++
                    SystemClock.sleep(1)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "audio sender failed", e)
            } finally {
                Log.i(TAG, "audio stopped after $chunksSent chunks")
                audioEffects.forEach { effect ->
                    runCatching {
                        effect.enabled = false
                        effect.release()
                    }
                }
            }
            record.stop()
            record.release()
        }.apply { start() }
        runOnUiThread { renderPet() }
    }

    private fun stopMicrophone(clearMode: Boolean) {
        if (clearMode) {
            continuousListening = false
            pttActive = false
            listenButton.alpha = .68f
        }
        micRunning = false
        audioThread = null
        audioRecord = null
        runOnUiThread { renderPet() }
    }

    private fun playSpeech(pcm: ByteArray) {
        speaking = true
        runOnUiThread {
            companionView.speakPulse()
            renderPet()
        }
        try {
            speakerTrack?.release()
            val minBuf = AudioTrack.getMinBufferSize(16000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val track = AudioTrack(
                AudioManager.STREAM_MUSIC,
                16000,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, pcm.size * 2),
                AudioTrack.MODE_STREAM
            )
            speakerTrack = track
            track.play()
            track.write(pcm, 0, pcm.size)
        } catch (e: Exception) {
            Log.e(TAG, "speech playback failed", e)
        } finally {
            speaking = false
            runOnUiThread { renderPet() }
        }
    }

    private fun interact(kind: String) {
        companionView.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        val today = LocalDate.now().toString()
        val yesterday = LocalDate.now().minusDays(1).toString()
        val nextStreak = when (pet.lastCareDay) {
            today -> pet.careStreak
            yesterday -> pet.careStreak + 1
            else -> 1
        }
        val careMessage = when (kind) {
            "feed" -> {
                pet = pet.copy(energy = min(100, pet.energy + 16), experience = pet.experience + 5, mood = PetMood.HAPPY)
                "能量补上了。"
            }
            "play" -> {
                pet = pet.copy(
                    energy = (pet.energy - 6).coerceAtLeast(0),
                    affection = min(100, pet.affection + 5),
                    experience = pet.experience + 9,
                    mood = PetMood.HAPPY
                )
                "再来一次！"
            }
            else -> {
                pet = pet.copy(affection = min(100, pet.affection + 3), experience = pet.experience + 2, mood = PetMood.CALM)
                "我在这里。"
            }
        }
        val levelUpMessage = checkLevelUp()
        pet = pet.copy(
            lastInteractionMs = System.currentTimeMillis(),
            totalCares = pet.totalCares + 1,
            careStreak = nextStreak,
            lastCareDay = today
        )
        savePet()
        companionView.poke()
        setMoteMoment(MoteMoment.TOUCH)
        val characterLine = MoteCharacterizationEngine.resolve(pet.appearance, MoteMoment.TOUCH, moteRelationship.level).line
        val message = "$careMessage $characterLine"
        say(if (levelUpMessage.isBlank()) message else "$message $levelUpMessage")
        renderPet()
        sendJson(JSONObject().put("type", "pet").put("action", kind).put("state", petJson()))
    }

    private fun checkLevelUp(): String {
        val previousLevel = pet.level
        while (pet.experience >= pet.level * 45) {
            pet = pet.copy(experience = pet.experience - pet.level * 45, level = pet.level + 1, energy = 100)
            logAdapter.add("success", "Mote 升到了 Lv.${pet.level}")
        }
        val unlocked = growthSkills().drop(previousLevel).take(pet.level - previousLevel)
        if (unlocked.isEmpty()) return ""
        val names = unlocked.joinToString("、") { it.first }
        val message = "解锁技能：$names"
        logAdapter.add("success", message)
        return message
    }

    private fun growthSkills(): List<Pair<String, String>> = listOf(
        "共感" to "读取你的语气和状态，调整陪伴方式。",
        "记忆锚" to "把重要事实固定在长期记忆里。",
        "任务直觉" to "把 Codex 任务进展转成主动提醒。",
        "环境感知" to "结合电量、网络和传感器判断现场。",
        "深链" to "把手机动作和桌面目标串成一条链。",
        "信号记忆" to "在互动游戏中记住更长的意图序列。",
        "现实标签" to "把镜头里的地点和物体变成可探索笔记。",
        "共生直觉" to "把 Codex、手机和环境线索合成下一步。"
    )

    private fun showDailyRoutineDialog() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(12))
        }
        val scroll = android.widget.ScrollView(this).apply { addView(content) }
        val dialog = AlertDialog.Builder(this)
            .setTitle("日常陪伴")
            .setView(scroll)
            .setNegativeButton("关闭", null)
            .create()
        var deliveryNote = if (BridgeLink.isOnline) "正在同步节点记录…" else "离线镜像 · 操作会标记为待同步；散步观察不请求定位或相机。"
        lateinit var render: (List<DailyRoutineEntry>) -> Unit
        render = { entries ->
            content.removeAllViews()
            content.addView(TextView(this).apply {
                text = deliveryNote
                textSize = 12f
                setTextColor(Color.rgb(135, 164, 149))
                setPadding(0, dp(4), 0, dp(12))
            })
            val latest = entries.groupBy { it.routineId }.mapValues { (_, values) ->
                values.firstOrNull { it.status in setOf("active", "paused", "pending") }
                    ?: values.maxByOrNull { it.updatedAt }
            }
            DailyRoutineProtocol.defaultCatalog.forEach { definition ->
                val entry = latest[definition.id]
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    setBackgroundResource(R.drawable.bg_chip)
                }
                val title = TextView(this).apply {
                    text = definition.title
                    textSize = 15f
                    setTextColor(Color.rgb(234, 255, 245))
                }
                val status = when {
                    entry == null -> "尚未开始"
                    entry.syncState == RoutineSyncState.PENDING -> "待同步 · ${entry.pendingAction ?: "操作"}"
                    entry.syncState == RoutineSyncState.REJECTED -> "服务端拒绝：${entry.syncReason ?: "请重试"}"
                    else -> when (entry.status) {
                        "active" -> "进行中 · 已专注 ${formatRoutineElapsed(entry.elapsedSeconds)}"
                        "paused" -> "已暂停 · ${formatRoutineElapsed(entry.elapsedSeconds)}"
                        "finished" -> "已完成 · ${formatRoutineElapsed(entry.elapsedSeconds)}"
                        "skipped" -> "已跳过"
                        "interrupted" -> "已中断"
                        else -> "尚未开始"
                    }
                }
                card.addView(title)
                card.addView(TextView(this).apply {
                    text = definition.description + "\n" + status
                    textSize = 12f
                    setTextColor(if (entry?.syncState == RoutineSyncState.REJECTED) Color.rgb(255, 157, 157) else Color.rgb(167, 197, 182))
                    setPadding(0, dp(5), 0, dp(7))
                })
                val actionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                val currentStatus = if (entry?.syncState == RoutineSyncState.REJECTED && entry.status == "pending") null else entry?.status
                val actions = if (entry?.syncState == RoutineSyncState.PENDING) emptySet() else DailyRoutineProtocol.allowedActions(currentStatus)
                actions.forEach { action ->
                    val actionLabel = when (action) {
                        "start" -> "开始"
                        "pause" -> "暂停"
                        "resume" -> "继续"
                        "finish" -> "完成"
                        "skip" -> "跳过"
                        "interrupt" -> "中断"
                        else -> action
                    }
                    actionRow.addView(Button(this).apply {
                        text = actionLabel
                        isAllCaps = false
                        minWidth = 0
                        setOnClickListener {
                            val submit: (String?) -> Unit = { reflection ->
                                queueDailyRoutineAction(definition.id, action, reflection) { queued ->
                                    if (queued) {
                                        dialog.dismiss()
                                        showDailyRoutineDialog()
                                    } else {
                                        appScope.launch(Dispatchers.IO) {
                                            val latestEntries = workspaceRepository.routineEntries()
                                            withContext(Dispatchers.Main) { render(latestEntries) }
                                        }
                                    }
                                }
                            }
                            if (definition.id == DailyRoutineProtocol.BEDTIME_REVIEW && action == "finish") {
                                promptRoutineReflection(submit)
                            } else submit(null)
                        }
                    })
                }
                if (actions.isEmpty()) {
                    actionRow.addView(TextView(this).apply {
                        text = if (entry?.syncState == RoutineSyncState.PENDING) "等待节点确认" else "暂无可执行操作"
                        textSize = 11f
                        setTextColor(Color.rgb(135, 164, 149))
                        setPadding(0, dp(8), 0, dp(4))
                    })
                }
                card.addView(actionRow)
                content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
            }
            entries.filter { it.routineId == DailyRoutineProtocol.BEDTIME_REVIEW && !it.reflection.isNullOrBlank() }
                .maxByOrNull { it.updatedAt }?.let { entry ->
                    content.addView(TextView(this).apply {
                        text = "最近一次睡前回顾（仅本地/节点日常记录，不写入长期记忆）：\n${entry.reflection}"
                        textSize = 12f
                        setTextColor(Color.rgb(167, 197, 182))
                        setPadding(0, dp(4), 0, dp(8))
                    })
                }
        }
        dialog.show()
        appScope.launch(Dispatchers.IO) {
            val local = runCatching { workspaceRepository.routineEntries() }.getOrDefault(emptyList())
            withContext(Dispatchers.Main) { render(local) }
        }
        if (BridgeLink.isOnline) {
            workspaceRequest("/api/routines", onSuccess = { response ->
                val snapshot = runCatching { DailyRoutineProtocol.parseSnapshot(response.toString()) }.getOrNull()
                if (snapshot == null) {
                    deliveryNote = "节点返回的日常记录无法识别；仍显示本地镜像。"
                    appScope.launch {
                        render(runCatching { withContext(Dispatchers.IO) { workspaceRepository.routineEntries() } }.getOrDefault(emptyList()))
                    }
                    return@workspaceRequest
                }
                deliveryNote = if (snapshot.migrationRequired) "日常数据待完成隐私迁移选择；当前仅查看，暂不能同步操作。"
                else "节点已同步 · 散步观察不需要位置或相机权限；睡前回顾不会写入长期记忆。"
                appScope.launch {
                    workspaceRepository.saveRoutineSnapshot(snapshot)
                    render(runCatching { workspaceRepository.routineEntries() }.getOrDefault(emptyList()))
                }
            }, onError = { error ->
                deliveryNote = "节点暂不可用，显示离线镜像；提交后会明确标记待同步。($error)"
                appScope.launch {
                    render(runCatching { withContext(Dispatchers.IO) { workspaceRepository.routineEntries() } }.getOrDefault(emptyList()))
                }
            })
        }
    }

    private fun promptRoutineReflection(onSubmit: (String?) -> Unit) {
        val input = EditText(this).apply {
            hint = "可选：写下一件今天想记住的事（最多 1000 字）"
            minLines = 3
            maxLines = 6
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        AlertDialog.Builder(this)
            .setTitle("睡前回顾")
            .setMessage("这是纯文本日常记录，不会进入长期记忆；留空也可以完成。")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("完成回顾", null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val text = input.text.toString().trim()
                        if (text.codePointCount(0, text.length) > 1000) {
                            input.error = "最多 1000 个字符"
                            return@setOnClickListener
                        }
                        dialog.dismiss()
                        onSubmit(text.ifBlank { null })
                    }
                }
            }.show()
    }

    private fun queueDailyRoutineAction(
        routineId: String,
        action: String,
        reflection: String?,
        onComplete: (Boolean) -> Unit
    ) {
        appScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val entries = workspaceRepository.routineEntries()
                val current = entries.filter { it.routineId == routineId }.maxByOrNull { it.updatedAt }
                val now = Instant.now()
                val status = if (current?.syncState == RoutineSyncState.REJECTED && current.status == "pending") null else current?.status
                if (current?.syncState == RoutineSyncState.PENDING) error("上一条操作仍待同步")
                if (action !in DailyRoutineProtocol.allowedActions(status)) error("当前日常状态不允许此操作")
                val elapsed = DailyRoutineProtocol.elapsedForAction(action, current, now.toEpochMilli())
                val eventId = "routine-${UUID.randomUUID()}"
                val request = DailyRoutineProtocol.createRequest(
                    eventId = eventId,
                    routineId = routineId,
                    action = action,
                    occurredAt = now.toString(),
                    elapsedSeconds = elapsed,
                    reflection = reflection
                ) ?: error("日常操作内容无效")
                val event = WorkspaceEvent(
                    eventId = eventId,
                    origin = "phone-${Build.MODEL}",
                    sequence = nextWorkspaceSequence(),
                    type = WorkspaceEventTypes.ROUTINE_EVENT,
                    payload = DailyRoutineProtocol.actionPayload(request, 0L)
                )
                val queued = workspaceRepository.enqueueRoutineAction(event, request)
                if (!queued.queued) error(queued.reason ?: "日常操作未能排队")
                queued
            }
            withContext(Dispatchers.Main) {
                result.onSuccess {
                    scheduleOutboxSync()
                    Toast.makeText(this@MainActivity, "已暂存 · 待节点确认", Toast.LENGTH_SHORT).show()
                    onComplete(true)
                }.onFailure { error ->
                    Toast.makeText(this@MainActivity, error.message ?: "日常操作失败", Toast.LENGTH_LONG).show()
                    onComplete(false)
                }
            }
        }
    }

    private fun formatRoutineElapsed(seconds: Long): String {
        val safe = seconds.coerceAtLeast(0L)
        return "%02d:%02d".format(Locale.ROOT, safe / 3600L, (safe / 60L) % 60L)
    }

    private fun showGoalBoardDialog() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(12))
        }
        val titleInput = EditText(this).apply { hint = "想完成什么？（最多 120 字）"; setSingleLine(true) }
        val descriptionInput = EditText(this).apply { hint = "补充说明（可选）"; minLines = 2; maxLines = 4 }
        val statusText = TextView(this).apply {
            text = if (BridgeLink.isOnline) "目标与普通任务共享同一节点；建议只在你主动点击时生成。" else "离线镜像 · 目标创建、建议和确认需要连接节点。"
            textSize = 12f
            setTextColor(Color.rgb(135, 164, 149))
            setPadding(0, dp(4), 0, dp(8))
        }
        val createButton = Button(this).apply { text = "创建目标"; isAllCaps = false }
        val goalsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(statusText)
        content.addView(titleInput)
        content.addView(descriptionInput)
        content.addView(createButton)
        content.addView(goalsContainer)
        val scroll = android.widget.ScrollView(this).apply { addView(content) }
        val dialog = AlertDialog.Builder(this)
            .setTitle("个人目标")
            .setView(scroll)
            .setNegativeButton("关闭", null)
            .create()
        dialog.setOnDismissListener { goalBoardRefresh = null }

        lateinit var renderGoals: (List<GoalBoardGoal>) -> Unit
        renderGoals = { goals ->
            goalsContainer.removeAllViews()
            if (goals.isEmpty()) {
                goalsContainer.addView(TextView(this).apply {
                    text = "还没有目标。先写下一个目标，再决定是否需要步骤建议。"
                    textSize = 13f
                    setTextColor(Color.rgb(167, 197, 182))
                    setPadding(0, dp(14), 0, dp(8))
                })
            }
            goals.forEach { goal ->
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    setBackgroundResource(R.drawable.bg_chip)
                }
                card.addView(TextView(this).apply {
                    text = goal.title
                    textSize = 15f
                    setTextColor(Color.rgb(234, 255, 245))
                })
                if (goal.description.isNotBlank()) card.addView(TextView(this).apply {
                    text = goal.description
                    textSize = 12f
                    setTextColor(Color.rgb(167, 197, 182))
                    setPadding(0, dp(3), 0, dp(5))
                })
                if (goal.milestones.isEmpty()) {
                    card.addView(TextView(this).apply {
                        text = "尚未确认步骤；生成建议不会自动创建任务。"
                        textSize = 11f
                        setTextColor(Color.rgb(135, 164, 149))
                    })
                } else {
                    goal.milestones.forEachIndexed { index, milestone ->
                        card.addView(TextView(this).apply {
                            text = "${index + 1}. ${milestone.title} · ${milestone.status} · 任务 ${milestone.taskId ?: "未绑定"}"
                            textSize = 12f
                            setTextColor(Color.rgb(167, 197, 182))
                            setPadding(0, dp(4), 0, 0)
                        })
                    }
                }
                if (goal.status == "active" && goal.milestones.isEmpty()) {
                    card.addView(Button(this).apply {
                        text = "生成步骤建议…"
                        isAllCaps = false
                        setOnClickListener { promptGoalDraftContext(goal) }
                    })
                }
                goalsContainer.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
            }
        }

        fun refreshRemote() {
            if (!BridgeLink.isOnline) return
            workspaceRequest("/api/goals", onSuccess = { response ->
                val snapshot = runCatching { GoalBoardProtocol.parseSnapshot(response.toString()) }.getOrNull()
                if (snapshot == null) {
                    statusText.text = "节点目标响应无法识别；保留本地镜像。"
                    return@workspaceRequest
                }
                statusText.text = if (snapshot.migrationRequired) {
                    "目标或任务隐私迁移仍待选择；请先在设置 → 隐私与数据完成处理。"
                } else {
                    "节点已同步 · 草案只有逐项确认后才会创建普通任务。"
                }
                appScope.launch {
                    workspaceRepository.saveGoalSnapshot(snapshot)
                    renderGoals(runCatching { workspaceRepository.goals() }.getOrDefault(emptyList()))
                }
            }, onError = { error -> statusText.text = "节点暂不可用，显示离线镜像。($error)" })
        }
        goalBoardRefresh = { refreshRemote() }

        createButton.setOnClickListener {
            val title = titleInput.text.toString().trim()
            val description = descriptionInput.text.toString().trim()
            if (title.isBlank() || title.codePointCount(0, title.length) > 120) {
                titleInput.error = if (title.isBlank()) "请先填写目标" else "标题最多 120 字"
                return@setOnClickListener
            }
            if (description.codePointCount(0, description.length) > 2000) {
                descriptionInput.error = "说明最多 2000 字"
                return@setOnClickListener
            }
            appScope.launch {
                val revision = withContext(Dispatchers.IO) { workspaceRepository.privacyRevision("goals") }
                if (revision == null) {
                    Toast.makeText(this@MainActivity, "先连接节点并同步目标隐私版本", Toast.LENGTH_LONG).show()
                    return@launch
                }
                createButton.isEnabled = false
                workspaceRequest(
                    "/api/goals",
                    method = "POST",
                    payload = JSONObject().put("title", title).put("description", description).put("privacyRevision", revision),
                    onSuccess = {
                        createButton.isEnabled = true
                        titleInput.setText("")
                        descriptionInput.setText("")
                        statusText.text = "目标已创建；尚未生成建议或任务。"
                        refreshRemote()
                    },
                    onError = { error ->
                        createButton.isEnabled = true
                        statusText.text = "创建失败：$error"
                    }
                )
            }
        }
        dialog.show()
        appScope.launch(Dispatchers.IO) {
            val local = runCatching { workspaceRepository.goals() }.getOrDefault(emptyList())
            withContext(Dispatchers.Main) { renderGoals(local) }
        }
        refreshRemote()
    }

    private fun promptGoalDraftContext(goal: GoalBoardGoal) {
        val contextInput = EditText(this).apply {
            hint = "可选补充：时间、限制或你希望的节奏"
            minLines = 2
            maxLines = 4
        }
        AlertDialog.Builder(this)
            .setTitle("为「${goal.title}」生成步骤建议？")
            .setMessage("只有点击生成后才会向当前主 provider 请求建议；请求失败时服务端使用本地规则。不会调用工具或自动创建任务。")
            .setView(contextInput)
            .setNegativeButton("暂不生成", null)
            .setPositiveButton("生成建议", null)
            .create().also { prompt ->
                prompt.setOnShowListener {
                    prompt.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val contextText = contextInput.text.toString().trim()
                        if (contextText.codePointCount(0, contextText.length) > 1000) {
                            contextInput.error = "补充内容最多 1000 字"
                            return@setOnClickListener
                        }
                        prompt.dismiss()
                        requestGoalDraft(goal, contextText)
                    }
                }
            }.show()
    }

    private fun requestGoalDraft(goal: GoalBoardGoal, contextText: String) {
        appScope.launch {
            val revision = withContext(Dispatchers.IO) { workspaceRepository.privacyRevision("goals") }
            if (revision == null) {
                Toast.makeText(this@MainActivity, "先连接节点并同步目标隐私版本", Toast.LENGTH_LONG).show()
                return@launch
            }
            val buttonEventId = "goal_accept_${UUID.randomUUID()}"
            workspaceRequest(
                "/api/goals/${android.net.Uri.encode(goal.id)}/draft",
                method = "POST",
                payload = JSONObject().put("context", contextText).put("privacyRevision", revision),
                onSuccess = { response ->
                    val draft = GoalBoardProtocol.parseDraft(response.toString(), buttonEventId)
                    if (draft == null || draft.goalId != goal.id) {
                        Toast.makeText(this@MainActivity, "服务端返回的草案无效；未创建任务", Toast.LENGTH_LONG).show()
                    } else {
                        goalDraftForUi = draft
                        showGoalDraftEditor(draft)
                    }
                },
                onError = { error -> Toast.makeText(this@MainActivity, "生成建议失败：$error", Toast.LENGTH_LONG).show() }
            )
        }
    }

    private fun showGoalDraftEditor(draft: GoalBoardDraft) {
        goalDraftForUi = draft
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(4), dp(18), dp(8))
        }
        val providerNote = when (draft.fallbackReason) {
            "provider_unavailable" -> "主 provider：${draft.providerId} · 不可用，已降级至 ${draft.fallbackProvider ?: "local"}。"
            "provider_output_invalid" -> "主 provider：${draft.providerId} · 输出未通过校验，已降级至 ${draft.fallbackProvider ?: "local"}。"
            null -> "建议来源：${draft.providerId}"
            else -> "建议来源：${draft.providerId} · 降级原因：${draft.fallbackReason}"
        }
        root.addView(TextView(this).apply {
            text = "$providerNote\n这些步骤只在你逐项确认后才会生成任务。"
            textSize = 12f
            setTextColor(Color.rgb(135, 164, 149))
            setPadding(0, 0, 0, dp(8))
        })
        val stepsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val fields = mutableListOf<Pair<EditText, EditText>>()
        fun appendStep(step: GoalBoardStep = GoalBoardStep("", "")) {
            if (fields.size >= 8) {
                Toast.makeText(this, "最多 8 个步骤", Toast.LENGTH_SHORT).show()
                return
            }
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val title = EditText(this).apply { setText(step.title); hint = "步骤名称（最多 120 字）"; setSingleLine(true) }
            val description = EditText(this).apply { setText(step.description); hint = "步骤说明（可选，最多 500 字）"; minLines = 1; maxLines = 3 }
            val remove = Button(this).apply { text = "删除这一步"; isAllCaps = false }
            row.addView(title)
            row.addView(description)
            row.addView(remove)
            val pair = title to description
            fields += pair
            remove.setOnClickListener {
                stepsContainer.removeView(row)
                fields.remove(pair)
            }
            stepsContainer.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        draft.steps.forEach(::appendStep)
        root.addView(stepsContainer)
        root.addView(Button(this).apply {
            text = "添加一步"
            isAllCaps = false
            setOnClickListener { appendStep() }
        })
        val scroll = android.widget.ScrollView(this).apply { addView(root) }
        val editor = AlertDialog.Builder(this)
            .setTitle("检查并编辑步骤")
            .setView(scroll)
            .setNegativeButton("放弃草案", null)
            .setPositiveButton("逐项确认…", null)
            .create()
        editor.setOnCancelListener { goalDraftForUi = null }
        editor.setOnShowListener {
            editor.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                goalDraftForUi = null
                editor.dismiss()
            }
            editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val edited = draft.copy(steps = fields.map { (title, description) ->
                    GoalBoardStep(title.text.toString().trim(), description.text.toString().trim())
                })
                if (!GoalBoardProtocol.canAccept(edited, draft.goalId)) {
                    Toast.makeText(this, "步骤标题、说明或数量不符合要求，请检查后再确认", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                goalDraftForUi = edited
                editor.dismiss()
                confirmGoalStep(edited, 0)
            }
        }
        editor.show()
    }

    private fun confirmGoalStep(draft: GoalBoardDraft, index: Int) {
        if (index !in draft.steps.indices) {
            acceptGoalDraft(draft)
            return
        }
        val step = draft.steps[index]
        AlertDialog.Builder(this)
            .setTitle("逐项确认 ${index + 1}/${draft.steps.size}")
            .setMessage(step.title + if (step.description.isBlank()) "" else "\n\n${step.description}")
            .setNegativeButton("取消确认", null)
            .setPositiveButton("确认此步骤") { _, _ -> confirmGoalStep(draft, index + 1) }
            .show()
    }

    private fun acceptGoalDraft(draft: GoalBoardDraft) {
        if (!GoalBoardProtocol.canAccept(draft, draft.goalId)) return
        appScope.launch {
            val revisions = withContext(Dispatchers.IO) {
                workspaceRepository.privacyRevision("goals") to workspaceRepository.privacyRevision("tasks")
            }
            val goalRevision = revisions.first
            val taskRevision = revisions.second
            if (goalRevision == null || taskRevision == null) {
                Toast.makeText(this@MainActivity, "目标或任务隐私版本未就绪；尚未创建步骤", Toast.LENGTH_LONG).show()
                return@launch
            }
            val payload = runCatching {
                JSONObject(GoalBoardProtocol.acceptancePayload(draft, goalRevision, taskRevision))
            }.getOrElse {
                Toast.makeText(this@MainActivity, "步骤内容无效：${it.message}", Toast.LENGTH_LONG).show()
                return@launch
            }
            workspaceRequest(
                "/api/goals/${android.net.Uri.encode(draft.goalId)}/accept",
                method = "POST",
                payload = payload,
                onSuccess = { response ->
                    val goalJson = response.optJSONObject("goal")
                    val goal = goalJson?.let {
                        runCatching {
                            GoalBoardProtocol.parseSnapshot(JSONObject().put("goals", JSONArray().put(it)).toString()).goals.firstOrNull()
                        }.getOrNull()
                    }
                    if (goal == null) {
                        Toast.makeText(this@MainActivity, "节点已接受，但目标镜像无法解析；请刷新目标列表", Toast.LENGTH_LONG).show()
                        return@workspaceRequest
                    }
                    val taskArray = response.optJSONArray("tasks") ?: JSONArray()
                    val taskMaps = (0 until taskArray.length()).mapNotNull { taskArray.optJSONObject(it)?.let { item -> runCatching { parseWorkspaceJsonObject(item.toString()) }.getOrNull() } }
                    val refs = GoalBoardProtocol.taskRefs(taskMaps).associateBy { it.taskId }
                    val tasks = (0 until taskArray.length()).mapNotNull { index ->
                        val item = taskArray.optJSONObject(index) ?: return@mapNotNull null
                        val id = item.optString("id")
                        val ref = refs[id] ?: return@mapNotNull null
                        WorkspaceTaskEntity(
                            id = id,
                            source = item.optString("source", "goal"),
                            title = item.optString("title", ref.title),
                            state = item.optString("state", ref.state),
                            progress = item.optInt("progress", 0).coerceIn(0, 100),
                            detail = item.optString("detail"),
                            error = item.optString("error").ifBlank { null },
                            retryCount = item.optInt("retryCount", 0),
                            artifactRefsJson = item.optJSONArray("artifactRefs")?.toString() ?: "[]",
                            createdAt = parseEpochMs(item.opt("createdAt")) ?: System.currentTimeMillis(),
                            updatedAt = parseEpochMs(item.opt("updatedAt")) ?: System.currentTimeMillis(),
                            goalId = ref.goalId,
                            milestoneId = ref.milestoneId
                        )
                    }
                    appScope.launch {
                        runCatching { withContext(Dispatchers.IO) { workspaceRepository.saveAcceptedGoal(goal, tasks) } }
                            .onSuccess {
                                goalDraftForUi = null
                                Toast.makeText(this@MainActivity, "已逐项确认 · 创建 ${tasks.size} 个普通任务", Toast.LENGTH_LONG).show()
                            }
                            .onFailure { error ->
                                Toast.makeText(this@MainActivity, "节点已接受；本地镜像待恢复：${error.message}", Toast.LENGTH_LONG).show()
                            }
                    }
                },
                onError = { error ->
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("确认结果尚不确定")
                        .setMessage("为避免重复创建或修改同一收据，请使用相同 eventId 和原步骤重试。\n$error")
                        .setNegativeButton("稍后") { _, _ -> goalDraftForUi = draft }
                        .setPositiveButton("重试原提交") { _, _ -> acceptGoalDraft(draft) }
                        .show()
                }
            )
        }
    }

    private fun showGrowthDialog() {
        val required = pet.level * 45
        val skills = growthSkills().mapIndexed { index, (name, detail) ->
            val state = if (index < pet.level) "已解锁" else "Lv.${index + 1} 解锁"
            "$state · $name：$detail"
        }.joinToString("\n")
        AlertDialog.Builder(this)
            .setTitle("Mote 成长")
            .setMessage("Lv.${pet.level} · 经验 ${pet.experience}/$required\n\n$skills")
            .setPositiveButton("好的", null)
            .show()
    }

    private fun handleMoteRelationshipEvent(value: JSONObject) {
        runCatching { MoteRelationshipSummary.fromJson(value.toString()) }.onSuccess {
            runOnUiThread { moteRelationship = it; renderCockpitSummary() }
        }
    }

    private fun handleAutonomyApprovalEvent(approval: JSONObject?) {
        if (approval == null) return
        val tool = approval.optString("toolId", "受限工具")
        if (approval.optString("state") == "needs_confirmation") runOnUiThread {
            speechText.text = "Mote：需要确认 $tool".takeLast(220)
            companionView.speakPulse()
        }
    }

    private fun showMoteDexDialog() {
        val cached = getSharedPreferences("mote_roster", Context.MODE_PRIVATE).getString("roster", null)
        val cachedState = getSharedPreferences("mote_roster", Context.MODE_PRIVATE).getString("state", null)
        val cachedStory = getSharedPreferences("mote_roster", Context.MODE_PRIVATE).getString("story", null)
        if (!cached.isNullOrBlank()) {
            handleMoteSnapshot(
                JSONObject()
                    .put("roster", JSONArray(cached))
                    .put("state", JSONObject(cachedState ?: "{}"))
                    .put("story", JSONArray(cachedStory ?: "[]"))
            )
            renderMoteDexDialog()
        }
        if (BridgeLink.isOnline) workspaceRequest("/api/motes", onSuccess = { handleMoteSnapshot(it); renderMoteDexDialog() })
        else if (cached.isNullOrBlank()) renderMoteDexDialog()
    }

    private fun renderMoteDexDialog() {
        moteDexDialog?.dismiss()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val exploration = moteStateJson.optJSONObject("exploration") ?: JSONObject()
        val target = exploration.optString("targetId").ifBlank { "暂无" }
        val fragments = exploration.optJSONObject("fragments")
        val storyEntries = MoteStoryProtocol.parse(moteStoryJson)
        container.addView(TextView(this).apply {
            val nextStory = storyEntries.firstOrNull { it.completed && !it.claimed }?.let { "\n待领奖：${it.title} +${it.rewardXp} XP" }.orEmpty()
            text = "探索目标：$target\n地点 ${if (RealityClueProtocol.booleanField(fragments?.opt("location"))) "✓" else "·"}  物体 ${if (RealityClueProtocol.booleanField(fragments?.opt("object"))) "✓" else "·"}  光线 ${if (RealityClueProtocol.booleanField(fragments?.opt("light"))) "✓" else "·"}\n${MoteStoryProtocol.summary(moteStoryJson)}$nextStory"
            setTextColor(Color.parseColor("#D9F5E6"))
            setPadding(0, 0, 0, dp(8))
        })
        val storyRecap = storyEntries.filter { it.completed }
            .sortedByDescending { it.completedAtMs }
            .take(5)
        if (storyRecap.isNotEmpty()) {
            container.addView(TextView(this).apply {
                text = "最近剧情回顾\n" + storyRecap.joinToString("\n") { entry ->
                    "· ${entry.title}" + entry.branchOutcome.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()
                }
                textSize = 12f
                setTextColor(Color.parseColor("#C7D6E7"))
                setPadding(0, 0, 0, dp(8))
            })
        }
        val exclusiveStories = storyEntries
            .filter { it.exclusive }
            .associateBy { it.moteId }
        val previewView = CompanionView(this).apply {
            setPalette(activeTheme.accent, activeTheme.secondary)
            setStageState(
                CompanionStageEngine.resolve(
                    CompanionStageInput(
                        hourOfDay = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY),
                        energy = pet.energy,
                        reduceMotion = stagePreferences.reduceMotion,
                        decorations = stageDecorations,
                    )
                )
            )
        }
        val previewCaption = TextView(this).apply {
            setTextColor(Color.parseColor("#D9F5E6"))
            setPadding(0, dp(4), 0, dp(8))
        }
        fun previewMote(id: String, name: String, moment: MoteMoment = MoteMoment.IDLE) {
            val appearance = PetAppearance.fromWire(id)
            val cue = MoteCharacterizationEngine.resolve(appearance, moment, moteRelationship.level)
            previewView.update(pet.copy(name = name, appearance = appearance, connected = false, cameraActive = false, listening = false, speaking = false))
            previewView.setBehaviorHint(
                MoteBehaviorEngine.resolve(
                    MoteProfiles.profile(appearance),
                    MoteBehaviorInput(relationshipLevel = moteRelationship.level),
                )
            )
            previewView.setCharacterCue(cue)
            previewCaption.text = "$name · ${cue.gesture}\n${cue.line}"
        }
        val activeProfileId = moteStateJson.optString("activeId", "mote")
        val activeProfileName = MoteProfiles.profile(PetAppearance.fromWire(activeProfileId)).name
        previewMote(activeProfileId, activeProfileName)
        container.addView(previewView, LinearLayout.LayoutParams(-1, dp(164)))
        container.addView(previewCaption)
        for (index in 0 until moteRosterJson.length()) {
            val profile = moteRosterJson.optJSONObject(index) ?: continue
            val id = profile.optString("id")
            val unlocked = profile.optBoolean("unlocked")
            val actionButton = Button(this).apply {
                text = if (unlocked) "${profile.optString("name")} · ${profile.optString("voice")}" else "${profile.optString("name")} · 未解锁（设为探索目标）"
                isAllCaps = false
                isEnabled = true
                setOnClickListener {
                    if (unlocked) {
                        workspaceRequest("/api/motes/active", "PATCH", JSONObject().put("id", id), onSuccess = {
                            handleMoteSnapshot(it)
                            val selected = MoteProfiles.profile(PetAppearance.fromWire(id))
                            renderMoteDexDialog()
                            Toast.makeText(this@MainActivity, "现在由${selected.name}陪伴", Toast.LENGTH_SHORT).show()
                        })
                    } else {
                        workspaceRequest("/api/motes/exploration", "PATCH", JSONObject().put("targetId", id), onSuccess = {
                            handleMoteSnapshot(it)
                            val explorationTarget = MoteProfiles.profile(PetAppearance.fromWire(id))
                            renderMoteDexDialog()
                            Toast.makeText(this@MainActivity, "已设${explorationTarget.name}为探索目标", Toast.LENGTH_SHORT).show()
                        })
                    }
                }
            }
            var rowPreviewMoment = MoteMoment.IDLE
            val previewButton = Button(this).apply {
                text = "预览互动"
                isAllCaps = false
            }
            previewButton.setOnClickListener {
                rowPreviewMoment = when (rowPreviewMoment) {
                    MoteMoment.IDLE -> MoteMoment.TOUCH
                    MoteMoment.TOUCH -> MoteMoment.TASK
                    MoteMoment.TASK -> MoteMoment.EXPLORATION
                    MoteMoment.EXPLORATION -> MoteMoment.IDLE
                }
                val previewLabel = when (rowPreviewMoment) {
                    MoteMoment.IDLE -> "待机"
                    MoteMoment.TOUCH -> "互动"
                    MoteMoment.TASK -> "任务"
                    MoteMoment.EXPLORATION -> "探索"
                }
                previewMote(id, profile.optString("name", id), rowPreviewMoment)
                previewButton.text = "预览${previewLabel}"
                previewButton.contentDescription = "预览${profile.optString("name")}的${previewLabel}动作和关系语气"
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(actionButton, LinearLayout.LayoutParams(0, dp(52), 2f))
                addView(previewButton, LinearLayout.LayoutParams(0, dp(52), 1f))
            }
            container.addView(row)
            val story = exclusiveStories[id]
            if (story == null) continue
            container.addView(TextView(this).apply {
                val progress = when {
                    story.claimed -> "已完成 · 已领取"
                    story.completed -> "已完成 · 待领取 ${story.rewardXp} XP"
                    else -> "未完成"
                }
                text = "专属剧情：${story.title}（$progress）\n完成条件：${story.completion}"
                textSize = 12f
                setTextColor(Color.parseColor("#AFC9C0"))
                setPadding(dp(12), 0, dp(12), dp(6))
            })
            if (story.branchOutcome.isNotBlank()) {
                container.addView(TextView(this).apply {
                    text = "结局：${story.branchOutcome}"
                    textSize = 12f
                    setTextColor(Color.parseColor("#D7C8FF"))
                    setPadding(dp(12), 0, dp(12), dp(6))
                })
            }
            if (story.completed && !story.claimed && story.branchChoiceId.isBlank()) {
                story.branches.forEach { branch ->
                    val branchButton = Button(this).apply {
                        text = "${branch.title} · ${if (branch.bonusXp > 0) "额外 +${branch.bonusXp} XP" else "无额外 XP"}"
                        isAllCaps = false
                        setOnClickListener {
                            isEnabled = false
                            chooseMoteStoryBranch(story.id, branch.id)
                        }
                    }
                    container.addView(branchButton)
                }
            }
            if (story.completed && !story.claimed && (!story.exclusive || story.branchChoiceId.isNotBlank())) {
                val claimButton = Button(this).apply {
                    val bonusXp = story.branches.firstOrNull { it.id == story.branchChoiceId }?.bonusXp ?: 0
                    text = "领取 ${story.title} · +${story.rewardXp + bonusXp} XP"
                    isAllCaps = false
                }
                claimButton.setOnClickListener {
                    claimButton.isEnabled = false
                    val claimId = "story-${story.id}-${System.currentTimeMillis()}"
                    workspaceRequest(
                        "/api/motes/story/${android.net.Uri.encode(story.id)}/claim",
                        "POST",
                        JSONObject().put("claimId", claimId),
                        onSuccess = {
                            workspaceRequest("/api/motes", onSuccess = { latest ->
                                handleMoteSnapshot(latest)
                                renderMoteDexDialog()
                            })
                        },
                        onError = { message ->
                            claimButton.isEnabled = true
                            Toast.makeText(this, "领取失败：$message", Toast.LENGTH_LONG).show()
                        }
                    )
                }
                container.addView(claimButton)
            }
        }
        val scrollableContent = android.widget.ScrollView(this).apply { addView(container) }
        moteDexDialog = AlertDialog.Builder(this)
            .setTitle("Mote 图鉴 · ${moteRosterJson.length()}/20")
            .setView(scrollableContent)
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun chooseMoteStoryBranch(storyId: String, choiceId: String) {
        workspaceRequest(
            "/api/motes/story/${android.net.Uri.encode(storyId)}/branch",
            "POST",
            JSONObject().put("choiceId", choiceId),
            onSuccess = { response ->
                response.optJSONArray("story")?.let { stories ->
                    moteStoryJson = JSONArray(stories.toString())
                    getSharedPreferences("mote_roster", Context.MODE_PRIVATE).edit()
                        .putString("story", moteStoryJson.toString())
                        .apply()
                    renderMoteDexDialog()
                }
            },
            onError = { error ->
                Toast.makeText(this, "剧情选择失败：$error", Toast.LENGTH_LONG).show()
                renderMoteDexDialog()
            },
        )
    }

    private fun showSignalGameDialog() {
        val status = TextView(this).apply {
            setTextColor(Color.parseColor("#D9F5E6"))
            textSize = 15f
            setLineSpacing(dp(3).toFloat(), 1f)
            text = "Mote 会点出一串信号，记住顺序再复述。"
        }

        val signalColors = listOf(
            Color.rgb(56, 189, 172),
            Color.rgb(96, 165, 250),
            Color.rgb(251, 191, 36),
            Color.rgb(244, 114, 182)
        )
        val activeColors = listOf(
            Color.rgb(148, 240, 222),
            Color.rgb(165, 205, 255),
            Color.rgb(255, 226, 145),
            Color.rgb(255, 178, 217)
        )
        val buttons = (0..3).map { index ->
            Button(this).apply {
                text = (index + 1).toString()
                textSize = 18f
                setTextColor(Color.parseColor("#08130D"))
                backgroundTintList = ColorStateList.valueOf(signalColors[index])
                isEnabled = false
            }
        }

        fun paintSignals(activeIndex: Int = -1) {
            buttons.forEachIndexed { index, button ->
                button.backgroundTintList = ColorStateList.valueOf(
                    if (index == activeIndex) activeColors[index] else signalColors[index]
                )
            }
        }

        val firstRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            addView(buttons[0], android.widget.LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(8) })
            addView(buttons[1], android.widget.LinearLayout.LayoutParams(0, dp(46), 1f))
        }
        val secondRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
            addView(buttons[2], android.widget.LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(8) })
            addView(buttons[3], android.widget.LinearLayout.LayoutParams(0, dp(46), 1f))
        }
        val gameRoot = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(4))
            addView(status)
            addView(firstRow, android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) })
            addView(secondRow)
        }

        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val sequence = mutableListOf<Int>()
        var inputIndex = 0
        var score = 0
        var acceptingInput = false
        var finished = false

        fun finishGame() {
            finished = true
            acceptingInput = false
            buttons.forEach { it.isEnabled = false }
            handler.removeCallbacksAndMessages(null)
            val reward = score * 3
            pet = pet.copy(experience = pet.experience + reward)
            val levelUpMessage = checkLevelUp()
            savePet()
            renderPet()
            sendJson(
                JSONObject()
                    .put("type", "pet")
                    .put("action", "signal_game")
                    .put("score", score)
                    .put("state", petJson())
            )
            status.text = buildString {
                append("信号断在这一步。连续接住 $score 轮")
                if (reward > 0) append("，获得 $reward 经验")
                append("。")
                if (levelUpMessage.isNotBlank()) append(" $levelUpMessage")
            }
        }

        fun playSequence() {
            acceptingInput = false
            inputIndex = 0
            buttons.forEach { it.isEnabled = false }
            status.text = "第 ${sequence.size} 轮 · Mote 发送信号"
            sequence.forEachIndexed { index, signal ->
                handler.postDelayed({
                    paintSignals(signal)
                    buttons[signal].performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }, index * 640L)
                handler.postDelayed({ paintSignals() }, index * 640L + 420L)
            }
            handler.postDelayed({
                if (!finished) {
                    buttons.forEach { it.isEnabled = true }
                    acceptingInput = true
                    status.text = "第 ${sequence.size} 轮 · 轮到你了"
                }
            }, sequence.size * 640L + 120L)
        }

        fun nextRound() {
            if (finished) return
            sequence.add(Random.nextInt(4))
            playSequence()
        }

        buttons.forEachIndexed { index, button ->
            button.setOnClickListener {
                if (!acceptingInput || finished) return@setOnClickListener
                paintSignals(index)
                button.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                handler.postDelayed({ paintSignals() }, 160L)
                if (sequence.getOrNull(inputIndex) != index) {
                    finishGame()
                    return@setOnClickListener
                }
                inputIndex++
                if (inputIndex == sequence.size) {
                    score++
                    acceptingInput = false
                    buttons.forEach { it.isEnabled = false }
                    status.text = "接住了！Mote 准备下一轮。"
                    handler.postDelayed({ nextRound() }, 620L)
                }
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("信号记忆")
            .setView(gameRoot)
            .setPositiveButton("结束", null)
            .create()
        dialog.setOnShowListener { handler.postDelayed({ nextRound() }, 320L) }
        dialog.setOnDismissListener {
            if (!finished) finishGame()
        }
        dialog.show()
    }

    private fun say(text: String) {
        runOnUiThread {
            speechText.text = text
            showPetBubble(text)
            stageMessage = text
            val expiresAt = System.currentTimeMillis() + 5_000L
            stageMessageExpiresAtMs = expiresAt
            renderPet()
            companionView.speakPulse()
            companionView.postDelayed({
                if (stageMessageExpiresAtMs == expiresAt) {
                    stageMessage = null
                    renderPet()
                }
            }, 5_050L)
        }
    }

    private fun setMoteMoment(moment: MoteMoment) {
        activeMoteMoment = moment
        val expiresAt = System.currentTimeMillis() + 3_600L
        moteMomentExpiresAtMs = expiresAt
        renderPet()
        companionView.postDelayed({
            if (moteMomentExpiresAtMs == expiresAt) {
                activeMoteMoment = MoteMoment.IDLE
                moteMomentExpiresAtMs = 0L
                renderPet()
            }
        }, 3_650L)
    }

    private fun setStatus(text: String) {
        runOnUiThread {
            statusChip.text = text
            renderPet()
        }
    }

    private fun enterFocusMode() {
        enterFocusMode(announce = true)
    }

    private fun enterFocusMode(announce: Boolean) {
        if (immersiveMode) return
        immersiveMode = true
        immersiveShellCoordinator.setSurface(ImmersiveSurface.COMPANION)
        immersiveShellCoordinator.closeDrawer()
        focusToolsExpanded = false
        normalHeroParams = heroPanel.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        rootLayout.setPadding(0, 0, 0, 0)
        listOf(
            R.id.titleText, R.id.subtitleText, R.id.statusChip, R.id.themeSwitcher,
            R.id.appearanceRow, R.id.metricRow, R.id.actionScroll, R.id.pttButton,
            R.id.panelTabs, R.id.panelHost, R.id.speechText, R.id.cockpitDeck
        ).forEach { id -> findViewById<View>(id).visibility = View.GONE }

        val params = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        ).apply {
            val parent = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            startToStart = parent
            endToEnd = parent
            topToTop = parent
            bottomToBottom = parent
            setMargins(dp(12), dp(38), dp(12), dp(54))
        }
        heroPanel.layoutParams = params
        focusExit.visibility = View.VISIBLE
        focusToolbar.visibility = View.VISIBLE
        focusSpeechLayer.visibility = View.VISIBLE
        focusSpeechScroll.visibility = View.VISIBLE
        findViewById<View>(R.id.focusInputRow).visibility = View.VISIBLE
        renderFocusTools()
        focusSpeechLayer.post { layoutFocusSpeechOverlay() }
        focusBackCallback.isEnabled = true
        hideSystemBars()
        updateAmbientSound()
        if (announce) say("进入沉浸模式。")
    }

    private fun exitFocusMode() {
        if (!immersiveMode) return
        immersiveMode = false
        immersiveShellCoordinator.setSurface(ImmersiveSurface.COMPANION)
        immersiveShellCoordinator.closeDrawer()
        normalHeroParams?.let { params ->
            heroPanel.layoutParams = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(params)
        }
        rootLayout.setPadding(dp(14), dp(14), dp(14), 0)
        listOf(
            R.id.titleText, R.id.subtitleText, R.id.statusChip, R.id.themeSwitcher,
            R.id.appearanceRow, R.id.metricRow, R.id.actionScroll, R.id.pttButton,
            R.id.panelTabs, R.id.panelHost, R.id.cockpitDeck
        ).forEach { id -> findViewById<View>(id).visibility = View.VISIBLE }
        focusExit.visibility = View.GONE
        focusToolbar.visibility = View.GONE
        focusSpeechLayer.visibility = View.GONE
        focusSpeechScroll.visibility = View.GONE
        focusSpeechStack.removeAllViews()
        findViewById<View>(R.id.focusInputRow).visibility = View.GONE
        focusBackCallback.isEnabled = false
        updateAmbientSound()
        saveImmersiveSurface(ImmersiveSurface.COMPANION)
        showSystemBars()
        say("回到工作台。")
    }

    private fun enterAdaptiveImmersiveMode() {
        if (isFinishing || immersiveMode) return
        val preferences = getSharedPreferences("immersive_entry", Context.MODE_PRIVATE)
        val state = ImmersiveEntryState(
            lastSurface = ImmersiveSurface.fromPersisted(preferences.getString("last_surface", null)),
            cameraPermissionGranted = hasCameraPermission(),
        )
        enterFocusMode(announce = false)
        pendingDeepLink?.let { deepLink ->
            if (immersiveShellCoordinator.openDeepLink(deepLink)) {
                focusToolsExpanded = true
                renderFocusTools()
            }
            pendingDeepLink = null
        }
        if (ImmersiveEntryPolicy.surfaceFor(state) == ImmersiveSurface.REALITY) {
            rootLayout.post { if (immersiveMode) enterRealityLens() }
        } else {
            setStatus(if (BridgeLink.isOnline) "在线 · Mote 已准备" else "离线 · Mote 已准备")
        }
    }

    private fun saveImmersiveSurface(surface: ImmersiveSurface) {
        getSharedPreferences("immersive_entry", Context.MODE_PRIVATE)
            .edit()
            .putString("last_surface", surface.persistedValue)
            .apply()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, rootLayout).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun showSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowCompat.getInsetsController(window, rootLayout).show(WindowInsetsCompat.Type.systemBars())
    }

    private fun dismissFocusKeyboard(): Boolean {
        val insets = WindowInsetsCompat.toWindowInsetsCompat(rootLayout.rootWindowInsets)
        if (!insets.isVisible(WindowInsetsCompat.Type.ime())) return false
        WindowInsetsControllerCompat(window, rootLayout)
            .hide(WindowInsetsCompat.Type.ime())
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (immersiveMode && keyCode == KeyEvent.KEYCODE_BACK) {
            if (!dismissFocusKeyboard()) onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun renderCompanionSessionSnapshot() {
        if (!::focusSnapshotText.isInitialized) return
        val snapshot = companionSessionRepository.snapshot.value
        val current = snapshot.currentTask()
        val latest = snapshot.recentResults(1).firstOrNull()
        val connection = when {
            snapshot.sync.recovering -> "重连中"
            snapshot.sync.online -> "在线"
            else -> "离线镜像"
        }
        val taskLine = current?.let {
            "${it.title.ifBlank { "当前任务" }} · ${statusLabel(it.state)} ${it.progress}%"
        } ?: "当前无进行中任务"
        val resultLine = latest?.let { "最近：${it.title.ifBlank { "任务" }} · ${statusLabel(it.state)}" } ?: "暂无最近结果"
        val attentionLine = if (snapshot.pendingAttention().isEmpty()) "无待确认" else "待确认 ${snapshot.pendingAttention().size}"
        focusSnapshotText.text = listOf(
            "$connection · ${snapshot.summary.providerName.ifBlank { snapshot.summary.providerId }}",
            taskLine,
            "$attentionLine · $resultLine"
        ).joinToString("\n")
    }

    private fun readCompanionStagePreferences(): CompanionStagePreferences {
        val prefs = getSharedPreferences("companion_stage", Context.MODE_PRIVATE)
        return CompanionStagePreferences(
            quietMode = prefs.getBoolean("quiet_mode", false),
            reduceMotion = prefs.getBoolean("reduce_motion", false),
            oneHanded = prefs.getBoolean("one_handed", false),
            ambientSound = prefs.getBoolean("ambient_sound", false),
        )
    }

    private fun readCompanionStageDecorations(): List<StageDecoration> = runCatching {
        val json = org.json.JSONArray(
            getSharedPreferences("companion_stage", Context.MODE_PRIVATE).getString("decorations", "[]")
        )
        List(json.length()) { index ->
            json.optJSONObject(index)?.let {
                StageDecoration(it.optString("id"), it.optString("name"))
            } ?: StageDecoration("", "")
        }.filter { it.id.isNotBlank() && it.name.isNotBlank() }.take(4)
    }.getOrDefault(emptyList())

    private fun saveCompanionStagePreferences(value: CompanionStagePreferences) {
        stagePreferences = value
        getSharedPreferences("companion_stage", Context.MODE_PRIVATE).edit()
            .putBoolean("quiet_mode", value.quietMode)
            .putBoolean("reduce_motion", value.reduceMotion)
            .putBoolean("one_handed", value.oneHanded)
            .putBoolean("ambient_sound", value.ambientSound)
            .apply()
        applyCompanionStagePreferences()
    }

    private fun applyCompanionStagePreferences(updateVoiceService: Boolean = true) {
        if (::focusToolbar.isInitialized) {
            focusToolbar.layoutParams = focusToolbar.layoutParams.apply {
                height = dp(if (stagePreferences.oneHanded) 56 else 36)
            }
            focusToolsToggle.minimumWidth = dp(if (stagePreferences.oneHanded) 52 else 34)
            focusToolsToggle.minimumHeight = dp(if (stagePreferences.oneHanded) 52 else 36)
            listOf(
                focusCameraButton, focusLensButton, focusListenButton, focusVoiceButton,
                focusMemoryButton, focusGameButton, focusRealityButton, focusExploreLogButton, focusCommandButton, focusStageButton,
                focusRoutinesButton, focusGoalsButton
            ).forEach { it.minimumHeight = dp(if (stagePreferences.oneHanded) 52 else 36) }
            if (stagePreferences.oneHanded && immersiveMode) {
                focusToolsExpanded = true
                immersiveShellCoordinator.openDrawer()
            }
            renderFocusTools()
        }
        if (::companionView.isInitialized) renderPet()
        updateAmbientSound()
        if (updateVoiceService && BridgeService.isRunning) {
            startService(
                Intent(this, BridgeService::class.java)
                    .setAction(BridgeService.ACTION_SET_QUIET_MODE)
                    .putExtra(BridgeService.EXTRA_QUIET_MODE, stagePreferences.quietMode)
            )
        }
    }

    private fun updateAmbientSound() {
        if (!::ambientSoundController.isInitialized) return
        val shouldPlay = StageAudioPolicy.shouldPlayAmbient(
            enabled = stagePreferences.ambientSound,
            quietMode = stagePreferences.quietMode,
            immersiveVisible = immersiveMode && !isFinishing,
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
        )
        if (shouldPlay) ambientSoundController.start() else ambientSoundController.stop()
    }

    private fun showCompanionStageSettings() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        content.addView(TextView(this).apply {
            text = "让舞台按你的习惯安静或更容易单手操作；家园摆件会显示在伙伴身边。"
            setTextColor(0xFFB8C8D8.toInt())
            textSize = 14f
        })
        fun settingSwitch(label: String, checked: Boolean, update: (Boolean) -> CompanionStagePreferences) {
            content.addView(androidx.appcompat.widget.SwitchCompat(this).apply {
                text = label
                isChecked = checked
                setPadding(0, dp(8), 0, dp(8))
                setOnCheckedChangeListener { _, enabled -> saveCompanionStagePreferences(update(enabled)) }
            })
        }
        settingSwitch("安静模式（保留文字提醒，停止语音和环境声）", stagePreferences.quietMode) {
            stagePreferences.copy(quietMode = it)
        }
        settingSwitch("减弱角色动画", stagePreferences.reduceMotion) {
            stagePreferences.copy(reduceMotion = it)
        }
        settingSwitch("单手布局（放大并展开底部工具）", stagePreferences.oneHanded) {
            stagePreferences.copy(oneHanded = it)
        }
        settingSwitch("环境声（本地生成，默认关闭）", stagePreferences.ambientSound) {
            stagePreferences.copy(ambientSound = it)
        }
        AlertDialog.Builder(this)
            .setTitle("舞台与家园")
            .setView(content)
            .setNeutralButton("布置家园") { _, _ -> openHabitatDecorationPicker() }
            .setPositiveButton("完成", null)
            .show()
    }

    private fun openHabitatDecorationPicker() {
        if (!BridgeLink.isOnline) {
            Toast.makeText(this, "家园摆件需要连接节点后布置；已布置的摆件仍会显示。", Toast.LENGTH_LONG).show()
            return
        }
        workspaceRequest(
            "/api/reality/catalog",
            onSuccess = { catalogResponse ->
                val catalog = catalogResponse.optJSONObject("catalog")?.optJSONArray("decorations")
                    ?: org.json.JSONArray()
                workspaceRequest(
                    "/api/reality/state",
                    onSuccess = { stateResponse ->
                        val habitat = stateResponse.optJSONObject("state")?.optJSONObject("habitat")
                        val placedIds = habitat?.optJSONArray("decorations") ?: org.json.JSONArray()
                        val placed = (0 until placedIds.length()).map { placedIds.optString(it) }.toSet()
                        val available = (0 until catalog.length()).mapNotNull { index ->
                            catalog.optJSONObject(index)?.let {
                                StageDecoration(it.optString("id"), it.optString("name"))
                            }
                        }.filter { it.id.isNotBlank() && it.name.isNotBlank() && it.id !in placed }
                        if (available.isEmpty()) {
                            Toast.makeText(this, "家园已布置所有可用摆件。", Toast.LENGTH_SHORT).show()
                        } else {
                            AlertDialog.Builder(this)
                                .setTitle("选择一个家园摆件")
                                .setItems(available.map { it.name }.toTypedArray()) { _, index ->
                                    val selected = available[index]
                                    workspaceRequest(
                                        "/api/reality/habitat",
                                        "PATCH",
                                        JSONObject().put("decorationId", selected.id),
                                        onSuccess = {
                                            refreshStageHabitat()
                                            say("${selected.name}已经安放在家园里了。")
                                        },
                                        onError = { error -> Toast.makeText(this, "布置失败：$error", Toast.LENGTH_LONG).show() },
                                    )
                                }
                                .setNegativeButton("取消", null)
                                .show()
                        }
                    },
                    onError = { error -> Toast.makeText(this, "家园读取失败：$error", Toast.LENGTH_LONG).show() },
                )
            },
            onError = { error -> Toast.makeText(this, "摆件目录读取失败：$error", Toast.LENGTH_LONG).show() },
        )
    }

    private fun refreshStageHabitat() {
        if (!BridgeLink.isOnline) return
        workspaceRequest("/api/reality/catalog", onSuccess = { catalogResponse ->
            val catalog = catalogResponse.optJSONObject("catalog")?.optJSONArray("decorations")
                ?: return@workspaceRequest
            val names = (0 until catalog.length()).mapNotNull { index ->
                catalog.optJSONObject(index)?.let { it.optString("id") to it.optString("name") }
            }.toMap()
            workspaceRequest("/api/reality/state", onSuccess = { stateResponse ->
                val ids = stateResponse.optJSONObject("state")?.optJSONObject("habitat")
                    ?.optJSONArray("decorations") ?: org.json.JSONArray()
                val values = (0 until ids.length()).mapNotNull { index ->
                    val id = ids.optString(index)
                    names[id]?.takeIf { id.isNotBlank() && it.isNotBlank() }?.let { StageDecoration(id, it) }
                }.take(4)
                stageDecorations = values
                val serialized = org.json.JSONArray().apply {
                    values.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) }
                }
                getSharedPreferences("companion_stage", Context.MODE_PRIVATE).edit()
                    .putString("decorations", serialized.toString()).apply()
                renderPet()
            })
        })
    }

    private fun renderFocusTools() {
        if (!immersiveMode) return
        focusToolScroll.visibility = if (focusToolsExpanded) View.VISIBLE else View.GONE
        focusToolsToggle.text = if (focusToolsExpanded) "▾" else "▸"
        focusToolsToggle.contentDescription =
            if (focusToolsExpanded) "折叠沉浸工具栏" else "展开沉浸工具栏"
        focusCameraButton.text = getString(if (isCameraActive()) R.string.stop_camera else R.string.start_camera)
        focusListenButton.alpha = if (continuousListening || micRunning || pttActive) 1f else .68f
        focusVoiceButton.alpha = if (BridgeService.isVoiceChatRunning) 1f else .68f
        renderCompanionSessionSnapshot()
    }

    private fun renderPet() {
        val online = BridgeLink.isOnline
        val now = System.currentTimeMillis()
        val absentHours = ((now - pet.lastInteractionMs).coerceAtLeast(0L)) / 3_600_000f
        val energy = pet.normalizedEnergy()
        val emotion = PetEmotion(
            joy = (
                pet.affection / 100f * .38f +
                    energy * .18f +
                    min(activeTasks.size, 3) / 3f * .16f +
                    if (now - pet.lastInteractionMs in 0..(15 * 60_000L)) .22f else 0f
                ).coerceIn(.04f, .96f),
            tension = (
                (if (speaking) .46f else 0f) +
                    (if (pttActive) .28f else 0f) +
                    (if (cameraRunning) .10f else 0f) +
                    (if (!online) .12f else 0f)
                ).coerceIn(.06f, .90f),
            fatigue = (((1f - energy) * .72f) + (absentHours.coerceAtMost(8f) / 8f) * .18f)
                .coerceIn(.05f, .92f),
            loneliness = ((absentHours.coerceAtMost(6f) / 6f) * .58f + if (!online) .20f else 0f)
                .coerceIn(.04f, .94f),
            attention = (
                (if (speaking || pttActive || continuousListening || micRunning) .58f else 0f) +
                    min(activeTasks.size, 2) / 2f * .24f +
                    energy * .14f
                ).coerceIn(.08f, .96f)
        )
        val moodScores = listOf(
            emotion.joy to PetMood.HAPPY,
            emotion.tension to PetMood.ALERT,
            emotion.fatigue to PetMood.SLEEPY,
            emotion.loneliness to PetMood.LONELY,
            emotion.attention to PetMood.CURIOUS,
            (.52f - maxOf(
                emotion.joy, emotion.tension, emotion.fatigue,
                emotion.loneliness, emotion.attention
            )).coerceAtLeast(0f) to PetMood.CALM
        )
        val mood = moodScores.maxByOrNull { it.first }?.second ?: PetMood.CALM
        pet = pet.copy(mood = mood, connected = online, cameraActive = isCameraActive(), listening = micRunning, activeTasks = activeTasks.size, emotion = emotion)
        val behavior = MoteBehaviorEngine.resolve(
            MoteProfiles.profile(pet.appearance),
            MoteBehaviorInput(
                taskState = activeTasks.values.firstOrNull()?.status,
                deviceHealth = deviceHealthState.overall.name,
                interaction = when { speaking -> "chat"; pttActive -> "ptt"; else -> "ambient" },
                explorationProgress = (moteStateJson.optJSONObject("exploration")?.optJSONObject("fragments")?.let { fragments ->
                    listOf("location", "object", "light").count { RealityClueProtocol.booleanField(fragments.opt(it)) }
                } ?: 0),
                relationshipLevel = moteRelationship.level,
                emotion = pet.emotion
            )
        )
        companionView.setBehaviorHint(behavior)
        realityLensView.setBehaviorHint(behavior)
        realityLensView.setPerformanceState(
            fps = fpsCounter.get().takeIf { it > 0 }?.toFloat() ?: 30f,
            temperatureCelsius = latestTelemetry?.batteryTemperature ?: 25f
        )
        companionView.update(pet)
        val nowWallMs = System.currentTimeMillis()
        val interactionAgeMs = (nowWallMs - pet.lastInteractionMs).takeIf { pet.lastInteractionMs > 0L && it in 0L..30_000L }
        val stageState = CompanionStageEngine.resolve(
            CompanionStageInput(
                hourOfDay = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY),
                energy = pet.energy,
                activeTasks = activeTasks.count { !it.value.status.equals("done", true) },
                observingReality = realityLensActive || cameraRunning,
                recentInteractionAgeMs = interactionAgeMs,
                quietMode = stagePreferences.quietMode,
                reduceMotion = stagePreferences.reduceMotion,
                nowMs = nowWallMs,
                message = stageMessage,
                messageExpiresAtMs = stageMessageExpiresAtMs,
                decorations = stageDecorations,
            )
        )
        if (stageMessageExpiresAtMs <= nowWallMs) stageMessage = null
        companionView.setStageState(stageState)
        val moment = if (moteMomentExpiresAtMs > nowWallMs) activeMoteMoment else MoteMoment.IDLE
        companionView.setCharacterCue(MoteCharacterizationEngine.resolve(pet.appearance, moment, moteRelationship.level))
        renderFocusTools()
        linkMetric.text = if (online) "链路 在线" else "链路 离线"
        audioMetric.text = when {
            pttActive -> "音频 对讲"
            continuousListening -> "音频 监听"
            micRunning -> "音频 开"
            else -> "音频 关"
        }
        fpsMetric.text = "画面 ${fpsCounter.get()}fps"
        taskMetric.text = "任务 ${activeTasks.count { !it.value.status.equals("done", true) }}"
        petLevelChip.text = "Lv.${pet.level}"
        petEnergyChip.text = "能量 ${pet.energy}%"
        petAffectionChip.text = "好感 ${pet.affection}"
        petGrowthChip.text = "经验 ${pet.experience}/${pet.level * 45} · 技能 ${pet.level}"
        statusChip.setTextColor(if (online) activeTheme.accent else activeTheme.secondary)
        renderCameraHeroState()
        renderAiAuthorizationStatus()
        renderAiPolicyState()
        renderCockpitSummary()
        publishSensorState()
        persistWidgetSnapshot()
    }

    private fun persistWidgetSnapshot() {
        getSharedPreferences("mote_widget", Context.MODE_PRIVATE).edit()
            .putBoolean("connected", BridgeLink.isOnline)
            .putBoolean("cameraActive", isCameraActive())
            .putBoolean("listening", micRunning)
            .putInt("fps", fpsCounter.get())
            .putInt("activeTasks", activeTasks.count { !it.value.status.equals("done", true) })
            .putInt("battery", latestTelemetry?.batteryPercent ?: 0)
            .putFloat("temperature", latestTelemetry?.batteryTemperature ?: 0f)
            .putInt("summaryTotalTasks", companionSummary.totalTasks)
            .putInt("summaryRunningTasks", companionSummary.runningTasks)
            .putInt("summaryAttention", companionSummary.openAttention)
            .putInt("summaryRealityEvents", companionSummary.realityEvents)
            .putBoolean("summaryOnline", companionSummary.connectionOnline)
            .putLong("updated", System.currentTimeMillis())
            .apply()
    }

    private fun petJson(): JSONObject = JSONObject()
        .put("name", pet.name)
        .put("level", pet.level)
        .put("experience", pet.experience)
        .put("energy", pet.energy)
        .put("affection", pet.affection)
        .put("careStreak", pet.careStreak)
        .put("totalCares", pet.totalCares)
        .put("mood", pet.mood.name)

    private fun loadPet() {
        val prefs = getSharedPreferences("mote_pet", Context.MODE_PRIVATE)
        lastCodexTaskId = prefs.getString("last_codex_task_id", "") ?: ""
        lastCodexProgress = if (lastCodexTaskId.isBlank()) -1 else prefs.getInt("last_codex_progress", -1)
        val hours = (System.currentTimeMillis() - prefs.getLong("last_seen", System.currentTimeMillis())) / 3_600_000f
        val energy = (prefs.getInt("energy", 82) - (hours * 3).toInt()).coerceIn(8, 100)
        val affection = (prefs.getInt("affection", 40) - (hours * 1.5f).toInt()).coerceIn(15, 100)
        val absentHours = (System.currentTimeMillis() - prefs.getLong("lastInteractionMs", System.currentTimeMillis())) / 3_600_000f
        pet = PetState(
            name = prefs.getString("name", "Mote") ?: "Mote",
            level = prefs.getInt("level", 1),
            experience = prefs.getInt("experience", 0),
            energy = energy,
            affection = affection,
            mood = when {
                absentHours >= 8f -> PetMood.LONELY
                else -> runCatching { PetMood.valueOf(prefs.getString("mood", "CURIOUS") ?: "CURIOUS") }
                    .getOrDefault(PetMood.CURIOUS)
            },
            lastInteractionMs = prefs.getLong("lastInteractionMs", 0L),
            totalCares = prefs.getInt("totalCares", 0),
            careStreak = prefs.getInt("careStreak", 0),
            lastCareDay = prefs.getString("lastCareDay", "") ?: "",
            successfulTasks = prefs.getInt("successfulTasks", 0),
            failedTasks = prefs.getInt("failedTasks", 0),
            appearance = runCatching { PetAppearance.valueOf(prefs.getString("appearance", "MOTE") ?: "MOTE") }
            .getOrDefault(PetAppearance.MOTE)
        )
    }

    private fun saveCodexProgressMarker() {
        getSharedPreferences("mote_pet", Context.MODE_PRIVATE).edit()
            .putString("last_codex_task_id", lastCodexTaskId)
            .putInt("last_codex_progress", lastCodexProgress)
            .apply()
    }

    private fun savePet() {
        getSharedPreferences("mote_pet", Context.MODE_PRIVATE).edit()
            .putString("name", pet.name)
            .putInt("level", pet.level)
            .putInt("experience", pet.experience)
            .putInt("energy", pet.energy)
            .putInt("affection", pet.affection)
            .putInt("totalCares", pet.totalCares)
            .putInt("careStreak", pet.careStreak)
            .putString("lastCareDay", pet.lastCareDay)
            .putLong("lastInteractionMs", pet.lastInteractionMs)
            .putInt("successfulTasks", pet.successfulTasks)
            .putInt("failedTasks", pet.failedTasks)
            .putString("appearance", pet.appearance.name)
            .putString("mood", pet.mood.name)
            .putLong("last_seen", System.currentTimeMillis())
            .apply()
        MoteWidget.refresh(this)
    }

    private fun askForServer() {
        val input = EditText(this)
        input.setText(savedServer())
        val tokenInput = EditText(this)
        tokenInput.hint = "访问令牌（可选）"
        tokenInput.setText(savedAccessToken())
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(input)
            addView(tokenInput)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.server_dialog_title)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                val value = input.text.toString().trim()
                if (value.isNotBlank()) {
                    disconnect()
                    saveServer(value)
                    saveAccessToken(tokenInput.text.toString().trim())
                    connectSavedServer()
                }
            }
            .setNeutralButton(if (pairingScanActive) "取消扫码" else "扫码配对") { _, _ ->
                if (pairingScanActive) cancelPairingScan() else beginPairingScan()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun beginPairingScan() {
        if (pairingScanActive) return
        pairingScanActive = true
        pairingScanOwnsCamera = !cameraRunning
        pairingScanLastFrameAt = 0L
        setStatus("正在扫码 · 画面仅在本机识别，不会上传")
        Toast.makeText(this, "将二维码对准手机镜头；再次打开节点设置可取消扫码", Toast.LENGTH_LONG).show()
        if (!hasCameraPermission()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.CAMERA),
                REQUEST_CAMERA_PERMISSION
            )
        } else if (!cameraRunning) {
            startCamera()
        }
    }

    private fun cancelPairingScan() {
        val stopOwnedCamera = pairingScanOwnsCamera
        pairingScanActive = false
        pairingScanOwnsCamera = false
        if (stopOwnedCamera && (cameraRunning || cameraStartPending)) stopCamera()
        else setStatus(if (cameraRunning) "眼睛开启" else "扫码已取消")
    }

    private fun receivePairingQr(payload: String) {
        val offer = runCatching { PairingProtocol.fromQrPayload(payload) }.getOrElse {
            setStatus("二维码格式无效")
            Toast.makeText(this, "无法识别 PhoneBridge 配对二维码", Toast.LENGTH_LONG).show()
            return
        }
        if (offer.version != 2) {
            setStatus("配对二维码版本过旧")
            AlertDialog.Builder(this)
                .setTitle("需要重新配对")
                .setMessage("此二维码使用旧版配对格式。请在 PhoneBridge 网页重新生成 v2 二维码。")
                .setPositiveButton("知道了", null)
                .show()
            return
        }
        if (!offer.isUsable(System.currentTimeMillis())) {
            setStatus("配对二维码已过期或校验失败")
            Toast.makeText(this, "二维码过期或缺少有效的 HTTPS/WSS 证书指纹，请重新生成", Toast.LENGTH_LONG).show()
            return
        }
        val fingerprint = offer.fingerprint ?: "本机回环连接"
        AlertDialog.Builder(this)
            .setTitle("确认安全配对")
            .setMessage("目标节点：${offer.endpointUrl()}\n证书指纹：$fingerprint\n\n仅在你信任此节点时继续。令牌将在证书校验通过并领取成功后保存。")
            .setPositiveButton("验证并配对") { _, _ -> claimPairingOffer(offer) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun claimPairingOffer(offer: PairingOffer) {
        setStatus("正在验证证书并领取一次性令牌")
        appScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { PairingClaimClient().claim(offer) }
            }
            val token = result.getOrNull()
            if (token == null) {
                val message = result.exceptionOrNull()?.message ?: "安全配对失败，请重新生成二维码。"
                setStatus("配对失败")
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                return@launch
            }

            val preferences = getSharedPreferences("phonebridge", Context.MODE_PRIVATE)
            val previousToken = savedAccessToken()
            val previousServer = preferences.getString("server", null)
            val previousFingerprint = preferences.getString("server_fingerprint", null)
            val previousAutoConnect = preferences.getBoolean("auto_connect", false)
            try {
                secureTokenStore.put(token)
                val editor = preferences.edit()
                    .putString("server", offer.endpointUrl())
                    .putBoolean("auto_connect", true)
                if (offer.fingerprint == null) editor.remove("server_fingerprint")
                else editor.putString("server_fingerprint", offer.fingerprint)
                check(editor.commit()) { "配对设置无法写入本机存储" }
            } catch (error: Exception) {
                preferences.edit()
                    .putString("server", previousServer)
                    .putString("server_fingerprint", previousFingerprint)
                    .putBoolean("auto_connect", previousAutoConnect)
                    .commit()
                runCatching { secureTokenStore.put(previousToken) }
                setStatus("配对凭据保存失败")
                Toast.makeText(this@MainActivity, "凭据未能安全保存；请重新生成二维码后重试", Toast.LENGTH_LONG).show()
                Log.w(TAG, "pairing credential persistence failed", error)
                return@launch
            }

            disconnect()
            connectSavedServer()
            setStatus("安全配对完成 · 正在连接")
        }
    }

    private fun startTelemetryLoop() {
        telemetryJob?.cancel()
        telemetryJob = appScope.launch {
            while (isActive) {
                val sensorsIdle = !cameraRunning && !micRunning && !pttActive
                delay(if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || !sensorsIdle) 2500L else 15000L)
                runCatching { refreshTelemetry() }.onFailure { Log.w(TAG, "telemetry failed", it) }
            }
        }
        appScope.launch {
            while (isActive) {
                delay(1000)
                renderPet()
                fpsCounter.set(0)
            }
        }
    }

    private fun refreshTelemetry(forceUiUpdate: Boolean = false) {
        appScope.launch {
            val sample = runCatching {
                withContext(Dispatchers.IO) { DeviceTelemetry.sample(this@MainActivity) }
            }.getOrElse {
                Log.w(TAG, "sensor sample failed", it)
                return@launch
            }
            if (forceUiUpdate || sensorsPanel.visibility == View.VISIBLE) {
                sensorText.text = sample.summary()
            }
            latestTelemetry = sample
            latestTelemetrySampledAtMs = SystemClock.elapsedRealtime()
            persistWidgetSnapshot()
            MoteWidget.refresh(this@MainActivity)
            sendJson(
                JSONObject()
                    .put("type", "telemetry")
                    .put("cpu", sample.cpuLoad)
                    .put("memory", sample.memoryPercent)
                    .put("battery", sample.batteryPercent)
                    .put("temperature", sample.batteryTemperature)
                    .put("network_rx", sample.networkRxMb)
                    .put("network_tx", sample.networkTxMb)
            )
        }
    }

    private fun Throwable.rootMessage(): String {
        var current: Throwable = this
        while (current.cause != null && current.cause !== current) current = current.cause!!
        return current.message ?: current.javaClass.simpleName
    }

    private fun currentTime(): String =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

    private fun startResident() {
        ContextCompat.startForegroundService(this, Intent(this, BridgeService::class.java))
        residentButton.alpha = 1f
        logAdapter.add("success", getString(R.string.resident_on))
    }

    private fun stopResident() {
        startService(Intent(this, BridgeService::class.java).setAction("stop"))
        residentButton.alpha = .68f
        logAdapter.add("warn", getString(R.string.resident_off))
    }

    private fun toggleResident() {
        if (BridgeService.isRunning) {
            stopResident()
            say("我先休息了。")
        } else {
            startResident()
            say("我会一直守着链路。")
        }
    }

    override fun onCompanionTouched(x: Float, y: Float) {
        interact(if (Random.nextInt(4) == 0) "play" else "stroke")
    }

    override fun onDecorationTouched(decorationId: String) {
        val decoration = stageDecorations.firstOrNull { it.id == decorationId } ?: return
        companionView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        setMoteMoment(MoteMoment.TOUCH)
        val cue = MoteCharacterizationEngine.resolve(pet.appearance, MoteMoment.TOUCH, moteRelationship.level)
        say("我喜欢这里的${decoration.name}。${cue.line}")
    }

    override fun onCompanionLongPressed() {
        setPtt(true)
    }

    override fun onCompanionLongPressReleased() {
        setPtt(false)
    }

    override fun onPause() {
        if (::arCoreRenderView.isInitialized) arCoreRenderView.onHostPause()
        ambientSoundController.stop()
        super.onPause()
        savePet()
        // A detached SurfaceView blocks CameraX session configuration when the
        // screen is off. Keep only the remote analysis stream in that state.
        cameraPreview?.let { preview -> runCatching { cameraProvider?.unbind(preview) } }
        cameraPreview = null
    }

    override fun onStop() {
        if (immersiveMode) {
            saveImmersiveSurface(if (realityLensActive) ImmersiveSurface.REALITY else ImmersiveSurface.COMPANION)
        }
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && immersiveMode) hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        updateAmbientSound()
        refreshExplorationLog()
        if (::arCoreRenderView.isInitialized) {
            arCoreRenderView.onHostResume()
            handleArCoreInstallResume()
        }
        if (cameraRunning) {
            cameraRunning = false
            startCamera()
        }
        if (BridgeLink.isOnline) drainChatOutbox()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent?.dataString?.takeIf { it.isNotBlank() }?.let { deepLink ->
            pendingDeepLink = deepLink
            if (immersiveMode && immersiveShellCoordinator.openDeepLink(deepLink)) {
                focusToolsExpanded = true
                renderFocusTools()
                pendingDeepLink = null
            } else if (!immersiveMode) {
                rootLayout.post { enterAdaptiveImmersiveMode() }
            }
        }
        intent?.getStringExtra("auto_care")?.takeIf { it.isNotBlank() }?.let { interact(it) }
        intent?.getStringExtra("server_url")?.takeIf { it.isNotBlank() }?.let { url ->
            saveServer(url)
            disconnect()
            connectSavedServer()
        }
        intent?.getStringExtra("auto_command")?.takeIf { it.isNotBlank() }?.let { command ->
            logAdapter.add("info", "自动指令：$command")
            sendJson(JSONObject().put("type", "command").put("text", command))
        }
        intent?.getStringExtra("access_token")?.let { saveAccessToken(it) }
    }

    override fun onDestroy() {
        if (::ambientSoundController.isInitialized) ambientSoundController.close()
        runCatching { unregisterReceiver(exitAppReceiver) }
        runCatching { unregisterReceiver(voiceStateReceiver) }
        savePet()
        releaseSensorHardware()
        DeviceCommandBus.setReceiver(null)
        destroyed = true
        telemetryJob?.cancel()
        client.dispatcher.executorService.shutdown()
        analysisExecutor.shutdown()
        networkExecutor.shutdown()
        speechExecutor.shutdown()
        appScope.cancel()
        if (!BridgeService.isRunning) {
            autoReconnect = false
            disconnect()
        }
        super.onDestroy()
    }

    private fun releaseSensorHardware() {
        if (::arCoreRenderView.isInitialized) {
            arCoreRenderView.shutdownSessionCreation()
            arCoreRenderView.setListener(null)
        }
        cameraRunning = false
        continuousListening = false
        pttActive = false
        micRunning = false
        audioThread = null
        audioRecord = null
        cameraPreview = null
        runCatching { cameraProvider?.unbindAll() }
        cameraProvider = null
        realityThermalHandler.removeCallbacks(realityThermalMonitor)
    }
}
