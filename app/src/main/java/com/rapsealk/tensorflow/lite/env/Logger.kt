package com.rapsealk.tensorflow.lite.env

import android.util.Log

class Logger {

    companion object {
        private const val DEFAULT_TAG = "tensorflow-lite"
        private const val DEFAULT_MIN_LOG_LEVEL = Log.DEBUG

        private val IGNORED_CLASS_NAMES = hashSetOf(
            "dalvik.system.VMStack",
            "java.lang.Thread",
            Logger::class.java.canonicalName
        )
    }
}