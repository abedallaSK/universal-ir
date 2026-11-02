package com.universalir.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

/**
 * Lets the phone's own volume buttons drive the remote that is on screen. MainActivity hands volume key presses
 * to [handler]; while no remote screen has registered one, the keys change the phone volume as usual.
 */
object VolumeKeys {
    /** (up, repeat) -> handled. repeat = the key is being held (Android auto-repeat). */
    @Volatile var handler: ((Boolean, Boolean) -> Boolean)? = null
}

/** Registers [onKey] for the phone's volume buttons while this composable is shown and [enabled]. */
@Composable
fun VolumeKeyHandler(enabled: Boolean, onKey: (up: Boolean, repeat: Boolean) -> Unit) {
    val latest by rememberUpdatedState(onKey)
    DisposableEffect(enabled) {
        if (!enabled) return@DisposableEffect onDispose {}
        val h: (Boolean, Boolean) -> Boolean = { up, repeat -> latest(up, repeat); true }
        VolumeKeys.handler = h
        onDispose { if (VolumeKeys.handler === h) VolumeKeys.handler = null }
    }
}
