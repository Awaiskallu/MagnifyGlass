package com.example.magnifyglass

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.util.Log
import android.view.*
import android.webkit.MimeTypeMap
import android.widget.SeekBar
import androidx.camera.core.*
import androidx.camera.core.ImageCapture.Metadata
import androidx.camera.core.R
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.net.toFile
import androidx.fragment.app.Fragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.appdoctor.magnifyingglass.magnifier.databinding.FragmentCameraXBinding
import com.example.magnifyglass.Constants.Companion.isTorchOn
import java.io.File
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.collections.ArrayList
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

typealias LumaListener = (luma: Double) -> Unit

class CameraXFragment : Fragment() {
    val KEY_EVENT_ACTION = "key_event_action"
    val KEY_EVENT_EXTRA = "key_event_extra"

    var cameraControl:CameraControl? = null
    var zoomLevel:Float=0f
    var exposureLevel:Int=0
    var minExposureValue:Int=0
    var maxExposureValue:Int=0

    private lateinit var b: FragmentCameraXBinding

    private lateinit var outputDirectory: File
    private lateinit var broadcastManager: LocalBroadcastManager

    private var displayId: Int = -1
    private var lensFacing: Int = CameraSelector.LENS_FACING_BACK
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var previewFreezed:Boolean = false
    private lateinit var windowManager: WindowManager

    private var displayManager : DisplayManager?=null

    private lateinit var cameraExecutor: ExecutorService


