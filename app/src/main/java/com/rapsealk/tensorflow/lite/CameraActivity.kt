package com.rapsealk.tensorflow.lite

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Camera
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.Image
import android.media.ImageReader
import android.os.*
import androidx.appcompat.app.AppCompatActivity
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.*
import androidx.annotation.UiThread
import androidx.fragment.app.Fragment
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.rapsealk.tensorflow.lite.env.ImageUtils
import com.rapsealk.tensorflow.lite.tflite.Classifier
import kotlinx.android.synthetic.main.activity_camera.*

abstract class CameraActivity : AppCompatActivity(), Camera.PreviewCallback, View.OnClickListener,
    ImageReader.OnImageAvailableListener, AdapterView.OnItemSelectedListener {

    companion object {
        private val TAG = CameraActivity::class.java.simpleName

        private const val PERMISSIONS_REQUEST = 0x0001
        private const val PERMISSION_CAMERA = Manifest.permission.CAMERA
    }

    protected var previewWidth: Int = 0
    protected var previewHeight: Int = 0

    private var handler: Handler? = null
    private var handlerThread: HandlerThread? = null

    private var useCamera2API: Boolean = false
    private var isProcessingFrame: Boolean = false

    private var yuvBytes: Array<ByteArray?> = Array(3) { null }
    private var rgbBytes: IntArray? = null
    private var yRowStride: Int = 0

    private var postInferenceCallback: Runnable? = null
    private var imageConverter: Runnable? = null

    // Layout
    private lateinit var bottomSheetLayout: LinearLayout
    private lateinit var gestureLayout: LinearLayout
    private lateinit var sheetBehavior: BottomSheetBehavior<LinearLayout>
    protected lateinit var recognitionTextView: TextView
    protected lateinit var recognition1TextView: TextView
    protected lateinit var recognition2TextView: TextView
    protected lateinit var recognitionValueTextView: TextView
    protected lateinit var recognition1ValueTextView: TextView
    protected lateinit var recognition2ValueTextView: TextView
    protected lateinit var frameValueTextView: TextView
    protected lateinit var cropValueTextView: TextView
    protected lateinit var cameraResolutionTextView: TextView
    protected lateinit var rotationTextView: TextView
    protected lateinit var inferenceTimeTextView: TextView
    protected lateinit var bottomSheetArrowImageView: ImageView
    private lateinit var plusImageView: ImageView
    private lateinit var minusImageView: ImageView
    private lateinit var modelSpinner: Spinner
    private lateinit var deviceSpinner: Spinner
    private lateinit var threadsTextView: TextView

    private var model = Classifier.Model.QUANTIZED
    private var device = Classifier.Device.CPU
    private var numThreads = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, "onCreate $this")
        super.onCreate(null)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_camera)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        if (hasPermission()) {
            setFragment()
        } else {
            requestPermission()
        }

        threadsTextView = findViewById(R.id.threads)
        plusImageView = findViewById(R.id.plus)
        minusImageView = findViewById(R.id.minus)
        modelSpinner = findViewById(R.id.model_spinner)
        deviceSpinner = findViewById(R.id.device_spinner)
        bottomSheetLayout = findViewById(R.id.bottom_sheet_layout)
        gestureLayout = findViewById(R.id.gesture_layout)
        sheetBehavior = BottomSheetBehavior.from(bottomSheetLayout)
        bottomSheetArrowImageView = findViewById(R.id.bottom_sheet_arrow)

        val vto = gestureLayout.viewTreeObserver.apply {
            addOnGlobalLayoutListener(object: ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN)
                    {
                        gestureLayout.viewTreeObserver.removeGlobalOnLayoutListener(this)
                    } else
                    {
                        gestureLayout.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    }

                    //val width = bottomSheetLayout.measuredWidth
                    val height = gestureLayout.measuredHeight
                    sheetBehavior.peekHeight = height
                }
            })
        }
        sheetBehavior.isHideable = false

        sheetBehavior.setBottomSheetCallback(object: BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                when (newState) {
                    BottomSheetBehavior.STATE_EXPANDED -> bottomSheetArrowImageView.setImageResource(R.drawable.icon_chevron_down)
                    BottomSheetBehavior.STATE_COLLAPSED,
                    BottomSheetBehavior.STATE_SETTLING -> bottomSheetArrowImageView.setImageResource(R.drawable.icon_chevron_up)
                    BottomSheetBehavior.STATE_HIDDEN,
                    BottomSheetBehavior.STATE_DRAGGING -> {}
                    else -> {}
                }
            }

            override fun onSlide(bottomSheet: View, slideOffset: Float) { /* TODO */ }
        })

        recognitionTextView = findViewById(R.id.detected_item)
        recognitionValueTextView = findViewById(R.id.detected_item_value)
        recognition1TextView = findViewById(R.id.detected_item1)
        recognition1ValueTextView = findViewById(R.id.detected_item1_value)
        recognition2TextView = findViewById(R.id.detected_item2)
        recognition2ValueTextView = findViewById(R.id.detected_item2_value)

        frameValueTextView = findViewById(R.id.frame_info)
        cropValueTextView = findViewById(R.id.crop_info)
        cameraResolutionTextView = findViewById(R.id.view_info)
        rotationTextView = findViewById(R.id.rotation_info)
        inferenceTimeTextView = findViewById(R.id.inference_info)

        modelSpinner.onItemSelectedListener = this
        deviceSpinner.onItemSelectedListener = this

        plusImageView.setOnClickListener(this)
        minusImageView.setOnClickListener(this)

        model = Classifier.Model.valueOf(modelSpinner.selectedItem.toString().toUpperCase())
        device = Classifier.Device.valueOf(deviceSpinner.selectedItem.toString())
        numThreads = threadsTextView.text.toString().trim().toInt()
    }

    @Synchronized
    override fun onStart() {
        Log.d(TAG, "onStart $this")
        super.onStart()
    }

    @Synchronized
    override fun onResume() {
        Log.d(TAG, "onResume $this")
        super.onResume()

        handlerThread = HandlerThread("inference").apply {
            start()
            handler = Handler(this.looper)
        }
    }

    @Synchronized
    override fun onPause() {
        Log.d(TAG, "onPause $this")

        handlerThread?.quitSafely()
        try {
            handlerThread?.apply {
                join()
                handlerThread = null
            }
            handler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "Exception!", e)
        }

        super.onPause()
    }

    @Synchronized
    override fun onStop() {
        Log.d(TAG, "onStop $this")
        super.onStop()
    }

    @Synchronized
    override fun onDestroy() {
        Log.d(TAG, "onDestroy $this")
        super.onDestroy()
    }

    protected fun getRgbBytes(): IntArray? {
        imageConverter?.run()
        return rgbBytes
    }

    protected fun getLuminanceStride(): Int = yRowStride

    protected fun getLuminance(): ByteArray = yuvBytes[0] ?: throw RuntimeException("Luminance is NULL!")

    /**
     * android.hardware.Camera.PreviewCallback
     */
    override fun onPreviewFrame(bytes: ByteArray, camera: Camera) {
        if (isProcessingFrame) {
            Log.w(TAG, "Dropping frame!")
            return
        }

        var rgb = rgbBytes

        try {
            // Initialize the storage bitmaps once when the resolution is known.
            if (rgb == null) {
                val previewSize = camera.parameters.previewSize
                previewWidth = previewSize.width
                previewHeight = previewSize.height
                rgb = IntArray(previewWidth * previewHeight).apply {
                    rgbBytes = this
                }
                onPreviewSizeChosen(Size(previewSize.width, previewSize.height), 90)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception!", e)
            return
        }

        isProcessingFrame = true
        yuvBytes[0] = bytes
        yRowStride = previewWidth

        imageConverter = Runnable {
            ImageUtils.convertYUV420SPToARGB8888(bytes, previewWidth, previewHeight, rgb)
        }
        postInferenceCallback = Runnable {
            camera.addCallbackBuffer(bytes)
            isProcessingFrame = false
        }
        processImage()
    }

    /**
     * Camera2
     */
    override fun onImageAvailable(reader: ImageReader) {
        if (previewWidth == 0 || previewHeight == 0) {
            return
        }
        val rgb = rgbBytes ?: IntArray(previewWidth * previewHeight).apply {
            rgbBytes = this
        }

        try {
            val image = reader.acquireLatestImage() ?: return
            if (isProcessingFrame) {
                image.close()
                return
            }
            isProcessingFrame = true
            Trace.beginSection("imageAvailable")
            val planes = image.planes
            fillBytes(planes, yuvBytes)
            yRowStride = planes[0].rowStride
            val uvRowStride = planes[1].rowStride
            val uvPixelStride = planes[1].pixelStride

            // TODO: yuvBytes
            imageConverter = Runnable {
                ImageUtils.convertYUV420ToARGB8888(
                    yuvBytes[0]!!,
                    yuvBytes[1]!!,
                    yuvBytes[2]!!,
                    previewWidth,
                    previewHeight,
                    yRowStride,
                    uvRowStride,
                    uvPixelStride,
                    rgb
                )
            }
            postInferenceCallback = Runnable {
                image.close()
                isProcessingFrame = false
            }
            processImage()
        } catch (e: Exception) {
            Log.e(TAG, "Exception!", e)
            Trace.endSection()
            return
        }
        Trace.endSection()
    }

    @Synchronized
    protected fun runInBackground(r: Runnable) {
        handler?.post(r)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == PERMISSIONS_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                setFragment()
            } else {
                requestPermission()
            }
        }
    }

    private fun hasPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return checkSelfPermission(PERMISSION_CAMERA) == PackageManager.PERMISSION_GRANTED
        } else {
            return true
        }
    }

    private fun requestPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (shouldShowRequestPermissionRationale(PERMISSION_CAMERA)) {
                Toast.makeText(this@CameraActivity, "Camera permission is required for this demo", Toast.LENGTH_LONG).show()
            }
            requestPermissions(arrayOf(PERMISSION_CAMERA), PERMISSIONS_REQUEST)
        }
    }

    private fun isHardwareLevelSupported(characteristics: CameraCharacteristics, requiredLevel: Int): Boolean {
        val deviceLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
        if (deviceLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) {
            return requiredLevel == deviceLevel
        }
        // deviceLevel is not LEGACY, can use numerical sort
        return (deviceLevel != null && requiredLevel <= deviceLevel)
    }

    private fun chooseCamera(): String? {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            for (cameraId in manager.cameraIdList) {
                val characteristics = manager.getCameraCharacteristics(cameraId)

                // FIXME: We don't use a front facing camera in this sample.
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    continue
                }

                val map: StreamConfigurationMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue

                // Fallback to camera1 API for internal cameras that don't have full support.
                // This should help with legacy situations where using the camera2 API causes
                // distorted or otherwise broken previews.
                useCamera2API = (facing == CameraCharacteristics.LENS_FACING_EXTERNAL
                        || isHardwareLevelSupported(characteristics, CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL))
                Log.i(TAG, "Camera API lv2?: ${useCamera2API}")
                return cameraId
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Not allowed to access camera", e)
        }
        return null
    }

    protected fun setFragment() {
        val cameraId = chooseCamera()
            ?: throw RuntimeException("Cannot choose suitable camera!")

        val fragment: Fragment
        if (useCamera2API) {
            val cameraFragment = CameraConnectionFragment.newInstance(object: CameraConnectionFragment.ConnectionCallback {
                override fun onPreviewSizeChosen(size: Size, cameraRotation: Int) {
                    previewHeight = size.height
                    previewWidth = size.width
                    this@CameraActivity.onPreviewSizeChosen(size, cameraRotation)
                }
            },
                this,
                getLayoutId(),
                getDesiredPreviewFrameSize())

            cameraFragment.setCamera(cameraId)
            fragment = cameraFragment
        } else {
            fragment = LegacyCameraConnectionFragment(this, getLayoutId(), getDesiredPreviewFrameSize())
        }

        supportFragmentManager
            .beginTransaction()
            .replace(R.id.container, fragment)
            .commit()
    }

    protected fun fillBytes(planes: Array<Image.Plane>, yuvBytes: Array<ByteArray?>) {
        // Because of the variable row stride it's not possible to know in
        // advance the actual necessary dimensions of the yuv planes.
        for (i in planes.indices) {
            val buffer = planes[i].buffer
            if (yuvBytes[i] == null) {
                Log.d(TAG, "Initializing buffer $i at size ${buffer.capacity()}.")
                yuvBytes[i] = ByteArray(buffer.capacity())
            }
            buffer.get(yuvBytes[i])
        }
    }

    protected fun readyForNextImage() {
        postInferenceCallback?.run()
    }

    protected fun getScreenOrientation(): Int {
        return when (windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_270 -> 270
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_90 -> 90
            else -> 0
        }
    }

    @UiThread
    protected fun showResultsInBottomSheet(results: List<Classifier.Companion.Recognition>) {
        if (results != null && results.size >= 3) {
            val recognition = results[0].apply {
                recognitionTextView.text = this.title
                recognitionValueTextView.text = "${String.format("%.2f", 100 * this.confidence)}%"
            }
            val recognition1 = results[1].apply {
                recognition1TextView.text = this.title
                recognition1ValueTextView.text = "${String.format("%.2f", 100 * this.confidence)}%"
            }
            val recognition2 = results[2].apply {
                recognition2TextView.text = this.title
                recognition2ValueTextView.text = "${String.format("%.2f", 100 * this.confidence)}%"
            }
        }
    }

    protected fun showFrameInfo(frameInfo: String) {
        frameValueTextView.text = frameInfo
    }

    protected fun showCropInfo(cropInfo: String) {
        cropValueTextView.text = cropInfo
    }

    protected fun showCameraResolution(cameraInfo: String) {
        cameraResolutionTextView.text = "${previewWidth}x${previewHeight}"
    }

    protected fun showRotationInfo(rotation: String) {
        rotationTextView.text = rotation
    }

    protected fun showInference(inferenceTime: String) {
        inferenceTimeTextView.text = inferenceTime
    }

    protected fun getModel(): Classifier.Model = model

    private fun setModel(model: Classifier.Model) {
        if (this.model != model) {
            Log.d(TAG, "Update model: $model")
            this.model = model
            onInferenceConfigurationChanged()
        }
    }

    protected fun getDevice(): Classifier.Device = device

    private fun setDevice(device: Classifier.Device) {
        if (this.device != device) {
            Log.d(TAG, "Updating device: $device")
            this.device = device
            val threadsEnabled = (device == Classifier.Device.CPU).apply {
                plusImageView.isEnabled = this
                minusImageView.isEnabled = this
                threadsTextView.text = if (this) numThreads.toString() else "N/A"
            }
            onInferenceConfigurationChanged()
        }
    }

    protected fun getNumThreads(): Int = numThreads

    private fun setNumThreads(numThreads: Int) {
        if (this.numThreads != numThreads) {
            Log.d(TAG, "Update numThreads: $numThreads")
            this.numThreads = numThreads
            onInferenceConfigurationChanged()
        }
    }

    protected abstract fun processImage()

    protected abstract fun onPreviewSizeChosen(size: Size, rotation: Int)

    protected abstract fun getLayoutId(): Int

    protected abstract fun getDesiredPreviewFrameSize(): Size

    protected abstract fun onInferenceConfigurationChanged()

    /**
     * View.OnClickListener
     */
    override fun onClick(view: View) {
        when (view.id) {
            R.id.plus -> {
                var numThreads = threadsTextView.text.toString().toInt()
                if (numThreads >= 9) return
                setNumThreads(++numThreads)
                threadsTextView.text = numThreads.toString()
            }
            R.id.minus -> {
                var numThreads = threadsTextView.text.toString().toInt()
                if (numThreads == 1) return
                setNumThreads(--numThreads)
                threadsTextView.text = numThreads.toString()
            }
        }
    }

    /**
     * AdapterView.OnItemSelectedListener
     */
    override fun onItemSelected(parent: AdapterView<*>, view: View, position: Int, id: Long) {
        if (parent == modelSpinner) {
            setModel(Classifier.Model.valueOf(parent.getItemAtPosition(position).toString().toUpperCase()))
        } else if (parent == deviceSpinner) {
            setDevice(Classifier.Device.valueOf(parent.getItemAtPosition(position).toString()))
        }
    }

    override fun onNothingSelected(parent: AdapterView<*>?) {
        // Do nothing.
    }
}