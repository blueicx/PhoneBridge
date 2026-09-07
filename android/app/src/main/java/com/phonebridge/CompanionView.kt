package com.phonebridge

import android.content.Context
import android.database.ContentObserver
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.provider.Settings
import android.util.AttributeSet
import androidx.core.graphics.ColorUtils
import android.os.Handler
import android.os.Looper
import android.view.ViewConfiguration
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private val MOOD_CALM_COLOR = 0xFF9EE37D.toInt()
private val MOOD_HAPPY_COLOR = 0xFF8FF0C4.toInt()
private val MOOD_ALERT_COLOR = 0xFFFFC86B.toInt()
private val MOOD_CURIOUS_COLOR = 0xFF79D9FF.toInt()
private val MOOD_LONELY_COLOR = 0xFF7E8CA8.toInt()
private val MOOD_SLEEPY_COLOR = 0xFF54677D.toInt()
private val GHOST_MIST_LIGHT = 0xFFF9FDFF.toInt()
private val GHOST_MIST_DEEP = 0xFF61789E.toInt()

class CompanionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Listener {
        fun onCompanionTouched(x: Float, y: Float)
        fun onCompanionLongPressed()
        fun onCompanionLongPressReleased()
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val groundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val auraPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mouthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val stagePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stageGridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val stageHorizonPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stageVignettePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bodyPath = Path()
    private val rect = RectF()

    private var state = PetState()
    private var displayedEmotion = PetEmotion()
    private var accentColor = Color.parseColor("#8FF0C4")
    private var secondaryColor = Color.parseColor("#FFC86B")
    private var startTime = System.nanoTime()
    private var lastFrame = 0L
    private var animationSeconds = 0f
    private var animatorDurationScale = 1f
    private var moodGlowColor = MOOD_CURIOUS_COLOR
    private var bodyShaderAppearance: PetAppearance? = null
    private var bodyShaderAccent = 0
    private var bodyShaderSecondary = 0
    private var bodyShaderWidth = 0
    private var bodyShaderHeight = 0
    private var glowShaderEmotion: PetEmotion? = null
    private var glowShaderConnected: Boolean? = null
    private var glowShaderAccent = 0
    private var glowShaderWidth = 0
    private var glowShaderHeight = 0
    private var groundShaderWidth = 0
    private var groundShaderHeight = 0
    private var stageShaderAppearance: PetAppearance? = null
    private var stageShaderEmotion: PetEmotion? = null
    private var stageShaderAccent = 0
    private var stageShaderSecondary = 0
    private var stageShaderWidth = 0
    private var stageShaderHeight = 0
    private var stageHorizonWidth = 0
    private var stageHorizonHeight = 0
    private var stageHorizonAccent = 0
    private var stageHorizonSecondary = 0
    private var stageVignetteWidth = 0
    private var stageVignetteHeight = 0
    private var gazeX = 0f
    private var gazeY = 0f
    private var gazeTargetX = 0f
    private var gazeTargetY = 0f
    private var lifePhase = 0f
    private var idlePhase = 0f
    private var idleGazeX = 0f
    private var idleGazeY = 0f
    private var touchActive = false
    private var touchPulse = 0f
    private var speakPulse = 0f
    private var voiceListeningOverride = false
    private var voiceSpeakingOverride = false
    private var voiceListeningAmount = 0f
    private var voiceSpeakingAmount = 0f
    private var listener: Listener? = null
    private val touchHandler = Handler(Looper.getMainLooper())
    private var longPressFired = false
    private var petting = false
    private var petStrokeDistance = 0f
    private var petJoy = 0f
    private var nextPetFeedbackAt = 0f
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var lastPetX = 0f
    private var lastPetY = 0f
    private val petTrail = ArrayDeque<PetTrailSpark>()
    private val longPressSlopPx = ViewConfiguration.get(context).scaledTouchSlop * 3f
    private val longPressRunnable = Runnable {
        longPressFired = true
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        listener?.onCompanionLongPressed()
    }

    init {
        isClickable = true
        isLongClickable = true
    }

    private val animationSettingsObserver = object : ContentObserver(touchHandler) {
        override fun onChange(selfChange: Boolean) {
            refreshAnimatorScale()
            restartAnimationIfEligible()
        }
    }

    private fun animationsEnabled(): Boolean =
        isAttachedToWindow &&
            windowVisibility == View.VISIBLE &&
            isShown &&
            animatorDurationScale > 0f

    private fun vitality(): Float {
        val emotionEnergy = displayedEmotion.joy * .30f +
            displayedEmotion.attention * .32f +
            displayedEmotion.tension * .22f -
            displayedEmotion.fatigue * .34f -
            displayedEmotion.loneliness * .16f
        return (state.normalizedEnergy() * .54f + (.72f + emotionEnergy) * .46f + petJoy * .10f)
            .coerceIn(.34f, 1.18f)
    }

    private fun idleGazeStrength(): Float {
        val emotionStrength = .24f + displayedEmotion.attention * .42f +
            displayedEmotion.joy * .20f + displayedEmotion.tension * .14f -
            displayedEmotion.fatigue * .38f - displayedEmotion.loneliness * .18f
        return emotionStrength.coerceIn(.18f, 1f) * (.52f + state.normalizedEnergy() * .48f)
    }

    private fun moodPosture(): Float {
        val e = displayedEmotion
        val curious = (sin(idlePhase * .55f) * 4.2f + gazeX * 1.8f) * e.attention
        val happy = sin(idlePhase * .42f) * 2.4f * e.joy
        val alert = sin(idlePhase * 1.10f + 2.1f) * 1.6f * e.tension
        val lonely = (-2.7f + sin(idlePhase * .14f) * .5f) * e.loneliness
        val sleepy = (2.9f + sin(idlePhase * .11f) * .7f) * e.fatigue
        val calm = sin(idlePhase * .17f) * 1.1f * (
            1f - maxOf(e.joy, e.tension, e.fatigue, e.loneliness, e.attention)
            ).coerceAtLeast(.08f)
        return (curious + happy + alert + lonely + sleepy + calm) *
            vitality().coerceAtLeast(.55f)
    }

    private fun emotionColor(): Int {
        var color = MOOD_CALM_COLOR
        color = ColorUtils.blendARGB(color, MOOD_HAPPY_COLOR, displayedEmotion.joy)
        color = ColorUtils.blendARGB(color, MOOD_CURIOUS_COLOR, displayedEmotion.attention)
        color = ColorUtils.blendARGB(color, MOOD_ALERT_COLOR, displayedEmotion.tension)
        color = ColorUtils.blendARGB(color, MOOD_SLEEPY_COLOR, displayedEmotion.fatigue)
        color = ColorUtils.blendARGB(color, MOOD_LONELY_COLOR, displayedEmotion.loneliness)
        return color
    }

    private fun refreshEmotion(animated: Boolean, dt: Float = 16f) {
        val target = state.emotion
        if (!animated) {
            displayedEmotion = target
            return
        }
        fun approach(current: Float, wanted: Float): Float =
            current + (wanted - current) * min(1f, dt / 430f)
        displayedEmotion = PetEmotion(
            joy = approach(displayedEmotion.joy, target.joy),
            tension = approach(displayedEmotion.tension, target.tension),
            fatigue = approach(displayedEmotion.fatigue, target.fatigue),
            loneliness = approach(displayedEmotion.loneliness, target.loneliness),
            attention = approach(displayedEmotion.attention, target.attention)
        )
    }

