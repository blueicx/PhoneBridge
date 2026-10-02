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
import org.json.JSONObject

data class UiTheme(
    val id: String,
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
    val buttonPressed: Int,
    val success: Int,
    val warning: Int,
    val danger: Int,
    val focus: Int
) {
    companion object {
        private const val PREF_NAME = "phonebridge"
        private const val PREF_KEY = "ui_theme"
        private val LEGACY_IDS = mapOf(
            "AURORA_GLASS" to "glass",
            "LIQUID_MOTION" to "liquid",
            "CIRCUIT_NOIR" to "noir"
        )

        private fun parseArgb(value: String): Int {
            require(value.matches(Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?"))) {
                "Invalid ARGB color: $value"
            }
            val digits = value.substring(1)
            val argb = if (digits.length == 6) "FF$digits" else digits
            return java.lang.Long.parseLong(argb, 16).toInt()
        }

        private fun red(color: Int) = (color ushr 16) and 0xFF
        private fun green(color: Int) = (color ushr 8) and 0xFF
        private fun blue(color: Int) = color and 0xFF
        private fun alpha(color: Int) = (color ushr 24) and 0xFF

        val fallback = UiTheme(
            id = "noir",
            label = "夜芯",
            backgroundTop = parseArgb("#060606"),
            backgroundBottom = parseArgb("#151311"),
            panel = parseArgb("#E00E0E0E"),
            card = parseArgb("#CC141414"),
            input = parseArgb("#D9111111"),
            bubble = parseArgb("#E0181818"),
            stroke = parseArgb("#40FFC86B"),
            accent = parseArgb("#FFFFB84D"),
            secondary = parseArgb("#FF53FFF2"),
            textPrimary = parseArgb("#FFF7F5F0"),
            textSecondary = parseArgb("#FFA6ADA8"),
            buttonText = parseArgb("#FFFFEFD4"),
            buttonFill = parseArgb("#CC121212"),
            buttonPressed = parseArgb("#E628241C"),
            success = parseArgb("#FF8FF0C4"),
            warning = parseArgb("#FFFFC86B"),
            danger = parseArgb("#FFFF7777"),
            focus = parseArgb("#FFFFB84D")
        )

        fun parseCatalog(source: String): UiThemeCatalog {
            val root = JSONObject(source)
            require(root.optInt("version") == 1) { "Unsupported UI theme catalog version" }
            val items = root.optJSONArray("themes") ?: error("UI theme catalog is missing themes")
            val themes = buildList {
                for (index in 0 until items.length()) {
                    val item = items.optJSONObject(index) ?: continue
                    val id = item.optString("id").takeIf { it.matches(Regex("[a-z0-9-]+")) } ?: continue
                    val label = item.optString("label").takeIf { it.isNotBlank() } ?: continue
                    val tokens = item.optJSONObject("tokens") ?: continue
                    fun color(name: String): Int = parseArgb(tokens.getString(name))
                    runCatching {
                        add(
                            UiTheme(
                                id = id,
                                label = label,
                                backgroundTop = color("backgroundTop"),
                                backgroundBottom = color("backgroundBottom"),
                                panel = color("panel"),
                                card = color("card"),
                                input = color("input"),
                                bubble = color("bubble"),
                                stroke = color("stroke"),
                                accent = color("accent"),
                                secondary = color("secondary"),
                                textPrimary = color("textPrimary"),
                                textSecondary = color("textSecondary"),
                                buttonText = color("buttonText"),
                                buttonFill = color("buttonFill"),
                                buttonPressed = color("buttonPressed"),
                                success = color("success"),
                                warning = color("warning"),
                                danger = color("danger"),
                                focus = color("focus")
                            )
                        )
                    }
                }
            }.distinctBy { it.id }
            require(themes.isNotEmpty()) { "UI theme catalog has no valid themes" }
            val requestedDefault = root.optString("defaultTheme", "noir")
            val defaultId = themes.firstOrNull { it.id == requestedDefault }?.id
                ?: themes.firstOrNull { it.id == "noir" }?.id
                ?: themes.first().id
            return UiThemeCatalog(defaultId, themes)
        }

        fun loadCatalog(context: Context): UiThemeCatalog = runCatching {
            context.assets.open("ui-themes.json").bufferedReader().use { parseCatalog(it.readText()) }
        }.getOrElse { UiThemeCatalog("noir", listOf(fallback)) }

        fun resolve(savedId: String?, catalog: UiThemeCatalog): UiTheme {
            val normalized = LEGACY_IDS[savedId] ?: savedId
            return catalog.themes.firstOrNull { it.id == normalized }
                ?: catalog.themes.firstOrNull { it.id == catalog.defaultThemeId }
                ?: fallback
        }

        fun load(context: Context): UiTheme {
            return load(context, loadCatalog(context))
        }

        fun load(context: Context, catalog: UiThemeCatalog): UiTheme {
            val saved = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getString(PREF_KEY, null)
            return resolve(saved, catalog)
        }

        fun save(context: Context, theme: UiTheme) {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putString(PREF_KEY, theme.id).apply()
        }

        fun compositeColor(foreground: Int, background: Int): Int {
            val foregroundAlpha = alpha(foreground) / 255f
            val backgroundAlpha = alpha(background) / 255f
            val outputAlpha = foregroundAlpha + backgroundAlpha * (1f - foregroundAlpha)
            if (outputAlpha <= 0f) return 0
            fun channel(fg: Int, bg: Int): Int =
                ((fg * foregroundAlpha + bg * backgroundAlpha * (1f - foregroundAlpha)) / outputAlpha)
                    .roundToInt().coerceIn(0, 255)
            return ((outputAlpha * 255).roundToInt() shl 24) or
                (channel(red(foreground), red(background)) shl 16) or
                (channel(green(foreground), green(background)) shl 8) or
                channel(blue(foreground), blue(background))
        }

        fun contrastRatio(foreground: Int, background: Int): Double {
            fun luminance(color: Int): Double {
                fun linear(channel: Int): Double {
                    val value = channel / 255.0
                    return if (value <= 0.04045) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)
                }
                return .2126 * linear(red(color)) + .7152 * linear(green(color)) + .0722 * linear(blue(color))
            }
            val first = luminance(foreground)
            val second = luminance(background)
            return (maxOf(first, second) + .05) / (minOf(first, second) + .05)
        }
    }
}

data class UiThemeCatalog(val defaultThemeId: String, val themes: List<UiTheme>)

class ThemeApplier(private val activity: Activity) {
    private var current = UiTheme.fallback

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

    fun styleThemeButton(button: Button, selected: Boolean) {
        val selectedText = ColorUtils.blendARGB(current.backgroundTop, Color.WHITE, .16f)
        button.backgroundTintList = ColorStateList.valueOf(if (selected) current.accent else current.buttonFill)
        button.setTextColor(if (selected) selectedText else current.buttonText)
        button.isSelected = selected
        button.contentDescription = "${button.text}主题${if (selected) "，当前选中" else ""}"
        button.translationZ = if (selected) 2f else 0f
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
