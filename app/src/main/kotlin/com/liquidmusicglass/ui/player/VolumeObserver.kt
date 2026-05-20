package com.liquidmusicglass.ui.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"

@Composable
fun rememberSystemVolume(): State<Float> {
    val context = LocalContext.current
    val audioManager = remember {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    val maxVolume = remember {
        audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat()
    }

    val volume = remember {
        mutableFloatStateOf(
            audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) / maxVolume
        )
    }

    DisposableEffect(Unit) {
        val handler = Handler(Looper.getMainLooper())

        // BroadcastReceiver — ловит VOLUME_CHANGED мгновенно
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                volume.floatValue = current / maxVolume
            }
        }
        context.registerReceiver(
            receiver,
            IntentFilter(VOLUME_CHANGED_ACTION)
        )

        // ContentObserver — fallback
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                volume.floatValue = current / maxVolume
            }
        }
        context.contentResolver.registerContentObserver(
            Settings.System.CONTENT_URI,
            true,
            observer
        )

        onDispose {
            context.unregisterReceiver(receiver)
            context.contentResolver.unregisterContentObserver(observer)
        }
    }

    return volume
}

fun setSystemVolume(context: Context, fraction: Float) {
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    val target = (fraction * max).toInt().coerceIn(0, max)
    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
}
