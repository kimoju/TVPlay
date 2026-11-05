package app.tvplay.ui.stage

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import app.tvplay.R
import app.tvplay.remote.CarPlayPicture
import app.tvplay.ui.theme.HeroBentoCardDrawable
import app.tvplay.ui.theme.TvColorScheme
import app.tvplay.ui.theme.TvIconGlyph
import app.tvplay.ui.theme.TvPlayButton
import app.tvplay.ui.theme.TvPlayButtonKind
import app.tvplay.ui.theme.TvVectorIcons

typealias CarPlayPicturePanel = StagePictureTuningOverlay

/**
 * In-activity real-time picture color/contrast tuning overlay (`ui-ux-pro-max`).
 *
 * Uses Bento slider cards, custom iOS/tvOS capsule switch ([TvVectorIcons.styleIosSwitch]),
 * and custom geometric vector icons ([TvVectorIcons]).
 */
class StagePictureTuningOverlay(
    context: Context,
    adjustmentsAvailable: Boolean = true,
    initialPalette: TvColorScheme = TvColorScheme.DARK,
    close: () -> Unit,
) : LinearLayout(context) {
    private var palette = initialPalette
    private val prefs = CarPlayPicture.preferences(context)
    private val controls = mutableMapOf<String, Pair<TextView, SeekBar>>()
    private val original = Switch(context)
    private val resetButton: TvPlayButton
    private val doneButton: TvPlayButton

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt().coerceAtLeast(1)

    private fun text(
        value: String,
        sizeSp: Float = 14f,
        bold: Boolean = false,
        secondary: Boolean = false,
    ) = TextView(context).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        if (bold) {
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        setTextColor(if (secondary) palette.overlaySecondaryText else palette.overlayPrimaryText)
    }

    private fun styleSwitch(view: Switch) {
        TvVectorIcons.styleIosSwitch(view, palette)
    }

    private fun panelBackground(scheme: TvColorScheme) =
        HeroBentoCardDrawable(scheme, cornerRadiusPx = dp(26).toFloat(), activeGlow = true)

    init {
        orientation = VERTICAL
        setPadding(dp(24), dp(20), dp(24), dp(20))
        background = panelBackground(palette)
        isClickable = true

        val headerRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        val headerBadge = TvVectorIcons.badgeWell(
            context = context,
            glyph = TvIconGlyph.SLIDERS,
            palette = palette,
            active = true,
            sizeDp = 36,
            iconDp = 18,
        )
        val headerCol = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            }
        }
        headerCol.addView(
            text(context.getString(R.string.picture_adjustments), sizeSp = 18.5f, bold = true),
        )
        headerCol.addView(
            text(
                context.getString(
                    if (adjustmentsAvailable) R.string.picture_live_hint
                    else R.string.picture_surface_output_unavailable,
                ),
                sizeSp = 12f,
                secondary = true,
            ).apply {
                setPadding(0, dp(2), 0, 0)
            },
        )
        headerRow.addView(headerBadge, LayoutParams(dp(36), dp(36)))
        headerRow.addView(headerCol)
        addView(headerRow)

        val rows = LinearLayout(context).apply { orientation = VERTICAL }
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(rows)
        }
        addView(scroll, LayoutParams(-1, 0, 1f))

        val labels = listOf(
            R.string.picture_brightness,
            R.string.picture_contrast,
            R.string.picture_saturation,
            R.string.picture_warmth,
        )
        CarPlayPicture.keys.forEachIndexed { index, key ->
            val sliderCard = LinearLayout(context).apply {
                orientation = VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(15).toFloat()
                    setColor(palette.overlaySurface)
                    setStroke(dp(1), palette.overlayOutline)
                }
            }
            val label = text("", sizeSp = 13f, bold = true)
            val range = CarPlayPicture.range(key)
            val slider = SeekBar(context).apply {
                max = range.last - range.first
                progress = CarPlayPicture.value(prefs, key) - range.first
                contentDescription = context.getString(labels[index])
                isEnabled = adjustmentsAvailable
                progressTintList = ColorStateList.valueOf(palette.overlayAccent)
                thumbTintList = ColorStateList.valueOf(palette.overlayAccent)
            }
            fun updateLabel(value: Int) {
                val sign = if (value > 0) "+$value" else value.toString()
                label.text = "${context.getString(labels[index])}：$sign"
            }
            updateLabel(CarPlayPicture.value(prefs, key))
            slider.setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seek: SeekBar, progress: Int, fromUser: Boolean) {
                        updateLabel(progress + range.first)
                        if (fromUser && adjustmentsAvailable) {
                            prefs.edit().putInt(key, progress + range.first).apply()
                        }
                    }

                    override fun onStartTrackingTouch(seek: SeekBar) = Unit
                    override fun onStopTrackingTouch(seek: SeekBar) = Unit
                },
            )
            controls[key] = label to slider
            sliderCard.addView(label)
            sliderCard.addView(slider, LayoutParams(-1, dp(36)))
            rows.addView(
                sliderCard,
                LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(8)
                },
            )
        }

        original.apply {
            text = context.getString(R.string.picture_show_original)
            setTextColor(palette.overlayPrimaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            styleSwitch(this)
            isEnabled = adjustmentsAvailable
            setPadding(dp(6), dp(8), dp(6), dp(8))
            setOnCheckedChangeListener { _, checked ->
                if (!adjustmentsAvailable) return@setOnCheckedChangeListener
                CarPlayPicture.showOriginal(checked)
                controls.values.forEach { it.second.isEnabled = adjustmentsAvailable && !checked }
            }
        }
        addView(
            original,
            LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
                bottomMargin = dp(10)
            },
        )

        val actions = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        resetButton = TvPlayButton(context).apply {
            text = context.getString(R.string.picture_reset)
            isEnabled = adjustmentsAvailable
            setVectorIcon(TvIconGlyph.REFRESH)
            style(TvPlayButtonKind.SECONDARY, palette, overlay = true)
            setOnClickListener {
                if (!adjustmentsAvailable) return@setOnClickListener
                original.isChecked = false
                CarPlayPicture.reset(prefs)
                controls.forEach { (key, pair) ->
                    pair.second.progress = CarPlayPicture.defaultValue(key) - CarPlayPicture.range(key).first
                }
            }
        }
        doneButton = TvPlayButton(context).apply {
            text = context.getString(R.string.picture_done)
            setVectorIcon(TvIconGlyph.CHECK)
            style(TvPlayButtonKind.PRIMARY, palette, overlay = true)
            setOnClickListener { close() }
        }
        actions.addView(
            resetButton,
            LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(10) },
        )
        actions.addView(
            doneButton,
            LayoutParams(0, dp(46), 1f),
        )
        addView(actions)
    }

    fun applyPalette(palette: TvColorScheme) {
        this.palette = palette
        background = panelBackground(palette)
        fun apply(view: View) {
            when (view) {
                is Switch -> {
                    view.setTextColor(palette.overlayPrimaryText)
                    styleSwitch(view)
                }
                is SeekBar -> {
                    view.progressTintList = ColorStateList.valueOf(palette.overlayAccent)
                    view.thumbTintList = ColorStateList.valueOf(palette.overlayAccent)
                }
                resetButton -> resetButton.style(TvPlayButtonKind.SECONDARY, palette, overlay = true)
                doneButton -> doneButton.style(TvPlayButtonKind.PRIMARY, palette, overlay = true)
                is TvPlayButton -> view.style(TvPlayButtonKind.PRIMARY, palette, overlay = true)
                is TextView -> view.setTextColor(palette.overlayPrimaryText)
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) apply(view.getChildAt(index))
            }
        }
        apply(this)
    }
}
