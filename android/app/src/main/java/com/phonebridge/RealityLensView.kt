package com.phonebridge

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

class RealityLensView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr), SensorEventListener {

    data class LensNode(
        val id: String,
        val title: String,
        val detail: String,
        val defaultXFraction: Float,
        val defaultYFraction: Float,
        val azimuthDeg: Float,
        val pitchDeg: Float
    )

    interface Listener {
        fun onNodeTapped(node: LensNode)
        fun onPetTapped(pet: PetState) {}
    }

    private val nodes = listOf(
        LensNode(
            "place",
            "地点线索",
            "Mote 记下了这里的位置氛围。",
            0.22f,
            0.28f,
            -28f,
            12f
        ),
        LensNode(
            "object",
            "物体轮廓",
            "Mote 把眼前物体的轮廓写进了观察笔记。",
            0.74f,
            0.36f,
            26f,
            -4f
        ),
        LensNode(
            "light",
            "光线样本",
            "Mote 采集了这束光的温度和方向。",
            0.35f,
            0.68f,
            64f,
            18f
        )
    )
    private val discovered = mutableSetOf<String>()
    private var listener: Listener? = null
    private val startedAt = System.currentTimeMillis()

    // 3DoF Spatial Sensors
    private var sensorManager: SensorManager? = null
    private var rotationSensor: Sensor? = null
    private var isTracking = false
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    // Orientation tracking & calibration
    private var calibrated = false
    private var baseAzimuth = 0f
    private var basePitch = 0f
    private var currentAzimuth = 0f
    private var currentPitch = 0f

    // Pet Companion State & AR World Positioning
    private var petState: PetState = PetState()
    private val moteAzimuthDeg = 0f
    private val motePitchDeg = -14f // Anchored on the floor/desk about 1.5m in front of camera
    private var renderedMoteX = 0f
    private var renderedMoteY = 0f
    private var renderedMoteRadius = 0f
    private var isMoteInView = true

    // Interactive Pet Animations
    private var petJumpProgress = 0f // 0f..1f bounce curve when tapped
    private var petJoyTimer = 0f     // 0f..1f spawns hearts & sparkles
    private var petSpeechBubble: String? = null
    private var petSpeechTimer = 0f

    // Runtime rendered coordinates: id -> Triple(cx, cy, inView)
    private val renderedPositions = mutableMapOf<String, Triple<Float, Float, Boolean>>()

