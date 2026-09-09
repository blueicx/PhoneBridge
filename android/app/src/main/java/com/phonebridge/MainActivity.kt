package com.phonebridge

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.content.res.ColorStateList
import android.graphics.Matrix
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlin.random.Random

class MainActivity : AppCompatActivity(), CompanionView.Listener, BridgeLink.Listener {

    companion object {
        private const val TAG = "PhoneBridge"
        private const val REQUEST_PERMISSIONS = 71
        private const val TYPE_FRAME = 1
        private const val TYPE_AUDIO = 2
        private const val TYPE_SPEAK = 5
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
    private lateinit var focusToolScroll: HorizontalScrollView
    private lateinit var focusToolsToggle: Button
    private lateinit var focusCameraButton: Button
    private lateinit var focusLensButton: Button
    private lateinit var focusListenButton: Button
    private lateinit var focusVoiceButton: Button
    private lateinit var focusMemoryButton: Button
    private lateinit var focusGameButton: Button
    private lateinit var focusRealityButton: Button
    private lateinit var focusCommandButton: Button
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
    private var workspaceRevision: Long = 0L
    private val workspaceEventGate = WorkspaceEventGate()
    private var cockpitUsesOfflineMirror = false
    private var cockpitSummaryExpanded = false
    private var workspaceEmergencyState: JSONObject? = null
    private var deviceHealthState = DeviceHealthState()
    private val workspaceClient = WorkspaceClient()
    private val workspaceRepository by lazy { WorkspaceRepository.get(this) }
    private val appearanceButtons = mutableMapOf<PetAppearance, Button>()
    private val themeButtons = mutableMapOf<UiTheme, Button>()
    private lateinit var themeApplier: ThemeApplier
    private var activeTheme = UiTheme.AURORA_GLASS
    private val chatAdapter = ChatAdapter()
    private var pendingVoiceCommand = false
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
    private var realityLensRequestedCamera = false
    private var normalPreviewParams: androidx.constraintlayout.widget.ConstraintLayout.LayoutParams? = null
    private var focusToolsExpanded = false
    private var normalHeroParams: androidx.constraintlayout.widget.ConstraintLayout.LayoutParams? = null
    private lateinit var focusBackCallback: OnBackPressedCallback
    private var pendingAutoCommand: String? = null
    private var streamingChatId = ""
    private val streamingText = StringBuilder()
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
                speaking = intent.getBooleanExtra(BridgeService.EXTRA_VOICE_SPEAKING, false)
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
        WindowCompat.setDecorFitsSystemWindows(window, true)
        focusBackCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                if (aiSpacePanel.visibility == View.VISIBLE) {
                    closeAiSpace()
                    return
                }
                if (dismissFocusKeyboard()) return
                if (realityLensActive) {
                    exitRealityLens()
                    return
                }
                exitFocusMode()
            }
        }
        onBackPressedDispatcher.addCallback(this, focusBackCallback)
        setupThemes()
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
        intent?.getStringExtra("access_token")?.let { saveAccessToken(it) }
        val autoConnect = intent?.getStringExtra("server_url")?.isNotBlank() == true ||
                getSharedPreferences("phonebridge", Context.MODE_PRIVATE).getBoolean("auto_connect", true)

        DeviceCommandBus.setReceiver { action -> runOnUiThread { applyDeviceCommand(action) } }
        if (hasPermissions()) {
            startResident()
            if (autoConnect) connectSavedServer()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO),
                REQUEST_PERMISSIONS
            )
            startResident()
        }
        renderPet()
        startTelemetryLoop()
        pendingAutoCare?.let { kind -> interact(kind) }
        pendingAutoCare = null
        // 首屏固定为伙伴驾驶舱；沉浸模式由用户通过明确按钮或手势进入。
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
        realityLensView.contentDescription = "现实镜头，三个可探索线索"
        realityLensView.setDiscovered(loadDiscoveredRealityNodes())
        realityLensView.setPetState(pet)
        realityLensView.setListener(object : RealityLensView.Listener {
            override fun onNodeTapped(node: RealityLensView.LensNode) {
                handleRealityNode(node)
            }
            override fun onPetTapped(pet: PetState) {
                handleRealityPetTapped()
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
        focusToolScroll = findViewById(R.id.focusToolScroll)
        focusToolsToggle = findViewById(R.id.focusToolsToggle)
        focusCameraButton = findViewById(R.id.focusCameraButton)
        focusLensButton = findViewById(R.id.focusLensButton)
        focusListenButton = findViewById(R.id.focusListenButton)
        focusVoiceButton = findViewById(R.id.focusVoiceButton)
        focusMemoryButton = findViewById(R.id.focusMemoryButton)
        focusGameButton = findViewById(R.id.focusGameButton)
        focusRealityButton = findViewById(R.id.focusRealityButton)
        focusCommandButton = findViewById(R.id.focusCommandButton)
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
        cockpitSummaryStatus.text = buildList {
            add(if (cockpitUsesOfflineMirror) "离线镜像" else "链路 ${deviceHealthLabel(deviceHealthState.overall.name)}")
            add("${activeCount} 条提醒")
            add(if (activeWorkspaceTask == null) "无进行中任务" else "有进行中任务")
            add("Mote Lv.${moteRelationship.level}")
        }.joinToString(" · ")
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
                if (aiSelectedSessionId.isBlank()) {
                    aiSelectedSessionId = sessions.firstOrNull()?.id.orEmpty()
                }
                rebuildAttentionItems(attentionJson)
                renderAttentionCenter()
                renderCockpitSummary()
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
        cameraButton.setOnClickListener { if (cameraRunning) stopCamera() else startCameraOrReportPermissions() }
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
            focusToolsExpanded = !focusToolsExpanded
            renderFocusTools()
        }
        focusCameraButton.setOnClickListener { if (cameraRunning) stopCamera() else startCameraOrReportPermissions() }
        focusLensButton.setOnClickListener { flipLens() }
        focusListenButton.setOnClickListener { toggleListening() }
        focusVoiceButton.setOnClickListener { toggleContinuousVoice() }
        focusMemoryButton.setOnClickListener { showMemoryDialog() }
        focusGameButton.setOnClickListener { showSignalGameDialog() }
        focusRealityButton.setOnClickListener { enterRealityLens() }
        focusCommandButton.setOnClickListener {
            exitFocusMode()
            commandInput.requestFocus()
            say("回到工作台，可以直接输入指令。")
        }
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
        findViewById<Button>(R.id.sendChat).setOnClickListener { submitChat() }
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
        if (requestCode != REQUEST_PERMISSIONS) return
        if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            connectSavedServer()
            setStatus("权限已准备，按需打开相机或麦克风")
            renderCameraHeroState()
        } else {
            setStatus("权限不足，Mote 看不见也听不见")
            say("我需要摄像头和耳朵。")
        }
    }

    private fun hasPermissions(): Boolean = arrayOf(
        android.Manifest.permission.CAMERA,
        android.Manifest.permission.RECORD_AUDIO
    ).all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun startCameraOrReportPermissions() {
        if (!hasPermissions()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO),
                REQUEST_PERMISSIONS
            )
            return
        }
        startCamera()
    }

    private fun applyDeviceCommand(action: String) {
        Log.i(TAG, "Device command received: $action listening=$continuousListening ptt=$pttActive mic=$micRunning")
        when (action) {
            "camera_on" -> startCameraOrReportPermissions()
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
        val session = cameraSession.incrementAndGet()
        cameraStartPending = true
        renderCameraHeroState()
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                if (session != cameraSession.get() || cameraRunning) return@addListener
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
                provider.bindToLifecycle(BridgeService.lifecycleOwner, selector, *useCases.toTypedArray())
                if (session != cameraSession.get()) {
                    runCatching { provider.unbindAll() }
                    return@addListener
                }
                cameraRunning = true
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                cameraButton.text = getString(R.string.stop_camera)
                renderCameraHeroState()
                renderPet()
                publishSensorState(force = true)
                setStatus(if (!BridgeLink.isOnline) "眼睛开启 · 未连节点" else "眼睛开启 · 在线")
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
        cameraRunning = false
        cameraSession.incrementAndGet()
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        cameraPreview = null
        runCatching { cameraProvider?.unbindAll() }
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

    private fun enterRealityLens() {
        if (!immersiveMode || realityLensActive) return
        realityLensActive = true
        realityLensRequestedCamera = !cameraRunning

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
        realityLensView.visibility = View.VISIBLE
        renderCameraHeroState()
        renderFocusTools()
        findViewById<View>(R.id.previewFrame).post {
            if (realityLensActive) startCameraOrReportPermissions()
        }
        say("现实镜头开启，和我一起找线索。")
    }

    private fun exitRealityLens() {
        if (!realityLensActive) return
        realityLensActive = false

        val frame = findViewById<View>(R.id.previewFrame)
        normalPreviewParams?.let {
            frame.layoutParams = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(it)
        }
        realityLensView.visibility = View.GONE
        renderCameraHeroState()
        if (immersiveMode) {
            focusToolbar.visibility = View.VISIBLE
            focusSpeechLayer.visibility = View.VISIBLE
            focusSpeechScroll.visibility = View.VISIBLE
            findViewById<View>(R.id.focusInputRow).visibility = View.VISIBLE
            focusSpeechLayer.post { layoutFocusSpeechOverlay() }
        }
        renderFocusTools()

        if (realityLensRequestedCamera) {
            if (cameraRunning) stopCamera() else cameraSession.incrementAndGet()
        }
        realityLensRequestedCamera = false
        say("退出现实镜头。")
    }

    private fun handleRealityNode(node: RealityLensView.LensNode) {
        val discovered = loadDiscoveredRealityNodes()
        if (node.id in discovered) {
            say("${node.title}已经记录过了。${node.detail}")
            return
        }

        val nextDiscovered = discovered + node.id
        saveDiscoveredRealityNodes(nextDiscovered)
        realityLensView.markDiscovered(node.id)
        pet = pet.copy(experience = pet.experience + 4)
        val levelUpMessage = checkLevelUp()
        savePet()
        renderPet()
        sendJson(
            JSONObject()
                .put("type", "pet")
                .put("action", "reality_lens")
                .put("node", node.id)
                .put("reward", 4)
                .put("state", petJson())
        )
        enqueueWorkspaceEvent(
            WorkspaceEventTypes.MOTE_EXPLORATION,
            JSONObject()
                .put("eventId", "reality-${node.id}-${System.currentTimeMillis()}" )
                .put("clueType", node.id)
        )
        logAdapter.add("success", "现实线索 +4 经验：${node.title}")
        say(buildString {
            append("${node.title}已记录，获得 4 经验。")
            if (levelUpMessage.isNotBlank()) append(" $levelUpMessage")
        })
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
        getSharedPreferences("reality_lens", Context.MODE_PRIVATE)
            .getStringSet("discovered", emptySet())?.toSet() ?: emptySet()

    private fun saveDiscoveredRealityNodes(ids: Set<String>) {
        getSharedPreferences("reality_lens", Context.MODE_PRIVATE).edit()
            .putStringSet("discovered", ids)
            .apply()
    }

    private fun sendCameraFrame(image: ImageProxy) {
        try {
            if (!cameraRunning || !BridgeLink.isOnline) return
            val now = SystemClock.elapsedRealtime()
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
            val bitmap = imageToBitmap(image)
            val output = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, if (hot || lowBattery) 46 else 58, output)
            bitmap.recycle()
            sendBinary(TYPE_FRAME, output.toByteArray())
        } catch (e: Exception) {
            Log.w(TAG, "frame failed", e)
        } finally {
            image.close()
        }
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
        BridgeLink.connect(url, savedAccessToken(), this)
    }

    override fun onBridgeOpen() {
        runOnUiThread {
            setStatus("在线")
            say("链接稳定，我能看见了。")
        }
        publishSensorState(force = true)
        sendJson(JSONObject().put("type", "hello").put("pet", petJson()))
        requestSnapshot()
        flushWorkspaceOutbox()
        scheduleOutboxSync()
        drainChatOutbox()
        pendingAutoCommand?.let { command ->
            sendJson(JSONObject().put("type", "command").put("text", command))
            runOnUiThread { logAdapter.add("info", "自动指令：$command") }
            pendingAutoCommand = null
        }
        if (continuousListening || pttActive) startMicrophone()
    }

    override fun onBridgeState(state: DeviceHealthState) {
        deviceHealthState = state
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
                BridgePhase.ONLINE -> if (!cameraRunning) setStatus("在线")
            }
            renderCockpitSummary()
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
        runOnUiThread { stopCamera() }
        cleanupConnection(false)
        runOnUiThread {
            setStatus("重连中")
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
        val state = (if (cameraRunning) 2 else 0) or (if (audioActive) 1 else 0)
        if (!force && state == lastPublishedSensorState) return
        lastPublishedSensorState = state
        sendJson(
            JSONObject()
                .put("type", "sensor_state")
                .put("camera", cameraRunning)
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
            text.equals("/camera", true) -> if (cameraRunning) stopCamera() else startCameraOrReportPermissions()
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
        networkExecutor.execute {
            val result = workspaceClient.request(server, token, path, method, payload)
            runOnUiThread {
                result.onSuccess(onSuccess).onFailure { onError(it.message ?: "节点请求失败") }
            }
        }
    }

    private fun enqueueWorkspaceEvent(type: String, payload: JSONObject) {
        val meta = getSharedPreferences("workspace_meta", Context.MODE_PRIVATE)
        val sequence = meta.getLong("sequence", 0L) + 1L
        meta.edit().putLong("sequence", sequence).apply()
        val event = WorkspaceEvent(
            origin = "phone-${android.os.Build.MODEL}",
            sequence = sequence,
            type = type,
            payload = payload.toString()
        )
        appScope.launch(Dispatchers.IO) {
            workspaceRepository.enqueue(event)
            if (BridgeLink.isOnline) withContext(Dispatchers.Main) { flushWorkspaceOutbox() }
        }
    }

    private fun flushWorkspaceOutbox() {
        if (!BridgeLink.isOnline) return
        appScope.launch(Dispatchers.IO) {
            val pending = workspaceRepository.readyOutbox()
            withContext(Dispatchers.Main) {
                pending.forEach { event ->
                    if (!BridgeLink.sendWorkspaceEvent(event)) {
                        appScope.launch(Dispatchers.IO) { workspaceRepository.retry(event.eventId, retryCount = 1) }
                    }
                }
            }
        }
    }

    override fun onWorkspaceAck(eventId: String, accepted: Boolean, status: String?) {
        if ((!accepted && status != "duplicate") || eventId.isBlank()) return
        appScope.launch(Dispatchers.IO) { workspaceRepository.acknowledge(eventId) }
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
        enqueueWorkspaceEvent(
            "workspace.message",
            JSONObject().put("sessionId", aiSelectedSessionId).put("messageId", messageId).put("role", "user").put("text", text)
        )
        saveAiSessionConfig()
        workspaceRequest(
            "/api/workspace/sessions/${android.net.Uri.encode(aiSelectedSessionId)}/messages", "POST",
            JSONObject().put("id", messageId).put("role", "user").put("text", text).put("runModel", true),
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
    }

    private fun handleWorkspaceTaskEvent(task: JSONObject?) {
        if (task == null || task.optString("id").isBlank()) return
        runOnUiThread {
            val taskId = task.optString("id")
            workspaceTaskMirror[taskId] = task
            persistWorkspaceTask(task)
            rebuildAttentionItems()
            renderAttentionCenter()
            renderCockpitSummary()
            refreshAiTasks()
        }
    }

    private fun handleAttentionEvent(attention: JSONObject?) {
        if (attention == null || attention.optString("id").isBlank()) return
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
        getSharedPreferences("mote_roster", Context.MODE_PRIVATE).edit()
            .putString("roster", moteRosterJson.toString())
            .putString("state", moteStateJson.toString())
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

    private fun handleMoteRosterEvent(roster: JSONArray?, state: JSONObject?) {
        handleMoteSnapshot(JSONObject().put("roster", roster ?: moteRosterJson).put("state", state ?: moteStateJson))
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
                    updatedAt = updatedAt
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
        val text = chatInput.text.toString().trim()
        if (text.isEmpty()) return
        chatInput.setText("")
        sendChatMessage(text)
    }

    private fun sendChatMessage(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val rememberMatch = Regex("^(?:记住|記住|remember)[：:，,\\s]+(.+)$", RegexOption.IGNORE_CASE).find(clean)
        if (rememberMatch != null) {
            val fact = rememberMatch.groupValues[1].trim()
            MoteMemory.add(this, fact)
            appendChat("assistant", "已记住：$fact")
            logAdapter.add("success", "长期记忆已保存。")
            say("我记住了。")
            return
        }
        val relevantMemories = MoteMemory.relevant(this, clean, 12)
        MoteMemory.touch(this, relevantMemories)
        if (!BridgeLink.isOnline) {
            appendChat("user", clean)
            logAdapter.add("info", "你：$text")
            requestOfflineReply(clean, relevantMemories.map { it.text })
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
                    .put("text", clean)
                    .put("memories", memoryArray)
            )
        ) {
            logAdapter.add("warn", "节点连接不可用，切换离线接口。")
            requestOfflineReply(clean, relevantMemories.map { it.text })
        }
    }

    private fun showMemoryDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.memory_manager, null)
        val input = view.findViewById<EditText>(R.id.memoryInput)
        val recycler = view.findViewById<RecyclerView>(R.id.memoryRecycler)
        val adapter = MemoryAdapter {}
        adapter.setOnDelete { item ->
            MoteMemory.removeById(this, item.id)
            adapter.submit(MoteMemory.load(this))
        }
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        adapter.submit(MoteMemory.load(this))
        val container = view

        AlertDialog.Builder(this)
            .setTitle(R.string.memory_dialog_title)
            .setView(container)
            .setPositiveButton("添加") { _, _ ->
                val value = input.text.toString().trim()
                if (value.isNotBlank()) MoteMemory.add(this, value)
            }
            .setNeutralButton("清空") { _, _ -> MoteMemory.clear(this) }
            .setNegativeButton("关闭", null)
            .show()
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
        showUserMessage: Boolean = false,
        onReply: ((String) -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        if (showUserMessage) logAdapter.add("info", "你：$clean")
        networkExecutor.execute {
            val history = chatAdapterCurrentMessages()
                .dropLast(1)
                .takeLast(12)
                .map { it.role to it.text }
            val result = OfflineBrain.chat(this, clean, memories, history)
            runOnUiThread {
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
            startService(Intent(this, BridgeService::class.java).setAction(BridgeService.ACTION_VOICE_STOP))
            logAdapter.add("info", "连续语音：关闭中")
        } else {
            startService(Intent(this, BridgeService::class.java).setAction(BridgeService.ACTION_VOICE_START))
            logAdapter.add("info", "连续语音：开启中，后台也会保持。")
        }
        renderVoiceState(running = !BridgeService.isVoiceChatRunning, listening = false, speaking = false)
    }

    private fun renderVoiceState(running: Boolean, listening: Boolean, speaking: Boolean = false) {
        companionView.setVoiceState(listening, speaking)
        voiceButton.text = if (running) "停止" else getString(R.string.action_voice)
        voiceButton.alpha = if (running) 1f else .78f
        audioMetric.text = when {
            listening -> "音频 语音对话"
            pttActive -> "音频 对讲"
            continuousListening -> "音频 监听"
            micRunning -> "音频 开"
            else -> "音频 关"
        }
    }

    private fun drainChatOutbox() {
        ChatOutbox.drain(this).forEach { text ->
            logAdapter.add("info", "发送快捷回复：$text")
            appendChat("user", text)
            sendJson(JSONObject().put("type", "chat").put("text", text))
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

    private fun handleServerJson(text: String) {
        runCatching {
            val json = JSONObject(text)
            when (json.optString("type")) {
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
                    val revision = json.optLong("eventRevision", json.optJSONObject("workspace")?.optLong("eventRevision", 0L) ?: 0L)
                    if (revision > workspaceRevision) {
                        workspaceRevision = revision
                        workspaceEventGate.markResynchronized(revision)
                        getSharedPreferences("workspace_meta", Context.MODE_PRIVATE).edit().putLong("revision", revision).apply()
                    }
                    json.optJSONObject("motes")?.let { handleMoteSnapshot(it) }
                    json.optJSONObject("workspace")?.let { workspace ->
                        runOnUiThread { applyWorkspaceSnapshot(workspace) }
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
                    for (index in 0 until events.length()) {
                        val event = events.optJSONObject(index) ?: continue
                        val eventRevision = event.optLong("revision", revision)
                        if (workspaceEventGate.accept(eventRevision, event.optString("eventId"))) {
                            val payload = event.optJSONObject("payload") ?: JSONObject()
                            payload.put("type", event.optString("type"))
                            handleServerJson(payload.toString())
                        }
                    }
                    if (workspaceEventGate.revisionGapDetected) {
                        sendJson(JSONObject().put("type", "snapshot").put("since", workspaceRevision))
                    } else if (workspaceEventGate.revision > workspaceRevision) {
                        workspaceRevision = workspaceEventGate.revision
                        getSharedPreferences("workspace_meta", Context.MODE_PRIVATE).edit().putLong("revision", workspaceRevision).apply()
                    }
                }
                "chat" -> {
                    val role = json.optString("role")
                    val message = json.optString("text")
                    val time = json.optString("time", currentTime())
                    streamingChatId = ""
                    streamingText.setLength(0)
                    runOnUiThread {
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
                "attention.upsert" -> handleAttentionEvent(json.optJSONObject("attention"))
                "action.run", "action.result" -> handleActionRunEvent(json.optJSONObject("actionRun"))
                "workspace.policy" -> handlePolicyEvent(json.optJSONObject("policy"))
                "autonomy.approval" -> handleAutonomyApprovalEvent(json.optJSONObject("approval"))
                "workspace.emergency_stop" -> handleEmergencyStopEvent(json.optJSONObject("state"))
                "mote.roster" -> handleMoteRosterEvent(json.optJSONArray("roster"), json.optJSONObject("state"))
                "mote.profile" -> json.optJSONObject("profile")?.let { profile ->
                    handleMoteRosterEvent(null, JSONObject().put("activeId", profile.optString("id")))
                }
                "mote.exploration" -> handleMoteRosterEvent(null, json.optJSONObject("state"))
                "mote.relationship" -> json.optJSONObject("relationship")?.let { handleMoteRelationshipEvent(it) }
                "mote.quest" -> runOnUiThread { speechText.text = "Mote：有新的陪伴任务" }
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
                    val id = json.optString("id")
                    val text = json.optString("text")
                    runOnUiThread {
                        if (streamingChatId != id) {
                            streamingChatId = id
                            streamingText.setLength(0)
                        }
                        streamingText.setLength(0)
                        streamingText.append(text)
                        appendChat("assistant", text, true, currentTime())
                        if (json.optString("sessionId") == aiSelectedSessionId && aiSpacePanel.visibility == View.VISIBLE) {
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
            }
        }.onFailure {
            Log.w(TAG, "bad server json", it)
        }
    }

    private fun setPtt(active: Boolean) {
        Log.i(TAG, "PTT $active websocket=$BridgeLink.isOnline micRunning=$micRunning")
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
        if (!hasPermissions() || !BridgeLink.isOnline || micRunning) return
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
        val message = when (kind) {
            "feed" -> {
                pet = pet.copy(energy = min(100, pet.energy + 16), experience = pet.experience + 5, mood = PetMood.HAPPY)
                listOf("能量补上了。", "很好吃。", "感觉亮了一点。").random()
            }
            "play" -> {
                pet = pet.copy(
                    energy = (pet.energy - 6).coerceAtLeast(0),
                    affection = min(100, pet.affection + 5),
                    experience = pet.experience + 9,
                    mood = PetMood.HAPPY
                )
                listOf("再来一次！", "信号在跳舞。", "这很有趣。").random()
            }
            else -> {
                pet = pet.copy(affection = min(100, pet.affection + 3), experience = pet.experience + 2, mood = PetMood.CALM)
                listOf("很舒服。", "我在这里。", "别担心，我看着呢。").random()
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
        if (!cached.isNullOrBlank()) {
            handleMoteSnapshot(JSONObject().put("roster", JSONArray(cached)).put("state", JSONObject(cachedState ?: "{}")))
            renderMoteDexDialog()
        }
        if (BridgeLink.isOnline) workspaceRequest("/api/motes", onSuccess = { handleMoteSnapshot(it); renderMoteDexDialog() })
        else if (cached.isNullOrBlank()) renderMoteDexDialog()
    }

    private fun renderMoteDexDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val exploration = moteStateJson.optJSONObject("exploration") ?: JSONObject()
        val target = exploration.optString("targetId").ifBlank { "暂无" }
        val fragments = exploration.optJSONObject("fragments")
        container.addView(TextView(this).apply {
            text = "探索目标：$target\n地点 ${if (fragments?.optBoolean("location") == true) "✓" else "·"}  物体 ${if (fragments?.optBoolean("object") == true) "✓" else "·"}  光线 ${if (fragments?.optBoolean("light") == true) "✓" else "·"}"
            setTextColor(Color.parseColor("#D9F5E6"))
            setPadding(0, 0, 0, dp(8))
        })
        for (index in 0 until moteRosterJson.length()) {
            val profile = moteRosterJson.optJSONObject(index) ?: continue
            val id = profile.optString("id")
            val unlocked = profile.optBoolean("unlocked")
            val button = Button(this).apply {
                text = if (unlocked) "${profile.optString("name")} · ${profile.optString("voice")}" else "${profile.optString("name")} · 未解锁（设为探索目标）"
                isAllCaps = false
                isEnabled = true
                setOnClickListener {
                    if (unlocked) {
                        workspaceRequest("/api/motes/active", "PATCH", JSONObject().put("id", id), onSuccess = { handleMoteSnapshot(it); say("已切换到${profile.optString("name")}") })
                    } else {
                        workspaceRequest("/api/motes/exploration", "PATCH", JSONObject().put("targetId", id), onSuccess = { handleMoteSnapshot(it); say("开始探索${profile.optString("name")}") })
                    }
                }
            }
            container.addView(button)
        }
        AlertDialog.Builder(this).setTitle("Mote 图鉴 · ${moteRosterJson.length()}/10").setView(container).setPositiveButton("关闭", null).show()
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
            companionView.speakPulse()
        }
    }

    private fun setStatus(text: String) {
        runOnUiThread {
            statusChip.text = text
            renderPet()
        }
    }

    private fun enterFocusMode() {
        if (immersiveMode) return
        immersiveMode = true
        normalHeroParams = heroPanel.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        rootLayout.setPadding(0, 0, 0, 0)
        listOf(
            R.id.titleText, R.id.subtitleText, R.id.statusChip, R.id.themeSwitcher,
            R.id.appearanceRow, R.id.metricRow, R.id.actionScroll, R.id.pttButton,
            R.id.panelTabs, R.id.panelHost, R.id.speechText
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
        WindowCompat.getInsetsController(window, heroPanel)
            .show(WindowInsetsCompat.Type.systemBars())
        say("进入沉浸模式。")
    }

    private fun exitFocusMode() {
        if (!immersiveMode) return
        immersiveMode = false
        normalHeroParams?.let { params ->
            heroPanel.layoutParams = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(params)
        }
        rootLayout.setPadding(dp(14), dp(14), dp(14), 0)
        listOf(
            R.id.titleText, R.id.subtitleText, R.id.statusChip, R.id.themeSwitcher,
            R.id.appearanceRow, R.id.metricRow, R.id.actionScroll, R.id.pttButton,
            R.id.panelTabs, R.id.panelHost
        ).forEach { id -> findViewById<View>(id).visibility = View.VISIBLE }
        focusExit.visibility = View.GONE
        focusToolbar.visibility = View.GONE
        focusSpeechLayer.visibility = View.GONE
        focusSpeechScroll.visibility = View.GONE
        focusSpeechStack.removeAllViews()
        findViewById<View>(R.id.focusInputRow).visibility = View.GONE
        focusBackCallback.isEnabled = false
        WindowCompat.getInsetsController(window, heroPanel)
            .show(WindowInsetsCompat.Type.systemBars())
        say("回到工作台。")
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
            if (!dismissFocusKeyboard()) exitFocusMode()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun renderFocusTools() {
        if (!immersiveMode) return
        focusToolScroll.visibility = if (focusToolsExpanded) View.VISIBLE else View.GONE
        focusToolsToggle.text = if (focusToolsExpanded) "▾" else "▸"
        focusToolsToggle.contentDescription =
            if (focusToolsExpanded) "折叠沉浸工具栏" else "展开沉浸工具栏"
        focusCameraButton.text = getString(if (cameraRunning) R.string.stop_camera else R.string.start_camera)
        focusListenButton.alpha = if (continuousListening || micRunning || pttActive) 1f else .68f
        focusVoiceButton.alpha = if (BridgeService.isVoiceChatRunning) 1f else .68f
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
        pet = pet.copy(mood = mood, connected = online, cameraActive = cameraRunning, listening = micRunning, activeTasks = activeTasks.size, emotion = emotion)
        val behavior = MoteBehaviorEngine.resolve(
            MoteProfiles.profile(pet.appearance),
            MoteBehaviorInput(
                taskState = activeTasks.values.firstOrNull()?.status,
                deviceHealth = deviceHealthState.overall.name,
                interaction = when { speaking -> "chat"; pttActive -> "ptt"; else -> "ambient" },
                explorationProgress = (moteStateJson.optJSONObject("exploration")?.optJSONObject("fragments")?.let { fragments ->
                    listOf("location", "object", "light").count { fragments.optBoolean(it) }
                } ?: 0),
                relationshipLevel = moteRelationship.level,
                emotion = pet.emotion
            )
        )
        companionView.setBehaviorHint(behavior)
        realityLensView.setBehaviorHint(behavior)
        companionView.update(pet)
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
            .putBoolean("cameraActive", cameraRunning)
            .putBoolean("listening", micRunning)
            .putInt("fps", fpsCounter.get())
            .putInt("activeTasks", activeTasks.count { !it.value.status.equals("done", true) })
            .putInt("battery", latestTelemetry?.batteryPercent ?: 0)
            .putFloat("temperature", latestTelemetry?.batteryTemperature ?: 0f)
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
            .setNegativeButton("取消", null)
            .show()
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

    override fun onCompanionLongPressed() {
        setPtt(true)
    }

    override fun onCompanionLongPressReleased() {
        setPtt(false)
    }

    override fun onPause() {
        super.onPause()
        savePet()
        // A detached SurfaceView blocks CameraX session configuration when the
        // screen is off. Keep only the remote analysis stream in that state.
        cameraPreview?.let { preview -> runCatching { cameraProvider?.unbind(preview) } }
        cameraPreview = null
    }

    override fun onResume() {
        super.onResume()
        if (cameraRunning) {
            cameraRunning = false
            startCamera()
        }
        if (BridgeLink.isOnline) drainChatOutbox()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
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
        cameraRunning = false
        continuousListening = false
        pttActive = false
        micRunning = false
        audioThread = null
        audioRecord = null
        cameraPreview = null
        runCatching { cameraProvider?.unbindAll() }
        cameraProvider = null
    }
}
