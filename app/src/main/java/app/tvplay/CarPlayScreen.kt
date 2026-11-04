package app.tvplay

import android.content.Context
import android.content.Intent
import java.lang.ref.WeakReference

/**
 * Tracks the active [TvPlayProjectionActivity] fullscreen CarPlay projection window.
 */
internal object CarPlayScreen {
    @Volatile private var open: WeakReference<TvPlayProjectionActivity>? = null

    fun target(@Suppress("UNUSED_PARAMETER") context: Context): Class<out TvPlayProjectionActivity> =
        TvPlayProjectionActivity::class.java

    fun intent(context: Context): Intent = Intent(context, TvPlayProjectionActivity::class.java)

    /** The open CarPlay screen, if it is not closing. */
    fun current(): TvPlayProjectionActivity? = open?.get()?.takeUnless { it.isFinishing || it.isDestroyed }

    fun forwarded(screen: TvPlayProjectionActivity): Boolean {
        open = WeakReference(screen)
        return false
    }

    fun destroyed(screen: TvPlayProjectionActivity) {
        if (open?.get() === screen) open = null
    }

    /** One diagnostic-report line: where the open screen runs. */
    fun report(@Suppress("UNUSED_PARAMETER") context: Context): String = buildString {
        val screen = current()
        if (screen == null) {
            append("open=none")
        } else {
            append("open=${screen.javaClass.simpleName} task=${screen.taskId}")
            append(" taskRoot=${runCatching { screen.isTaskRoot }.getOrNull()}")
            append(" multiWindow=${screen.isInMultiWindowMode}")
            append(" display=${screen.window?.decorView?.display?.displayId}")
        }
    }
}
