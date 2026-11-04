package app.tvplay.ui.stage

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import app.tvplay.R
import app.tvplay.remote.CarPlayPicture
import app.tvplay.ui.theme.TvColorScheme
import app.tvplay.ui.theme.TvPlayButton
import app.tvplay.ui.theme.TvPlayButtonKind

typealias CarPlayPicturePanel = StagePictureTuningOverlay

/** An in-activity overlay: no dimmed background, decoder restart, or stream negotiation. */
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
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun text(value: String) = TextView(context).apply {
        text = value
        textSize = 15f
        setTextColor(palette.overlayPrimaryText)
    }

    private fun styleSwitch(view: Switch) {
        val states = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(-android.R.attr.state_checked),
        )
        view.thumbTintList = ColorStateList(
            states,
            intArrayOf(palette.overlayAccent, palette.overlaySecondaryText),
        )
        view.trackTintList = ColorStateList(
            states,
            intArrayOf(palette.overlayAccentTrack, palette.overlayTrackOff),
        )
    }

    init {
        orientation = VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(8))
        setBackgroundColor(palette.overlayBackground)
        isClickable = true
        addView(text(context.getString(R.string.picture_adjustments)))
        val rows = LinearLayout(context).apply { orientation = VERTICAL }
        val scroll = ScrollView(context).apply { addView(rows) }
        addView(scroll, LayoutParams(-1, 0, 1f))
        rows.addView(
            text(
                context.getString(
                    if (adjustmentsAvailable) R.string.picture_live_hint
                    else R.string.picture_surface_output_unavailable,
                ),
            ),
        )
        val labels = listOf(
            R.string.picture_brightness,
            R.string.picture_contrast,
            R.string.picture_saturation,
            R.string.picture_warmth,
        )
        CarPlayPicture.keys.forEachIndexed { index, key ->
            val label = text("")
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
                label.text = "${context.getString(labels[index])}: $value"
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
            rows.addView(label)
            rows.addView(slider, LayoutParams(-1, dp(48)))
        }
        original.apply {
            text = context.getString(R.string.picture_show_original)
            setTextColor(palette.overlayPrimaryText)
            styleSwitch(this)
            isEnabled = adjustmentsAvailable
            setOnCheckedChangeListener { _, checked ->
                if (!adjustmentsAvailable) return@setOnCheckedChangeListener
                CarPlayPicture.showOriginal(checked)
                controls.values.forEach { it.second.isEnabled = adjustmentsAvailable && !checked }
            }
        }
        addView(original, LayoutParams(-1, dp(48)))
        val actions = LinearLayout(context)
        actions.addView(
            TvPlayButton(context).apply {
                text = context.getString(R.string.picture_reset)
                isEnabled = adjustmentsAvailable
                setOnClickListener {
                    if (!adjustmentsAvailable) return@setOnClickListener
                    original.isChecked = false
                    CarPlayPicture.reset(prefs)
                    controls.forEach { (key, pair) ->
                        pair.second.progress = CarPlayPicture.defaultValue(key) - CarPlayPicture.range(key).first
                    }
                }
            },
            LayoutParams(0, dp(48), 1f),
        )
        actions.addView(
            TvPlayButton(context).apply {
                text = context.getString(R.string.picture_done)
                setOnClickListener { close() }
            },
            LayoutParams(0, dp(48), 1f),
        )
        addView(actions)
    }

    fun applyPalette(palette: TvColorScheme) {
        this.palette = palette
        setBackgroundColor(palette.overlayBackground)
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
