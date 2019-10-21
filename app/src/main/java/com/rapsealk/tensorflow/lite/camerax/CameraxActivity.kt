package com.rapsealk.tensorflow.lite.camerax

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Matrix
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Rational
import android.util.Size
import android.view.Surface
import android.view.WindowManager
import androidx.camera.core.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.rapsealk.tensorflow.lite.R
import kotlinx.android.synthetic.main.activity_camerax.*

class CameraxActivity : AppCompatActivity() {

    companion object {
        private val TAG = CameraxActivity::class.java.simpleName

        val REQUEST_CODE = CameraxActivity::class.java.hashCode()
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, "onCreate $this")
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camerax)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (REQUIRED_PERMISSIONS.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            viewFinder.post { startCamera() }
        } else {
            ActivityCompat.requestPermissions(this,
                REQUIRED_PERMISSIONS,
                REQUEST_CODE
            )
        }

        viewFinder.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateTransform()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        when (requestCode) {
            REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    viewFinder.post { startCamera() }
                } else {
                    // TODO: Snackbar
                }
            }
        }
    }

    private fun startCamera() {
        CameraX.unbindAll()
        val preview = buildPreviewUseCase()
        val imageAnalysis = buildImageAnalysisUseCase()
        CameraX.bindToLifecycle(this, preview, imageAnalysis)
    }

    private fun buildPreviewUseCase(): Preview {
        val config = PreviewConfig.Builder().apply {
            setTargetAspectRatio(Rational(1, 1))
            //setTargetRotation()
            setTargetResolution(Size(640, 640))
            setLensFacing(CameraX.LensFacing.BACK)
            //setCaptureProcessor()
        }.build()
        val preview = Preview(config).apply {
            setOnPreviewOutputUpdateListener {
                viewFinder.surfaceTexture = it.surfaceTexture
                // updateTransform()
            }
        }
        return preview
    }

    private fun buildImageAnalysisUseCase(): ImageAnalysis {
        val config = ImageAnalysisConfig.Builder().apply {
            setTargetAspectRatio(Rational(1, 1))
            //setTargetRotation()
            setTargetResolution(Size(640, 640))
            setImageReaderMode(ImageAnalysis.ImageReaderMode.ACQUIRE_LATEST_IMAGE)
            //setImageQueueDepth()

            // Analyzer thread
            val analyzerThread = HandlerThread("MobileNetAnalysis").apply { start() }
            setCallbackHandler(Handler(analyzerThread.looper))
        }.build()
        val imageAnalysis = ImageAnalysis(config).apply {
            analyzer = MobileNetAnalyzer(this@CameraxActivity)
        }
        return imageAnalysis
    }

    private fun buildImageCaptureUseCase(): ImageCapture {
        val config = ImageCaptureConfig.Builder().apply {
            setTargetAspectRatio(Rational(1, 1))
            //setTargetRotation()
            //setTargetResolution(Size(640, 640))
            setCaptureMode(ImageCapture.CaptureMode.MIN_LATENCY)
            setFlashMode(FlashMode.OFF)
        }.build()
        val imageCapture = ImageCapture(config)
        // Capture Button
        return imageCapture
    }

    private fun updateTransform() {
        val matrix = Matrix()
        val centerX = viewFinder.width / 2f
        val centerY = viewFinder.height / 2f

        val rotationDegrees = when (viewFinder.display.rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> return
        }   // viewFinder.display.rotation * 90
        matrix.postRotate(-rotationDegrees.toFloat(), centerX, centerY)

        viewFinder.setTransform(matrix)
    }
}