    private fun refreshAnimatorScale() {
        animatorDurationScale = Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        )
    }

    private fun restartAnimationIfEligible() {
        lastFrame = 0L
        if (animationsEnabled()) postInvalidateOnAnimation()
    }

    private fun requestRedraw() {
        if (isAttachedToWindow && windowVisibility == View.VISIBLE && isShown) {
            postInvalidate()
        }
    }

    private fun prepareShaders() {
        val w = width
        val h = height
        if (w == 0 || h == 0) return

        if (groundShaderWidth != w || groundShaderHeight != h) {
            val r = min(w, h) * .215f
            groundPaint.shader = RadialGradient(
                w * .5f,
                h * .52f + r * 1.04f,
                r * 1.12f,
                intArrayOf(Color.argb(78, 2, 10, 16), Color.TRANSPARENT),
                null,
                Shader.TileMode.CLAMP
            )
            groundShaderWidth = w
            groundShaderHeight = h
        }

        if (bodyShaderAppearance != state.appearance ||
            bodyShaderAccent != accentColor ||
            bodyShaderSecondary != secondaryColor ||
            bodyShaderWidth != w ||
            bodyShaderHeight != h
        ) {
            val r = min(w, h) * .215f
            val cx = w * .5f
            val cy = h * .52f
            bodyPaint.shader = when (state.appearance) {
                PetAppearance.SPRITE -> RadialGradient(
                    cx - r * .22f, cy - r * .42f, r * 1.72f,
                    intArrayOf(
                        Color.parseColor("#FBFFF2"),
                        Color.parseColor("#7CC48C"),
                        Color.parseColor("#2E6C50")
                    ),
                    null,
                    Shader.TileMode.CLAMP
                )
                PetAppearance.GHOST -> RadialGradient(
                    cx, cy - r * .30f, r * 1.78f,
                    intArrayOf(
                        GHOST_MIST_LIGHT,
                        ColorUtils.blendARGB(GHOST_MIST_DEEP, Color.WHITE, .38f),
                        ColorUtils.blendARGB(GHOST_MIST_DEEP, accentColor, .22f)
                    ),
                    null,
                    Shader.TileMode.CLAMP
                )
                PetAppearance.CIRCUIT -> RadialGradient(
                    cx, cy - r * .12f, r * 1.62f,
                    intArrayOf(
                        Color.parseColor("#FFF8CE"),
                        Color.parseColor("#F5C867"),
                        Color.parseColor("#315069")
                    ),
                    null,
                    Shader.TileMode.CLAMP
                )
                PetAppearance.CLOUD_WHALE -> RadialGradient(
                    cx, cy - r * .28f, r * 1.70f,
                    intArrayOf(
                        Color.parseColor("#FFFFFF"),
                        Color.parseColor("#A8E7FF"),
                        Color.parseColor("#5B6BBF")
                    ),
                    null,
                    Shader.TileMode.CLAMP
                )
                PetAppearance.RIMURU -> RadialGradient(
                    cx - r * .24f, cy - r * .46f, r * 1.82f,
                    intArrayOf(
                        Color.parseColor("#F7FFFF"),
                        Color.parseColor("#9FE4FF"),
                        Color.parseColor("#38A5DE"),
                        Color.parseColor("#174C80")
                    ),
                    null,
                    Shader.TileMode.CLAMP
                )
                else -> RadialGradient(
                    cx - r * .27f, cy - r * .40f, r * 1.66f,
                    intArrayOf(
                        Color.parseColor("#FAFFFE"),
                        Color.parseColor("#9DF5D2"),
                        Color.parseColor("#37B391"),
                        Color.parseColor("#153F3C")
                    ),
                    null,
                    Shader.TileMode.CLAMP
                )
            }
            bodyShaderAppearance = state.appearance
            bodyShaderAccent = accentColor
            bodyShaderSecondary = secondaryColor
            bodyShaderWidth = w
            bodyShaderHeight = h
        }

        if (glowShaderEmotion != displayedEmotion.rounded() ||
            glowShaderConnected != state.connected ||
            glowShaderAccent != accentColor ||
            glowShaderWidth != w ||
            glowShaderHeight != h
        ) {
            val moodBase = emotionColor()
            moodGlowColor = ColorUtils.blendARGB(moodBase, accentColor, .34f)
            val r = min(w, h) * .215f
            val radius = r * (if (state.connected) 3.1f else 2.2f)
            glowPaint.shader = RadialGradient(
                w * .5f, h * .52f, radius,
                Color.argb(
                    if (state.connected) 105 else 45,
                    Color.red(moodGlowColor),
                    Color.green(moodGlowColor),
                    Color.blue(moodGlowColor)
                ),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
            glowShaderEmotion = displayedEmotion.rounded()
            glowShaderConnected = state.connected
            glowShaderAccent = accentColor
            glowShaderWidth = w
            glowShaderHeight = h
        }

        if (isImmersiveStage(w, h)) {
            if (stageShaderAppearance != state.appearance ||
                stageShaderEmotion != displayedEmotion.rounded() ||
                stageShaderAccent != accentColor ||
                stageShaderSecondary != secondaryColor ||
                stageShaderWidth != w ||
                stageShaderHeight != h
            ) {
                val deep = Color.parseColor("#04060F")
                val upper = ColorUtils.blendARGB(deep, moodGlowColor, .13f)
                val middle = ColorUtils.blendARGB(deep, accentColor, .06f)
                val lower = ColorUtils.blendARGB(deep, secondaryColor, .09f)
                stagePaint.shader = LinearGradient(
                    0f,
                    h * .04f,
                    w * .26f,
                    h.toFloat(),
                    intArrayOf(
                        Color.argb(196, Color.red(upper), Color.green(upper), Color.blue(upper)),
                        Color.argb(148, Color.red(middle), Color.green(middle), Color.blue(middle)),
                        Color.argb(172, Color.red(lower), Color.green(lower), Color.blue(lower))
                    ),
                    null,
                    Shader.TileMode.CLAMP
                )
                stageShaderAppearance = state.appearance
                stageShaderEmotion = displayedEmotion.rounded()
                stageShaderAccent = accentColor
                stageShaderSecondary = secondaryColor
                stageShaderWidth = w
                stageShaderHeight = h
            }

            if (stageHorizonWidth != w ||
                stageHorizonHeight != h ||
                stageHorizonAccent != accentColor ||
                stageHorizonSecondary != secondaryColor
            ) {
                stageHorizonPaint.shader = LinearGradient(
                    0f, 0f, w.toFloat(), 0f,
                    intArrayOf(
                        Color.TRANSPARENT,
                        ColorUtils.setAlphaComponent(accentColor, 58),
                        ColorUtils.setAlphaComponent(secondaryColor, 38),
                        Color.TRANSPARENT
                    ),
                    null,
                    Shader.TileMode.CLAMP
                )
                stageHorizonWidth = w
                stageHorizonHeight = h
                stageHorizonAccent = accentColor
                stageHorizonSecondary = secondaryColor
            }

            if (stageVignetteWidth != w || stageVignetteHeight != h) {
                val radius = max(w, h) * .74f
                stageVignettePaint.shader = RadialGradient(
                    w * .5f,
                    h * .54f,
                    radius,
                    intArrayOf(
                        Color.TRANSPARENT,
                        Color.argb(36, 1, 4, 10),
                        Color.argb(132, 1, 4, 10)
                    ),
                    null,
                    Shader.TileMode.CLAMP
                )
                stageVignetteWidth = w
                stageVignetteHeight = h
            }
        } else {
            stagePaint.shader = null
            stageShaderWidth = 0
            stageShaderHeight = 0
            stageHorizonWidth = 0
            stageHorizonHeight = 0
            stageHorizonAccent = 0
            stageHorizonSecondary = 0
            stageVignetteWidth = 0
            stageVignetteHeight = 0
        }
    }

    fun setListener(value: Listener) {
        listener = value
    }

    fun update(state: PetState) {
        this.state = state
        prepareShaders()
        requestRedraw()
    }

    fun setPalette(accent: Int, secondary: Int) {
        accentColor = accent
        secondaryColor = secondary
        prepareShaders()
        requestRedraw()
    }

    fun poke() {
        touchPulse = 1f
        requestRedraw()
    }

    private class PetTrailSpark(
        val x: Float,
        val y: Float,
        val seed: Float,
        var life: Float = 1f
    )

    fun speakPulse() {
        speakPulse = 1f
        requestRedraw()
    }

    fun setVoiceState(listening: Boolean, speaking: Boolean) {
        val changed = voiceListeningOverride != listening || voiceSpeakingOverride != speaking
        voiceListeningOverride = listening
        voiceSpeakingOverride = speaking
        if (changed) requestRedraw()
    }

    private fun isListening() = state.listening || voiceListeningOverride

    private fun isSpeaking() = state.speaking || voiceSpeakingOverride || speakPulse > 0f

    private fun refreshVoiceAmounts(animated: Boolean, dt: Float = 16f) {
        val listeningTarget = if (isListening()) 1f else 0f
        val speakingTarget = if (isSpeaking()) 1f else 0f
        if (!animated) {
            voiceListeningAmount = listeningTarget
            voiceSpeakingAmount = speakingTarget
            return
        }
        voiceListeningAmount += (listeningTarget - voiceListeningAmount) * min(1f, dt / 180f)
        voiceSpeakingAmount += (speakingTarget - voiceSpeakingAmount) * min(1f, dt / 240f)
    }

    private fun isImmersiveStage(w: Int, h: Int): Boolean =
        w > 0 && h > 0 && h.toFloat() / w > 1.08f

    private fun cycle(value: Float): Float = ((value % 1f) + 1f) % 1f

    private fun drawImmersiveStage(canvas: Canvas, w: Float, h: Float, seconds: Float) {
        val radius = min(w, h) * .215f
        rect.set(0f, 0f, w, h)
        stagePaint.style = Paint.Style.FILL
        canvas.drawRoundRect(rect, radius * .32f, radius * .32f, stagePaint)

        // Tiny parallax motes read as depth without turning the stage into soft blobs.
        particlePaint.style = Paint.Style.FILL
        repeat(30) { index ->
            val seed = index * 12.9898f
            val drift = seconds * (.014f + (index % 5) * .0038f)
            val fx = cycle((sin(seed) * .5f + .5f) + drift * (if (index % 2 == 0) 1f else -.35f))
            val fy = 1f - cycle((cos(seed * 1.71f) * .5f + .5f) + drift * .58f)
            val depth = .22f + (index % 7) / 7f * .78f
            val px = fx * w - gazeX * w * .018f * depth
            val py = fy * h * .82f - gazeY * h * .010f * depth
            particlePaint.color = when (index % 3) {
                0 -> Color.argb((24 + depth * 44).toInt(), 255, 255, 255)
                1 -> Color.argb(
                    (18 + depth * 36).toInt(),
                    Color.red(accentColor),
                    Color.green(accentColor),
                    Color.blue(accentColor)
                )
                else -> Color.argb(
                    (16 + depth * 32).toInt(),
                    Color.red(secondaryColor),
                    Color.green(secondaryColor),
                    Color.blue(secondaryColor)
                )
            }
            canvas.drawCircle(px, py, max(.7f, depth * 1.45f), particlePaint)
        }

        // A receding light floor anchors the companion and makes focus mode feel spatial.
        stageGridPaint.color = Color.argb(
            42,
            Color.red(accentColor),
            Color.green(accentColor),
            Color.blue(accentColor)
        )
        stageGridPaint.strokeWidth = max(1f, radius * .012f)
        val horizonY = h * .685f
        val floorBottom = h * .985f
        repeat(9) { row ->
            val progress = (row + 1) / 9f
            val y = horizonY + (floorBottom - horizonY) * progress * progress
            canvas.drawLine(w * .04f, y, w * .96f, y, stageGridPaint)
        }
        repeat(13) { column ->
            val spread = (column - 6) / 6f
            val sway = sin(seconds * .23f + column * .52f) * w * .012f - gazeX * w * .028f
            canvas.drawLine(
                w * .5f + sway, horizonY,
                w * .5f + spread * w * 1.18f, floorBottom,
                stageGridPaint
            )
        }

        // A quiet horizon band separates sky from floor without stealing focus.
        stageHorizonPaint.style = Paint.Style.FILL
        rect.set(
            w * .08f,
            horizonY - radius * .13f,
            w * .92f,
            horizonY + radius * .13f
        )
        canvas.drawRoundRect(rect, radius * .13f, radius * .13f, stageHorizonPaint)

        // Darkened edges pull attention to the companion while preserving HUD contrast.
        stageVignettePaint.style = Paint.Style.FILL
        canvas.drawRect(0f, 0f, w, h, stageVignettePaint)
        particlePaint.style = Paint.Style.STROKE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = System.nanoTime()
        val animating = animationsEnabled()
        if (!animating) {
            lastFrame = 0L
        } else {
            val dt = if (lastFrame == 0L) 16f else (now - lastFrame) / 1_000_000f
            touchPulse = (touchPulse - dt / 420f).coerceAtLeast(0f)
            petJoy = (petJoy - dt / 2600f).coerceAtLeast(0f)
            speakPulse = (speakPulse - dt / 900f).coerceAtLeast(0f)
            val liveliness = vitality()
            lifePhase += dt / 1000f * liveliness
            idlePhase += dt / 1000f
            if (touchActive) {
                idleGazeX += (0f - idleGazeX) * min(1f, dt / 260f)
                idleGazeY += (0f - idleGazeY) * min(1f, dt / 260f)
            } else {
                val idleAmount = idleGazeStrength()
                val wantedIdleX = sin(idlePhase * .43f + 1.1f) * .46f * idleAmount
                val wantedIdleY = sin(idlePhase * .29f) * .24f * idleAmount +
                    cos(idlePhase * .17f + .7f) * .08f * idleAmount
                idleGazeX += (wantedIdleX - idleGazeX) * min(1f, dt / 420f)
                idleGazeY += (wantedIdleY - idleGazeY) * min(1f, dt / 420f)
            }
            val effectiveGazeX = gazeTargetX + idleGazeX
            val effectiveGazeY = gazeTargetY + idleGazeY
            gazeX += (effectiveGazeX - gazeX) * min(1f, dt / 190f)
            gazeY += (effectiveGazeY - gazeY) * min(1f, dt / 190f)
            lastFrame = now
            animationSeconds = (now - startTime) / 1_000_000_000f
            refreshVoiceAmounts(animated = true, dt = dt)
            refreshEmotion(animated = true, dt = dt)
            val trailIterator = petTrail.iterator()
            while (trailIterator.hasNext()) {
                val spark = trailIterator.next()
                spark.life -= dt / 720f
                if (spark.life <= 0f) trailIterator.remove()
            }
        }
        if (!animating) {
            gazeX = gazeTargetX + idleGazeX
            gazeY = gazeTargetY + idleGazeY
            refreshVoiceAmounts(animated = false)
            refreshEmotion(animated = false)
        }
        val seconds = animationSeconds
        val w = width.toFloat()
        val h = height.toFloat()
        prepareShaders()
        val liveliness = vitality()
        val motionScale = .54f + liveliness * .46f
        val cx = w * 0.5f +
            cos(lifePhase * 0.7f) * w * 0.008f * motionScale +
            gazeX * 8f
        val cy = h * 0.52f +
            sin(lifePhase * 0.9f) * h * 0.014f * motionScale +
            gazeY * 7f
        val breath = when (state.appearance) {
            PetAppearance.SPRITE -> sin(lifePhase * 2.6f)
            PetAppearance.GHOST -> sin(lifePhase * .8f)
            PetAppearance.CIRCUIT -> if ((lifePhase % 1.4f) < .12f) 1f else 0f
            PetAppearance.RIMURU -> sin(lifePhase * 1.25f)
            else -> sin(lifePhase * 1.9f)
        }
        val nominalCx = w * .5f
        val nominalCy = h * .52f
        val nominalRadius = min(w, h) * .215f
        val baseRadius = min(w, h) * (
            .215f + state.normalizedEnergy() * .022f +
                breath * (.003f + vitality() * .007f) +
                touchPulse * .05f
            )
        val shaderScale = if (nominalRadius > 0f) baseRadius / nominalRadius else 1f

        if (isImmersiveStage(width, height)) {
            drawImmersiveStage(canvas, w, h, seconds)
        }

        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(shaderScale, shaderScale)
        canvas.translate(-nominalCx, -nominalCy)

        glowPaint.alpha = (
            (if (state.connected) 215 else 155) *
                (.56f + liveliness * .44f)
            ).toInt().coerceIn(32, 255)
        canvas.drawCircle(
            nominalCx,
            nominalCy,
            nominalRadius * (if (state.connected) 3.1f else 2.2f),
            glowPaint
        )

        rect.set(
            nominalCx - nominalRadius * 1.12f,
            nominalCy + nominalRadius * .58f,
            nominalCx + nominalRadius * 1.12f,
            nominalCy + nominalRadius * 1.50f
        )
        canvas.drawOval(rect, groundPaint)

        drawOrbit(canvas, nominalCx, nominalCy, nominalRadius, seconds, moodGlowColor)
        drawBody(canvas, nominalCx, nominalCy, nominalRadius, seconds, moodGlowColor, breath)

        canvas.save()
        canvas.rotate(moodPosture(), nominalCx, nominalCy)
        drawEyes(canvas, nominalCx, nominalCy, nominalRadius, seconds, lifePhase)
        if (state.appearance != PetAppearance.CIRCUIT && state.appearance != PetAppearance.RIMURU) {
            drawMouth(canvas, nominalCx, nominalCy, nominalRadius, seconds)
        }
        drawMoodWeather(canvas, nominalCx, nominalCy, nominalRadius, seconds)
        canvas.restore()

        drawSignalRings(canvas, nominalCx, nominalCy, nominalRadius, seconds)

        drawAmbient(canvas, nominalCx, nominalCy, nominalRadius, seconds, moodGlowColor)
        canvas.restore()

        if (petJoy > .04f) drawJoyHeart(canvas, w, h, seconds)
        drawPetTrail(canvas, accentColor)

        if (animating) postInvalidateOnAnimation()
    }

    private fun drawBody(
        canvas: Canvas, cx: Float, cy: Float, radius: Float,
        seconds: Float, moodColor: Int, breath: Float
    ) {
        when (state.appearance) {
            PetAppearance.SPRITE -> {
                bodyPaint.style = Paint.Style.FILL
                drawFox(canvas, cx, cy, radius, seconds, breath)
            }
            PetAppearance.GHOST -> {
                bodyPaint.style = Paint.Style.FILL
                drawCat(canvas, cx, cy, radius, seconds, breath)
            }
            PetAppearance.CLOUD_WHALE -> {
                bodyPaint.style = Paint.Style.FILL
                drawCloudWhale(canvas, cx, cy, radius, seconds, breath)
            }
            PetAppearance.RIMURU -> {
                bodyPaint.style = Paint.Style.FILL
                drawRimuru(canvas, cx, cy, radius, seconds, breath)
            }
            PetAppearance.CIRCUIT -> {
                bodyPaint.style = Paint.Style.FILL
                bodyPaint.strokeWidth = max(3f, radius*.07f)
                drawMecha(canvas, cx, cy, radius, seconds)
                ringPaint.strokeWidth = radius*.055f
                ringPaint.color = Color.parseColor("#FFD166")
                repeat(4) { i ->
                    val inset = radius*(.30f+i*.19f)
                    rect.set(cx-inset, cy-inset, cx+inset, cy+inset)
                    canvas.drawArc(rect, seconds*70f+i*90f, 54f, false, ringPaint)
                }
            }
            else -> {
                bodyPaint.style = Paint.Style.FILL
                drawCore(canvas, cx, cy, radius, seconds, breath, moodColor)
            }
        }
    }

    private fun rimuruFloat(seconds: Float, radius: Float) =
        sin(seconds * 1.18f) * radius * .055f

    private fun drawRimuru(
        canvas: Canvas, cx: Float, cy: Float,
        radius: Float, seconds: Float, breath: Float
    ) {
        val wobble = sin(seconds * 1.7f) * radius * .035f
        val top = cy - radius * (.95f + breath * .035f)
        val middle = cy + radius * .10f
        val bottom = cy + radius * .60f
        val halfWidth = radius * (1.05f + wobble * .16f)
        val left = cx - halfWidth
        val right = cx + halfWidth

        auraPaint.style = Paint.Style.STROKE
        auraPaint.strokeWidth = radius * .09f
        auraPaint.color = Color.argb(42, 150, 226, 255)
        canvas.drawCircle(cx, cy - radius * .06f, radius * 1.16f, auraPaint)

        bodyPath.reset()
        bodyPath.moveTo(left, middle)
        bodyPath.cubicTo(
            left, top + radius * .34f,
            cx - radius * .58f, top,
            cx + wobble * .22f, top
        )
        bodyPath.cubicTo(
            cx + radius * .58f, top,
            right, top + radius * .34f,
            right, middle
        )
        bodyPath.cubicTo(
            right, bottom - radius * .08f,
            cx + radius * .50f, bottom + radius * .10f,
            cx, bottom + radius * .10f
        )
        bodyPath.cubicTo(
            cx - radius * .50f, bottom + radius * .10f,
            left, bottom - radius * .08f,
            left, middle
        )
        bodyPath.close()
        canvas.drawPath(bodyPath, bodyPaint)

        canvas.save()
        canvas.clipPath(bodyPath)
        innerPaint.style = Paint.Style.FILL
        innerPaint.shader = RadialGradient(
            cx, bottom, radius * 1.02f,
            intArrayOf(Color.argb(0, 9, 46, 88), Color.argb(132, 9, 46, 88)),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(
            left - radius * .2f, top - radius * .2f,
            right + radius * .2f, bottom + radius * .3f,
            innerPaint
        )
        innerPaint.shader = RadialGradient(
            cx - radius * .08f, cy + radius * .02f, radius * .68f,
            intArrayOf(Color.argb(126, 226, 252, 255), Color.argb(0, 226, 252, 255)),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy + radius * .04f, radius * .68f, innerPaint)
        innerPaint.shader = RadialGradient(
            cx + radius * .58f, cy + radius * .34f, radius * .48f,
            intArrayOf(Color.argb(84, 178, 238, 255), Color.argb(0, 178, 238, 255)),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx + radius * .58f, cy + radius * .34f, radius * .48f, innerPaint)
        innerPaint.shader = null

        ringPaint.style = Paint.Style.STROKE
        ringPaint.strokeWidth = radius * .07f
        ringPaint.color = Color.argb(58, 226, 252, 255)
        canvas.drawPath(bodyPath, ringPaint)
        canvas.restore()

        innerPaint.style = Paint.Style.FILL
        canvas.save()
        canvas.clipPath(bodyPath)
        canvas.rotate(-18f, cx - radius * .38f, cy - radius * .50f)
        innerPaint.color = Color.argb(196, 255, 255, 255)
        rect.set(
            cx - radius * .74f,
            cy - radius * .68f,
            cx - radius * .04f,
            cy - radius * .36f
        )
        canvas.drawOval(rect, innerPaint)
        canvas.restore()

        innerPaint.color = Color.argb(96, 236, 253, 255)
        canvas.drawCircle(cx + radius * .48f, cy - radius * .52f, radius * .07f, innerPaint)
        innerPaint.color = Color.argb(52, 226, 252, 255)
        canvas.drawCircle(cx - radius * .12f, cy + radius * .44f, radius * .10f, innerPaint)

        ringPaint.style = Paint.Style.STROKE
        ringPaint.strokeWidth = max(2f, radius * .032f)
        ringPaint.color = Color.argb(216, 104, 178, 226)
        canvas.drawPath(bodyPath, ringPaint)

        ringPaint.strokeWidth = max(1f, radius * .016f)
        ringPaint.color = Color.argb(96, 236, 253, 255)
        rect.set(cx - halfWidth * .68f, bottom - radius * .16f, cx + halfWidth * .30f, bottom + radius * .16f)
        canvas.drawArc(rect, 198f, 108f, false, ringPaint)
    }

    private fun drawCore(
        canvas: Canvas, cx: Float, cy: Float, radius: Float,
        seconds: Float, breath: Float, moodColor: Int
    ) {
        val size = radius * (.94f + breath * .03f + touchPulse * .05f)
        val angle = seconds * .18f
        fun vertexX(index: Int): Float {
            val theta = angle + index * Math.PI.toFloat() / 3f
            return cx + cos(theta) * size
        }
        fun vertexY(index: Int): Float {
            val theta = angle + index * Math.PI.toFloat() / 3f
            return cy + sin(theta) * size * .92f
        }

        bodyPath.reset()
        repeat(6) { index ->
            val x = vertexX(index)
            val y = vertexY(index)
            if (index == 0) bodyPath.moveTo(x, y) else bodyPath.lineTo(x, y)
        }
        bodyPath.close()
        auraPaint.style = Paint.Style.STROKE
        auraPaint.strokeWidth = size * .18f
        auraPaint.color = Color.argb(
            (34 + vitality() * 24).toInt(),
            Color.red(moodColor),
            Color.green(moodColor),
            Color.blue(moodColor)
        )
        canvas.drawPath(bodyPath, auraPaint)
        canvas.drawPath(bodyPath, bodyPaint)

        canvas.save()
        canvas.clipPath(bodyPath)

        innerPaint.style = Paint.Style.FILL
        innerPaint.shader = RadialGradient(
            cx, cy + size * .52f, size * 1.08f,
            Color.argb(118, 6, 38, 35),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy + size * .52f, size, innerPaint)

        innerPaint.shader = RadialGradient(
            cx - size * .10f, cy - size * .14f, size * .88f,
            ColorUtils.blendARGB(Color.WHITE, moodColor, .24f),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, size * .70f, innerPaint)
        innerPaint.shader = null

        innerPaint.style = Paint.Style.STROKE
        innerPaint.strokeWidth = max(1f, radius * .017f)
        innerPaint.color = Color.argb(78, 226, 255, 245)
        repeat(6) { index ->
            canvas.drawLine(cx, cy, vertexX(index), vertexY(index), innerPaint)
        }

        innerPaint.style = Paint.Style.FILL
        innerPaint.color = Color.argb(104, 255, 255, 255)
        rect.set(
            cx - size * .64f,
            cy - size * .74f,
            cx + size * .04f,
            cy - size * .08f
        )
        canvas.save()
        canvas.rotate(-32f, cx - size * .30f, cy - size * .41f)
        canvas.drawOval(rect, innerPaint)
        canvas.restore()

        innerPaint.color = Color.argb(42, 12, 58, 52)
        rect.set(
            cx + size * .14f,
            cy + size * .16f,
            cx + size * .76f,
            cy + size * .80f
        )
        canvas.drawOval(rect, innerPaint)

        innerPaint.shader = RadialGradient(
            cx, cy, size * .48f,
            Color.argb(214, Color.red(moodColor), Color.green(moodColor), Color.blue(moodColor)),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy + size * .02f, size * .46f, innerPaint)
        innerPaint.shader = null
        canvas.restore()

        auraPaint.style = Paint.Style.STROKE
        auraPaint.strokeWidth = max(1.6f, radius * .034f)
        auraPaint.color = ColorUtils.blendARGB(
            ColorUtils.blendARGB(accentColor, Color.WHITE, .44f),
            moodColor,
            .28f
        )
        auraPaint.alpha = (168 + petJoy * 54).toInt().coerceIn(120, 235)
        canvas.drawPath(bodyPath, auraPaint)

        ringPaint.style = Paint.Style.STROKE
        ringPaint.strokeWidth = max(1f, radius * .013f)
        ringPaint.color = Color.argb(96, 4, 22, 20)
        canvas.drawPath(bodyPath, ringPaint)

        repeat(4) { index ->
            val phase = seconds * (.62f + index * .07f) + index * Math.PI.toFloat() / 2f
            val orbit = size * (1.19f + .06f * sin(seconds * .84f + index))
            val shardX = cx + cos(phase) * orbit * 1.05f
            val shardY = cy + sin(phase) * orbit * .82f
            val shardSize = radius * (.034f + .010f * sin(seconds * 2.1f + index))
            particlePaint.style = Paint.Style.FILL
            particlePaint.strokeWidth = radius * .02f
            particlePaint.color = Color.argb(
                (68 + 42 * abs(sin(seconds * 1.25f + index))).toInt(),
                Color.red(accentColor),
                Color.green(accentColor),
                Color.blue(accentColor)
            )
            bodyPath.reset()
            bodyPath.moveTo(shardX, shardY - shardSize)
            bodyPath.lineTo(shardX + shardSize * .72f, shardY)
            bodyPath.lineTo(shardX, shardY + shardSize)
            bodyPath.lineTo(shardX - shardSize * .72f, shardY)
            bodyPath.close()
            canvas.drawPath(bodyPath, particlePaint)
        }
    }

    private fun drawFox(
        canvas: Canvas, cx: Float, cy: Float, radius: Float,
        seconds: Float, breath: Float
    ) {
        val wagSpeed = 1.7f + petJoy * 3.4f
        val sway = sin(seconds * wagSpeed) * radius * (.10f + petJoy * .045f)
        bodyPath.reset()
        bodyPath.moveTo(cx - radius * .30f, cy - radius * .58f)
        bodyPath.lineTo(cx - radius * .78f, cy - radius * (1.16f + breath * .03f))
        bodyPath.lineTo(cx - radius * .10f, cy - radius * .82f)
        bodyPath.lineTo(cx + radius * .10f, cy - radius * .82f)
        bodyPath.lineTo(cx + radius * .78f, cy - radius * (1.16f + breath * .03f))
        bodyPath.lineTo(cx + radius * .30f, cy - radius * .58f)
        bodyPath.cubicTo(
            cx + radius * .74f, cy - radius * .12f,
            cx + radius * .62f, cy + radius * .72f,
            cx, cy + radius * (.90f + breath * .03f)
        )
        bodyPath.cubicTo(
            cx - radius * .62f, cy + radius * .72f,
            cx - radius * .74f, cy - radius * .12f,
            cx - radius * .30f, cy - radius * .58f
        )
        bodyPath.close()
        canvas.drawPath(bodyPath, bodyPaint)

        val foxLight = ColorUtils.blendARGB(accentColor, Color.WHITE, .28f)
        innerPaint.color = Color.argb(
            (48 + petJoy * 58).toInt(),
            Color.red(foxLight),
            Color.green(foxLight),
            Color.blue(foxLight)
        )
        rect.set(cx - radius * .26f, cy + radius * .16f, cx + radius * .26f, cy + radius * .72f)
        canvas.drawOval(rect, innerPaint)

        bodyPath.reset()
        bodyPath.moveTo(cx + radius * .48f, cy + radius * .48f)
        bodyPath.cubicTo(
            cx + radius * 1.22f, cy + radius * .38f,
            cx + radius * 1.18f + sway, cy - radius * .28f,
            cx + radius * .68f + sway, cy - radius * .48f
        )
        bodyPath.cubicTo(
            cx + radius * 1.02f + sway, cy + radius * .02f,
            cx + radius * .92f, cy + radius * .52f,
            cx + radius * .34f, cy + radius * .78f
        )
        bodyPath.close()
        canvas.drawPath(bodyPath, bodyPaint)
    }

    private fun drawCat(
        canvas: Canvas, cx: Float, cy: Float, radius: Float,
        seconds: Float, breath: Float
    ) {
        val x = cx + mistCatSway(seconds, radius)
        val y = cy + mistCatFloat(seconds, radius)

        // A thick curled tail reads as cat rather than fox; its root stays under the haunch.
        bodyPath.reset()
        bodyPath.moveTo(x + radius * .38f, y + radius * .52f)
        bodyPath.cubicTo(
            x + radius * 1.08f, y + radius * (.72f - breath * .01f),
            x + radius * 1.20f, y + radius * .18f,
            x + radius * .92f, y - radius * .24f
        )
        bodyPath.cubicTo(
            x + radius * .78f, y - radius * .46f,
            x + radius * .50f, y - radius * .50f,
            x + radius * .34f, y - radius * .36f
        )
        bodyPath.cubicTo(
            x + radius * .64f, y - radius * .30f,
            x + radius * .86f, y - radius * .06f,
            x + radius * .78f, y + radius * .22f
        )
        bodyPath.cubicTo(
            x + radius * .70f, y + radius * .54f,
            x + radius * .46f, y + radius * .66f,
            x + radius * .22f, y + radius * .58f
        )
        bodyPath.close()
        canvas.drawPath(bodyPath, bodyPaint)

        // One continuous silhouette: cheek, pointed ears, rounded skull, chest and haunches.
        bodyPath.reset()
        bodyPath.moveTo(x - radius * .60f, y - radius * .40f)
        bodyPath.cubicTo(
            x - radius * .72f, y - radius * .68f,
            x - radius * (.68f - breath * .005f), y - radius * 1.05f,
            x - radius * .50f, y - radius * (1.26f + breath * .02f)
        )
        bodyPath.cubicTo(
            x - radius * .38f, y - radius * 1.02f,
            x - radius * .30f, y - radius * .90f,
            x - radius * .19f, y - radius * .84f
        )
        bodyPath.cubicTo(
            x - radius * .07f, y - radius * .93f,
            x + radius * .07f, y - radius * .93f,
            x + radius * .19f, y - radius * .84f
        )
        bodyPath.cubicTo(
            x + radius * .30f, y - radius * .90f,
            x + radius * .38f, y - radius * 1.02f,
            x + radius * .50f, y - radius * (1.26f + breath * .02f)
        )
        bodyPath.cubicTo(
            x + radius * (.68f - breath * .005f), y - radius * 1.05f,
            x + radius * .72f, y - radius * .68f,
            x + radius * .60f, y - radius * .40f
        )
        bodyPath.cubicTo(
            x + radius * .78f, y - radius * .10f,
            x + radius * .82f, y + radius * .36f,
            x + radius * .64f, y + radius * .70f
        )
        bodyPath.cubicTo(
            x + radius * .48f, y + radius * (1.00f + breath * .01f),
            x - radius * .48f, y + radius * (1.00f + breath * .01f),
            x - radius * .64f, y + radius * .70f
        )
        bodyPath.cubicTo(
            x - radius * .82f, y + radius * .36f,
            x - radius * .78f, y - radius * .10f,
            x - radius * .60f, y - radius * .40f
        )
        bodyPath.close()
        canvas.drawPath(bodyPath, bodyPaint)

        rect.set(x - radius * .44f, y - radius * .70f, x + radius * .44f, y + radius * .14f)
        innerPaint.color = Color.argb(58, 245, 253, 255)
        canvas.drawOval(rect, innerPaint)
        rect.set(x - radius * .28f, y + radius * .16f, x + radius * .28f, y + radius * .82f)
        innerPaint.color = Color.argb(46, 235, 250, 255)
        canvas.drawOval(rect, innerPaint)

        val earColor = ColorUtils.blendARGB(accentColor, secondaryColor, .25f)
        innerPaint.color = Color.argb(145, Color.red(earColor), Color.green(earColor), Color.blue(earColor))
        bodyPath.reset()
        bodyPath.moveTo(x - radius * .43f, y - radius * 1.03f)
        bodyPath.quadTo(x - radius * .32f, y - radius * .84f, x - radius * .19f, y - radius * .86f)
        bodyPath.quadTo(x - radius * .31f, y - radius * .94f, x - radius * .43f, y - radius * 1.03f)
        bodyPath.close()
        bodyPath.moveTo(x + radius * .43f, y - radius * 1.03f)
        bodyPath.quadTo(x + radius * .32f, y - radius * .84f, x + radius * .19f, y - radius * .86f)
        bodyPath.quadTo(x + radius * .31f, y - radius * .94f, x + radius * .43f, y - radius * 1.03f)
        bodyPath.close()
        canvas.drawPath(bodyPath, innerPaint)

        val tailGlow = ColorUtils.blendARGB(secondaryColor, accentColor, .40f)
        innerPaint.style = Paint.Style.STROKE
        innerPaint.strokeWidth = max(2f, radius * .035f)
        innerPaint.color = Color.argb(
            (105 + petJoy * 70).toInt(),
            Color.red(tailGlow),
            Color.green(tailGlow),
            Color.blue(tailGlow)
        )
        bodyPath.reset()
        bodyPath.moveTo(x + radius * .56f, y + radius * .50f)
        bodyPath.cubicTo(
            x + radius * 1.00f, y + radius * .55f,
            x + radius * 1.08f, y + radius * .17f,
            x + radius * .85f, y - radius * .15f
        )
        canvas.drawPath(bodyPath, innerPaint)

        if (petJoy > .04f) {
            innerPaint.strokeWidth = max(1.2f, radius * .022f)
            repeat(2) { wave ->
                val phase = cycle(seconds * (.38f + wave * .17f))
                innerPaint.color = Color.argb(
                    (68 * petJoy * (1f - phase)).toInt(),
                    Color.red(accentColor),
                    Color.green(accentColor),
                    Color.blue(accentColor)
                )
                val inset = radius * (.92f + phase * .24f + wave * .07f)
                rect.set(x - inset, y - inset * .86f, x + inset, y + inset * .86f)
                canvas.drawArc(rect, 118f, 104f, false, innerPaint)
            }
        }

        // The cat skull sits above the shared form origin; keep whiskers on the muzzle.
        innerPaint.color = ColorUtils.blendARGB(Color.WHITE, accentColor, .34f)
        innerPaint.alpha = 170
        bodyPath.reset()
        bodyPath.moveTo(x - radius * .36f, y - radius * .60f)
        bodyPath.quadTo(x - radius * .64f, y - radius * .70f, x - radius * .90f, y - radius * .72f)
        bodyPath.moveTo(x - radius * .36f, y - radius * .48f)
        bodyPath.quadTo(x - radius * .62f, y - radius * .42f, x - radius * .85f, y - radius * .36f)
        bodyPath.moveTo(x + radius * .36f, y - radius * .60f)
        bodyPath.quadTo(x + radius * .64f, y - radius * .70f, x + radius * .90f, y - radius * .72f)
        bodyPath.moveTo(x + radius * .36f, y - radius * .48f)
        bodyPath.quadTo(x + radius * .62f, y - radius * .42f, x + radius * .85f, y - radius * .36f)
        canvas.drawPath(bodyPath, innerPaint)
        innerPaint.style = Paint.Style.FILL
    }

    private fun mistCatSway(seconds: Float, radius: Float) =
        sin(seconds * .62f) * radius * .04f

    private fun mistCatFloat(seconds: Float, radius: Float) =
        sin(seconds * .80f) * radius * .05f

    private fun cloudWhaleFloat(seconds: Float, radius: Float) =
        sin(seconds * .72f) * radius * .035f

    private fun drawPetTrail(canvas: Canvas, color: Int) {
        petTrail.forEach { spark ->
            val age = spark.life.coerceIn(0f, 1f)
            val drift = (1f - age) * 18f
            val x = spark.x + cos(spark.seed * 6.28f) * drift * .45f
            val y = spark.y - drift
            val size = (2.2f + spark.seed * 3.4f) * (.45f + age * .55f)
            particlePaint.style = Paint.Style.FILL
            particlePaint.color = Color.argb(
                (128 * age).toInt(),
                Color.red(color),
                Color.green(color),
                Color.blue(color)
            )
            canvas.drawCircle(x, y, size, particlePaint)

            if (spark.seed > .62f) {
                bodyPath.reset()
                bodyPath.moveTo(x, y - size * 1.8f)
                bodyPath.lineTo(x + size * 1.25f, y)
                bodyPath.lineTo(x, y + size * 1.8f)
                bodyPath.lineTo(x - size * 1.25f, y)
                bodyPath.close()
                particlePaint.color = Color.argb(
                    (86 * age).toInt(),
                    Color.WHITE,
                    Color.WHITE,
                    Color.WHITE
                )
                canvas.drawPath(bodyPath, particlePaint)
            }
        }
        particlePaint.style = Paint.Style.STROKE
    }

    private fun drawJoyHeart(canvas: Canvas, w: Float, h: Float, seconds: Float) {
        val radius = min(w, h) * .215f
        val size = radius * (.10f + petJoy * .05f)
        val x = w * .5f + sin(seconds * .9f) * w * .17f
        val y = h * .21f - sin(seconds * 1.8f) * h * .012f
        particlePaint.style = Paint.Style.FILL
        particlePaint.color = Color.argb(
            (168 * petJoy).toInt(),
            255,
            148,
            176
        )
        canvas.drawCircle(x - size * .55f, y - size * .35f, size * .62f, particlePaint)
        canvas.drawCircle(x + size * .55f, y - size * .35f, size * .62f, particlePaint)
        bodyPath.reset()
        bodyPath.moveTo(x - size * 1.12f, y - size * .16f)
        bodyPath.lineTo(x + size * 1.12f, y - size * .16f)
        bodyPath.lineTo(x, y + size * 1.18f)
        bodyPath.close()
        canvas.drawPath(bodyPath, particlePaint)
        particlePaint.style = Paint.Style.STROKE
    }

    private fun drawSmallHeart(
        canvas: Canvas, x: Float, y: Float,
        size: Float, color: Int
    ) {
        particlePaint.style = Paint.Style.FILL
        particlePaint.color = color
        canvas.drawCircle(x - size * .55f, y - size * .35f, size * .58f, particlePaint)
        canvas.drawCircle(x + size * .55f, y - size * .35f, size * .58f, particlePaint)
        bodyPath.reset()
        bodyPath.moveTo(x - size * 1.08f, y - size * .14f)
        bodyPath.lineTo(x + size * 1.08f, y - size * .14f)
        bodyPath.lineTo(x, y + size * 1.14f)
        bodyPath.close()
        canvas.drawPath(bodyPath, particlePaint)
    }

    private fun drawMoodWeather(
        canvas: Canvas, cx: Float, cy: Float,
        radius: Float, seconds: Float
    ) {
        // Several signals may be visible at once; their weights keep the mix readable.
        val intensity = (.30f + vitality() * .70f).coerceIn(.18f, 1f)
        val e = displayedEmotion
        particlePaint.style = Paint.Style.STROKE
        particlePaint.strokeWidth = max(1.1f, radius * .018f)

        if (e.attention > .16f) {
            val alpha = (72 * intensity * e.attention).toInt()
            particlePaint.color = Color.argb(alpha, 168, 226, 255)
            repeat(2) { index ->
                val phase = cycle(seconds * (.17f + index * .05f) + index * .48f)
                val angle = (-126f + index * 31f + sin(idlePhase * .48f + index) * 9f) *
                    (Math.PI.toFloat() / 180f)
                val distance = radius * (1.15f + phase * .27f)
                val x = cx + cos(angle) * distance
                val y = cy + sin(angle) * distance * .91f
                canvas.drawLine(x, y, x + radius * .05f, y - radius * .03f, particlePaint)
                canvas.drawLine(x + radius * .05f, y - radius * .03f, x + radius * .10f, y, particlePaint)
            }
        }

        if (e.joy > .18f) {
            val alpha = (88 * intensity * e.joy).toInt()
            repeat(2) { index ->
                val phase = cycle(seconds * (.20f + index * .04f) + index * .52f)
                val x = cx + (index - .5f) * radius * .46f + sin(phase * 6.28f) * radius * .07f
                val y = cy - radius * (.70f + phase * .64f)
                drawSmallHeart(canvas, x, y, radius * .061f, Color.argb(alpha, 255, 158, 182))
            }
        }

        if (e.tension > .22f) {
            val alpha = (74 * intensity * e.tension).toInt()
            particlePaint.color = Color.argb(alpha, 255, 214, 138)
            repeat(2) { index ->
                val phase = cycle(seconds * .34f + index * .47f)
                val sweep = -52f + index * 58f + sin(idlePhase * 1.08f) * 8f
                rect.set(cx - radius * 1.20f, cy - radius * 1.20f, cx + radius * 1.20f, cy + radius * 1.20f)
                canvas.drawArc(rect, sweep, 10f + phase * 8f, false, particlePaint)
            }
        }

        if (e.loneliness > .24f) {
            val alpha = (62 * intensity * e.loneliness).toInt()
            particlePaint.color = Color.argb(alpha, 178, 196, 224)
            repeat(2) { index ->
                val phase = cycle(seconds * (.10f + index * .023f) + index * .38f)
                val x = cx + (if (index == 0) -1f else 1f) * radius * (.78f + index * .09f)
                val y = cy - radius * .18f + phase * radius * .86f
                canvas.drawLine(x, y, x, y + radius * .08f, particlePaint)
            }
        }

        if (e.fatigue > .28f) {
            val alpha = (66 * intensity * e.fatigue).toInt()
            innerPaint.style = Paint.Style.FILL
            innerPaint.color = Color.argb(alpha, 198, 216, 240)
            repeat(2) { index ->
                val phase = cycle(seconds * (.08f + index * .019f) + index * .44f)
                canvas.drawCircle(
                    cx + radius * (.46f + index * .18f),
                    cy - radius * (.74f + phase * .48f) + sin(phase * 6.28f) * radius * .03f,
                    max(1f, radius * (.025f - index * .004f)),
                    innerPaint
                )
            }
            innerPaint.style = Paint.Style.FILL
        }

        particlePaint.style = Paint.Style.STROKE
    }

    private fun drawLegacyMoodWeather(
        canvas: Canvas, cx: Float, cy: Float,
        radius: Float, seconds: Float
    ) {
        val intensity = (.34f + vitality() * .66f).coerceIn(.20f, 1f)
        particlePaint.style = Paint.Style.STROKE
        particlePaint.strokeWidth = max(1.1f, radius * .020f)

        when (state.mood) {
            PetMood.CURIOUS -> repeat(3) { index ->
                val phase = cycle(seconds * (.16f + index * .055f) + index / 3f)
                val angle = (-132f + index * 26f + sin(idlePhase * .48f + index) * 8f) *
                    (Math.PI.toFloat() / 180f)
                val distance = radius * (1.18f + phase * .28f)
                val alpha = (64 * intensity * (1f - phase)).toInt()
                particlePaint.color = Color.argb(alpha, 168, 226, 255)
                val x = cx + cos(angle) * distance
                val y = cy + sin(angle) * distance * .92f
                canvas.drawLine(x, y, x + radius * .05f, y - radius * .03f, particlePaint)
                canvas.drawLine(x + radius * .05f, y - radius * .03f, x + radius * .10f, y, particlePaint)
            }
            PetMood.HAPPY -> repeat(3) { index ->
                val phase = cycle(seconds * (.19f + index * .04f) + index / 3f)
                val alpha = (82 * intensity * (1f - phase)).toInt()
                val x = cx + (index - 1) * radius * .44f + sin(phase * 6.28f) * radius * .07f
                val y = cy - radius * (.72f + phase * .68f)
                drawSmallHeart(
                    canvas, x, y, radius * .065f,
                    Color.argb(alpha, 255, 158, 182)
                )
            }
            PetMood.ALERT -> repeat(3) { index ->
                val phase = cycle(seconds * .32f + index / 3f)
                val sweep = -54f + index * 54f + sin(idlePhase * 1.1f) * 8f
                rect.set(
                    cx - radius * 1.22f, cy - radius * 1.22f,
                    cx + radius * 1.22f, cy + radius * 1.22f
                )
                particlePaint.color = Color.argb((74 * intensity * (1f - phase)).toInt(), 255, 214, 138)
                canvas.drawArc(rect, sweep, 16f, false, particlePaint)
            }
            PetMood.LONELY -> repeat(3) { index ->
                val phase = cycle(seconds * (.09f + index * .023f) + index / 3f)
                val alpha = (56 * intensity * (1f - phase)).toInt()
                particlePaint.color = Color.argb(alpha, 178, 196, 224)
                val x = cx + (if (index % 2 == 0) -1f else 1f) * radius * (.78f + index * .08f)
                val y = cy - radius * .18f + phase * radius * .88f
                canvas.drawLine(x, y, x, y + radius * .08f, particlePaint)
            }
            PetMood.SLEEPY -> repeat(3) { index ->
                val phase = cycle(seconds * (.07f + index * .018f) + index / 3f)
                val alpha = (60 * intensity * (1f - phase)).toInt()
                innerPaint.color = Color.argb(alpha, 198, 216, 240)
                innerPaint.style = Paint.Style.FILL
                canvas.drawCircle(
                    cx + radius * (.46f + index * .17f),
                    cy - radius * (.76f + phase * .50f) + sin(phase * 6.28f) * radius * .03f,
                    max(1f, radius * (.025f - index * .003f)),
                    innerPaint
                )
            }
            else -> repeat(3) { index ->
                val phase = cycle(seconds * (.08f + index * .025f) + index / 3f)
                val alpha = (42 * intensity * (1f - phase)).toInt()
                particlePaint.color = Color.argb(
                    alpha,
                    Color.red(accentColor),
                    Color.green(accentColor),
                    Color.blue(accentColor)
                )
                val angle = (-108f + index * 36f) * (Math.PI.toFloat() / 180f)
                val distance = radius * (1.08f + phase * .18f)
                canvas.drawPoint(
                    cx + cos(angle) * distance,
                    cy + sin(angle) * distance,
                    particlePaint
                )
            }
        }

        innerPaint.style = Paint.Style.FILL
        particlePaint.style = Paint.Style.STROKE
    }

    private fun drawCloudWhale(
        canvas: Canvas, cx: Float, cy: Float,
        radius: Float, seconds: Float, breath: Float
    ) {
        val floatY = cloudWhaleFloat(seconds, radius)
        val x = cx
        val y = cy + floatY
        val leftWing = radius * (.14f - sin(seconds * .92f) * .07f)
        val rightWing = radius * (.14f + sin(seconds * .92f + .55f) * .07f)

        // A two-lobe fluke keeps the silhouette whale-like even when the broad wings glide.
        bodyPath.reset()
        bodyPath.moveTo(x, y + radius * .56f)
        bodyPath.cubicTo(
            x + radius * .09f, y + radius * (.82f + breath * .01f),
            x + radius * .40f, y + radius * (.90f + breath * .01f),
            x + radius * (.64f + breath * .01f), y + radius * 1.18f
        )
        bodyPath.cubicTo(
            x + radius * .26f, y + radius * 1.06f,
            x + radius * .08f, y + radius * (.98f + breath * .01f),
            x, y + radius * .88f
        )
        bodyPath.cubicTo(
            x - radius * .08f, y + radius * (.98f + breath * .01f),
            x - radius * .26f, y + radius * 1.06f,
            x - radius * (.64f + breath * .01f), y + radius * 1.18f
        )
        bodyPath.cubicTo(
            x - radius * .40f, y + radius * (.90f + breath * .01f),
            x - radius * .09f, y + radius * (.82f + breath * .01f),
            x, y + radius * .56f
        )
        bodyPath.close()
        canvas.drawPath(bodyPath, bodyPaint)

        // A broad manta-whale body: two gliding wings and a rounded cloud belly.
        bodyPath.reset()
        bodyPath.moveTo(x - radius * 1.20f, y + leftWing)
        bodyPath.cubicTo(
            x - radius * 1.04f, y - radius * .42f,
            x - radius * .36f, y - radius * (.88f + breath * .02f),
            x, y - radius * (.84f + breath * .02f)
        )
        bodyPath.cubicTo(
            x + radius * .36f, y - radius * (.88f + breath * .02f),
            x + radius * 1.04f, y - radius * .42f,
            x + radius * 1.20f, y + rightWing
        )
        bodyPath.cubicTo(
            x + radius * .78f, y + radius * .64f,
            x + radius * .32f, y + radius * (.92f + breath * .01f),
            x, y + radius * (.92f + breath * .01f)
        )
        bodyPath.cubicTo(
            x - radius * .32f, y + radius * (.92f + breath * .01f),
            x - radius * .78f, y + radius * .64f,
            x - radius * 1.20f, y + leftWing
        )
        bodyPath.close()
        canvas.drawPath(bodyPath, bodyPaint)

        rect.set(x - radius * .54f, y + radius * .14f, x + radius * .54f, y + radius * .74f)
        innerPaint.color = Color.argb(64, 244, 253, 255)
        canvas.drawOval(rect, innerPaint)

        // Ventral grooves are quiet, but they stop the belly from reading as a plain ellipse.
        innerPaint.style = Paint.Style.STROKE
        innerPaint.strokeWidth = max(1f, radius * .018f)
        innerPaint.color = Color.argb(46, 226, 247, 255)
        repeat(3) { row ->
            val inset = radius * (.14f + row * .13f)
            rect.set(
                x - radius * (.50f - row * .07f),
                y + radius * .20f + inset,
                x + radius * (.50f - row * .07f),
                y + radius * .68f + inset
            )
            canvas.drawArc(rect, 12f, 156f, false, innerPaint)
        }

        // Small pectoral fins anchor the wings to the body without adding visual noise.
        innerPaint.style = Paint.Style.FILL
        repeat(2) { side ->
            val sign = if (side == 0) -1f else 1f
            bodyPath.reset()
            bodyPath.moveTo(x + sign * radius * .44f, y + radius * .30f)
            bodyPath.cubicTo(
                x + sign * radius * (.72f - sin(seconds * .92f + side) * .03f),
                y + radius * .44f,
                x + sign * radius * (.80f - sin(seconds * .92f + side) * .03f),
                y + radius * .68f,
                x + sign * radius * .48f,
                y + radius * .64f
            )
            bodyPath.quadTo(x + sign * radius * .30f, y + radius * .47f, x + sign * radius * .44f, y + radius * .30f)
            bodyPath.close()
            innerPaint.color = Color.argb(82, 238, 252, 255)
            canvas.drawPath(bodyPath, innerPaint)
        }

        innerPaint.style = Paint.Style.FILL
        rect.set(x - radius * .18f, y - radius * .72f, x + radius * .18f, y - radius * .56f)
        innerPaint.color = Color.argb(105, 233, 250, 255)
        canvas.drawOval(rect, innerPaint)

        if (petJoy > .03f) {
            repeat(4) { index ->
                val rise = cycle(seconds * (.22f + index * .045f) + index / 4f)
                val bubbleAlpha = (96 * petJoy * (1f - rise)).toInt()
                innerPaint.color = Color.argb(bubbleAlpha, 238, 251, 255)
                canvas.drawCircle(
                    x + sin(index * 2.1f + seconds * .7f) * radius * .09f,
                    y - radius * (.64f + rise * .42f),
                    max(1.2f, radius * (.025f + index % 2 * .011f)),
                    innerPaint
                )
            }
        }

        innerPaint.color = Color.argb(48, 255, 255, 255)
        canvas.drawCircle(x - radius * .82f, y - radius * .26f, radius * .13f, innerPaint)
        canvas.drawCircle(x + radius * .74f, y - radius * .34f, radius * .11f, innerPaint)
    }

    private fun drawMecha(
        canvas: Canvas, cx: Float, cy: Float, radius: Float, seconds: Float
    ) {
        val charge = if ((seconds % 1.4f) < .12f) 1f else 0f
        repeat(2) { side ->
            val sign = if (side == 0) -1f else 1f
            bodyPath.reset()
            bodyPath.moveTo(cx + sign * radius * .28f, cy - radius * .68f)
            bodyPath.lineTo(cx + sign * radius * .88f, cy - radius * (1.24f + charge * .04f))
            bodyPath.lineTo(cx + sign * radius * .16f, cy - radius * .92f)
            bodyPath.close()
            canvas.drawPath(bodyPath, bodyPaint)
        }
        bodyPath.reset()
        bodyPath.moveTo(cx - radius * .28f, cy - radius * 1.02f)
        bodyPath.lineTo(cx + radius * .28f, cy - radius * 1.02f)
        bodyPath.lineTo(cx + radius * .18f, cy - radius * .62f)
        bodyPath.lineTo(cx + radius * .62f, cy - radius * .48f)
        bodyPath.lineTo(cx + radius * .94f, cy - radius * .10f)
        bodyPath.lineTo(cx + radius * .72f, cy + radius * .72f)
        bodyPath.lineTo(cx + radius * .24f, cy + radius * .92f)
        bodyPath.lineTo(cx - radius * .24f, cy + radius * .92f)
        bodyPath.lineTo(cx - radius * .72f, cy + radius * .72f)
        bodyPath.lineTo(cx - radius * .94f, cy - radius * .10f)
        bodyPath.lineTo(cx - radius * .62f, cy - radius * .48f)
        bodyPath.lineTo(cx - radius * .18f, cy - radius * .62f)
        bodyPath.close()
        canvas.drawPath(bodyPath, bodyPaint)

        repeat(4) { leg ->
            val sign = if (leg % 2 == 0) -1f else 1f
            val depth = if (leg < 2) .34f else .62f
            bodyPath.reset()
            bodyPath.moveTo(cx + sign * radius * depth, cy + radius * .52f)
            bodyPath.lineTo(cx + sign * radius * (depth + .18f), cy + radius * .52f)
            bodyPath.lineTo(cx + sign * radius * (depth + .10f), cy + radius * (1.02f + charge * .03f))
            bodyPath.lineTo(cx + sign * radius * (depth - .10f), cy + radius * (1.02f + charge * .03f))
            bodyPath.close()
            canvas.drawPath(bodyPath, bodyPaint)
        }

        bodyPath.reset()
        bodyPath.moveTo(cx + radius * .70f, cy + radius * .10f)
        bodyPath.lineTo(cx + radius * 1.18f, cy - radius * .18f)
        bodyPath.lineTo(cx + radius * .94f, cy + radius * .28f)
        bodyPath.lineTo(cx + radius * 1.22f, cy + radius * .54f)
        bodyPath.lineTo(cx + radius * .62f, cy + radius * .58f)
        bodyPath.close()
        canvas.drawPath(bodyPath, bodyPaint)

        innerPaint.style = Paint.Style.STROKE
        innerPaint.strokeWidth = max(2.5f, radius * .05f)
        innerPaint.color = Color.argb(
            (150 + charge * 80 + petJoy * 45).toInt(),
            255,
            209,
            102
        )
        bodyPath.reset()
        bodyPath.moveTo(cx - radius * .48f, cy - radius * .18f)
        bodyPath.lineTo(cx, cy - radius * .42f)
        bodyPath.lineTo(cx + radius * .48f, cy - radius * .18f)
        bodyPath.lineTo(cx + radius * .34f, cy + radius * .52f)
        bodyPath.lineTo(cx - radius * .34f, cy + radius * .52f)
        bodyPath.close()
        canvas.drawPath(bodyPath, innerPaint)

        innerPaint.style = Paint.Style.FILL

        repeat(3) { lamp ->
            val pulse = .55f + .45f * abs(sin(seconds * (2.1f + petJoy * 3.2f) + lamp))
            innerPaint.color = when (lamp) {
                0 -> Color.argb((78 + petJoy * 115 * pulse).toInt(), 143, 240, 196)
                1 -> Color.argb((70 + petJoy * 110 * pulse).toInt(), 121, 217, 255)
                else -> Color.argb((66 + petJoy * 105 * pulse).toInt(), 255, 209, 102)
            }
            canvas.drawCircle(
                cx + (lamp - 1) * radius * .21f,
                cy + radius * .20f,
                max(1.4f, radius * .035f),
                innerPaint
            )
        }
    }

    private fun drawAmbient(
        canvas: Canvas, cx: Float, cy: Float, radius: Float,
        seconds: Float, color: Int
    ) {
        particlePaint.strokeWidth = 2.6f
        val count = when (state.appearance) {
            PetAppearance.GHOST -> 14
            PetAppearance.CIRCUIT -> 8
            PetAppearance.CLOUD_WHALE -> 16
            PetAppearance.RIMURU -> 15
            else -> 13
        }
        particlePaint.style = if (state.appearance == PetAppearance.CIRCUIT) {
            Paint.Style.STROKE
        } else {
            Paint.Style.FILL
        }
        repeat(count) { index ->
            val phase = seconds * (.24f + index%5*.07f) + index
            val orbit = radius*(1.45f+index%7*.17f)
            var px = cx+cos(phase)*orbit
            var py = cy+sin(phase*1.23f)*orbit*.48f
            if (state.appearance == PetAppearance.GHOST) py += sin(seconds*1.7f+index)*radius*.18f
            particlePaint.strokeWidth = 2.6f
            particlePaint.color = when (state.appearance) {
                PetAppearance.SPRITE -> Color.argb(64, 178, 240, 148)
                PetAppearance.GHOST -> Color.argb(56, 228, 244, 255)
                PetAppearance.CIRCUIT -> Color.argb(96, 255, 205, 102)
                PetAppearance.CLOUD_WHALE -> Color.argb(70, 214, 245, 255)
                PetAppearance.RIMURU -> Color.argb(88, 168, 236, 255)
                else -> Color.argb((34+index%4*13), Color.red(color), Color.green(color), Color.blue(color))
            }

            if (state.appearance == PetAppearance.CIRCUIT) {
                canvas.drawLine(px, py, px+cos(phase*3.1f)*radius*.13f, py+sin(phase*2.7f)*radius*.13f, particlePaint)
            } else {
                canvas.drawCircle(px, py, 2.2f+index%3, particlePaint)
            }
        }
    }

    private fun drawOrbit(canvas: Canvas, cx: Float, cy: Float, radius: Float, seconds: Float, color: Int) {
        ringPaint.strokeWidth = 2.4f
        rect.set(cx - radius * 1.75f, cy - radius * .95f, cx + radius * 1.75f, cy + radius * 1.85f)
        ringPaint.color = Color.argb(46, Color.red(color), Color.green(color), Color.blue(color))
        canvas.drawArc(rect, -190f + seconds * 15f, 118f, false, ringPaint)
        ringPaint.color = Color.argb(72, Color.red(secondaryColor), Color.green(secondaryColor), Color.blue(secondaryColor))
        canvas.drawArc(rect, -20f - seconds * 21f, 54f, false, ringPaint)
    }

    private fun drawEyes(
        canvas: Canvas, cx: Float, cy: Float, radius: Float,
        seconds: Float, lifeTime: Float
    ) {
        val isGhost = state.appearance == PetAppearance.GHOST
        val isWhale = state.appearance == PetAppearance.CLOUD_WHALE
        val isSlime = state.appearance == PetAppearance.RIMURU
        val eyeOffsetX = if (isSlime) radius * .31f else radius * .30f
        val eyeOffsetY = when {
            isGhost -> -radius * .66f
            isSlime -> -radius * .16f
            isWhale -> -radius * .04f
            else -> -radius * .11f
        }
        val faceX = when {
            isGhost -> mistCatSway(seconds, radius)
            isSlime -> sin(seconds * 1.05f) * radius * .045f
            else -> 0f
        }
        val faceY = when {
            isGhost -> mistCatFloat(seconds, radius)
            isSlime -> rimuruFloat(seconds, radius)
            isWhale -> cloudWhaleFloat(seconds, radius)
            else -> 0f
        }
        val e = displayedEmotion
        val eyeOpen = (1f - e.fatigue * .58f + e.attention * .10f + e.tension * .06f)
            .coerceIn(.34f, 1.18f)
        val blinkRate = .36f + (1f - e.fatigue) * .25f
        val blinkPhase = abs(sin(lifeTime * blinkRate))
        val droop = radius * .035f * e.fatigue
        val blinkHeight = if (blinkPhase > .97f - e.fatigue * .03f) {
            radius * (.032f + eyeOpen * .012f)
        } else {
            radius * (if (isSlime) .23f else .19f) * eyeOpen +
                abs(sin(lifeTime * .23f)) * radius * .028f * e.fatigue
        }
        val eyeCenterY = cy + faceY + eyeOffsetY + droop
        eyePaint.color = if (isSlime) Color.parseColor("#1B4F86") else Color.parseColor("#08150E")
        canvas.drawRoundRect(
            cx + faceX - eyeOffsetX - radius*.14f,
            eyeCenterY - blinkHeight,
            cx + faceX - eyeOffsetX + radius*.14f,
            eyeCenterY + blinkHeight,
            blinkHeight,
            blinkHeight,
            eyePaint
        )
        canvas.drawRoundRect(
            cx + faceX + eyeOffsetX - radius*.14f,
            eyeCenterY - blinkHeight,
            cx + faceX + eyeOffsetX + radius*.14f,
            eyeCenterY + blinkHeight,
            blinkHeight,
            blinkHeight,
            eyePaint
        )
        eyePaint.color = Color.WHITE
        val sparkle = radius * (if (isSlime) .072f else .04f) * (1f + petJoy * .30f)
        canvas.drawCircle(
            cx + faceX - eyeOffsetX + gazeX * radius * .05f,
            eyeCenterY - blinkHeight * .35f,
            sparkle,
            eyePaint
        )
        canvas.drawCircle(
            cx + faceX + eyeOffsetX + gazeX * radius * .05f,
            eyeCenterY - blinkHeight * .35f,
            sparkle,
            eyePaint
        )
        if (isSlime) {
            eyePaint.color = Color.argb(216, 236, 252, 255)
            canvas.drawCircle(
                cx + faceX - eyeOffsetX + radius * .045f,
                eyeCenterY + blinkHeight * .30f,
                radius * .028f,
                eyePaint
            )
            canvas.drawCircle(
                cx + faceX + eyeOffsetX + radius * .045f,
                eyeCenterY + blinkHeight * .30f,
                radius * .028f,
                eyePaint
            )
        }
    }

    private fun drawMouth(canvas: Canvas, cx: Float, cy: Float, radius: Float, seconds: Float) {
        mouthPaint.color = Color.parseColor("#08150E")
        mouthPaint.strokeWidth = radius * .075f
        val isGhost = state.appearance == PetAppearance.GHOST
        val isWhale = state.appearance == PetAppearance.CLOUD_WHALE
        val isSlime = state.appearance == PetAppearance.RIMURU
        val faceX = when {
            isGhost -> mistCatSway(seconds, radius)
            isSlime -> sin(seconds * 1.05f) * radius * .045f
            else -> 0f
        }
        val faceY = when {
            isGhost -> mistCatFloat(seconds, radius)
            isSlime -> rimuruFloat(seconds, radius)
            isWhale -> cloudWhaleFloat(seconds, radius)
            else -> 0f
        }
        val x = cx + faceX
        val y = cy + faceY + when {
            isGhost -> -radius * .34f
            isSlime -> radius * .16f
            isWhale -> radius * .20f
            else -> radius * .30f
        }
        val width = radius * ((if (isWhale) .34f else .26f) + petJoy * .06f)
        if (isSpeaking()) {
            val openness = radius * (.06f + abs(sin(seconds * 11f)) * .13f * speakPulse.coerceAtLeast(.45f))
            canvas.drawLine(x - width, y, x + width, y, mouthPaint)
            canvas.drawCircle(x, y, openness, mouthPaint)
        } else {
            val e = displayedEmotion
            val smile = (
                e.joy * 1.08f + petJoy * .34f + e.attention * .26f -
                    e.loneliness * .58f - e.fatigue * .30f - e.tension * .16f +
                    sin(idlePhase * .31f) * .035f
                ).coerceIn(-1f, 1.12f)
            if (abs(smile) < .12f) {
                canvas.drawLine(
                    x - width * (.72f + abs(smile)),
                    y + radius * .012f,
                    x + width * (.72f + abs(smile)),
                    y - radius * .010f,
                    mouthPaint
                )
            } else {
                val magnitude = abs(smile).coerceIn(.12f, 1.1f)
                rect.set(
                    x - width,
                    y - radius * (.10f + magnitude * .075f),
                    x + width,
                    y + radius * (.10f + magnitude * .075f)
                )
                val sweep = 78f + magnitude * 74f
                val start = if (smile >= 0) 90f - sweep * .50f else 270f - sweep * .50f
                canvas.drawArc(rect, start, sweep, false, mouthPaint)
            }
        }
    }

    private fun drawSignalRings(
        canvas: Canvas, cx: Float, cy: Float,
        radius: Float, seconds: Float
    ) {
        ringPaint.strokeWidth = 2.8f
        rect.set(cx - radius*1.30f, cy-radius*1.30f, cx+radius*1.30f, cy+radius*1.30f)
        ringPaint.color = Color.argb(24, 255, 255, 255)
        canvas.drawArc(rect, -90f, 360f, false, ringPaint)
        ringPaint.color = secondaryColor
        canvas.drawArc(rect, -90f, state.normalizedEnergy() * 360f, false, ringPaint)
        drawVoiceRings(canvas, cx, cy, radius, seconds)
        if (!state.connected) {
            ringPaint.color = Color.argb(52, 126, 140, 168)
            canvas.drawArc(rect, 118f, 34f, false, ringPaint)
        }
    }

    private fun drawVoiceRings(
        canvas: Canvas, cx: Float, cy: Float,
        radius: Float, seconds: Float
    ) {
        if (isSpeaking()) {
            val wave = (sin(seconds * 9.5f) + 1f) * .5f
            ringPaint.strokeWidth = max(1.8f, radius * .04f)
            ringPaint.color = Color.argb(
                ((44 + wave * 54) * voiceSpeakingAmount).toInt(),
                Color.red(secondaryColor),
                Color.green(secondaryColor),
                Color.blue(secondaryColor)
            )
            canvas.drawCircle(cx, cy, radius * (1.36f + wave * .10f), ringPaint)
        }

        if (!isListening()) return
        if (!animationsEnabled()) {
            ringPaint.strokeWidth = max(2f, radius * .038f)
            ringPaint.color = Color.argb(
                (82 * voiceListeningAmount).toInt(),
                Color.red(accentColor),
                Color.green(accentColor),
                Color.blue(accentColor)
            )
            canvas.drawCircle(cx, cy, radius * 1.62f, ringPaint)
            return
        }

        repeat(3) { index ->
            val progress = ((seconds * .58f) + index / 3f) % 1f
            val fade = (1f - progress).coerceIn(0f, 1f)
            ringPaint.strokeWidth = max(.8f, radius * .05f * fade)
            ringPaint.color = Color.argb(
                ((18 + fade * 74) * voiceListeningAmount).toInt(),
                Color.red(accentColor),
                Color.green(accentColor),
                Color.blue(accentColor)
            )
            canvas.drawCircle(cx, cy, radius * (1.42f + progress * .56f), ringPaint)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refreshAnimatorScale()
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            animationSettingsObserver
        )
        restartAnimationIfEligible()
    }

    override fun onDetachedFromWindow() {
        touchHandler.removeCallbacks(longPressRunnable)
        context.contentResolver.unregisterContentObserver(animationSettingsObserver)
        lastFrame = 0L
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        prepareShaders()
        restartAnimationIfEligible()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        restartAnimationIfEligible()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        restartAnimationIfEligible()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gazeTargetX = ((event.x / width.toFloat()) - .5f) * 2f
        gazeTargetY = ((event.y / height.toFloat()) - .5f) * 2f
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchActive = true
                touchPulse = .55f
                longPressFired = false
                petting = false
                petStrokeDistance = 0f
                nextPetFeedbackAt = 0f
                touchStartX = event.x
                touchStartY = event.y
                lastPetX = event.x
                lastPetY = event.y
                touchHandler.postDelayed(longPressRunnable, 420L)
            }
            MotionEvent.ACTION_MOVE -> {
                val movedPastSlop = abs(event.x - touchStartX) > longPressSlopPx ||
                    abs(event.y - touchStartY) > longPressSlopPx
                if (movedPastSlop) {
                    touchHandler.removeCallbacks(longPressRunnable)
                    if (!petting) {
                        petting = true
                        touchPulse = max(touchPulse, .82f)
                        performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                        requestRedraw()
                    }
                }
                if (petting) {
                    val dx = event.x - lastPetX
                    val dy = event.y - lastPetY
                    val segment = kotlin.math.sqrt(dx * dx + dy * dy)
                    petStrokeDistance += segment
                    petJoy = (petJoy + segment / 520f).coerceIn(0f, 1f)
                    if (segment > 4f) {
                        if (petTrail.size > 26) petTrail.removeFirst()
                        petTrail.addLast(
                            PetTrailSpark(
                                x = event.x,
                                y = event.y,
                                seed = ((petStrokeDistance * .37f) % 1f).coerceIn(0f, 1f)
                            )
                        )
                    }
                    if (petStrokeDistance >= nextPetFeedbackAt) {
                        performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                        nextPetFeedbackAt += 58f
                    }
                    lastPetX = event.x
                    lastPetY = event.y
                    requestRedraw()
                }
            }
            MotionEvent.ACTION_UP -> {
                touchActive = false
                touchHandler.removeCallbacks(longPressRunnable)
                if (longPressFired) {
                    listener?.onCompanionLongPressReleased()
                } else {
                    performClick()
                }
                petting = false
                gazeTargetX = 0f
                gazeTargetY = 0f
                requestRedraw()
            }
            MotionEvent.ACTION_CANCEL -> {
                touchHandler.removeCallbacks(longPressRunnable)
                petting = false
                touchActive = false
                gazeTargetX = 0f
                gazeTargetY = 0f
                if (longPressFired) listener?.onCompanionLongPressReleased()
                requestRedraw()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        listener?.onCompanionTouched(width / 2f, height / 2f)
        return true
    }
}