    // Paints
    private val nodeFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val nodeRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
    }
    private val nodeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 34f
    }
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 32f
    }
    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val radarArrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val groundShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val modelBodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val modelGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val modelEyePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val modelMouthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4.5f
        strokeCap = Paint.Cap.ROUND
    }
    private val modelAccentPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val energyBeamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
        color = 0xAA8FF0C4.toInt()
    }

    // Paths & Rects
    private val bodyPath = Path()
    private val bodyBounds = RectF()
    private val shadowRect = RectF()
    private val chipRect = RectF()
    private val arrowPath = Path()

    init {
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ORIENTATION)
    }

    fun setListener(value: Listener) {
        listener = value
    }

    fun setPetState(state: PetState) {
        petState = state
        invalidate()
    }

    fun setDiscovered(ids: Collection<String>) {
        discovered.clear()
        discovered.addAll(ids)
        invalidate()
    }

    fun markDiscovered(id: String) {
        discovered.add(id)
        triggerHaptic(50)
        // Feed Mote with joy
        petJoyTimer = 1.0f
        petSpeechBubble = "好棒！又收集到了一个现实样本！"
        petSpeechTimer = 2.5f
        invalidate()
    }

    fun startSpatialSensors() {
        if (isTracking || rotationSensor == null) return
        sensorManager?.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME)
        isTracking = true
        calibrated = false
    }

    fun stopSpatialSensors() {
        if (!isTracking) return
        sensorManager?.unregisterListener(this)
        isTracking = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == View.VISIBLE) startSpatialSensors()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopSpatialSensors()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == View.VISIBLE) {
            startSpatialSensors()
        } else {
            stopSpatialSensors()
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            SensorManager.getOrientation(rotationMatrix, orientationAngles)
            val azimuthDeg = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
            val pitchDeg = Math.toDegrees(orientationAngles[1].toDouble()).toFloat()

            if (!calibrated) {
                baseAzimuth = azimuthDeg
                basePitch = pitchDeg
                calibrated = true
            }

            currentAzimuth = lerpAngle(currentAzimuth, azimuthDeg, 0.22f)
            currentPitch = currentPitch + (pitchDeg - currentPitch) * 0.22f
        } else if (event.sensor.type == Sensor.TYPE_ORIENTATION) {
            val azimuthDeg = event.values[0]
            val pitchDeg = event.values[1]
            if (!calibrated) {
                baseAzimuth = azimuthDeg
                basePitch = pitchDeg
                calibrated = true
            }
            currentAzimuth = lerpAngle(currentAzimuth, azimuthDeg, 0.22f)
            currentPitch = currentPitch + (pitchDeg - currentPitch) * 0.22f
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun lerpAngle(from: Float, to: Float, weight: Float): Float {
        val diff = ((to - from + 540f) % 360f) - 180f
        return (from + diff * weight + 360f) % 360f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val elapsed = System.currentTimeMillis() - startedAt
        val seconds = elapsed / 1000f
        val pulse = (elapsed % 1600L) / 1600f
        val fovH = 62f
        val fovV = 78f
        val margin = min(width, height) * 0.08f

        // Advance interactive animations
        if (petJumpProgress > 0f) {
            petJumpProgress = (petJumpProgress - 0.045f).coerceAtLeast(0f)
        }
        if (petJoyTimer > 0f) {
            petJoyTimer = (petJoyTimer - 0.02f).coerceAtLeast(0f)
        }
        if (petSpeechTimer > 0f) {
            petSpeechTimer -= 0.032f
            if (petSpeechTimer <= 0f) petSpeechBubble = null
        }

        renderedPositions.clear()

        // 1. Draw In-World Clue Relics
        nodes.forEach { node ->
            val cx: Float
            val cy: Float
            val inView: Boolean

            if (calibrated) {
                val deltaAzimuth = ((node.azimuthDeg - (currentAzimuth - baseAzimuth) + 540f) % 360f) - 180f
                val deltaPitch = node.pitchDeg - (currentPitch - basePitch)

                val projX = width * 0.5f + (deltaAzimuth / (fovH * 0.5f)) * (width * 0.5f)
                val projY = height * 0.5f - (deltaPitch / (fovV * 0.5f)) * (height * 0.5f)

                inView = abs(deltaAzimuth) <= fovH * 0.5f && abs(deltaPitch) <= fovV * 0.5f

                if (inView) {
                    cx = projX
                    cy = projY
                    drawInViewNode(canvas, node, cx, cy, pulse)
                } else {
                    val angle = atan2(projY - height * 0.5f, projX - width * 0.5f)
                    val edgeX = width * 0.5f + cos(angle) * (width * 0.5f - margin)
                    val edgeY = height * 0.5f + sin(angle) * (height * 0.5f - margin)
                    cx = edgeX
                    cy = edgeY
                    drawRadarArrow(canvas, node.title, edgeX, edgeY, angle, pulse, abs(deltaAzimuth).toInt(), node.id in discovered)
                }
            } else {
                val sway = sin(elapsed / 800.0 + node.defaultXFraction * 4.0).toFloat() * 12f
                cx = width * node.defaultXFraction
                cy = height * node.defaultYFraction + sway
                inView = true
                drawInViewNode(canvas, node, cx, cy, pulse)
            }

            renderedPositions[node.id] = Triple(cx, cy, inView)
        }

        // 2. Render Pokemon-GO Style 3D Living Companion Model Anchored in Real Space
        renderLivingCompanionModel(canvas, seconds, pulse, fovH, fovV, margin)

        postInvalidateDelayed(32)
    }

    /**
     * Renders the animated, 3D-shaded companion model anchored in physical space
     * (standing on the floor/desk in front of the player).
     */
    private fun renderLivingCompanionModel(
        canvas: Canvas, seconds: Float, pulse: Float,
        fovH: Float, fovV: Float, margin: Float
    ) {
        val moteBaseRadius = min(width, height) * 0.135f
        val breath = sin(seconds * 2.2f) * 0.04f
        val jumpOffset = sin(petJumpProgress * Math.PI.toFloat()) * moteBaseRadius * 0.75f
        val floatSway = sin(seconds * 1.8f) * moteBaseRadius * 0.06f

        val projX: Float
        val projY: Float
        val deltaAzimuth: Float
        val deltaPitch: Float

        if (calibrated) {
            deltaAzimuth = ((moteAzimuthDeg - (currentAzimuth - baseAzimuth) + 540f) % 360f) - 180f
            deltaPitch = motePitchDeg - (currentPitch - basePitch)

            projX = width * 0.5f + (deltaAzimuth / (fovH * 0.5f)) * (width * 0.5f)
            projY = height * 0.5f - (deltaPitch / (fovV * 0.5f)) * (height * 0.5f) - jumpOffset - floatSway

            isMoteInView = abs(deltaAzimuth) <= fovH * 0.52f && abs(deltaPitch) <= fovV * 0.52f
        } else {
            deltaAzimuth = 0f
            deltaPitch = 0f
            projX = width * 0.5f
            projY = height * 0.65f - jumpOffset - floatSway
            isMoteInView = true
        }

        renderedMoteX = projX
        renderedMoteY = projY
        renderedMoteRadius = moteBaseRadius

        if (isMoteInView) {
            draw3DCompanionEntity(canvas, projX, projY, moteBaseRadius, breath, jumpOffset, seconds)
        } else {
            // Draw Pokemon-GO style radar arrow for Mote when player turns away
            val angle = atan2(projY - height * 0.5f, projX - width * 0.5f)
            val edgeX = width * 0.5f + cos(angle) * (width * 0.5f - margin)
            val edgeY = height * 0.5f + sin(angle) * (height * 0.5f - margin)
            drawRadarArrow(canvas, "🐾 ${petState.name}", edgeX, edgeY, angle, pulse, abs(deltaAzimuth).toInt(), false, isMote = true)
        }
    }

    private fun draw3DCompanionEntity(
        canvas: Canvas, cx: Float, cy: Float, radius: Float,
        breath: Float, jumpOffset: Float, seconds: Float
    ) {
        val r = radius * (1f + breath)

        // 1. Perspective Ground Shadow on Real Floor
        val shadowY = cy + r * 1.15f + jumpOffset
        val shadowScale = (1f - jumpOffset / (radius * 1.8f)).coerceIn(0.4f, 1f)
        shadowRect.set(
            cx - r * 1.05f * shadowScale,
            shadowY - r * 0.24f * shadowScale,
            cx + r * 1.05f * shadowScale,
            shadowY + r * 0.24f * shadowScale
        )
        groundShadowPaint.shader = RadialGradient(
            cx, shadowY, r * 1.05f * shadowScale,
            intArrayOf(0x66000000.toInt(), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawOval(shadowRect, groundShadowPaint)

        // 2. Soft Ambient Aura
        val auraRadius = r * 1.38f
        modelGlowPaint.shader = RadialGradient(
            cx, cy, auraRadius,
            intArrayOf(0x448FF0C4.toInt(), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, auraRadius, modelGlowPaint)

        // 3. Render 3D Creature Body based on selected appearance
        drawCreatureBody(canvas, cx, cy, r, seconds)

        // 4. Living Animated Eyes (Blinks periodically and looks at player)
        drawLivingEyes(canvas, cx, cy, r, seconds)

        // 5. Cute Smile
        drawLivingMouth(canvas, cx, cy, r)

        // 6. Floating AR Overhead Status Badge (Name + Level)
        val badgeY = cy - r * 1.25f
        val badgeText = "Lv.${petState.level} ${petState.name} · 感官同伴"
        drawChip(canvas, badgeText, cx, badgeY, 0xDD0D1E17.toInt(), 0xFF8FF0C4.toInt())

        // 7. Speech Bubble when interacted
        petSpeechBubble?.let { speech ->
            val bubbleY = badgeY - 54f
            drawChip(canvas, "💬 $speech", cx, bubbleY, 0xF2122A21.toInt(), 0xFFFFE89E.toInt())
        }

        // 8. Joy Particles (Floating hearts when petted/collected)
        if (petJoyTimer > 0f) {
            val heartY = cy - r * 1.6f - (1f - petJoyTimer) * 45f
            val heartAlpha = (petJoyTimer * 255).toInt()
            nodeText.color = Color.argb(heartAlpha, 255, 107, 129)
            nodeText.textSize = 42f
            canvas.drawText("💖", cx - 22f, heartY, nodeText)
            canvas.drawText("✨", cx + 26f, heartY - 14f, nodeText)
        }
    }

    private fun drawCreatureBody(canvas: Canvas, cx: Float, cy: Float, r: Float, seconds: Float) {
        when (petState.appearance) {
            PetAppearance.RIMURU -> {
                // Translucent Slime (Cyan Pearl Volumetric Shading)
                modelBodyPaint.shader = RadialGradient(
                    cx - r * 0.28f, cy - r * 0.38f, r * 1.45f,
                    intArrayOf(0xFFF6FFFF.toInt(), 0xFF76D7F8.toInt(), 0xFF1B82BC.toInt(), 0xFF0D466E.toInt()),
                    null, Shader.TileMode.CLAMP
                )
                bodyBounds.set(cx - r * 1.05f, cy - r * 0.85f, cx + r * 1.05f, cy + r * 0.95f)
                val wobble = sin(seconds * 3.5f) * r * 0.04f
                canvas.drawRoundRect(bodyBounds, r * 0.95f + wobble, r * 0.85f - wobble, modelBodyPaint)

                // Cute Blush Cheeks
                modelAccentPaint.color = 0x55FF6B8B.toInt()
                canvas.drawCircle(cx - r * 0.42f, cy + r * 0.12f, r * 0.16f, modelAccentPaint)
                canvas.drawCircle(cx + r * 0.42f, cy + r * 0.12f, r * 0.16f, modelAccentPaint)
            }
            PetAppearance.SPRITE -> {
                // Leaf Fox (Emerald green body with fox ears & tail)
                modelBodyPaint.shader = RadialGradient(
                    cx - r * 0.25f, cy - r * 0.35f, r * 1.5f,
                    intArrayOf(0xFFFBFFF2.toInt(), 0xFF7CC48C.toInt(), 0xFF235C42.toInt()),
                    null, Shader.TileMode.CLAMP
                )
                canvas.drawCircle(cx, cy, r * 0.85f, modelBodyPaint)

                // Fox Ears
                bodyPath.reset()
                bodyPath.moveTo(cx - r * 0.68f, cy - r * 0.4f)
                bodyPath.lineTo(cx - r * 0.45f, cy - r * 1.25f)
                bodyPath.lineTo(cx - r * 0.18f, cy - r * 0.65f)
                bodyPath.close()
                canvas.drawPath(bodyPath, modelBodyPaint)

                bodyPath.reset()
                bodyPath.moveTo(cx + r * 0.18f, cy - r * 0.65f)
                bodyPath.lineTo(cx + r * 0.45f, cy - r * 1.25f)
                bodyPath.lineTo(cx + r * 0.68f, cy - r * 0.4f)
                bodyPath.close()
                canvas.drawPath(bodyPath, modelBodyPaint)
            }
            PetAppearance.GHOST -> {
                // Mist Cat (Ethereal gradient + floating cat ears)
                modelBodyPaint.shader = RadialGradient(
                    cx, cy - r * 0.3f, r * 1.6f,
                    intArrayOf(0xFFFFFFFF.toInt(), 0xFFBACBDE.toInt(), 0xFF546A8B.toInt()),
                    null, Shader.TileMode.CLAMP
                )
                canvas.drawCircle(cx, cy, r * 0.88f, modelBodyPaint)
                // Whisker Lines
                modelMouthPaint.color = 0xAAFFFFFF.toInt()
                canvas.drawLine(cx - r * 0.45f, cy, cx - r * 0.85f, cy - r * 0.08f, modelMouthPaint)
                canvas.drawLine(cx - r * 0.45f, cy + r * 0.1f, cx - r * 0.82f, cy + r * 0.12f, modelMouthPaint)
                canvas.drawLine(cx + r * 0.45f, cy, cx + r * 0.85f, cy - r * 0.08f, modelMouthPaint)
                canvas.drawLine(cx + r * 0.45f, cy + r * 0.1f, cx + r * 0.82f, cy + r * 0.12f, modelMouthPaint)
            }
            PetAppearance.CLOUD_WHALE -> {
                // Ocean Whale with Fins
                modelBodyPaint.shader = RadialGradient(
                    cx, cy - r * 0.28f, r * 1.65f,
                    intArrayOf(0xFFFFFFFF.toInt(), 0xFFA8E7FF.toInt(), 0xFF4A5FA8.toInt()),
                    null, Shader.TileMode.CLAMP
                )
                bodyBounds.set(cx - r * 1.2f, cy - r * 0.75f, cx + r * 1.2f, cy + r * 0.75f)
                canvas.drawOval(bodyBounds, modelBodyPaint)
                // Gliding Fins
                val finFlap = sin(seconds * 3.2f) * r * 0.12f
                bodyPath.reset()
                bodyPath.moveTo(cx - r * 0.6f, cy)
                bodyPath.lineTo(cx - r * 1.45f, cy + r * 0.35f + finFlap)
                bodyPath.lineTo(cx - r * 0.45f, cy + r * 0.35f)
                bodyPath.close()
                canvas.drawPath(bodyPath, modelBodyPaint)

                bodyPath.reset()
                bodyPath.moveTo(cx + r * 0.6f, cy)
                bodyPath.lineTo(cx + r * 1.45f, cy + r * 0.35f + finFlap)
                bodyPath.lineTo(cx + r * 0.45f, cy + r * 0.35f)
                bodyPath.close()
                canvas.drawPath(bodyPath, modelBodyPaint)
            }
            PetAppearance.CIRCUIT -> {
                // Mecha Beast (Amber Cyber Orb with Orbital Rings)
                modelBodyPaint.shader = RadialGradient(
                    cx, cy - r * 0.2f, r * 1.55f,
                    intArrayOf(0xFFFFF7CE.toInt(), 0xFFF5C867.toInt(), 0xFF2A4254.toInt()),
                    null, Shader.TileMode.CLAMP
                )
                canvas.drawCircle(cx, cy, r * 0.85f, modelBodyPaint)
                // Rotating Ring
                nodeRing.color = 0xFFF5C867.toInt()
                nodeRing.strokeWidth = 4f
                val ringAngle = seconds * 65f
                shadowRect.set(cx - r * 1.15f, cy - r * 0.45f, cx + r * 1.15f, cy + r * 0.45f)
                canvas.save()
                canvas.rotate(ringAngle, cx, cy)
                canvas.drawOval(shadowRect, nodeRing)
                canvas.restore()
            }
            else -> {
                // Default Mote: Luminous Mint Core
                modelBodyPaint.shader = RadialGradient(
                    cx - r * 0.28f, cy - r * 0.38f, r * 1.55f,
                    intArrayOf(0xFFFAFFFE.toInt(), 0xFF8FF0C4.toInt(), 0xFF28967A.toInt(), 0xFF0E382E.toInt()),
                    null, Shader.TileMode.CLAMP
                )
                canvas.drawCircle(cx, cy, r * 0.9f, modelBodyPaint)

                // Satellite Orbit Dust
                val orbitAngle = seconds * 2.4f
                val ox = cx + cos(orbitAngle) * r * 1.25f
                val oy = cy + sin(orbitAngle) * r * 0.55f
                modelAccentPaint.color = 0xEE8FF0C4.toInt()
                canvas.drawCircle(ox, oy, r * 0.12f, modelAccentPaint)
            }
        }
    }

    private fun drawLivingEyes(canvas: Canvas, cx: Float, cy: Float, r: Float, seconds: Float) {
        // Natural blinking every 3.8 seconds
        val blinkCycle = (seconds % 3.8f)
        val isBlinking = blinkCycle < 0.16f

        val eyeSpacing = r * 0.28f
        val eyeY = cy - r * 0.08f
        val eyeRadius = r * 0.09f

        modelEyePaint.color = 0xFF0C1914.toInt()

        if (isBlinking) {
            modelMouthPaint.color = 0xFF0C1914.toInt()
            canvas.drawLine(cx - eyeSpacing - eyeRadius, eyeY, cx - eyeSpacing + eyeRadius, eyeY, modelMouthPaint)
            canvas.drawLine(cx + eyeSpacing - eyeRadius, eyeY, cx + eyeSpacing + eyeRadius, eyeY, modelMouthPaint)
        } else {
            // Left eye + specular catchlight
            canvas.drawCircle(cx - eyeSpacing, eyeY, eyeRadius, modelEyePaint)
            modelAccentPaint.color = Color.WHITE
            canvas.drawCircle(cx - eyeSpacing - eyeRadius * 0.3f, eyeY - eyeRadius * 0.3f, eyeRadius * 0.35f, modelAccentPaint)

            // Right eye + specular catchlight
            canvas.drawCircle(cx + eyeSpacing, eyeY, eyeRadius, modelEyePaint)
            canvas.drawCircle(cx + eyeSpacing - eyeRadius * 0.3f, eyeY - eyeRadius * 0.3f, eyeRadius * 0.35f, modelAccentPaint)
        }
    }

    private fun drawLivingMouth(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        modelMouthPaint.color = 0xDD0C1914.toInt()
        val mouthY = cy + r * 0.16f
        val mouthWidth = r * 0.14f
        // Friendly smile arc
        shadowRect.set(cx - mouthWidth, mouthY - r * 0.08f, cx + mouthWidth, mouthY + r * 0.08f)
        canvas.drawArc(shadowRect, 10f, 160f, false, modelMouthPaint)
    }

    private fun drawInViewNode(canvas: Canvas, node: LensNode, cx: Float, cy: Float, pulse: Float) {
        val radius = min(width, height) * (if (node.id in discovered) 0.024f else 0.034f)
        if (node.id in discovered) {
            nodeFill.color = 0xCC8FF0C4.toInt()
            canvas.drawCircle(cx, cy, radius, nodeFill)
            nodeText.textSize = radius * 1.1f
            canvas.drawText("✓", cx, cy + radius * 0.38f, nodeText)
            drawChip(canvas, node.title, cx, cy - radius * 2.1f, 0xCC10201B.toInt(), Color.WHITE)
        } else {
            val wave = radius * (1.25f + pulse * 0.75f)
            nodeRing.color = 0x668FF0C4.toInt()
            canvas.drawCircle(cx, cy, wave, nodeRing)

            nodeFill.color = 0xCC0E1814.toInt()
            canvas.drawCircle(cx, cy, radius, nodeFill)

            nodeRing.color = 0xFFF5D06F.toInt()
            canvas.drawCircle(cx, cy, radius, nodeRing)

            nodeText.textSize = radius * 1.1f
            nodeText.color = 0xFFF5D06F.toInt()
            canvas.drawText("?", cx, cy + radius * 0.38f, nodeText)

            drawChip(canvas, "${node.title} · 待收纳", cx, cy - radius * 2.2f, 0xEE1A2F25.toInt(), 0xFFF5D06F.toInt())
        }
    }

    private fun drawRadarArrow(
        canvas: Canvas, title: String, x: Float, y: Float,
        angleRad: Float, pulse: Float, degDiff: Int,
        isCollected: Boolean, isMote: Boolean = false
    ) {
        val arrowSize = if (isMote) 28f + pulse * 6f else 22f + pulse * 5f
        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(Math.toDegrees(angleRad.toDouble()).toFloat() + 90f)

        arrowPath.reset()
        arrowPath.moveTo(0f, -arrowSize * 1.2f)
        arrowPath.lineTo(arrowSize * 0.8f, arrowSize * 0.8f)
        arrowPath.lineTo(0f, arrowSize * 0.3f)
        arrowPath.lineTo(-arrowSize * 0.8f, arrowSize * 0.8f)
        arrowPath.close()

        radarArrowPaint.color = when {
            isMote -> 0xFF8FF0C4.toInt()
            isCollected -> 0xAA8FF0C4.toInt()
            else -> 0xFFF5D06F.toInt()
        }
        canvas.drawPath(arrowPath, radarArrowPaint)
        canvas.restore()

        val badgeText = "$title ${degDiff}°"
        val badgeColor = if (isMote) 0xFF8FF0C4.toInt() else (if (isCollected) 0xFF8FF0C4.toInt() else 0xFFF5D06F.toInt())
        drawChip(canvas, badgeText, x, y - 32f, 0xDD081410.toInt(), badgeColor)
    }

    private fun drawChip(canvas: Canvas, text: String, centerX: Float, bottomY: Float, bgColor: Int, textColor: Int) {
        val textWidth = labelText.measureText(text)
        val padH = 18f
        val padV = 8f
        chipRect.set(
            centerX - textWidth / 2f - padH,
            bottomY - labelText.textSize - padV,
            centerX + textWidth / 2f + padH,
            bottomY + padV * 0.8f
        )
        chipPaint.color = bgColor
        canvas.drawRoundRect(chipRect, chipRect.height() / 2f, chipRect.height() / 2f, chipPaint)
        labelText.color = textColor
        canvas.drawText(text, centerX, bottomY, labelText)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN) return super.onTouchEvent(event)

        // 1. Check if user tapped Mote companion directly in physical AR space!
        if (isMoteInView && renderedMoteRadius > 0f) {
            val dx = event.x - renderedMoteX
            val dy = event.y - renderedMoteY
            val hitR = renderedMoteRadius * 1.25f
            if (dx * dx + dy * dy <= hitR * hitR) {
                performClick()
                triggerHaptic(40)
                petJumpProgress = 1.0f
                petJoyTimer = 1.0f
                val greetings = listOf(
                    "抓到我啦！一起在房间里找找看～",
                    "这里就是现实世界呀！很有趣呢！",
                    "好舒服～我们在现实中同频啦！",
                    "左边和右边好像有特别的线索哦！"
                )
                petSpeechBubble = greetings.random()
                petSpeechTimer = 3.0f
                listener?.onPetTapped(petState)
                invalidate()
                return true
            }
        }

        // 2. Check if user tapped an AR Clue Node
        val radius = min(width, height) * 0.034f
        val hitRadius = radius * 2.4f

        val node = nodes.lastOrNull { node ->
            val pos = renderedPositions[node.id] ?: return@lastOrNull false
            val dx = event.x - pos.first
            val dy = event.y - pos.second
            dx * dx + dy * dy <= hitRadius * hitRadius
        }

        if (node != null) {
            performClick()
            triggerHaptic(35)
            listener?.onNodeTapped(node)
            return true
        }

        return super.onTouchEvent(event)
    }

    private fun triggerHaptic(durationMs: Long) {
        runCatching {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return@runCatching
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(durationMs)
            }
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
