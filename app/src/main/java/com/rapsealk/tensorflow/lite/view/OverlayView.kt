package com.rapsealk.tensorflow.lite.view

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import java.util.*

/**
 * A simple View providing a render callback to other classes.
 */
class OverlayView(context: Context, attrs: AttributeSet) : View(context, attrs) {

    interface DrawCallback {
        fun drawCallback(canvas: Canvas)
    }

    private val callbacks: List<DrawCallback> = LinkedList()

    @Synchronized
    override fun draw(canvas: Canvas?) {
        canvas?.let {
            for (callback in callbacks) {
                callback.drawCallback(it)
            }
        }
    }
}