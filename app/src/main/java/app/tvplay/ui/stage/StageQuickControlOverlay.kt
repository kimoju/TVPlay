package app.tvplay.ui.stage

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import app.tvplay.engine.session.TvWirelessMode
import app.tvplay.remote.CarPlayNightMode
import app.tvplay.ui.theme.HeroBentoCardDrawable
import app.tvplay.ui.theme.TvColorScheme
import app.tvplay.ui.theme.TvIconGlyph
import app.tvplay.ui.theme.TvVectorIcons
import kotlin.math.roundToInt

typealias QuickControlOverlayView = StageQuickControlOverlay

/**
 * Full-screen Apple TV / tvOS Control Center style quick overlay (`ui-ux-pro-max`).
 *
 * Uses custom geometric vector icons ([TvVectorIcons]), glassmorphic Bento surface,
 * and non-jittering high-contrast focus borders.
 */
object StageQuickControlOverlay {
    fun build(
        context: Context,
        wirelessEnabled: Boolean,
        wirelessHotspotMode: TvWirelessMode,
        fps: Int,
        sessionHevc: Boolean,
        hevcEnabled: Boolean,
        displayScalePercent: Int,
        fpsCounterShown: Boolean,
        carPlayNightMode: CarPlayNightMode,
        onResumeProjection: () -> Unit,
        onReturnToWorkbench: () -> Unit,
        onOpenPicturePanel: () -> Unit,
        onCycleDisplayScale: () -> Unit,
        onToggleFps: () -> Unit,
        onToggleHevc: () -> Unit,
        onToggleFpsCounter: () -> Unit,
        onCycleNightMode: () -> Unit,
        onReconnectNow: () -> Unit,
        onDisconnectAndExit: () -> Unit,
        initialFocusIndex: Int = 0,
    ): View {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).roundToInt().coerceAtLeast(1)
        val palette = TvColorScheme.DARK

