package com.rapsealk.tensorflow.lite

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Typeface
import android.media.ImageReader
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.util.TypedValue
import android.widget.Toast
import com.rapsealk.tensorflow.lite.env.BorderedText
import com.rapsealk.tensorflow.lite.env.ImageUtils
import com.rapsealk.tensorflow.lite.tflite.Classifier
import java.io.IOException

class ClassifierActivity : CameraActivity(), ImageReader.OnImageAvailableListener {

    companion object {
        private val TAG = ClassifierActivity::class.java.simpleName

        private const val MAINTAIN_ASPECT = true
        private val DESIRED_PREVIEW_SIZE = Size(640, 480)
        private const val TEXT_SIZE_DIP: Float = 10f
    }

    private var rgbFrameBitmap: Bitmap? = null
    private var croppedBitmap: Bitmap? = null
    private var cropCopyBitmap: Bitmap? = null
    private var lastProcessingTimeMs: Long = 0
    private var sensorOrientation: Int = 0
    private var classifier: Classifier? = null
    private lateinit var frameToCropTransform: Matrix
    private lateinit var cropToFrameTransform: Matrix
    private lateinit var borderedText: BorderedText

    override protected fun getLayoutId(): Int = R.layout.fragment_camera_connection

    override fun getDesiredPreviewFrameSize(): Size = DESIRED_PREVIEW_SIZE

    override fun onPreviewSizeChosen(size: Size, rotation: Int) {
        val textSizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, TEXT_SIZE_DIP, resources.displayMetrics)
        borderedText = BorderedText(textSizePx)
        borderedText.setTypeface(Typeface.MONOSPACE)

        recreateClassifier(getModel(), getDevice(), getNumThreads())
        val classifier = classifier
            ?: throw RuntimeException("No classifier on preview!")

        previewWidth = size.width
        previewHeight = size.height

        sensorOrientation = rotation - getScreenOrientation()
        Log.i(TAG, "Camera orientation relative to screen canvas: $sensorOrientation")

        Log.i(TAG, "Initializing at size ${previewWidth}x${previewHeight}")
        rgbFrameBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888)
        croppedBitmap = Bitmap.createBitmap(classifier.getImageSizeX(), classifier.getImageSizeY(), Bitmap.Config.ARGB_8888)

        frameToCropTransform = ImageUtils.getTransformationMatrix(previewWidth,
            previewHeight,
            classifier.getImageSizeX(),
            classifier.getImageSizeY(),
            sensorOrientation,
            MAINTAIN_ASPECT)
        cropToFrameTransform = Matrix()
        frameToCropTransform.invert(cropToFrameTransform)
    }

    override fun processImage() {
        val rgbFrameBitmap = rgbFrameBitmap ?:
        throw RuntimeException("rgbFrameBitmap is NULL!")
        val croppedBitmap = croppedBitmap ?:
        throw RuntimeException("CroppedBitmap is NULL!")

        rgbFrameBitmap.setPixels(getRgbBytes(), 0, previewWidth, 0, 0, previewWidth, previewHeight)
        val canvas = Canvas(croppedBitmap).apply {
            drawBitmap(rgbFrameBitmap, frameToCropTransform, null)
        }

        runInBackground(Runnable {
            classifier?.run {
                val startTime: Long = SystemClock.uptimeMillis()
                val results = this.recognizeImage(croppedBitmap)
                lastProcessingTimeMs = SystemClock.uptimeMillis()
                Log.v(TAG, "Detect: $results")
                val cropCopyBitmap = Bitmap.createBitmap(croppedBitmap).also {
                    cropCopyBitmap = it
                }

                runOnUiThread(Runnable {
                    showResultsInBottomSheet(results)
                    showFrameInfo("${previewWidth}x${previewHeight}")
                    showCropInfo("${cropCopyBitmap.width}x${cropCopyBitmap.height}")
                    showCameraResolution("${canvas.width}x${canvas.height}")
                    showRotationInfo(sensorOrientation.toString())
                    showInference("${lastProcessingTimeMs - startTime}ms")
                })
            }
            readyForNextImage()
        })
    }

    override fun onInferenceConfigurationChanged() {
        // Defer creation until we are getting camera frames.
        croppedBitmap ?: return

        val device = getDevice()
        val model = getModel()
        val numThreads = getNumThreads()

        runInBackground(Runnable {
            recreateClassifier(model, device, numThreads)
        })
    }

    private fun recreateClassifier(model: Classifier.Model, device: Classifier.Device, numThreads: Int) {
        classifier?.apply {
            Log.d(TAG, "Closing classifier..")
            close()
            classifier = null
        }
        if (device == Classifier.Device.GPU && model == Classifier.Model.QUANTIZED) {
            Log.w(TAG, "Not creating classifier: GPU doesn't support quantized models.")
            runOnUiThread {
                Toast.makeText(this, "GPU does not yet support quantized models.", Toast.LENGTH_LONG).show()
            }
            return
        }
        try {
            Log.d(TAG, "Creating classifier (model=$model, device=$device, numThreads=$numThreads)")
            classifier = Classifier.create(this, model, device, numThreads)
        } catch (e: IOException) {
            Log.e(TAG, "Failed to create classifier.", e)
        }
    }
}