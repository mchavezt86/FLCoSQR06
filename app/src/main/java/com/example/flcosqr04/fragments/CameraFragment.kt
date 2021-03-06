package com.example.flcosqr04.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.MediaCodec
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.opengl.Visibility
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.*
import android.widget.Button
import androidx.constraintlayout.solver.widgets.Rectangle
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.Navigation
import androidx.navigation.fragment.navArgs
import com.example.android.camera.utils.*
import com.example.flcosqr04.R
import com.example.flcosqr04.BuildConfig
import com.example.flcosqr04.MainActivity //must match our main root activity class
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import androidx.lifecycle.Observer
import kotlinx.android.synthetic.main.fragment_camera.*
import com.example.flcosqr04.ImageProcess
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.core.view.drawToBitmap
import kotlinx.coroutines.*
import org.bytedeco.javacpp.annotation.Const
import org.bytedeco.opencv.opencv_core.Rect
import java.lang.Math.abs
import java.lang.Runnable

class CameraFragment : Fragment()  {

    /** AndroidX navigation arguments */
    private val args: CameraFragmentArgs by navArgs()

    /** Host's navigation controller */
    private val navController: NavController by lazy {
        Navigation.findNavController(requireActivity(), R.id.fragment_container)
    }

    /** Detects, characterizes, and connects to a CameraDevice (used for all camera operations) */
    private val cameraManager: CameraManager by lazy {
        val context = requireContext().applicationContext
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    /** [CameraCharacteristics] corresponding to the provided Camera ID */
    private val characteristics: CameraCharacteristics by lazy {
        cameraManager.getCameraCharacteristics(args.cameraId)
    }

    /** File where the recording will be saved */
    private val outputFile: File by lazy { createFile(requireContext(), "mp4") }

    /**
     * Setup a persistent [Surface] for the recorder so we can use it as an output target for the
     * camera session without preparing the recorder
     */
    private val recorderSurface: Surface by lazy {

        // Get a persistent Surface from MediaCodec, don't forget to release when done
        val surface = MediaCodec.createPersistentInputSurface()

        // Prepare and release a dummy MediaRecorder with our new surface
        // Required to allocate an appropriately sized buffer before passing the Surface as the
        //  output target to the high speed capture session
        createRecorder(surface).apply {
            prepare()
            release()
        }

        surface
    }

    /** Saves the video recording */
    private val recorder: MediaRecorder by lazy { createRecorder(recorderSurface) }

    /** [HandlerThread] where all camera operations run */
    private val cameraThread = HandlerThread("CameraThread").apply { start() }

    /** [Handler] corresponding to [cameraThread] */
    private val cameraHandler = Handler(cameraThread.looper)

    /** Performs recording animation of flashing screen */
    private val animationTask: Runnable by lazy {
        Runnable {
            // Flash white animation
            overlay.foreground = Color.argb(150, 255, 255, 255).toDrawable()
            // Wait for ANIMATION_FAST_MILLIS
            overlay.postDelayed({
                // Remove white flash animation
                overlay.foreground = null
                // Restart animation recursively
                overlay.postDelayed(animationTask, MainActivity.ANIMATION_FAST_MILLIS)
            }, MainActivity.ANIMATION_FAST_MILLIS)
        }
    }

    /** Where the camera preview is displayed */
    private lateinit var viewFinder: AutoFitSurfaceView

    /** Overlay on top of the camera preview */
    private lateinit var overlay: View

    /** Captures high speed frames from a [CameraDevice] for our slow motion video recording */
    private lateinit var session: CameraConstrainedHighSpeedCaptureSession

    /** The [CameraDevice] that will be opened in this fragment */
    private lateinit var camera: CameraDevice

    /*Added by Miguel 23-11*/
    /**The [Rectangle] which will show the ROI */
    private lateinit var roiRectView : View

    /** The [ImageReader] that will be opened in this fragment to detect the ROI */
    //private lateinit var imgRdrROI : ImageReader

    /** The [ImageReader] that will be opened in this fragment to detect the ROI */
    //private lateinit var imgRdrQR : ImageReader

    /*Added by Miguel 11-01*/
    /** The [Bitmap] to receive the PixelCopy result*/
    private lateinit var bmpSurf : Bitmap

    /** Requests used for preview only in the [CameraConstrainedHighSpeedCaptureSession] */
    private val previewRequestList: List<CaptureRequest> by lazy {
        // Capture request holds references to target surfaces
        session.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            // Add the preview surface target
            addTarget(viewFinder.holder.surface)
            //Add the ImageReader target - Miguel 23-11
            //addTarget(imgRdrROI.surface)
            // High speed capture session requires a target FPS range, even for preview only
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(FPS_PREVIEW_ONLY, args.fps))
            //Added by Miguel - Zoom 4x
            set(CaptureRequest.SCALER_CROP_REGION,args.zoom)
            //Added by Miguel 02/09 - Lowest AE Exposure Compensation
            set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,args.aeLow)
            //Added by Miguel 10/10 - Lock AE when previewing.
            //set(CaptureRequest.CONTROL_AE_LOCK,true)
            //Added by Miguel 12/01 - AE region and focus region
            set(CaptureRequest.CONTROL_AE_REGIONS,arrayOf(MeteringRectangle(args.zoom,
                MeteringRectangle.METERING_WEIGHT_MAX-1)))
            set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(MeteringRectangle(args.zoom,
                MeteringRectangle.METERING_WEIGHT_MAX-1)))
            //Added by Miguel 19/2
            /*Control Mode to auto + AF to Continuous + AE to Auto*/
            set(CaptureRequest.CONTROL_MODE,CaptureRequest.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_ON)
        }.let {
            // Creates a list of highly optimized capture requests sent to the camera for a high
            // speed video session. Important note: Must use repeating burst request type
            session.createHighSpeedRequestList(it.build())
        }
    }

    //private var recordingStartMillis: Long = 0L

    //Added by Miguel 20/08/2020: Avoids break of port/land when the camera is rotated.
    //private var recorded: Boolean = false
    //Added by Miguel 28/08
    //private lateinit var mainActivity : MainActivity

    //Added by Miguel 20-12-2020
    private var recordingDuration: Long = 0L

    /** Live data listener for changes in the device orientation relative to the camera */
    private lateinit var relativeOrientation: OrientationLiveData

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_camera, container, false)

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        overlay = view.findViewById(R.id.overlay)
        viewFinder = view.findViewById(R.id.view_finder)

        //Added by Miguel 28/08
        //recorded = false

        //Added by Miguel 31-11
        //Initialize the ImgReaders
        /*imgRdrROI = ImageReader.newInstance(args.width, args.height,
            ImageFormat.PRIVATE,2)*/
        /*imgRdrQR = ImageReader.newInstance(args.width, args.height,
            ImageFormat.YUV_420_888,10)*/
        //Rectangle to show the ROI.
        roiRectView = view.findViewById(R.id.roirect)

        //Added by Miguel 20-12-2020
        /*This variable controls the duration of the recording in milliseconds. Change according to
        * this formula:
        * recordingDuration = (N° of frames / video FPS) * 1000 */
        recordingDuration = ((175.0/60.0)*1000).toLong()

        viewFinder.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int) = Unit

            override fun surfaceCreated(holder: SurfaceHolder) {

                // Selects appropriate preview size and configures view finder
                val previewSize = getConstrainedPreviewOutputSize(
                    viewFinder.display, characteristics, SurfaceHolder::class.java)
                Log.d(TAG, "View finder size: ${viewFinder.width} x ${viewFinder.height}")
                Log.d(TAG, "Selected preview size: $previewSize")
                viewFinder.setAspectRatio(previewSize.width, previewSize.height)

                // To ensure that size is set, initialize camera in the view's thread
                viewFinder.post { initializeCamera() }
                //Added by Miguel 24-11
                /*Bitmap of the same size as the camera resolution (thus the resulting OpenCV Mat
                * to process and find the ROI from the preview. */
                bmpSurf = createBitmap(previewSize.height,previewSize.width)
            }
        })

        // Used to rotate the output media to match device orientation
        relativeOrientation = OrientationLiveData(requireContext(), characteristics).apply {
            observe(viewLifecycleOwner, Observer {
                    orientation -> Log.d(TAG, "Orientation changed: $orientation")
            })
        }
    }

    /** Creates a [MediaRecorder] instance using the provided [Surface] as input */
    private fun createRecorder(surface: Surface) = MediaRecorder().apply {
    //Modified by Miguel 20/08/2020. No MIC.
        //setAudioSource(MediaRecorder.AudioSource.MIC)
        setVideoSource(MediaRecorder.VideoSource.SURFACE)
        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        setOutputFile(outputFile.absolutePath)
        setVideoEncodingBitRate(RECORDER_VIDEO_BITRATE)
        setVideoFrameRate(args.fps)
        setVideoSize(args.width, args.height)
        setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        //Modified by Miguel 20/08/2020. No audio.
        //setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        setInputSurface(surface)
    }

    /**
     * Preview size is subject to the same rules compared to a normal capture session with the
     * additional constraint that the selected size must also be available as one of possible
     * constrained high-speed session sizes.
     */
    private fun <T>getConstrainedPreviewOutputSize(
        display: Display,
        characteristics: CameraCharacteristics,
        targetClass: Class<T>,
        format: Int? = null
    ): Size {

        // Find which is smaller: screen or 1080p
        val screenSize = getDisplaySmartSize(display)
        val hdScreen = screenSize.long >= SIZE_1080P.long || screenSize.short >= SIZE_1080P.short
        val maxSize = if (hdScreen) SIZE_1080P else screenSize

        // If image format is provided, use it to determine supported sizes; else use target class
        val config = characteristics.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        if (format == null) {
            //assert(StreamConfigurationMap.isOutputSupportedFor(targetClass))
            if (BuildConfig.DEBUG && !StreamConfigurationMap.isOutputSupportedFor(targetClass)) {
                error("Assertion failed")
            }
        } else {
            //assert(config.isOutputSupportedFor(format))
            if (BuildConfig.DEBUG && !config.isOutputSupportedFor(format)) {
                error("Assertion failed")
            }
        }

        val allSizes = if (format == null)
            config.getOutputSizes(targetClass) else config.getOutputSizes(format)

        // Get a list of potential high speed video sizes for the selected FPS
        val highSpeedSizes = config.getHighSpeedVideoSizesFor(Range(args.fps, args.fps))

        // Filter sizes which are part of the high speed constrained session
        val validSizes = allSizes
            .filter { highSpeedSizes.contains(it) }
            .sortedWith(compareBy { it.height * it.width })
            .map { SmartSize(it.width, it.height) }.reversed()

        // Then, get the largest output size that is smaller or equal than our max size
        return validSizes.first { it.long <= maxSize.long && it.short <= maxSize.short }.size
    }

    /**
     * Begin all camera operations in a coroutine in the main thread. This function:
     * - Opens the camera
     * - Configures the camera session
     * - Starts the preview by dispatching a repeating burst request
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun initializeCamera() = lifecycleScope.launch(Dispatchers.Main) {

        //Added by Miguel
        //This variable will control the recording button actions: start/stop the recording.
        var record : Boolean = false
        //24-11
        var rectROI : Rect? = null
        //
        val previewSize = Size(bmpSurf.width,bmpSurf.height)
        //added 18-12

        //Miguel 23-11
        //Set the listener for the ImageReader to detect the ROI.
        //imgRdrROI.setOnImageAvailableListener(roiOnImageAvailableListener, cameraHandler)

        // Open the selected camera
        camera = openCamera(cameraManager, args.cameraId, cameraHandler)

        // Creates list of Surfaces where the camera will output frames
        // Add the ImageReader surface - Miguel 23-11
        val targets = listOf(viewFinder.holder.surface, recorderSurface)

        // Start a capture session using our open camera and list of Surfaces where frames will go
        session = createCaptureSession(camera, targets, cameraHandler)

        // Ensures the requested size and FPS are compatible with this camera
        val fpsRange = Range(args.fps, args.fps)
        //assert(true == characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        //                ?.getHighSpeedVideoFpsRangesFor(Size(args.width, args.height))?.contains(fpsRange))
        if (BuildConfig.DEBUG && true != characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getHighSpeedVideoFpsRangesFor(Size(args.width, args.height))?.contains(fpsRange)) {
            error("Assertion failed")
        }

        // Sends the capture request as frequently as possible until the session is torn down or
        // session.stopRepeating() is called
        session.setRepeatingBurst(previewRequestList, null, cameraHandler)

        //Added by Miguel
        /*Code to find the ROI in the preview:
        * NOTE: The ImageReader (and its listener) is more suitable for this but for Constrained
        * High Speed Capture Session does not allow an ImageReader surface, only Preview and
        * Recorder Surfaces */

        val handlerThread = HandlerThread("PixelCopier")
        handlerThread.start()

        delay(1000) /*Wait for camera to open and start*/
        do {
            /*Get the Surface into a Bitmap. Cannot use View.drawToBitmap because it saves the View
            * but not the camera image displayed*/
            ImageProcess.getBitMapFromSurfaceView(viewFinder,bmpSurf){
                bmpSurf = it
            }
            rectROI = ImageProcess.detectROI(bmpSurf)
            delay(100) /*Period between each try to find the ROI. If delay is too short
             the program might run out of memory as this process does not wait for a new image
             available, it just reads the current pixels from the surface. */
        } while (rectROI == null)
        handlerThread.quit()
        Log.i("ROI","x:${rectROI.x()}, y:${rectROI.y()}, w:${rectROI.width()}," +
                "h:${rectROI.height()}")
        /*Set the ROI View parameters to be displayed in the camera surface*/
        roiRectView.x = (rectROI.x() * viewFinder.width / previewSize.width).toFloat()
        roiRectView.y = (rectROI.y() * viewFinder.height / previewSize.height).toFloat()
        roiRectView.layoutParams = ConstraintLayout.LayoutParams(
            rectROI.width() * viewFinder.width / previewSize.width,
            rectROI.height() * viewFinder.height / previewSize.height)
        roiRectView.visibility = View.VISIBLE

        /*Change the AE and AF regions to match the ROI area*/
        val regionAEAF = ImageProcess.scaleRect(args.zoom,bmpSurf,rectROI)
        val roiFrame = ImageProcess.roiScale(rectROI,args.width,args.height,previewSize)

        /** Requests used for preview only in the [CameraConstrainedHighSpeedCaptureSession] */
        /*Added to create a new preview with AE and AF region set to the display area*/
        val newPreviewRequestList: List<CaptureRequest> by lazy {
            // Capture request holds references to target surfaces
            session.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                // Add the preview surface target
                addTarget(viewFinder.holder.surface)
                // High speed capture session requires a target FPS range, even for preview only
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(FPS_PREVIEW_ONLY, args.fps))
                //Added by Miguel - Zoom 4x
                set(CaptureRequest.SCALER_CROP_REGION,args.zoom)
                //Added by Miguel 02/09 - Lowest AE Exposure Compensation
                set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, args.aeLow)
                //Added by Miguel 10/10 - Lock AE when previewing.
                set(CaptureRequest.CONTROL_AE_LOCK,true)
                //Added by Miguel 12/01 - AE region and focus region
                set(CaptureRequest.CONTROL_AE_REGIONS,arrayOf(MeteringRectangle(regionAEAF,
                    MeteringRectangle.METERING_WEIGHT_MAX-1)))
                set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(MeteringRectangle(regionAEAF,
                    MeteringRectangle.METERING_WEIGHT_MAX-1)))
                //Added by Miguel 19/2
                /*Control Mode to auto + AF to Continuous + AE to Auto*/
                set(CaptureRequest.CONTROL_MODE,CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_ON)
            }.let {
                // Creates a list of highly optimized capture requests sent to the camera for a high
                // speed video session. Important note: Must use repeating burst request type
                session.createHighSpeedRequestList(it.build())
            }
        }

        /*Stop preview session*/
        session.stopRepeating()

        /*Set the capture_button visible*/
        capture_button.visibility = Button.VISIBLE

        /*Change the preview to one focused on the display*/
        session.setRepeatingBurst(newPreviewRequestList, null, cameraHandler)

        /** Requests used for preview and recording in the [CameraConstrainedHighSpeedCaptureSession] */
        val recordRequestList: List<CaptureRequest> by lazy {
            // Capture request holds references to target surfaces
            session.device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                // Add the preview and recording surface targets
                addTarget(viewFinder.holder.surface)
                addTarget(recorderSurface)
                // Sets user requested FPS for all targets
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(args.fps, args.fps))
                //Added by Miguel - Zoom 4x
                set(CaptureRequest.SCALER_CROP_REGION,args.zoom)
                //Added by Miguel 02/09 - Lowest AE Exposure Compensation
                set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,args.aeLow)
                //Added by Miguel 10/10 - Lock AE when recording.
                set(CaptureRequest.CONTROL_AE_LOCK,true)
                //Added by Miguel 12/01 - AE region and focus region
                set(CaptureRequest.CONTROL_AE_REGIONS,arrayOf(MeteringRectangle(regionAEAF,
                    MeteringRectangle.METERING_WEIGHT_MAX-1)))
                set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(MeteringRectangle(regionAEAF,
                    MeteringRectangle.METERING_WEIGHT_MAX-1)))
                //Added by Miguel 19/2
                /*Control Mode to auto + AF to Continuous + AE to Auto*/
                set(CaptureRequest.CONTROL_MODE,CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_ON)
            }.let {
                // Creates a list of highly optimized capture requests sent to the camera for a high
                // speed video session. Important note: Must use repeating burst request type
                session.createHighSpeedRequestList(it.build())
            }
        }

        // Listen to the capture button
        capture_button.setOnTouchListener { view, event ->
            when (event.action) {

                MotionEvent.ACTION_DOWN -> lifecycleScope.launch(Dispatchers.IO) {

                    // Prevents screen rotation during the video recording
                    requireActivity().requestedOrientation =
                        ActivityInfo.SCREEN_ORIENTATION_LOCKED

                    // Stops preview requests, and start record requests
                    session.stopRepeating()
                    session.setRepeatingBurst(recordRequestList, null, cameraHandler)

                    // Finalizes recorder setup and starts recording
                    recorder.apply {
                        // Sets output orientation based on current sensor value at start time
                        relativeOrientation.value?.let { setOrientationHint(it) }
                        prepare()
                        start()
                    }
                    Log.d(TAG, "Recording started")


                    // Starts recording animation
                    overlay.post(animationTask)

                    //Record for a fixed time
                    delay(recordingDuration)

                    requireActivity().requestedOrientation =
                        ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

                    Log.d(TAG, "Recording stopped. Output file: $outputFile")
                    recorder.stop()

                    // Removes recording animation
                    overlay.removeCallbacks(animationTask)

                    Handler(Looper.getMainLooper()).post {
                        /**Maybe disable button?*/
                        Navigation.findNavController(requireActivity(), R.id.fragment_container)
                            .navigate(
                                /*CameraFragmentDirections.actionCameraToDecoder(
                                    "$outputFile", rectROI.y(), previewSize.width -
                                            rectROI.x() - rectROI.width(), rectROI.height(),
                                    rectROI.width()
                                )*/
                                CameraFragmentDirections.actionCameraToDecoder(
                                        "$outputFile", roiFrame.x(), roiFrame.y(),
                                        roiFrame.width(), roiFrame.height()
                            )
                            )
                        /*The actual values of the ROI rectangle needed for OpenCV Mat requires
                        * width and height to be swap, Mat_x coordinate is y  and Mat_x is
                        * measured from the other end so its 720 - x - w */
                    }
                }
            }
            true
        }
    }

    /** Opens the camera and returns the opened device (as the result of the suspend coroutine) */
    @SuppressLint("MissingPermission")
    private suspend fun openCamera(
        manager: CameraManager,
        cameraId: String,
        handler: Handler? = null
    ): CameraDevice = suspendCancellableCoroutine { cont ->
        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) = cont.resume(device)

            override fun onDisconnected(device: CameraDevice) {
                Log.w(TAG, "Camera $cameraId has been disconnected")
                //navController.popBackStack() //Added by Miguel 28/08
                //requireActivity().finish()
            }

            override fun onError(device: CameraDevice, error: Int) {
                val msg = when(error) {
                    ERROR_CAMERA_DEVICE -> "Fatal (device)"
                    ERROR_CAMERA_DISABLED -> "Device policy"
                    ERROR_CAMERA_IN_USE -> "Camera in use"
                    ERROR_CAMERA_SERVICE -> "Fatal (service)"
                    ERROR_MAX_CAMERAS_IN_USE -> "Maximum cameras in use"
                    else -> "Unknown"
                }
                val exc = RuntimeException("Camera $cameraId error: ($error) $msg")
                Log.e(TAG, exc.message, exc)
                if (cont.isActive) cont.resumeWithException(exc)
            }
        }, handler)
    }

    /**
     * Creates a [CameraCaptureSession] and returns the configured session (as the result of the
     * suspend coroutine)
     */
    private suspend fun createCaptureSession(
        device: CameraDevice,
        targets: List<Surface>,
        handler: Handler? = null
    ): CameraConstrainedHighSpeedCaptureSession = suspendCoroutine { cont ->

        // Creates a capture session using the predefined targets, and defines a session state
        // callback which resumes the coroutine once the session is configured
        device.createConstrainedHighSpeedCaptureSession(
            targets, object: CameraCaptureSession.StateCallback() {

                override fun onConfigured(session: CameraCaptureSession) =
                    cont.resume(session as CameraConstrainedHighSpeedCaptureSession)

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    val exc = RuntimeException("Camera ${device.id} session configuration failed")
                    Log.e(TAG, exc.message, exc)
                    cont.resumeWithException(exc)
                }
            }, handler)
    }

    override fun onStop() {
        super.onStop()
        try {
            camera.close()
        } catch (exc: Throwable) {
            Log.e(TAG, "Error closing camera", exc)
        }
        //Added by Miguel 27/08
        //Log.i("mact","CameraOnStop")
        /*if (recorded) {
            Navigation.findNavController(requireActivity(), R.id.fragment_container)
                .navigate(SelectorFragmentDirections.actionSelectorToDecoder("$outputFile"))
        }*/
        //imgRdrROI.close() //Miguel 23-11

    }

    override fun onDestroy() {
        super.onDestroy()
        cameraThread.quitSafely()
        recorder.release()
        recorderSurface.release()
        /*imgRdrROI.surface.release() //Miguel 23-11
        imgRdrROI.close()*/
    }

    //Miguel 23-11
    /*private var roiOnImageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        val img = reader.acquireLatestImage()
        if (img != null){
            val roiRect = ImageProcess.detect(img)
            if (roiRect != null){
                img.close()
                roiRectView.x = roiRect.x().toFloat()
                roiRectView.y = roiRect.y().toFloat()
                val rectLayout = roiRectView.layoutParams
                rectLayout.width = roiRect.width()
                rectLayout.height = roiRect.height()
                roiRectView.layoutParams = rectLayout
            }
        }
    }*/

    companion object {
        private val TAG = CameraFragment::class.java.simpleName

        private const val RECORDER_VIDEO_BITRATE: Int = 10000000
        private const val MIN_REQUIRED_RECORDING_TIME_MILLIS: Long = 1000L

        /**
         * FPS rate for preview-only requests, 30 is *guaranteed* by framework. See:
         * [StreamConfigurationMap.getHighSpeedVideoFpsRanges]
         */
        private const val FPS_PREVIEW_ONLY: Int = 30

        /** Creates a [File] named with the current date and time */
        private fun createFile(context: Context, extension: String): File {
            val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US)
            return File(context.filesDir, "VID_${sdf.format(Date())}.$extension")
        }
    }

}