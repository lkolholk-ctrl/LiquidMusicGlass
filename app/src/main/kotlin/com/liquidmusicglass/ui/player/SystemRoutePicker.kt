package com.liquidmusicglass.ui.player

import android.content.Context
import android.content.ContextWrapper
import androidx.fragment.app.FragmentActivity
import androidx.mediarouter.app.MediaRouteChooserDialogFragment
import androidx.mediarouter.media.MediaControlIntent
import androidx.mediarouter.media.MediaRouteSelector

private fun Context.findFragmentActivity(): FragmentActivity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is FragmentActivity) return current
        current = current.baseContext
    }
    return null
}

fun showSystemRoutePicker(context: Context) {
    val activity = context.findFragmentActivity() ?: return

    val selector = MediaRouteSelector.Builder()
        .addControlCategory(MediaControlIntent.CATEGORY_LIVE_AUDIO)
        .addControlCategory(MediaControlIntent.CATEGORY_REMOTE_PLAYBACK)
        .addControlCategory(MediaControlIntent.CATEGORY_LIVE_VIDEO)
        .build()

    val tag = "liquid_music_route_picker"
    val existing = activity.supportFragmentManager.findFragmentByTag(tag)
    if (existing != null) return

    val dialog = MediaRouteChooserDialogFragment().apply {
        routeSelector = selector
    }

    dialog.show(activity.supportFragmentManager, tag)
}