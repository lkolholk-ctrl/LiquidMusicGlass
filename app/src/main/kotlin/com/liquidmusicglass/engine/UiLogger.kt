package com.liquidmusicglass.engine

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList

object UiLogger {
    val logs: SnapshotStateList<String> = mutableStateListOf()

    fun log(msg: String) {
        val ts = System.currentTimeMillis() % 100000
        val entry = "[$ts] $msg"
        logs.add(entry)
        android.util.Log.d("UiLogger", entry)
        // Keep last 100 entries
        while (logs.size > 100) {
            logs.removeAt(0)
        }
    }

    fun clear() {
        logs.clear()
    }
}