    private var alteredBitmap: Bitmap? = null
    private var photo: Bitmap? = null
    private val volumeDownReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(KEY_EVENT_EXTRA, KeyEvent.KEYCODE_UNKNOWN)) {
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                }
            }
        }
    }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) = view?.let { view ->
            if (displayId == this@CameraXFragment.displayId) {
                Log.d(TAG, "Rotation changed: ${view.display.rotation}")
                imageCapture?.targetRotation = view.display.rotation
                imageAnalyzer?.targetRotation = view.display.rotation
            }
        } ?: Unit
    }

    override fun onDestroyView() {
        cameraExecutor.shutdown()
        broadcastManager.unregisterReceiver(volumeDownReceiver)
        displayManager?.unregisterDisplayListener(displayListener)
        super.onDestroyView()
    }

    private fun captureImage() {
        imageCapture?.let { imageCapture ->

            val photoFile = createFile(
                outputDirectory,
                getString( R.strings.app_name)+" ${System.currentTimeMillis()}"
            )

            photoFile.setLastModified(System.currentTimeMillis())

            val metadata = Metadata().apply {
                isReversedHorizontal = lensFacing == CameraSelector.LENS_FACING_FRONT
            }

            val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile)
                .setMetadata(metadata)
                .build()

            imageCapture.takePicture(outputOptions, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val savedUri = output.savedUri ?: Uri.fromFile(photoFile)

                    requireActivity().let {
                        it.runOnUiThread {
                            Log.e("TAG", "onImageSaved: $savedUri"  )
                            it.showToast("Image saved!")
                            photo = MediaStore.Images.Media.getBitmap(requireActivity().contentResolver, savedUri)

                            val afterBitmap = resizeImage(photo!!)
                            b.layoutLivePreview.visibility = View.GONE
                            b.layoutImageMagnifier.visibility = View.VISIBLE
                            alteredBitmap = Bitmap.createBitmap(
                                afterBitmap.width,
                                afterBitmap.height,
                                afterBitmap.config
                            )

                            b.imageView.setNewImage(alteredBitmap, afterBitmap)

                            b.sbFactorBar.setOnSeekBarChangeListener(mOnFactorChangeListener)
                            b.sbFactorBar.progress = 30
                            b.sbRadiusBar.setOnSeekBarChangeListener(mOnRadiusChangeListener)
                            b.sbRadiusBar.progress = 100                            }
                    }

                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                        requireActivity().sendBroadcast(
                            Intent(android.hardware.Camera.ACTION_NEW_PICTURE, savedUri)
                        )
                    }

                    val mimeType = MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(savedUri.toFile().extension)
                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(savedUri.toFile().absolutePath),
                        arrayOf(mimeType)
                    ) { _, uri ->
                        Log.d(TAG, "Image capture scanned into media store: $uri")
                    }
                }
            })

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                b.root.postDelayed({
                    b.root.foreground = ColorDrawable(Color.WHITE)
                    b.root.postDelayed({ b.root.foreground = null }, ANIMATION_FAST_MILLIS)
                }, ANIMATION_SLOW_MILLIS)
            }
        }
    }

    private fun resizeImage(image: Bitmap): Bitmap {
        val width = image.width
        val height = image.height

        val scaleWidth = width * 0.9
        val scaleHeight = height * 0.9

        if (image.byteCount <= 1000000)
            return image

        return Bitmap.createScaledBitmap(image, scaleWidth.toInt(), scaleHeight.toInt(), false)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        b = FragmentCameraXBinding.inflate(inflater, container, false)
        return b.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        displayManager =  requireContext().getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

        cameraExecutor = Executors.newSingleThreadExecutor()

        broadcastManager = LocalBroadcastManager.getInstance(view.context)

        val filter = IntentFilter().apply { addAction(KEY_EVENT_ACTION) }
        broadcastManager.registerReceiver(volumeDownReceiver, filter)

        displayManager?.registerDisplayListener(displayListener, null)

        windowManager = (requireContext().getSystemService(Context.WINDOW_SERVICE) as WindowManager)

        outputDirectory = requireContext().getOutputDirectory()

        b.viewFinder.post {
            try{
                displayId = b.viewFinder.display.displayId
            }catch (e:Exception){
                e.printStackTrace()
            }

            updateCameraUi()

            setUpCamera()
        }

        changeFlashIcon()
        showNativeAd()

        b.btnBack1.setOnClickListener{
            b.apply {
                layoutImageMagnifier.visibility = View.GONE
                layoutLivePreview.visibility =View.VISIBLE
            }
        }
    }

    private fun showNativeAd() {
        if(requireContext().isAlreadyPurchased()){
            b.llAdContainer.visibility = View.GONE
            return
        }

    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        bindCameraUseCases()

        updateCameraSwitchButton()
    }

    private fun setUpCamera() {
        try{
            val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
            cameraProviderFuture.addListener({

                cameraProvider = cameraProviderFuture.get()

                lensFacing = when {
                    hasBackCamera() -> CameraSelector.LENS_FACING_BACK
                    hasFrontCamera() -> CameraSelector.LENS_FACING_FRONT
                    else -> throw IllegalStateException("Back and front camera are unavailable")
                }

                updateCameraSwitchButton()

                bindCameraUseCases()
            }, ContextCompat.getMainExecutor(requireContext()))
        }catch (e:Exception){
            e.printStackTrace()
        }
    }
    private fun bindCameraUseCases() {

        // Get screen metrics used to setup camera for full screen resolution
        val metrics = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds
        }else{
            val m = DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(m)
            Rect(0,0,m.widthPixels,m.heightPixels)
        }

        Log.d(TAG, "Screen metrics: ${metrics.width()} x ${metrics.height()}")

        val screenAspectRatio = aspectRatio(metrics.width(), metrics.height())
        Log.d(TAG, "Preview aspect ratio: $screenAspectRatio")

        val rotation = b.viewFinder.display.rotation

        val cameraProvider = cameraProvider
            ?: throw IllegalStateException("Camera initialization failed.")

        val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

        // Preview
        preview = Preview.Builder()
            .setTargetAspectRatio(screenAspectRatio)
            .setTargetRotation(rotation)
            .build()

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetAspectRatio(screenAspectRatio)
            .setTargetRotation(rotation)
            .build()

        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(screenAspectRatio)
            .setTargetRotation(rotation)
            .build()
            .also {
                it.setAnalyzer(cameraExecutor, LuminosityAnalyzer { luma ->

                    Log.d(TAG, "Average luminosity: $luma")
                })
            }

        cameraProvider.unbindAll()

        try {
            camera = cameraProvider.bindToLifecycle(
                this, cameraSelector, preview, imageCapture, imageAnalyzer
            )
            cameraControl = camera?.cameraControl
            cameraPreviewStarted()

            // Attach the viewfinder's surface provider to preview use case
            preview?.setSurfaceProvider(b.viewFinder.surfaceProvider)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun cameraPreviewStarted() {
        previewFreezed = false
        b.freezePreviewBtn.setImageResource(R.drawable.pause)
        cameraControl?.setLinearZoom(zoomLevel)
        cameraControl?.enableTorch(isTorchOn)
        initExposureValues()
    }

    private fun initExposureValues() {
        val state = camera?.cameraInfo?.exposureState
        val range = state!!.exposureCompensationRange
        val exposureRange = range.toString()
            .replace("[","")
            .replace("]","")
            .replace(" ","")
            .split(",")
        minExposureValue = exposureRange[0].toInt()
        maxExposureValue = exposureRange[1].toInt() - exposureRange[0].toInt()
        b.brightnessSeekBar.max = maxExposureValue
        b.brightnessSeekBar.progress = maxExposureValue/2
    }

    private fun updateCameraExposure() {
        cameraControl?.setExposureCompensationIndex(exposureLevel)
    }

    private fun aspectRatio(width: Int, height: Int): Int {
        val previewRatio = max(width, height).toDouble() / min(width, height)
        if (abs(previewRatio - RATIO_4_3_VALUE) <= abs(previewRatio - RATIO_16_9_VALUE)) {
            return AspectRatio.RATIO_4_3
        }
        return AspectRatio.RATIO_16_9
    }

    private fun updateCameraUi() {
        // Listener for button used to capture photo
        b.takePhotoBtn.setOnClickListener {
            stopCrashing()
            captureImage()
        }

        // Setup for button used to switch cameras
        b.changeCameraBtn.let {

            // Disable the button until the camera is set up
            it.isEnabled = false

            // Listener for button used to switch cameras. Only called if the button is enabled
            it.setOnClickListener {
                stopCrashing()

                lensFacing = if (CameraSelector.LENS_FACING_FRONT == lensFacing) {
                    CameraSelector.LENS_FACING_BACK
                } else {
                    CameraSelector.LENS_FACING_FRONT
                }

                // Re-bind use cases to update selected camera
                bindCameraUseCases()
            }
        }

        b.zoomSeekBar.setOnSeekBarChangeListener(object :SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val xValue = progress/10
                b.seekBarValueText.text = xValue.toString()+"x"
                zoomLevel = progress/100.toFloat()
                cameraControl?.setLinearZoom(zoomLevel)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
            }
        })

        b.brightnessSeekBar.setOnSeekBarChangeListener(object :SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                exposureLevel = minExposureValue + progress
                b.brightnessValueText.text = exposureLevel.toString()
                b.brightnessLabel.text = exposureLevel.toString()
                updateCameraExposure()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
            }
        })

        b.backButton.setOnClickListener {
            requireActivity().onBackPressed()
        }

        b.freezePreviewBtn.setOnClickListener {
            stopCrashing()
            if(previewFreezed){
                b.freezePreviewBtn.setImageResource(R.drawable.pause)
                bindCameraUseCases()
                b.apply {
                    takePhotoBtn.isEnabled = true
                    changeCameraBtn.isEnabled = true
                    flashLight.isEnabled = true

                }
            }else{
                previewFreezed = true
                b.freezePreviewBtn.setImageResource(R.drawable.play)
                cameraProvider?.unbind(preview)
                b.apply {
                    takePhotoBtn.isEnabled = false
                    changeCameraBtn.isEnabled = false
                    flashLight.isEnabled = false

                }
            }
        }

        b.flashLight.setOnClickListener {
            isTorchOn = !isTorchOn
            changeFlashIcon()
            cameraControl?.enableTorch(isTorchOn)
        }
    }

    private fun changeFlashIcon() {
        if(isTorchOn){
            b.flashLight.setImageResource(R.drawable.flash_on)
        }else{
            b.flashLight.setImageResource(R.drawable.flash_off)
        }
    }

    private fun stopCrashing() {
        Looper.getMainLooper()?.let {
            b.freezePreviewBtn.isClickable = false
            b.takePhotoBtn.isClickable = false
            b.changeCameraBtn.isClickable = false
            b.flashLight.isClickable = false

            Handler(it).postDelayed({
                b.freezePreviewBtn.isClickable = true
                b.takePhotoBtn.isClickable = true
                b.changeCameraBtn.isClickable = true
                b.flashLight.isClickable = true
            }, 1000)
        }
    }

    private fun updateCameraSwitchButton() {
        try {
            b.changeCameraBtn.isEnabled = hasBackCamera() && hasFrontCamera()
        } catch (exception: CameraInfoUnavailableException) {
            b.changeCameraBtn.isEnabled = false
        }
    }


    private fun hasBackCamera(): Boolean {
        return cameraProvider?.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) ?: false
    }


    private fun hasFrontCamera(): Boolean {
        return cameraProvider?.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) ?: false
    }


    private class LuminosityAnalyzer(listener: LumaListener? = null) : ImageAnalysis.Analyzer {
        private val frameRateWindow = 8
        private val frameTimestamps = ArrayDeque<Long>(5)
        private val listeners = ArrayList<LumaListener>().apply { listener?.let { add(it) } }
        private var lastAnalyzedTimestamp = 0L
        var framesPerSecond: Double = -1.0
            private set


        fun onFrameAnalyzed(listener: LumaListener) = listeners.add(listener)

        private fun ByteBuffer.toByteArray(): ByteArray {
            rewind()    // Rewind the buffer to zero
            val data = ByteArray(remaining())
            get(data)   // Copy the buffer into a byte array
            return data // Return the byte array
        }

        override fun analyze(image: ImageProxy) {
            // If there are no listeners attached, we don't need to perform analysis
            if (listeners.isEmpty()) {
                image.close()
                return
            }

            // Keep track of frames analyzed
            val currentTime = System.currentTimeMillis()
            frameTimestamps.push(currentTime)

            // Compute the FPS using a moving average
            while (frameTimestamps.size >= frameRateWindow) frameTimestamps.removeLast()
            val timestampFirst = frameTimestamps.peekFirst() ?: currentTime
            val timestampLast = frameTimestamps.peekLast() ?: currentTime
            framesPerSecond = 1.0 / ((timestampFirst - timestampLast) /
                    frameTimestamps.size.coerceAtLeast(1).toDouble()) * 1000.0

            // Analysis could take an arbitrarily long amount of time
            // Since we are running in a different thread, it won't stall other use cases

            lastAnalyzedTimestamp = frameTimestamps.first

            // Since format in ImageAnalysis is YUV, image.planes[0] contains the luminance plane
            val buffer = image.planes[0].buffer

            // Extract image data from callback object
            val data = buffer.toByteArray()

            // Convert the data into an array of pixel values ranging 0-255
            val pixels = data.map { it.toInt() and 0xFF }

            // Compute average luminance for the image
            val luma = pixels.average()

            // Call all listeners with new value
            listeners.forEach { it(luma) }

            image.close()
        }
    }

    companion object {
        private const val TAG = "CameraXBasic"
        private const val PHOTO_EXTENSION = ".jpg"
        private const val RATIO_4_3_VALUE = 4.0 / 3.0
        private const val RATIO_16_9_VALUE = 16.0 / 9.0

        fun createFile(baseFolder: File, name: String) =
            File(
                baseFolder,
                name + PHOTO_EXTENSION
            )
    }

    override fun onResume() {
        super.onResume()
        if(previewFreezed){
            b.freezePreviewBtn.setImageResource(R.drawable.pause)
            b.apply {
                takePhotoBtn.isEnabled = true
                changeCameraBtn.isEnabled = true
                flashLight.isEnabled = true
            }
            bindCameraUseCases()
        }
    }

    private val mOnFactorChangeListener: SeekBar.OnSeekBarChangeListener = object :
        SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
            val factor = (progress/20)+1
            b.imageView.setMFactor(factor)
            val factorText = "${progress/20}x"
            b.magnificientFactorValueText.text = factorText
        }

        override fun onStartTrackingTouch(seekBar: SeekBar) {}
        override fun onStopTrackingTouch(seekBar: SeekBar) {}
    }

    private val mOnRadiusChangeListener: SeekBar.OnSeekBarChangeListener = object :
        SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
            b.imageView.setRadius(progress)
            b.loupeRadiusValueText.text = progress.toString()
        }

        override fun onStartTrackingTouch(seekBar: SeekBar) {}
        override fun onStopTrackingTouch(seekBar: SeekBar) {}
    }

}