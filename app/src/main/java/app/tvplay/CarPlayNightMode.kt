package app.tvplay

/** Stable preference values; never persist enum ordinals. */
enum class CarPlayNightMode(val key: String) {
    SYSTEM("system"),
    DAY("day"),
    NIGHT("night");

    companion object {
        fun fromKey(key: String?): CarPlayNightMode = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

/** Main-thread controller. Only its output is sent to CarPlay; Android theme is never changed. */
internal class CarPlayNightModeController(
    initialNight: Boolean,
    private val onNightChanged: (Boolean) -> Unit,
) {
    var night: Boolean = initialNight
        private set
    private var mode = CarPlayNightMode.SYSTEM
    private var systemNight = initialNight

    fun configure(
        mode: CarPlayNightMode,
        systemNight: Boolean,
    ) {
        this.mode = mode
        this.systemNight = systemNight
        applyMode()
    }

    fun resume(systemNight: Boolean) {
        this.systemNight = systemNight
        applyMode()
    }

    fun pause() = Unit

    fun systemChanged(night: Boolean) {
        systemNight = night
        if (mode == CarPlayNightMode.SYSTEM) applyNight(night)
    }

    private fun applyMode() {
        when (mode) {
            CarPlayNightMode.SYSTEM -> applyNight(systemNight)
            CarPlayNightMode.DAY -> applyNight(false)
            CarPlayNightMode.NIGHT -> applyNight(true)
        }
    }

    private fun applyNight(value: Boolean) {
        if (value == night) return
        night = value
        onNightChanged(value)
    }
}
