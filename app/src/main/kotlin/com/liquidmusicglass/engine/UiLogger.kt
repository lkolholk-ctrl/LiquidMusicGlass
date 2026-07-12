package com.liquidmusicglass.engine

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList

object UiLogger {
    val logs: SnapshotStateList<String> = mutableStateListOf()

    fun log(msg: String) {
        val ts = System.currentTimeMillis() % 100000
        val entry = "[$ts] $msg"
        android.util.Log.d("UiLogger", entry)
        // synchronized (P2, аудит): log() зовётся и с loader-потока ExoPlayer
        // (resolveStreamUrlSync), и с main — check-then-act на size под
        // конкуренцией ловил IndexOutOfBounds.
        synchronized(logs) {
            logs.add(entry)
            while (logs.size > 100) {
                logs.removeAt(0)
            }
        }
    }

    fun clear() {
        logs.clear()
    }
}
