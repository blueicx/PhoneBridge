package com.phonebridge

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.content.res.ColorStateList
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import kotlin.math.roundToInt

enum class UiTheme(
    val label: String,
    val backgroundTop: Int,
    val backgroundBottom: Int,
    val panel: Int,
    val card: Int,
    val input: Int,
    val bubble: Int,
    val stroke: Int,
    val accent: Int,
    val secondary: Int,
    val textPrimary: Int,
    val textSecondary: Int,
    val buttonText: Int,
    val buttonFill: Int,
    val buttonPressed: Int
) {
    AURORA_GLASS(
        label = "玻璃",
        backgroundTop = Color.parseColor("#070B16"),
        backgroundBottom = Color.parseColor("#101C36"),
        panel = Color.parseColor("#82122344"),
        card = Color.parseColor("#66182C4A"),
        input = Color.parseColor("#7A14243E"),
        bubble = Color.parseColor("#8A1A304C"),
        stroke = Color.parseColor("#4D9FD8FF"),
        accent = Color.parseColor("#9FE8FF"),
        secondary = Color.parseColor("#C7A7FF"),
        textPrimary = Color.parseColor("#F2F8FF"),
        textSecondary = Color.parseColor("#AFC4DE"),
        buttonText = Color.parseColor("#DFF3FF"),
        buttonFill = Color.parseColor("#4A192B45"),
        buttonPressed = Color.parseColor("#662C4A73")
    ),
    LIQUID_MOTION(
        label = "流光",
        backgroundTop = Color.parseColor("#04101B"),
        backgroundBottom = Color.parseColor("#132B2C"),
        panel = Color.parseColor("#B80D2028"),
        card = Color.parseColor("#99113038"),
        input = Color.parseColor("#AD0F252E"),
        bubble = Color.parseColor("#B2143844"),
        stroke = Color.parseColor("#4D64FFDA"),
        accent = Color.parseColor("#64FFDA"),
        secondary = Color.parseColor("#FF7EB6"),
        textPrimary = Color.parseColor("#F0FFFA"),
        textSecondary = Color.parseColor("#9CBDB8"),
        buttonText = Color.parseColor("#DDFFF4"),
        buttonFill = Color.parseColor("#660F2A33"),
        buttonPressed = Color.parseColor("#8C184652")
    ),
    CIRCUIT_NOIR(
        label = "夜芯",
        backgroundTop = Color.parseColor("#060606"),
        backgroundBottom = Color.parseColor("#151311"),
        panel = Color.parseColor("#E00E0E0E"),
        card = Color.parseColor("#CC141414"),
        input = Color.parseColor("#D9111111"),
        bubble = Color.parseColor("#E0181818"),
        stroke = Color.parseColor("#40FFC86B"),
        accent = Color.parseColor("#FFB84D"),
        secondary = Color.parseColor("#53FFF2"),
        textPrimary = Color.parseColor("#F7F5F0"),
        textSecondary = Color.parseColor("#A6ADA8"),
        buttonText = Color.parseColor("#FFEFD4"),
        buttonFill = Color.parseColor("#CC121212"),
        buttonPressed = Color.parseColor("#E628241C")
    );

    companion object {
        private const val PREF_NAME = "phonebridge"
        private const val PREF_KEY = "ui_theme"

        fun load(context: Context): UiTheme {
            val saved = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getString(PREF_KEY, null)
            return entries.firstOrNull { it.name == saved } ?: AURORA_GLASS
        }

        fun save(context: Context, theme: UiTheme) {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putString(PREF_KEY, theme.name).apply()
        }
    }
}

class ThemeApplier(private val activity: Activity) {
    private var current = UiTheme.AURORA_GLASS

    fun apply(theme: UiTheme): UiTheme {
        current = theme
        val root = activity.findViewById<View>(android.R.id.content)
        paintRoot(root)
        paintPanels()
        paintChips()
        paintInputs()
        paintButtons(root)
        paintFocusExit()
        paintVoiceCommand()
        paintThemeSwitcher()
        paintTexts()
        paintPreviewFrame()
        activity.window?.statusBarColor = theme.backgroundTop
        activity.window?.navigationBarColor = theme.backgroundBottom
        return theme
    }

