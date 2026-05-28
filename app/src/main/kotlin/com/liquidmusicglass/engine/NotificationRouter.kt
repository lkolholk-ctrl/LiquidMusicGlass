package com.liquidmusicglass.engine

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Lightweight event bus for notification → UI navigation.
 * Emitted from MainActivity when user taps the media notification.
 * Collected in AppRoot to trigger FullPlayer expansion.
 */
object NotificationRouter {
    private val _openLargePlayer = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val openLargePlayer: SharedFlow<Unit> = _openLargePlayer.asSharedFlow()

    fun emitOpenLargePlayer() {
        _openLargePlayer.tryEmit(Unit)
    }
}
