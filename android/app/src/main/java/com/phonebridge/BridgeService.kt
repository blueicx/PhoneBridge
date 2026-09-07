package com.phonebridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.app.RemoteInput
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.Locale

class AlwaysResumedLifecycle : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry
    fun activate() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }
}

class BridgeService : Service(), BridgeLink.DeviceListener {
    companion object {
        const val CHANNEL_ID = "mote_resident"
        const val NOTIFICATION_ID = 9503
        const val QUICK_REPLY_ID = 9504
        const val ACTION_SEND_REPLY = "com.phonebridge.SEND_REPLY"
        const val EXTRA_REPLY_TEXT = "reply_text"
        const val ACTION_VOICE_START = "com.phonebridge.VOICE_START"
        const val ACTION_VOICE_STOP = "com.phonebridge.VOICE_STOP"
        const val ACTION_VOICE_STATE = "com.phonebridge.VOICE_STATE"
        const val EXTRA_VOICE_RUNNING = "running"
        const val EXTRA_VOICE_LISTENING = "listening"
        const val EXTRA_VOICE_SPEAKING = "speaking"
        const val EXTRA_VOICE_STATUS = "status"
        const val EXTRA_VOICE_DETAIL = "detail"
        const val ACTION_EXIT_APP = "com.phonebridge.EXIT_APP"
        const val DEFAULT_SCREEN_OFF_EXIT_MINUTES = 10
        private const val VOICE_SAMPLE_RATE = 16_000
        private const val TAG = "BridgeService"
        val lifecycleOwner = AlwaysResumedLifecycle()
        @Volatile var isRunning = false
            private set
        @Volatile var screenOffExitMinutes = DEFAULT_SCREEN_OFF_EXIT_MINUTES
            private set
        @Volatile var isVoiceChatRunning = false
            private set
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var screenOffSinceMs: Long? = null
    private val voiceLock = Any()
    @Volatile private var voskModel: Model? = null
    @Volatile private var voskRecognizer: Recognizer? = null
    @Volatile private var voiceAudioRecord: AudioRecord? = null
    @Volatile private var voiceCaptureThread: Thread? = null
    @Volatile private var voiceCaptureRunning = false
    @Volatile private var voicePaused = false
    @Volatile private var voiceInterruptedText: String? = null
    @Volatile private var voiceLastFinalText = ""
    @Volatile private var voiceLastFinalAt = 0L
    private var acousticEchoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false
    @Volatile private var serverSpeechTrack: AudioTrack? = null
    @Volatile private var voiceListening = false
    @Volatile private var voiceWaitingForReply = false
    @Volatile private var voiceSpeaking = false
    @Volatile private var voiceReplyPreview = ""
    @Volatile private var foregroundState = VoiceForegroundState(
        serviceAlive = false,
        bridgeOnline = false
    )
    private val voiceHandler = Handler(Looper.getMainLooper())
    private val voiceModelExecutor = Executors.newSingleThreadExecutor()
    private val statusExecutor = Executors.newSingleThreadScheduledExecutor()
    private val replyExecutor = Executors.newSingleThreadExecutor()
    private val speechAudioExecutor = Executors.newSingleThreadExecutor()
    private val http = OkHttpClient.Builder()
        .connectTimeout(8L, TimeUnit.SECONDS)
        .readTimeout(125L, TimeUnit.SECONDS)
        .build()

    override fun onCreate() {
        super.onCreate()
        createChannel()
        foregroundState = VoiceForegroundState(
            serviceAlive = true,
            bridgeOnline = BridgeLink.isOnline
        ).stopped("监听已停止。")
        startForegroundCompat()
        lifecycleOwner.activate()
        BridgeLink.setDeviceListener(this)
        ensureBridgeLink()
        val power = getSystemService(PowerManager::class.java)
        val prefs = getSharedPreferences("phonebridge", Context.MODE_PRIVATE)
        screenOffExitMinutes = prefs.getInt(
            "screen_off_exit_minutes",
            DEFAULT_SCREEN_OFF_EXIT_MINUTES
        ).coerceIn(0, 120)
        if (prefs.getBoolean("voice_chat_enabled", false)) {
            prefs.edit().putBoolean("voice_chat_enabled", false).apply()
            syncForegroundState(
                currentForegroundState().paused("已暂停，请在 App 内重新开启连续监听。")
            )
        }
        screenOffSinceMs = if (power?.isInteractive == false) {
            SystemClock.elapsedRealtime()
        } else {
            null
        }
        registerReceiver(screenStateReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        })
        wakeLock = power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhoneBridge:MoteResident")?.apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L)
        }
        isRunning = true
        statusExecutor.scheduleWithFixedDelay({
            if (checkScreenOffExit()) return@scheduleWithFixedDelay
            ensureBridgeLink()
            refreshStatusNotification()
        }, 2L, 30L, TimeUnit.SECONDS)
    }

    override fun onServerStats(stats: JSONObject) {
        updateScreenOffTimeout(stats.optInt("screenOffExitMinutes", screenOffExitMinutes))
    }

    override fun onProactive(message: String, key: String) {
        syncForegroundState(currentForegroundState().withProactive(message, key))
        updateQuickReply(
            VoiceForegroundFormatter.proactiveTitle(key),
            VoiceForegroundFormatter.proactiveText(message, key)
        )
    }

    private fun startVoiceChat(persist: Boolean = true) {
        if (isVoiceChatRunning) return
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            syncForegroundState(
                currentForegroundState().permissionMissing("请先打开 App 授予麦克风权限。")
            )
            updateQuickReply("连续语音未开启", "请先打开 App 授予麦克风权限。")
            return
        }
        isVoiceChatRunning = true
        resetVoiceConversationState()
        if (persist) {
            getSharedPreferences("phonebridge", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("voice_chat_enabled", true)
                .apply()
        }
        syncForegroundState(
            currentForegroundState().preparing("正在加载离线中文识别模型。")
        )
        prepareTextToSpeech()
        DeviceCommandBus.dispatch("listen_off")
        updateQuickReply("Mote 正在准备…", "正在加载离线中文识别模型。")
        voiceModelExecutor.execute { initializeOfflineRecognition() }
    }

    private fun stopVoiceChat(persist: Boolean = true, notify: Boolean = true) {
        val wasRunning = isVoiceChatRunning || voskRecognizer != null || voiceCaptureRunning
        val hadCapture = voiceCaptureRunning
        isVoiceChatRunning = false
        stopVoiceCapture(joinThread = hadCapture)
        synchronized(voiceLock) {
            runCatching { voskRecognizer?.close() }
            voskRecognizer = null
            runCatching { voskModel?.close() }
            voskModel = null
        }
        voiceHandler.removeCallbacksAndMessages(null)
        runCatching { textToSpeech?.stop() }
        runCatching { textToSpeech?.shutdown() }
        textToSpeech = null
        ttsReady = false
        runCatching { serverSpeechTrack?.pause() }
        runCatching { serverSpeechTrack?.flush() }
        runCatching { serverSpeechTrack?.release() }
        serverSpeechTrack = null
        resetVoiceConversationState()
        if (persist) {
            getSharedPreferences("phonebridge", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("voice_chat_enabled", false)
                .apply()
        }
        syncForegroundState(currentForegroundState().stopped("监听已停止。"))
        if (wasRunning && notify) {
            updateQuickReply("连续语音已关闭", "按对话页的“语音”可以重新打开。")
        }
    }

    private fun prepareTextToSpeech() {
        if (textToSpeech != null) return
        textToSpeech = TextToSpeech(applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                runCatching {
                    textToSpeech?.language = Locale.SIMPLIFIED_CHINESE
                    textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            voiceSpeaking = true
                            syncForegroundState(
                                currentForegroundState().speaking(
                                    voiceReplyPreview.ifBlank { "正在播报回复。" }
                                )
                            )
                        }

                        override fun onDone(utteranceId: String?) {
                            voiceSpeaking = false
                            refreshForegroundState()
                            if (isVoiceChatRunning && !voiceListening && !voiceWaitingForReply) {
                                voiceHandler.postDelayed({ resumeVoiceCapture() }, 180L)
                            }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            voiceSpeaking = false
                            refreshForegroundState()
                            if (isVoiceChatRunning && !voiceListening && !voiceWaitingForReply) {
                                voiceHandler.postDelayed({ resumeVoiceCapture() }, 320L)
                            }
                        }
                    })
                }
            }
        }
    }

    private fun initializeOfflineRecognition() {
        var loadedModel: Model? = null
        var loadedRecognizer: Recognizer? = null
        try {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            val modelPath = StorageService.sync(
                this,
                "model-small-cn",
                "model-small-cn"
            )
            if (!isVoiceChatRunning) return

            loadedModel = Model(modelPath)
            loadedRecognizer = Recognizer(loadedModel, VOICE_SAMPLE_RATE.toFloat())
            synchronized(voiceLock) {
                if (!isVoiceChatRunning) return
                runCatching { voskRecognizer?.close() }
                runCatching { voskModel?.close() }
                voskModel = loadedModel
                voskRecognizer = loadedRecognizer
            }
            loadedModel = null
            loadedRecognizer = null
            startVoiceCapture()
        } catch (error: Exception) {
            Log.e(TAG, "Offline voice initialization failed", error)
            voiceHandler.post {
                stopVoiceChat(persist = true, notify = false)
                updateQuickReply(
                    "连续语音启动失败",
                    error.message ?: "离线识别模型加载失败。"
                )
            }
        } finally {
            if (loadedRecognizer != null) runCatching { loadedRecognizer.close() }
            if (loadedModel != null) runCatching { loadedModel.close() }
        }
    }

    private fun resetVoiceConversationState() {
        voiceListening = false
        voiceWaitingForReply = false
        voiceSpeaking = false
        voicePaused = false
        voiceInterruptedText = null
        voiceLastFinalText = ""
        voiceLastFinalAt = 0L
        voiceReplyPreview = ""
    }

    private fun startVoiceCapture() {
        if (voiceCaptureRunning) return
        val minBuffer = AudioRecord.getMinBufferSize(
            VOICE_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        require(minBuffer > 0) { "麦克风缓冲区初始化失败" }
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            VOICE_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer * 4, 8_192)
        )
        require(record.state == AudioRecord.STATE_INITIALIZED) { "麦克风初始化失败" }

        if (AcousticEchoCanceler.isAvailable()) {
            acousticEchoCanceler = AcousticEchoCanceler.create(record.audioSessionId)
            acousticEchoCanceler?.enabled = true
        }
        if (NoiseSuppressor.isAvailable()) {
            noiseSuppressor = NoiseSuppressor.create(record.audioSessionId)
            noiseSuppressor?.enabled = true
        }

        record.startRecording()
        voiceAudioRecord = record
        voiceCaptureRunning = true
        voicePaused = false
        voiceInterruptedText = null
        val reader = Thread { readVoiceFrames(record) }.apply {
            name = "phonebridge-vosk"
            priority = Thread.NORM_PRIORITY - 1
        }
        voiceCaptureThread = reader
        reader.start()
        voiceListening = true
        syncForegroundState(
            currentForegroundState().listening("离线连续语音聊天中，直接说话即可。")
        )
        updateQuickReply("Mote 正在听…", "离线连续语音聊天中，直接说话即可。")
    }

    private fun readVoiceFrames(record: AudioRecord) {
        val samples = ShortArray(1_600)
        while (isVoiceChatRunning && voiceCaptureRunning && !Thread.currentThread().isInterrupted) {
            val read = runCatching { record.read(samples, 0, samples.size) }.getOrDefault(0)
            if (read <= 0) {
                Log.w(TAG, "Voice AudioRecord read failed: $read")
                Thread.sleep(25L)
                continue
            }
            if (voicePaused) continue

            val recognizer = voskRecognizer ?: break
            val isFinal = recognizer.acceptWaveForm(samples, read)
            val payload = if (isFinal) recognizer.result else recognizer.partialResult
            val text = extractVoiceText(payload)
            if (text.isEmpty()) continue
            Log.d(TAG, "Voice ${if (isFinal) "final" else "partial"}: $text")

            if (voiceSpeaking && text.length >= 2) interruptSpeech()
            if (!isFinal) continue

            val timestamp = System.currentTimeMillis()
            val duplicate = text == voiceLastFinalText && timestamp - voiceLastFinalAt < 900L
            Log.d(TAG, "Voice final dispatch: duplicate=$duplicate waiting=$voiceWaitingForReply")
            voiceLastFinalText = text
            voiceLastFinalAt = timestamp
            if (!duplicate) processVoiceCommand(text)
        }
    }

    private fun extractVoiceText(payload: String?): String = runCatching {
        JSONObject(payload.orEmpty()).let { json ->
            json.optString("text").ifBlank { json.optString("partial") }.trim()
        }
    }.getOrDefault("")

    private fun pauseVoiceCapture() {
        voicePaused = true
        voiceListening = false
        voiceInterruptedText = null
    }

    private fun resumeVoiceCapture() {
        if (!isVoiceChatRunning || !voiceCaptureRunning || voiceSpeaking) return
        runCatching { voskRecognizer?.reset() }
        voiceWaitingForReply = false
        voicePaused = false
        voiceListening = true
        voiceInterruptedText = null
        syncForegroundState(currentForegroundState().listening("继续说吧。"))
        updateQuickReply("Mote 正在听…", "继续说吧。")
    }

    private fun stopVoiceCapture(joinThread: Boolean) {
        voiceCaptureRunning = false
        voiceListening = false
        val reader = voiceCaptureThread
        if (joinThread && reader != null && reader !== Thread.currentThread()) {
            runCatching { reader.interrupt() }
            runCatching { reader.join(500L) }
        }
        voiceCaptureThread = null
        runCatching { voiceAudioRecord?.stop() }
        runCatching { voiceAudioRecord?.release() }
        voiceAudioRecord = null
        runCatching { acousticEchoCanceler?.enabled = false }
        runCatching { acousticEchoCanceler?.release() }
        acousticEchoCanceler = null
        runCatching { noiseSuppressor?.enabled = false }
        runCatching { noiseSuppressor?.release() }
        noiseSuppressor = null
    }

    private fun interruptSpeech() {
        if (!voiceSpeaking) return
        voiceSpeaking = false
        runCatching { textToSpeech?.stop() }
    }

    private fun processVoiceCommand(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) {
            return
        }
        if (voiceWaitingForReply) return

        val requestUrl = "${savedServerHttp()}/api/chat"
        Log.i(TAG, "Voice command accepted: $clean")

        pauseVoiceCapture()
        voiceListening = false
        voiceWaitingForReply = true
        syncForegroundState(currentForegroundState().processing(clean))
        updateQuickReply("Mote 正在想…", "你说：${clean.take(120)}")

        val rememberMatch = Regex(
            "^(?:记住|記住|remember)[：:，,\\s]+(.+)$",
            RegexOption.IGNORE_CASE
        ).find(clean)
        if (rememberMatch != null) {
            val fact = rememberMatch.groupValues[1].trim()
            MoteMemory.add(this, fact)
            voiceWaitingForReply = false
            speakReply("好的，我记住了。")
            return
        }

        val memories = MoteMemory.relevant(this, clean, 12)
        MoteMemory.touch(this, memories)
        val memoryArray = JSONArray()
        memories.forEach { memoryArray.put(it.text) }
        val payload = JSONObject()
            .put("type", "chat")
            .put("source", "voice")
            .put("text", clean)
            .put("memories", memoryArray)
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(requestUrl)
            .header("x-phonebridge-token", savedAccessToken())
            .post(payload)
            .build()

        replyExecutor.execute {
            try {
                http.newCall(request).execute().use { response ->
                    val bodyText = response.body?.string().orEmpty()
                    Log.i(TAG, "Voice chat HTTP ${response.code}: ${bodyText.take(240)}")
                    if (!response.isSuccessful) throw IllegalStateException("节点 HTTP ${response.code}")
                    val json = JSONObject(bodyText)
                    if (!json.optBoolean("ok", false)) {
                        throw IllegalStateException(json.optString("error", "模型请求失败"))
                    }
                    val reply = json.optString("reply").ifBlank { "（空回复）" }
                    voiceHandler.post { speakReply(reply) }
                }
            } catch (error: Exception) {
                Log.w(TAG, "Bridge voice chat failed; trying offline API", error)
                OfflineBrain.chat(this@BridgeService, clean, memories.map { it.text }).fold(
                    onSuccess = { reply ->
                        voiceHandler.post { speakReply(reply) }
                    },
                    onFailure = { offlineError ->
                        val message = listOf(error.message, offlineError.message)
                            .filterNotNull()
                            .joinToString(";")
                        Log.e(TAG, "Voice chat failed", offlineError)
                        voiceHandler.post {
                            voiceWaitingForReply = false
                            resumeVoiceCapture()
                            updateQuickReply("连续语音失败", message.take(500))
                            speakReply(message.take(180))
                        }
                    }
                )
            }
        }
    }

    private fun speakReply(text: String) {
        voiceWaitingForReply = false
        voiceReplyPreview = text
        updateQuickReply("Mote 回复", text)
        speakViaNode(text, fallbackToDevice = true)
    }

    private fun speakWithDeviceTts(text: String): Boolean {
        val queued = runCatching {
            textToSpeech?.setPitch(1.15f)
            textToSpeech?.setSpeechRate(.95f)
            textToSpeech?.speak(
                text,
                TextToSpeech.QUEUE_FLUSH,
                Bundle(),
                "mote_voice_reply_${System.currentTimeMillis()}"
            )
        }.getOrDefault(TextToSpeech.ERROR) == TextToSpeech.SUCCESS
        Log.i(TAG, "Device TTS reply queued=$queued: ${text.take(120)}")
        if (!queued) voiceHandler.postDelayed({ resumeVoiceCapture() }, 320L)
        return queued
    }

    private fun speakViaNode(text: String, fallbackToDevice: Boolean = false) {
        Log.i(TAG, "Requesting neural speech")
        replyExecutor.execute {
            try {
                val payload = JSONObject().put("text", text).toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder()
                    .url("${savedServerHttp()}/api/tts")
                    .header("x-phonebridge-token", savedAccessToken())
                    .post(payload)
                    .build()
                http.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) throw IllegalStateException("节点语音 HTTP ${response.code}")
                    val audio = Base64.decode(JSONObject(body).optString("audio"), Base64.DEFAULT)
                    if (audio.isEmpty()) throw IllegalStateException("节点语音为空")
                    Log.i(TAG, "Neural speech received: ${audio.size} bytes")
                    voiceHandler.post { playServerSpeech(text, audio) }
                }
            } catch (error: Exception) {
                Log.e(TAG, "Neural speech failed", error)
                voiceHandler.post {
                    if (fallbackToDevice && ttsReady && speakWithDeviceTts(text)) return@post
                    updateQuickReply("语音回复失败", error.message ?: "无法播放节点语音。")
                    resumeVoiceCapture()
                }
            }
        }
    }

    private fun playServerSpeech(text: String, pcm: ByteArray) {
        if (!isVoiceChatRunning || !voiceCaptureRunning) return
        pauseVoiceCapture()
        voiceWaitingForReply = false
        voiceSpeaking = true
        voiceReplyPreview = text
        syncForegroundState(currentForegroundState().speaking(text))
        updateQuickReply("Mote 回复", text)
        speechAudioExecutor.execute {
            var track: AudioTrack? = null
            try {
                Log.i(TAG, "Server speech playback start: ${pcm.size} bytes")
                val minBuffer = AudioTrack.getMinBufferSize(
                    VOICE_SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                track = AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    VOICE_SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuffer, pcm.size),
                    AudioTrack.MODE_STREAM
                )
                serverSpeechTrack = track
                track.play()
                val written = track.write(pcm, 0, pcm.size)
                Log.i(TAG, "Server speech written: $written bytes")
                Thread.sleep(pcm.size * 1_000L / (VOICE_SAMPLE_RATE * 2L))
            } catch (error: Exception) {
                Log.e(TAG, "Server speech playback failed", error)
            } finally {
                runCatching { track?.stop() }
                runCatching { track?.release() }
                Log.i(TAG, "Server speech playback finished")
                if (serverSpeechTrack === track) serverSpeechTrack = null
                voiceHandler.post {
                    voiceSpeaking = false
                    refreshForegroundState()
                    resumeVoiceCapture()
                }
            }
        }
    }

    private fun savedServerHttp(): String =
        savedServer()
            .replaceFirst("wss://", "https://")
            .replaceFirst("ws://", "http://")
            .trimEnd('/')

    private fun currentForegroundState(): VoiceForegroundState =
        foregroundState.copy(serviceAlive = true, bridgeOnline = BridgeLink.isOnline)

    private fun syncForegroundState(next: VoiceForegroundState) {
        foregroundState = next.copy(serviceAlive = true, bridgeOnline = BridgeLink.isOnline)
        publishForegroundState()
    }

    private fun refreshForegroundState() {
        foregroundState = currentForegroundState()
        publishForegroundState()
    }

    private fun publishForegroundState() {
        val presentation = VoiceForegroundFormatter.present(currentForegroundState())
        broadcastVoiceState(presentation)
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, buildNotification(presentation))
    }

    private fun broadcastVoiceState(presentation: VoiceForegroundPresentation) {
        sendBroadcast(
            Intent(ACTION_VOICE_STATE)
                .setPackage(packageName)
                .putExtra(EXTRA_VOICE_RUNNING, presentation.running)
                .putExtra(EXTRA_VOICE_LISTENING, presentation.listening)
                .putExtra(EXTRA_VOICE_SPEAKING, presentation.speaking)
                .putExtra(EXTRA_VOICE_STATUS, presentation.status)
                .putExtra(EXTRA_VOICE_DETAIL, presentation.detail)
        )
    }

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            screenOffSinceMs = when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> SystemClock.elapsedRealtime()
                else -> null
            }
        }
    }

    private fun updateScreenOffTimeout(minutes: Int) {
        val safeMinutes = minutes.coerceIn(0, 120)
        if (safeMinutes == screenOffExitMinutes) return
        screenOffExitMinutes = safeMinutes
        getSharedPreferences("phonebridge", Context.MODE_PRIVATE)
            .edit()
            .putInt("screen_off_exit_minutes", safeMinutes)
            .apply()
        Log.i(TAG, "Screen-off exit timeout: $safeMinutes minutes")
    }

    private fun checkScreenOffExit(): Boolean {
        val minutes = screenOffExitMinutes
        val offSince = screenOffSinceMs ?: return false
        if (minutes <= 0) return false
        if (SystemClock.elapsedRealtime() - offSince < minutes * 60_000L) return false

        Log.i(TAG, "Screen off for $minutes minutes; exiting app")
        DeviceCommandBus.dispatch("camera_off")
        DeviceCommandBus.dispatch("listen_off")
        BridgeLink.disconnect(permanent = true)
        sendBroadcast(Intent(ACTION_EXIT_APP).setPackage(packageName))
        stopSelf()
        return true
    }

    override fun onDeviceCommand(action: String) {
        Log.i(TAG, "Device command via resident link: $action")
        if (!DeviceCommandBus.dispatch(action)) {
            // Activity destruction releases camera/mic first, so a missing
            // receiver must never leave an off command silently half-applied.
            Log.i(TAG, "No activity receiver for $action; hardware already released")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "stop" -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_VOICE_START -> startVoiceChat()
            ACTION_VOICE_STOP -> stopVoiceChat()
            else -> startForegroundCompat()
        }
        if (intent?.action == ACTION_SEND_REPLY) {
            val text = intent.getStringExtra(EXTRA_REPLY_TEXT)?.trim().orEmpty()
            if (text.isNotEmpty()) replyExecutor.execute { answerReply(text) }
        }
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val notification = buildNotification(VoiceForegroundFormatter.present(currentForegroundState()))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureBridgeLink() {
        if (BridgeLink.isOnline) return
        val prefs = getSharedPreferences("phonebridge", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("auto_connect", true)) return
        Log.i(TAG, "Restoring bridge link")
        BridgeLink.connect(
            prefs.getString("server", "ws://127.0.0.1:9503") ?: "ws://127.0.0.1:9503",
            SecureTokenStore(this).migrateLegacy(prefs),
            null
        )
    }

    private fun buildNotification(presentation: VoiceForegroundPresentation): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(presentation.title)
            .setContentText(presentation.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(presentation.text))
            .setSubText(presentation.status)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPendingIntent())
            .addAction(0, "抚摸一下", carePendingIntent("stroke"))
            .addAction(0, "换眼", commandPendingIntent("/camera"))
            .apply {
                presentation.stopActionLabel?.let {
                    addAction(0, it, servicePendingIntent(ACTION_VOICE_STOP))
                }
                buildReplyAction()?.let(::addAction)
            }
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    

    private fun openPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun carePendingIntent(kind: String): PendingIntent = PendingIntent.getActivity(
        this,
        kind.hashCode(),
        Intent(this, MainActivity::class.java).putExtra("auto_care", kind),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun commandPendingIntent(command: String): PendingIntent = PendingIntent.getActivity(
        this,
        command.hashCode(),
        Intent(this, MainActivity::class.java).putExtra("auto_command", command),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun servicePendingIntent(action: String): PendingIntent = PendingIntent.getService(
        this,
        action.hashCode(),
        Intent(this, BridgeService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun buildReplyAction(): NotificationCompat.Action? {
        val replyIntent = Intent(this, QuickReplyReceiver::class.java)
        val replyPending = PendingIntent.getBroadcast(
            this,
            0,
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        return NotificationCompat.Action.Builder(0, "回复", replyPending)
            .addRemoteInput(
                RemoteInput.Builder(QuickReplyNotification.KEY_REPLY).setLabel("和 Mote 说话").build()
            )
            .build()
    }

    private fun refreshStatusNotification() {
        refreshForegroundState()
    }

    private fun answerReply(text: String) {
        updateQuickReply("Mote 正在想…", text.take(120))
        val rememberMatch = Regex("^(?:记住|記住|remember)[：:，,\\s]+(.+)$", RegexOption.IGNORE_CASE).find(text)
        if (rememberMatch != null) {
            val fact = rememberMatch.groupValues[1].trim()
            MoteMemory.add(this, fact)
            updateQuickReply("已记住：$fact", "这条已经放进长期记忆。")
            ChatOutbox.remove(this, text)
            return
        }

        val memories = MoteMemory.relevant(this, text)
        MoteMemory.touch(this, memories)
        val memoryArray = JSONArray()
        memories.forEach { memoryArray.put(it.text) }
        val payload = JSONObject()
            .put("type", "chat")
            .put("text", text)
            .put("memories", memoryArray)
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        try {
            val base = savedServer()
                .replaceFirst("wss://", "https://")
                .replaceFirst("ws://", "http://")
                .trimEnd('/')
            val request = Request.Builder()
                .url("$base/api/chat")
                .header("x-phonebridge-token", savedAccessToken())
                .post(payload)
                .build()
            http.newCall(request).execute().use { response ->
                val bodyText = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw IllegalStateException("节点 HTTP ${response.code}")
                val json = JSONObject(bodyText)
                if (!json.optBoolean("ok", false)) {
                    throw IllegalStateException(json.optString("error", "模型请求失败"))
                }
                updateQuickReply(json.optString("reply", "（空回复）"), "你说：${text.take(90)}")
                ChatOutbox.remove(this, text)
            }
        } catch (error: Exception) {
            OfflineBrain.chat(this, text, memories.map { it.text }).fold(
                onSuccess = { reply ->
                    updateQuickReply(reply, "离线回复 · 你说：${text.take(90)}")
                    ChatOutbox.remove(this, text)
                },
                onFailure = { offlineError ->
                    val message = listOf(error.message, offlineError.message)
                        .filterNotNull()
                        .joinToString(";")
                    updateQuickReply("后台对话失败", message.take(500))
                }
            )
        }
    }

    private fun updateQuickReply(title: String, text: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openPendingIntent())
            .build()
        getSystemService(NotificationManager::class.java)?.notify(QUICK_REPLY_ID, notification)
    }

    private fun savedServer(): String =
        getSharedPreferences("phonebridge", Context.MODE_PRIVATE)
            .getString("server", "ws://127.0.0.1:9503") ?: "ws://127.0.0.1:9503"

    private fun savedAccessToken(): String =
        SecureTokenStore(this).migrateLegacy(getSharedPreferences("phonebridge", Context.MODE_PRIVATE))

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Mote 常驻链路", NotificationManager.IMPORTANCE_LOW).apply {
                description = "保持后台服务可见，并显示监听、处理中、暂停和离线状态"
                setShowBadge(false)
            }
        )
    }

    override fun onDestroy() {
        isRunning = false
        stopVoiceChat(persist = true, notify = false)
        runCatching { unregisterReceiver(screenStateReceiver) }
        wakeLock?.release()
        wakeLock = null
        statusExecutor.shutdownNow()
        replyExecutor.shutdownNow()
        voiceModelExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

}
