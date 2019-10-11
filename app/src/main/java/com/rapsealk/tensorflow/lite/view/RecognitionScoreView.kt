package com.rapsealk.tensorflow.lite.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.rapsealk.tensorflow.lite.tflite.Classifier

class RecognitionScoreView(context: Context, attrs: AttributeSet) : View(context, attrs), ResultsView {

    companion object {
        private const val TEXT_SIZE_DIP: Float = 16.0f
    }

    private val textSizePx: Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, TEXT_SIZE_DIP, resources.displayMetrics)
    private val fgPaint: Paint = Paint().apply { textSize = textSizePx }
    private val bgPaint: Paint = Paint().apply { color = 0xCC4285F4.toInt() }
    private var results: List<Classifier.Companion.Recognition>? = null

    override fun setResults(results: List<Classifier.Companion.Recognition>) {
        this.results = results
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val x = 10
        var y = (fgPaint.textSize * 1.5f).toInt()

        canvas.drawPaint(bgPaint)

        results?.forEach { recog ->
            canvas.drawText("${recog.title}: ${recog.confidence}", x.toFloat(), y.toFloat(), fgPaint)
            y += (fgPaint.textSize * 1.5f).toInt()
        }
    }
}