    private fun paintRoot(root: View) {
        val gradient = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(current.backgroundTop, current.backgroundBottom)
        )
        val wash = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                ColorUtils.setAlphaComponent(current.accent, 9),
                Color.TRANSPARENT,
                ColorUtils.setAlphaComponent(current.secondary, 8)
            )
        )
        val depth = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                Color.TRANSPARENT,
                ColorUtils.setAlphaComponent(current.accent, 7),
                ColorUtils.setAlphaComponent(current.secondary, 10)
            )
        )
        val horizon = GradientDrawable(
            GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(ColorUtils.setAlphaComponent(current.accent, 15), Color.TRANSPARENT)
        )
        root.background = LayerDrawable(arrayOf(gradient, depth, wash, horizon))
    }

    private fun glassPanel(radiusDp: Float): LayerDrawable {
        val radius = dp(radiusDp)
        val base = GradientDrawable()
        base.setColor(current.panel)
        base.setStroke(dp(1f).roundToInt(), current.stroke)
        base.cornerRadius = radius
        val highlight = ColorUtils.blendARGB(Color.WHITE, current.accent, .32f)
        val sheen = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.argb(24, Color.red(highlight), Color.green(highlight), Color.blue(highlight)), Color.TRANSPARENT)
        ).apply { cornerRadius = radius }
        val innerStroke = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke(dp(1f).roundToInt(), Color.argb(48, 255, 255, 255))
            cornerRadius = radius * .86f
        }
        return LayerDrawable(arrayOf(base, innerStroke, sheen))
    }

    private fun paintThemeSwitcher() {
        val selected = when (current) {
            UiTheme.AURORA_GLASS -> R.id.themeGlass
            UiTheme.LIQUID_MOTION -> R.id.themeLiquid
            UiTheme.CIRCUIT_NOIR -> R.id.themeNoir
        }
        val selectedText = ColorUtils.blendARGB(current.backgroundTop, Color.WHITE, .16f)
        listOf(R.id.themeGlass, R.id.themeLiquid, R.id.themeNoir).forEach { id ->
            activity.findViewById<Button>(id)?.let { button ->
                val isSelected = button.id == selected
                button.backgroundTintList = ColorStateList.valueOf(
                    if (isSelected) current.accent else current.buttonFill
                )
                button.setTextColor(if (isSelected) selectedText else current.buttonText)
                button.translationZ = if (isSelected) 2f else 0f
            }
        }
    }

    private fun shape(id: Int, color: Int, radiusDp: Float, radiiDp: FloatArray? = null) {
        val view = activity.findViewById<View>(id) ?: return
        val drawable = view.background?.mutate() as? GradientDrawable ?: return
        drawable.setColor(color)
        drawable.setStroke(dp(1f).roundToInt(), current.stroke)
        if (radiiDp == null) {
            drawable.cornerRadius = dp(radiusDp)
        } else {
            // GradientDrawable requires x/y radii pairs for TL, TR, BR, BL.
            drawable.cornerRadii = FloatArray(radiiDp.size * 2) { index ->
                dp(radiiDp[index / 2])
            }
        }
        view.background = drawable
    }

    private fun paintPanels() {
        activity.findViewById<View>(R.id.heroPanel)?.background = glassPanel(22f)
        activity.findViewById<View>(R.id.panelHost)?.background = glassPanel(18f)
        shape(R.id.previewView, current.card, 18f)
        shape(R.id.speechText, current.bubble, 15f, floatArrayOf(5f, 15f, 15f, 15f))
    }

    private fun paintPreviewFrame() {
        activity.findViewById<MaterialCardView>(R.id.previewFrame)?.let { card ->
            card.setCardBackgroundColor(current.card)
            card.strokeColor = current.accent
            card.strokeWidth = dp(2f).roundToInt()
            card.radius = dp(18f)
        }
    }

    private fun paintVoiceCommand() {
        activity.findViewById<Button>(R.id.pttButton)?.let { button ->
            val pressed = ColorUtils.setAlphaComponent(current.accent, 226)
            button.backgroundTintList = ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_pressed),
                    intArrayOf()
                ),
                intArrayOf(pressed, current.accent)
            )
            button.setTextColor(ColorUtils.blendARGB(current.backgroundTop, Color.WHITE, .16f))
            button.translationZ = 4f
        }
    }

    private fun paintFocusExit() {
        activity.findViewById<Button>(R.id.focusExit)?.let { button ->
            button.backgroundTintList = ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_pressed),
                    intArrayOf()
                ),
                intArrayOf(
                    ColorUtils.setAlphaComponent(current.accent, 186),
                    ColorUtils.setAlphaComponent(current.accent, 48)
                )
            )
            button.setTextColor(current.textPrimary)
            button.translationZ = 8f
        }
    }

    private fun paintChips() {
        listOf(
            R.id.petLevelChip, R.id.petEnergyChip,
            R.id.petAffectionChip, R.id.linkMetric, R.id.audioMetric,
            R.id.fpsMetric, R.id.taskMetric
        ).forEach { shape(it, current.card, 13f) }
        activity.findViewById<View>(R.id.statusChip)?.let { status ->
            (status.background?.mutate() as? GradientDrawable)?.let { drawable ->
                drawable.setColor(current.card)
                drawable.setStroke(dp(1f).roundToInt(), ColorUtils.setAlphaComponent(current.accent, 92))
                drawable.cornerRadius = dp(15f)
                status.background = drawable
            }
        }
    }

    private fun paintInputs() {
        shape(R.id.commandInput, current.input, 12f)
        shape(R.id.chatInput, current.input, 12f)
        shape(R.id.codexTaskSpinner, current.input, 12f)
        shape(R.id.modelSpinner, current.input, 12f)
        listOf(R.id.commandInput, R.id.chatInput).forEach { id ->
            activity.findViewById<EditText>(id)?.setHintTextColor(current.textSecondary)
        }
    }

    private fun paintButtons(root: View) {
        recursively(root) { view ->
            if (view is Button && view.id != View.NO_ID) {
                val isTab = view.parent == activity.findViewById<MaterialButtonToggleGroup>(R.id.panelTabs)
                val normal = if (isTab) current.buttonFill else current.buttonFill
                val active = if (isTab) ColorUtils.setAlphaComponent(current.accent, 190) else current.buttonPressed
                val states = arrayOf(
                    intArrayOf(android.R.attr.state_checked),
                    intArrayOf(android.R.attr.state_pressed),
                    intArrayOf()
                )
                val colors = intArrayOf(active, active, normal)
                view.backgroundTintList = ColorStateList(states, colors)
                view.setTextColor(current.buttonText)
            }
        }
    }

    private fun paintTexts() {
        activity.findViewById<TextView>(R.id.titleText)?.setTextColor(current.textPrimary)
        activity.findViewById<TextView>(R.id.subtitleText)?.setTextColor(current.textSecondary)
        activity.findViewById<TextView>(R.id.speechText)?.setTextColor(current.textPrimary)
        activity.findViewById<TextView>(R.id.sensorText)?.setTextColor(current.textSecondary)
        activity.findViewById<TextView>(R.id.selectedTaskTitle)?.setTextColor(current.textPrimary)
        activity.findViewById<TextView>(R.id.selectedTaskMeta)?.setTextColor(current.textSecondary)
        activity.findViewById<TextView>(R.id.selectedTaskDetail)?.setTextColor(current.textSecondary)
        activity.findViewById<TextView>(R.id.codexDetail)?.setTextColor(current.textSecondary)
        activity.findViewById<TextView>(R.id.selectedTaskPercent)?.setTextColor(current.accent)
    }

    private fun recursively(view: View, block: (View) -> Unit) {
        block(view)
        if (view is ViewGroup) {
            repeat(view.childCount) { index -> recursively(view.getChildAt(index), block) }
        }
    }

    private fun dp(value: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value, activity.resources.displayMetrics
    )
}
