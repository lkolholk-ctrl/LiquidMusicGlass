package com.liquidmusicglass.automix

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

object ModelLoader {

    fun load(context: Context): Interpreter {
        context.assets.openFd("automix_v2.tflite").use { fileDescriptor ->
            fileDescriptor.createInputStream().channel.use { channel ->
                val mapped: MappedByteBuffer = channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    fileDescriptor.startOffset,
                    fileDescriptor.declaredLength
                )

                return Interpreter(
                    mapped,
                    Interpreter.Options().apply {
                        setNumThreads(4)
                    }
                )
            }
        }
    }
}