        val overlay = FrameLayout(context).apply {
            setBackgroundColor(Color.parseColor("#DC05080F"))
            isClickable = true
            clipChildren = false
            clipToPadding = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(28), dp(22), dp(28), dp(22))
            background = HeroBentoCardDrawable(palette, cornerRadiusPx = dp(28).toFloat(), activeGlow = true)
        }

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(14))
        }
        val headerBadge = TvVectorIcons.badgeWell(
            context = context,
            glyph = TvIconGlyph.SLIDERS,
            palette = palette,
            active = true,
            sizeDp = 38,
            iconDp = 18,
        )
        val headerTextCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            }
        }
        headerTextCol.addView(
            TextView(context).apply {
                text = "TVPlay 快捷控制中心"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setTextColor(Color.WHITE)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            },
        )
        headerTextCol.addView(
            TextView(context).apply {
                val modeText = if (!wirelessEnabled) {
                    "USB 有线模式"
                } else if (wirelessHotspotMode == TvWirelessMode.LAN_WIFI) {
                    "无线 · 家庭局域网"
                } else {
                    "无线 · Wi-Fi Direct 5GHz"
                }
                text = "$modeText  ·  按返回键收起面板"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(Color.parseColor("#94A3B8"))
                setPadding(0, dp(2), 0, 0)
            },
        )
        val liveSpecBadge = TextView(context).apply {
            text = "${fps} FPS · ${if (sessionHevc) "HEVC" else "H.264"}"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(Color.parseColor("#38BDF8"))
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 999f * density
                setColor(Color.parseColor("#243B82F6"))
                setStroke(dp(1), Color.parseColor("#6638BDF8"))
            }
        }
        headerRow.addView(headerBadge, LinearLayout.LayoutParams(dp(38), dp(38)))
        headerRow.addView(headerTextCol)
        headerRow.addView(liveSpecBadge)
        card.addView(headerRow, LinearLayout.LayoutParams(-1, -2))

        val divider = View(context).apply {
            setBackgroundColor(Color.parseColor("#24324B"))
        }
        card.addView(
            divider,
            LinearLayout.LayoutParams(-1, dp(1)).apply { bottomMargin = dp(12) },
        )

        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        val buttons = mutableListOf<View>()
        fun addMenuButton(
            label: String,
            glyph: TvIconGlyph,
            primary: Boolean = false,
            danger: Boolean = false,
            action: () -> Unit,
        ) {
            val fillColor = when {
                danger -> 0xCC7F1D1D.toInt()
                primary -> 0xFF3B82F6.toInt()
                else -> 0xFF141E30.toInt()
            }
            val strokeColor = when {
                danger -> 0xFFFCA5A5.toInt()
                primary -> 0xFF93C5FD.toInt()
                else -> 0xFF283650.toInt()
            }
            val btn = TextView(context).apply {
                id = View.generateViewId()
                text = label
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                isFocusable = true
                isFocusableInTouchMode = false
                isClickable = true
                compoundDrawablePadding = dp(12)
                setPadding(dp(18), dp(10), dp(18), dp(10))
                fun applyRowStyle(focused: Boolean) {
                    val iconTint = when {
                        danger -> 0xFFFCA5A5.toInt()
                        primary || focused -> Color.WHITE
                        else -> 0xFF60A5FA.toInt()
                    }
                    val icon = TvVectorIcons.drawable(context, glyph, iconTint, sizeDp = 17, strokeDp = 2f)
                    setCompoundDrawablesRelative(icon, null, null, null)
                    val topFill = ColorUtils.blendARGB(fillColor, Color.WHITE, if (focused) 0.18f else 0.05f)
                    val bottomFill = if (focused) {
                        ColorUtils.blendARGB(fillColor, Color.parseColor("#3B82F6"), 0.28f)
                    } else {
                        fillColor
                    }
                    background = GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        intArrayOf(topFill, bottomFill),
                    ).apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(15).toFloat()
                        setStroke(
                            if (focused) dp(2) else dp(1),
                            if (focused) Color.parseColor("#38BDF8") else strokeColor,
                        )
                    }
                }
                applyRowStyle(false)
                setOnFocusChangeListener { _, hasFocus ->
                    applyRowStyle(hasFocus)
                }
                setOnClickListener { action() }
            }
            buttons += btn
            list.addView(
                btn,
                LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(7) },
            )
        }

        addMenuButton("继续全屏投屏", glyph = TvIconGlyph.PLAY, primary = true, action = onResumeProjection)
        addMenuButton("返回 TVPlay 工作台", glyph = TvIconGlyph.HOME, action = onReturnToWorkbench)
        addMenuButton("画面色彩与对比度调节", glyph = TvIconGlyph.SLIDERS, action = onOpenPicturePanel)
        addMenuButton(
            "分辨率缩放  ·  当前 ${displayScalePercent}%（点击切换）",
            glyph = TvIconGlyph.DISPLAY,
            action = onCycleDisplayScale,
        )
        addMenuButton(
            "视频帧率  ·  当前 $fps FPS（点击切换 60 / 30）",
            glyph = TvIconGlyph.FPS_GAUGE,
            action = onToggleFps,
        )
        addMenuButton(
            "视频编码  ·  当前 ${if (hevcEnabled) "HEVC (H.265)" else "H.264"}（点击切换）",
            glyph = TvIconGlyph.SPARKLES,
            action = onToggleHevc,
        )
        addMenuButton(
            "实时帧率浮窗 (FPS)  ·  ${if (fpsCounterShown) "已开启" else "已关闭"}",
            glyph = TvIconGlyph.FPS_GAUGE,
            action = onToggleFpsCounter,
        )
        addMenuButton(
            "CarPlay 外观  ·  ${nightModeLabel(carPlayNightMode)}",
            glyph = TvIconGlyph.MOON,
            action = onCycleNightMode,
        )
        addMenuButton("立即重新连接 iPhone", glyph = TvIconGlyph.REFRESH, action = onReconnectNow)
        addMenuButton("断开连接并退出投屏", glyph = TvIconGlyph.POWER, danger = true, action = onDisconnectAndExit)

        if (buttons.isNotEmpty()) {
            buttons.first().nextFocusUpId = buttons.first().id
            buttons.last().nextFocusDownId = buttons.last().id
            buttons.forEach { btn ->
                btn.nextFocusLeftId = btn.id
                btn.nextFocusRightId = btn.id
            }
        }

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            addView(list, ViewGroup.LayoutParams(-1, -2))
        }
        card.addView(scroll, LinearLayout.LayoutParams(-1, -2))
        overlay.addView(
            card,
            FrameLayout.LayoutParams(dp(560), -2, Gravity.CENTER),
        )
        val targetButton = buttons.getOrNull(initialFocusIndex.coerceIn(0, (buttons.size - 1).coerceAtLeast(0)))
        targetButton?.post { targetButton.requestFocus() }
        return overlay
    }

    private fun nightModeLabel(mode: CarPlayNightMode): String = when (mode) {
        CarPlayNightMode.NIGHT -> "始终深色"
        CarPlayNightMode.DAY -> "始终浅色"
        else -> "跟随系统"
    }
}
