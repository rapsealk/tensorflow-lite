package com.rapsealk.tensorflow.lite

import android.annotation.SuppressLint
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.os.Bundle
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.util.SparseIntArray
import android.view.Surface
import android.view.TextureView
import android.view.View
import androidx.fragment.app.Fragment
import com.rapsealk.tensorflow.lite.env.ImageUtils
import com.rapsealk.tensorflow.lite.view.AutoFitTextureView
import java.io.IOException

@SuppressLint("ValidFragment")
class LegacyCameraConnectionFragment(private val imageListener: Camera.PreviewCallback,
                                     private val layout: Int,
                                     private val desiredSize: Size
)
    : Fragment() {

    companion object {
        private val TAG = LegacyCameraConnectionFragment::class.java.simpleName

        // Conversion from screen rotation to JPEG orientation. */
        private val ORIENTATIONS = SparseIntArray().apply {
            append(Surface.ROTATION_0, 90)
            append(Surface.ROTATION_90, 0)
            append(Surface.ROTATION_180, 270)
            append(Surface.ROTATION_270, 180)
        }
    }

    private var camera: Camera? = null

    private lateinit var textureView: AutoFitTextureView
    private val surfaceTextureListener = object: TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            val index: Int = getCameraId()
            camera = Camera.open(index)

            try {
                val parameters = camera?.parameters
                    ?: throw RuntimeException("Failed to retrieve camera parameters!")
                val focusModes = parameters.supportedFocusModes
                if (focusModes != null && focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                    parameters.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
                }
                val cameraSizes = parameters.supportedPreviewSizes
                val sizes = cameraSizes.map { Size(it.width, it.height) }.toTypedArray()
                val previewSize = CameraConnectionFragment.chooseOptimalSize(sizes, desiredSize.width, desiredSize.height)
                parameters.setPreviewSize(previewSize.width, previewSize.height)
                camera?.apply {
                    setDisplayOrientation(90)
                    setParameters(parameters)
                    setPreviewTexture(texture)
                }
            } catch (e: IOException) {
                camera?.release()
            }

            camera?.apply {
                setPreviewCallbackWithBuffer(imageListener)
                val size = this.parameters.previewSize
                addCallbackBuffer(ByteArray(ImageUtils.getYUVByteSize(size.height, size.width)))
                textureView.setAspectRatio(size.height, size.width)
                startPreview()
            }
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) { }

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean = true

        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) { }
    }

    /** An additional thread for running tasks that shouldn't block the UI. */
    private var backgroundThread: HandlerThread? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        textureView = view.findViewById(R.id.texture)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        // When the screen is turned off and turned back on, the SurfaceTexture is already
        // available, and "onSurfaceTextureAvailable" will not be called. In that case, we can open
        // a camera and start preview from here (otherwise, we wait until the surface is ready in
        // the SurfaceTextureListener).

        if (textureView.isAvailable) {
            camera?.startPreview()
        } else {
            textureView.surfaceTextureListener = surfaceTextureListener
        }
    }

    override fun onPause() {
        stopCamera()
        stopBackgroundThread()
        super.onPause()
    }

    /** Starts a background thread and its {@link Handler}. */
    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").apply {
            start()
        }
    }

    /** Stops the background thread and its {@link Handler}. */
    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.apply {
                join()
                backgroundThread = null
            }
        } catch (e: InterruptedException) {
            Log.e(TAG, "Exception!", e)
        }
    }

    protected fun stopCamera() {
        camera?.apply {
            stopPreview()
            setPreviewCallback(null)
            release()
            camera = null
        }
    }

    private fun getCameraId(): Int {
        val cameraInfo = Camera.CameraInfo()
        for (i in 0 until Camera.getNumberOfCameras()) {
            Camera.getCameraInfo(i, cameraInfo)
            if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_BACK) return i
        }
        return -1   // No camera found
    }
}