package com.rapsealk.tensorflow.lite

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.text.TextUtils
import android.util.Log
import android.util.Size
import android.util.SparseIntArray
import android.view.*
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import com.rapsealk.tensorflow.lite.view.AutoFitTextureView
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.Comparator
import kotlin.collections.ArrayList
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

@SuppressLint("ValidFragment")
class CameraConnectionFragment private constructor(private val cameraConnectionCallback: ConnectionCallback,
                                                   private val imageListener: ImageReader.OnImageAvailableListener,
                                                   private val layout: Int,
                                                   private val inputSize: Size
)
    : Fragment() {

    companion object {
        private val TAG = CameraConnectionFragment::class.java.simpleName

        private val FRAGMENT_DIALOG = "Fragment"

        /**
         * The camera preview size will be chosen to be the smallest frame by pixel size capable of
         * containing a DESIRED_SIZE x DESIRED_SIZE square.
         */
        private const val MINIMUM_PREVIEW_SIZE: Int = 320

        /** Conversion from screen rotation to JPEG orientation. */
        private val ORIENTATIONS = SparseIntArray().apply {
            append(Surface.ROTATION_0, 90)
            append(Surface.ROTATION_90, 0)
            append(Surface.ROTATION_180, 270)
            append(Surface.ROTATION_270, 180)
        }

        /**
         * Given {@code choices} of {@code Size}s supported by a camera, chooses the smallest one whose
         * width and height are at least as large as the minimum of both, or an exact match if possible.
         *
         * @param choices The list of sizes that the camera supports for the intended output class
         * @param width The minimum desired width
         * @param height The minimum desired height
         * @return The optimal {@code Size}, or an arbitrary one if none were big enough
         */
        fun chooseOptimalSize(choices: Array<Size>, width: Int, height: Int): Size {
            val minSize: Int = max(min(width, height), MINIMUM_PREVIEW_SIZE)
            val desiredSize = Size(width, height)

            // Collect the supported resolutions that are at least as big as the preview Surface
            var exactSizeFound = false
            val bigEnough = ArrayList<Size>()
            val tooSmall = ArrayList<Size>()
            for (option in choices) {
                if (option.equals(desiredSize)) {
                    // Set the size but don't return yet so that remaining sizes will still be logged.
                    exactSizeFound = true
                }
                if (option.height >= minSize && option.width >= minSize) {
                    bigEnough.add(option)
                } else {
                    tooSmall.add(option)
                }
            }

            Log.i(TAG, "Desired size: $desiredSize, min size: ${minSize}x${minSize}")
            Log.i(TAG, "Valid preview sizes: [${TextUtils.join(", ", bigEnough)}]")
            Log.i(TAG, "Rejected preview sizes: [${TextUtils.join(", ", tooSmall)}]")

            if (exactSizeFound) {
                Log.i(TAG, "Exact size match found.")
                return desiredSize
            }

            // Pick the smallest of those, assuming we found any
            if (bigEnough.isNotEmpty()) {
                val chosenSize = Collections.min(bigEnough, CompareSizesByArea())
                Log.i(TAG, "Chosen size: ${chosenSize.width}x${chosenSize.height}")
                return chosenSize
            } else {
                Log.e(TAG, "Couldn't find any suitable preview size")
                return choices[0]
            }
        }

        class CompareSizesByArea : Comparator<Size> {
            override fun compare(lhs: Size, rhs: Size): Int {
                return (lhs.width * lhs.height - rhs.width * rhs.height).toLong().sign
            }
        }

        fun newInstance(callback: ConnectionCallback,
                        imageListener: ImageReader.OnImageAvailableListener,
                        layout: Int,
                        inputSize: Size
        ): CameraConnectionFragment {
            return CameraConnectionFragment(callback, imageListener, layout, inputSize)
        }

        class ErrorDialog : DialogFragment() {
            companion object {
                private val ARG_MESSAGE = "message"

                fun newInstance(message: String) = ErrorDialog().apply {
                    arguments = Bundle().apply {
                        putString(ARG_MESSAGE, message)
                    }
                }

            }

            override fun onCreateDialog(savedInstanceState: Bundle?): Dialog = AlertDialog.Builder(requireActivity())
                .setMessage(requireArguments().getString(ARG_MESSAGE))
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    activity?.finish()
                }
                .create()
        }
    }

    /** A {@link Semaphore} to prevent the app from exiting before closing the camera. */
    private val cameraOpenCloseLock = Semaphore(1)
    /** A {@link OnImageAvailableListener} to receive frames as they are available. */
    //private val imageListener: ImageReader.OnImageAvailableListener
    /** The input size in pixels desired by TensorFlow (width and height of a square bitmap). */
    //private val inputSize: Size
    /** The layout identifier to inflate for this Fragment. */
    //private val layout: Int

    //private val cameraConnectionCallback: MediaBrowser.ConnectionCallback
    private val captureCallback = object: CameraCaptureSession.CaptureCallback() {
        override fun onCaptureProgressed(session: CameraCaptureSession, request: CaptureRequest, partialResult: CaptureResult) {
            //super.onCaptureProgressed(session, request, partialResult)
        }

        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            //super.onCaptureCompleted(session, request, result)
        }
    }
    /** ID of the current {@link CameraDevice}. */
    private lateinit var cameraId: String
    /** An {@link AutoFitTextureView} for camera preview. */
    private lateinit var textureView: AutoFitTextureView
    /** A {@link CameraCaptureSession } for camera preview. */
    private var captureSession: CameraCaptureSession? = null
    /** A reference to the opened {@link CameraDevice}. */
    private var cameraDevice: CameraDevice? = null
    /** The rotation in degrees of the camera sensor from the display. */
    private var sensorOrientation: Int = 0
    /** The {@link Size} of camera preview. */
    private var previewSize: Size = Size(0, 0)
    /** An additional thread for running tasks that shouldn't block the UI. */
    private var backgroundThread: HandlerThread? = null
    /** A {@link Handler} for running tasks in the background. */
    private var backgroundHandler: Handler? = null
    /**
     * {@link TextureView.SurfaceTextureListener} handles several lifecycle events on a {@link
     * TextureView}.
     */
    private val surfaceTextureListener = object: TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            openCamera(width, height)
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
            configureTransform(width, height)
        }

        override fun onSurfaceTextureDestroyed(p0: SurfaceTexture?): Boolean = true

        override fun onSurfaceTextureUpdated(p0: SurfaceTexture?) { }
    }

    /** An {@link ImageReader} that handles preview frame capture. */
    private var previewReader: ImageReader? = null
    /** {@link CaptureRequest.Builder} for the camera preview */
    private lateinit var previewRequestBuilder: CaptureRequest.Builder
    /** {@link CaptureRequest} generated by {@link #previewRequestBuilder} */
    private lateinit var previewRequest: CaptureRequest
    /** {@link CameraDevice.StateCallback} is called when {@link CameraDevice} changes its state. */
    private val stateCallback = object: CameraDevice.StateCallback() {
        override fun onOpened(cd: CameraDevice) {
            // This method is called when the camera is opened. We start camera preview here.
            cameraOpenCloseLock.release()
            cameraDevice = cd
            createCameraPreviewSession()
        }

        override fun onDisconnected(cd: CameraDevice) {
            cameraOpenCloseLock.release()
            cd.close()
            cameraDevice = null
        }

        override fun onError(cd: CameraDevice, error: Int) {
            cameraOpenCloseLock.release()
            cd.close()
            cameraDevice = null
            activity?.finish()
        }
    }

    private fun showToast(text: String) {
        activity?.runOnUiThread {
            Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(layout, container, false)
    }

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
            openCamera(textureView.width, textureView.height)
        } else {
            textureView.surfaceTextureListener = surfaceTextureListener
        }
    }

    override fun onPause() {
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    fun setCamera(cameraId: String) {
        this.cameraId = cameraId
    }

    /** Sets up member variables related to camera. */
    private fun setUpCameraOutputs() {
        val manager = requireActivity().getSystemService(Context.CAMERA_SERVICE) as CameraManager

        try {
            val characteristics = manager.getCameraCharacteristics(cameraId)
            val map: StreamConfigurationMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: throw RuntimeException("CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP is NULL!")
            sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

            // Danger, W.R.! Attempting to use too large a preview size could  exceed the camera
            // bus' bandwidth limitation, resulting in gorgeous previews but the storage of
            // garbage capture data.
            previewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture::class.java),
                inputSize.width,
                inputSize.height)

            // We fit the aspect ratio of TextureView to the size of preview we picked.
            val orientation = resources.configuration.orientation
            if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                textureView.setAspectRatio(previewSize.width, previewSize.height)
            } else {
                textureView.setAspectRatio(previewSize.height, previewSize.width)
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Exception!", e)
        } catch (e: NullPointerException) {
            // Currently an NPE is thrown when the Camera2API is used but not supported on the
            // device this code runs.
            // TODO(andrewharp): abstract ErrorDialog/RuntimeException handling out into new method and
            // reuse throughout app.
            ErrorDialog.newInstance(getString(R.string.camera_error))
                .show(childFragmentManager, FRAGMENT_DIALOG)
            throw RuntimeException(getString(R.string.camera_error))
        }
        cameraConnectionCallback.onPreviewSizeChosen(previewSize, sensorOrientation)
    }

    /** Opens the camera specified by {@link CameraConnectionFragment#cameraId}. */
    private fun openCamera(width: Int, height: Int) {
        setUpCameraOutputs()
        configureTransform(width, height)
        val manager = requireActivity().getSystemService(Context.CAMERA_SERVICE) as CameraManager

        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Time out waiting to lock camera opening.")
            }
            manager.openCamera(cameraId, stateCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Exception!", e)
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera opening.", e)
        }
    }

    /** Closes the current {@link CameraDevice}. */
    private fun closeCamera() {
        try {
            cameraOpenCloseLock.acquire()
            captureSession?.apply {
                close()
                captureSession = null
            }
            cameraDevice?.apply {
                close()
                cameraDevice = null
            }
            previewReader?.apply {
                close()
                previewReader = null
            }
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera closing.", e)
        } finally {
            cameraOpenCloseLock.release()
        }
    }

    /** Starts a background thread and its {@link Handler}. */
    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("ImageListener").apply {
            start()
            backgroundHandler = Handler(this.looper)
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
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "Exception!", e)
        }
    }

    /** Creates a new {@link CameraCaptureSession} for camera preview. */
    private fun createCameraPreviewSession() {
        try {
            val texture = textureView.surfaceTexture
                ?: throw AssertionError(textureView.surfaceTexture != null)

            val cameraDevice = cameraDevice ?: return

            // We configure the size of default buffer to be the size of camera preview we want.
            texture.setDefaultBufferSize(previewSize.width, previewSize.height)

            // This is the output Surface we need to start preview.
            val surface = Surface(texture)

            previewRequestBuilder =
                cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(surface)
                }
            Log.i(TAG, "Opening camera preview: ${previewSize.width}x${previewSize.height}")

            // Create the reader for the preview frames.
            var previewReaderSurface: Surface
            previewReader = ImageReader.newInstance(
                previewSize.width,
                previewSize.height,
                ImageFormat.YUV_420_888,
                2
            ).apply {
                setOnImageAvailableListener(imageListener, backgroundHandler)
                previewRequestBuilder.addTarget(this.surface)
                previewReaderSurface = this.surface
            }

            // TODO: Here, we create a CameraCaptureSession for camera preview.
            cameraDevice.createCaptureSession(
                listOf(surface, previewReaderSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                        // The camera is already closed
                        val cameraDevice = cameraDevice ?: return

                        // When the session is ready, we start displaying the preview.
                        captureSession = cameraCaptureSession

                        try {
                            // Auto focus should be continuous for camera preview.
                            previewRequestBuilder.set(
                                CaptureRequest.CONTROL_AF_MODE,
                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                            )
                            // Flash is automatically enabled when necessary.
                            previewRequestBuilder.set(
                                CaptureRequest.CONTROL_AE_MODE,
                                CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
                            )

                            // Finally, we start displaying the camera preview.
                            previewRequest = previewRequestBuilder.build()
                            cameraCaptureSession.setRepeatingRequest(
                                previewRequest,
                                captureCallback,
                                backgroundHandler
                            )
                        } catch (e: CameraAccessException) {
                            Log.e(TAG, "Exception!", e)
                        }
                    }

                    override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {
                        showToast("Failed")
                    }
                },
                null
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Exception!", e)
        }
    }

    /**
     * Configures the necessary {@link Matrix} transformation to `mTextureView`. This method should be
     * called after the camera preview size is determined in setUpCameraOutputs and also the size of
     * `mTextureView` is fixed.
     *
     * @param viewWidth The width of `mTextureView`
     * @param viewHeight The height of `mTextureView`
     */
    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        val textureView = textureView ?: return
        val previewSize = previewSize ?: return
        val rotation = activity?.windowManager?.defaultDisplay?.rotation ?: return

        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(0f, 0f, previewSize.height.toFloat(), previewSize.width.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()

        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = max(viewHeight.toFloat() / previewSize.height, viewWidth.toFloat() / previewSize.width)
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate(90 * (rotation - 2).toFloat(), centerX, centerY)
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180f, centerX, centerY)
        }
        textureView.setTransform(matrix)
    }

    /**
     * Callback for Activities to use to initialize their data once the selected preview size is
     * known.
     */
    interface ConnectionCallback {
        fun onPreviewSizeChosen(size: Size, cameraRotation: Int)
    }
}