package app.tvplay.ui.projection

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.tvplay.CarPlayNightMode
import app.tvplay.orchestration.WirelessHotspotMode
import kotlin.math.roundToInt

/**
 * Full-screen Apple TV-style quick control overlay menu (`KEYCODE_MENU` or long-press `KEYCODE_BACK`).
 */
internal object QuickControlOverlayView {
    fun build(
        context: Context,
        wirelessEnabled: Boolean,
        wirelessHotspotMode: WirelessHotspotMode,
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
    ): View {
        fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()

        val overlay = FrameLayout(context).apply {
            setBackgroundColor(Color.parseColor("#CC06090F"))
            isClickable = true
        }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(24), dp(28), dp(24))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(26).toFloat()
                setColor(Color.parseColor("#121826"))
                setStroke(dp(2), Color.parseColor("#2E3C56"))
            }
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(16))
        }
        header.addView(
            TextView(context).apply {
                text = "TVPlay 快捷控制中心"
                textSize = 22f
                setTextColor(Color.WHITE)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            },
        )
        header.addView(
            TextView(context).apply {
                val modeText = if (!wirelessEnabled) {
                    "USB 有线模式"
                } else if (wirelessHotspotMode == WirelessHotspotMode.EXISTING_WIFI) {
                    "无线 · 同一局域网 Wi-Fi"
                } else {
                    "无线 · Wi-Fi Direct 直连"
                }
                text = "$modeText  ·  $fps FPS  ·  ${if (sessionHevc) "HEVC" else "H.264"}"
                textSize = 14f
                setTextColor(Color.parseColor("#94A3B8"))
                setPadding(0, dp(4), 0, 0)
            },
        )
        card.addView(header, LinearLayout.LayoutParams(-1, -2))

        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        fun addMenuButton(label: String, primary: Boolean = false, danger: Boolean = false, action: () -> Unit) {
            list.addView(
                ProjectionWaitingView.createTvButton(
                    context,
                    label = label,
                    primary = primary,
                    danger = danger,
                    onClick = action,
                ),
                LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(10) },
            )
        }

        addMenuButton("▶ 继续全屏投屏", primary = true, action = onResumeProjection)
        addMenuButton("🏠 返回 TVPlay 工作台", action = onReturnToWorkbench)
        addMenuButton("🎨 画面色彩与对比度调节", action = onOpenPicturePanel)
        addMenuButton("🖥 分辨率缩放 · ${displayScalePercent}%（点击切换）", action = onCycleDisplayScale)
        addMenuButton("⚡ 视频帧率 · $fps FPS（点击切换 60 / 30）", action = onToggleFps)
        addMenuButton("🎞 视频编码 · ${if (hevcEnabled) "HEVC (H.265)" else "H.264"}（点击切换）", action = onToggleHevc)
        addMenuButton("📊 实时帧率浮窗 (FPS) · ${if (fpsCounterShown) "已开启" else "已关闭"}", action = onToggleFpsCounter)
        addMenuButton("🌙 CarPlay 外观 · ${nightModeLabel(carPlayNightMode)}", action = onCycleNightMode)
        addMenuButton("🔄 立即重新连接 iPhone", action = onReconnectNow)
        addMenuButton("⏹ 断开连接并退出投屏", danger = true, action = onDisconnectAndExit)

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            addView(list, ViewGroup.LayoutParams(-1, -2))
        }
        card.addView(scroll, LinearLayout.LayoutParams(-1, -2))
        overlay.addView(
            card,
            FrameLayout.LayoutParams(dp(520), -2, Gravity.CENTER),
        )
        return overlay
    }

    private fun nightModeLabel(mode: CarPlayNightMode): String = when (mode) {
        CarPlayNightMode.NIGHT -> "始终深色"
        CarPlayNightMode.DAY -> "始终浅色"
        else -> "跟随系统"
    }
}
