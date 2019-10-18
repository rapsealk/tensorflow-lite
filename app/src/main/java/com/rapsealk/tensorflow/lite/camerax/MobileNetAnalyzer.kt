package com.rapsealk.tensorflow.lite.camerax

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.media.ImageReader
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.rapsealk.tensorflow.lite.env.ImageUtils
import com.rapsealk.tensorflow.lite.tflite.Classifier
import com.rapsealk.tensorflow.lite.toByteArray

/**
 * Created by rapsealk on 2019-10-18..
 */
class MobileNetAnalyzer(private val activity: Activity) : ImageAnalysis.Analyzer {

    companion object {
        private val TAG = MobileNetAnalyzer::class.java.simpleName

        private const val MAINTAIN_ASPECT = true
    }

    private val classifier: Classifier = Classifier.create(activity, Classifier.Model.QUANTIZED, Classifier.Device.CPU, 1)

    private var rgbBytes: IntArray? = null

    init {

    }

    override fun analyze(image: ImageProxy, rotationDegrees: Int) {

        val rgb = rgbBytes ?: IntArray(image.width * image.height).apply {
            rgbBytes = this
        }

        // [1] YUV -> RGB
        val yBuffer = image.planes[0].buffer.toByteArray()
        val uBuffer = image.planes[1].buffer.toByteArray()
        val vBuffer = image.planes[2].buffer.toByteArray()
        val yuvBuffer = byteArrayOf(*yBuffer, *uBuffer, *vBuffer)
        //val yRowStride = image.width
        ImageUtils.convertYUV420SPToARGB8888(yuvBuffer, image.width, image.height, rgb)

        val yuvBytes = arrayOf(yuvBuffer)

        //

        val sensorOrientation = rotationDegrees - getScreenOrientation()
        val rgbFrameBitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        val croppedBitmap = Bitmap.createBitmap(classifier.getImageSizeX(), classifier.getImageSizeY(), Bitmap.Config.ARGB_8888)
        val frameToCropTransform = ImageUtils.getTransformationMatrix(
            image.width,
            image.height,
            classifier.getImageSizeX(),
            classifier.getImageSizeY(),
            sensorOrientation,
            MAINTAIN_ASPECT)
        val cropToFrameTransform = Matrix()
        frameToCropTransform.invert(cropToFrameTransform)

        //processImage()
        //val rgbBytes = IntArray(image.width * image.height)
        rgbFrameBitmap.setPixels(rgb, 0, image.width, 0, 0, image.width, image.height)
        val canvas = Canvas(croppedBitmap).apply {
            drawBitmap(rgbFrameBitmap, frameToCropTransform, null)
        }

        //runInBackground
        //classifier?.run {
        //}
        val startTime = SystemClock.uptimeMillis()
        val results = classifier.recognizeImage(croppedBitmap)
        Log.v(TAG, "Detect: $results")
        Log.v(TAG, "crop: ${croppedBitmap.width}x${croppedBitmap.height}")
        Log.v(TAG, "processing time: ${SystemClock.uptimeMillis() - startTime}ms")
        /*
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
        */
    }

    private fun processImage() {

    }

    private fun getScreenOrientation(): Int = when (activity.windowManager.defaultDisplay.rotation) {
        Surface.ROTATION_270 -> 270
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_90 -> 90
        else -> 0
    }
}