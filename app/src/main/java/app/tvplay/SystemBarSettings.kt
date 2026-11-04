package app.tvplay

import app.tvplay.R

internal fun addSystemBarControls(
    hideTopBar: Boolean,
    hideBottomBar: Boolean,
    onHideTopBarChanged: (Boolean) -> Unit,
    onHideBottomBarChanged: (Boolean) -> Unit,
    addSwitch: (label: Int, checked: Boolean, onChanged: (Boolean) -> Unit) -> Unit,
) {
    addSwitch(R.string.hide_the_status_bar, hideTopBar, onHideTopBarChanged)
    addSwitch(R.string.hide_the_navigation_bar, hideBottomBar, onHideBottomBarChanged)
}
