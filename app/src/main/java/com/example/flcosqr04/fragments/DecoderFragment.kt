package com.example.flcosqr04.fragments

import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.addCallback
import androidx.fragment.app.Fragment
import androidx.navigation.Navigation
import androidx.navigation.fragment.navArgs
import com.example.flcosqr04.R
import com.google.zxing.*
import com.google.zxing.common.DetectorResult
import com.google.zxing.common.HybridBinarizer
import org.bytedeco.javacv.*
import org.bytedeco.javacv.OpenCVFrameConverter
import org.bytedeco.opencv.opencv_core.*
import org.bytedeco.opencv.global.opencv_core.*
import org.bytedeco.opencv.global.opencv_imgproc.*
import kotlinx.coroutines.*
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.detector.*
import java.util.*
import kotlin.Exception


class DecoderFragment : Fragment() {
    /** AndroidX navigation arguments */
    private val args: DecoderFragmentArgs by navArgs()
    //Variables which represent the layout elements.
    private lateinit var videoproc : TextView
    private lateinit var runtime : TextView
    private lateinit var totalframes : TextView
    private lateinit var processButton: Button
    //Radio button value.`
    private var radio = 0
    //Variables to get the area which contains only the QR
    //private lateinit var rOI : Array<ResultPoint>
    private val crop : IntArray = IntArray(size = 6) /*[w:width,h:height,x:left coordinate,y:top
    coordinate,imgw:width of the original image,imgh:height of the original image]*/

    //Zxing QR reader and hints for its configuration
    private val qrReader = QRCodeReader()
    private val hints = Hashtable<DecodeHintType, Any>()

    //Added by Miguel 31/08
    private var job = Job()
    private val scope = CoroutineScope(job + Dispatchers.Main)

    /*Function to decode sequence by grabbing frame by frame from a video file using the FFmpeg
    * package. Then, using JavaCV's OpenCV package, the frames are image-processed before calling
    * the QR decoder from the Zxing package
    * Input: String : video file path and name
    * Output: None : QR results are printed in the console */
    private suspend fun decode(videoFile : String) = withContext(Dispatchers.Default) {
        //Variables to get frames: FrameGrabber, FFmpegFrameFilter, Frame
        val frameG = FFmpegFrameGrabber(videoFile)
        /*val frameF = FFmpegFrameFilter("format=pix_fmts=bgr24, crop=w=${crop[0]}:h=${crop[1]}:x=${crop[2]}:y=${crop[3]}","",
            crop[4],crop[5],0)
        frameF.pixelFormat = frameG.pixelFormat
        var preFrame : Frame?*/
        var frame : Frame?
        //Variables for the OpenCV Mat
        var frameMat = Mat() // Frame in Mat format.
        var matGray = Mat()// Frame converted to grayscale
        var matNeg = Mat() // Negative grayscale image
        var matEqP = Mat() // Equalised grayscale image
        var matEqN = Mat() // Equalised negative image
        var frameProc : Frame //Frame processed
        //Variable for the region of interest (ROI)
        //Variables for total frame count and check if a QR is detected
        var totalFrames = 0
        var noQR : Boolean
        //Array of ResultPoints for detect the ROI (x,y,w,h)
        val roi = Rect(crop[2],crop[3]-crop[1],crop[0],crop[1])
        /*rOI = arrayOf(ResultPoint(0.0F, 0.0F), ResultPoint(0.0F, 0.0F),
            ResultPoint(0.0F, 0.0F))*/
        //IntArray(size = 4).copyInto(crop)

        //Start of execution time
        val starTime = System.currentTimeMillis()

        try {//Start FrameGrabber
            frameG.start()
            //frameF.start()
        } catch (e : FrameGrabber.Exception) {
            Log.e("javacv", "Failed to start FrameGrabber: $e")
        } /*catch (e : FrameFilter.Exception){
            Log.e("javacv", "Failed to start FrameFilter: $e")
        }*/
        frame = null
        do {//Loop to grab all frames
            try {
                frame = frameG.grabFrame()
                //Clear variables.
                matGray.release()
                frameMat.release()
                if (frame != null){
                    //Log.i("javacv","Frame grabbed")

                    //Conversions
                    //frameMat = converterToMat.convert(frame) //Frame to Mat
                    frameMat = Mat(converterToMat.convert(frame),roi) //Frame to Mat with ROI
                    cvtColor(frameMat,matGray, COLOR_BGR2GRAY) //To Gray
                    //Log.i("mact","Mat size: (${matGray.size().width()},${matGray.size().height()})")

                    /*Main logic:
                    * If no QR is detected, the code will continue to try to detect QRs according to
                    * user selection of the radio button*/
                    //First detection attempt using grayscale image.
                    noQR = decodeQR(matGray)
                    if (!noQR) Log.i("QR Reader","Detected by gray")

                    //Second detection attempt using negative image.
                    if (noQR && radio > 1) {
                        matNeg.release()
                        bitwise_not(matGray,matNeg)
                        noQR = decodeQR(matNeg)
                        if (!noQR) Log.i("QR Reader","Detected by negative")
                    }

                    //Third attempt using grayscale equalised image.
                    if (noQR && radio > 2) {
                        matEqP.release()
                        equalizeHist(matGray,matEqP)
                        noQR = decodeQR(matEqP)
                        if (!noQR) Log.i("QR Reader","Detected by equalised gray")
                    }

                    //Fourth attempt using negative equalised image.
                    if (noQR && radio > 3) {
                        matEqN.release()
                        equalizeHist(matNeg, matEqN)
                        noQR = decodeQR(matEqN)
                        if (!noQR) Log.i("QR Reader","Detected by equalised neg")
                    }

                    //Print message if nothing detected
                    if(noQR) Log.i("QR Reader","No QR detected")

                    totalFrames += 1
                }
            } catch (e : FrameGrabber.Exception){
                Log.e("javacv", "Failed to grab frame: $e")
            } catch (e: FrameFilter.Exception){
                Log.e("javacv", "Failed to grab filtered frame: $e")
            }
        } while (frame != null) //preFrame
        try {//Stop FrameGrabber
            //frameF.stop()
            frameG.stop()
        } catch (e : FrameGrabber.Exception){
            Log.e("javacv", "Failed to stop FrameGrabber: $e")
        }
        //End of execution time.
        val endTime = System.currentTimeMillis()

        scope.launch(Dispatchers.Main){
            //videoproc.text = textFrames.plus(totalFrames.toString())
            totalframes.text = getString(R.string.totalframes).plus(totalFrames.toString())
            runtime.text = getString(R.string.runtime).plus("${endTime-starTime} ms")
            videoproc.visibility = View.INVISIBLE
            processButton.isEnabled = true
        }
    }

    /*Function to decode QR based on a OpenCV Mat
    * Input: OpenCV Mat()
    * Output: Booolean: true if success in detection, false otherwise
    * Note: runs in the Default Thread, not Main. Called from the decode() function*/
    private suspend fun decodeQR(gray: Mat) : Boolean = withContext(Dispatchers.Default) {
        //Conversion from Mat -> Frame -> Bitmap -> IntArray -> BinaryBitmap
        val frame = converterToMat.convert(gray)
        val bitmap = converterAndroid.convert(frame)
        val intData = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(intData,0,bitmap.width,0,0,bitmap.width,bitmap.height)
        val binBitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width,
            bitmap.height,intData)))
        //Store result of QR detection
        val result : Result
        //Initialise as "no QR detected"
        var noQR = false

        try { //Detect QR and print result
            result = qrReader.decode(binBitmap,hints)
            Log.i("QR Reader", result.text)
        } catch (e : NotFoundException){
            noQR = true //If not found, return true.
        }catch (e : Exception){
            Log.e("QR Reader", "Reader error: $e")
            noQR = true //If not found, return true.
        }
        return@withContext noQR
    }

    /*Function to detect the QR and save its coordinates. Uses de Zxing module to detect any QR in
    * The video but does not decode it.
    * Input: String : video file path and name
    * Output: Boolean : true if detected, false if not.
    * Modifies the global variable coordinates[] */
    private suspend fun detect(videoFile: String) : Boolean = withContext(Dispatchers.Default) {
        //Variables to hold Frame and Mat values.
        val frameG : FrameGrabber = FFmpegFrameGrabber(videoFile)  //FrameGrabber for the video
        var frame : Frame? // Frame grabbed.
        var bitmap : Bitmap
        var result : DetectorResult
        var detected = false

        try {//Start FrameGrabber
            frameG.start()
        } catch (e : FrameGrabber.Exception) {
            Log.e("javacv", "Failed to start FrameGrabber: $e")
        }
        frame = null

        do {
            frame = frameG.grabFrame()
            bitmap = converterAndroid.convert(frame)
            val intData = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(intData,0,bitmap.width,0,0,bitmap.width,bitmap.height)
            val binMatrix = HybridBinarizer(RGBLuminanceSource(bitmap.width,
                bitmap.height,intData)).blackMatrix
            val qrDetector = Detector(binMatrix)
            try {
                result = qrDetector.detect(hints)
                getCrop(result.points)
                crop[4] = bitmap.width
                crop[5] = bitmap.height
                detected = true
                Log.i("QR Detector","Result size = ${result.points.size}")
                break
            } catch (e : NotFoundException){
                Log.i("QR Detector","No QR detected")
            }
        } while (frame != null)
        try {//Stop FrameGrabber
            frameG.stop()
        } catch (e : FrameGrabber.Exception){
            Log.e("javacv", "Failed to stop FrameGrabber: $e")
        }
        return@withContext detected
    }

    /*Function to calculate the crop parameters for the Frame
    * Input: ResultPoint[] (the QR has only 3 ResultPoints)
    * Output: None
    * Modifies the global variable crop[]*/
    private suspend fun getCrop(r : Array<ResultPoint>) = withContext(Dispatchers.Default) {
        /*val xmin = minOf(r[0].x,r[1].x,r[2].x)
        val xmax = maxOf(r[0].x,r[1].x,r[2].x)
        val ymin = minOf(r[0].y,r[1].y,r[2].y)
        val ymax = maxOf(r[0].y,r[1].y,r[2].y)*/

        var xMin = r[0].x
        var xMax = r[0].x
        var yMin = r[0].y
        var yMax = r[0].y

        r.forEach {
            Log.i("Crop","(${it.x},${it.y})")
            if (xMin > it.x) xMin = it.x
            if (xMax < it.x) xMax = it.x
            if (yMin > it.y) yMin = it.y
            if (yMax < it.y) yMax = it.y
        }

        val d = maxOf(xMax-xMin,yMax-yMin )

        crop[0] = (1.6 * d).toInt()
        crop[1] = (1.6 * d).toInt()
        crop[2] = (xMin - 0.3 * d).toInt()
        crop[3] = (yMax + 0.3 * d).toInt()

        Log.i("Crop","(${crop[0]},${crop[1]},${crop[2]},${crop[3]})")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val callback = requireActivity().onBackPressedDispatcher.addCallback(this) {
            // Handle the back button event
            Navigation.findNavController(requireActivity(),R.id.fragment_container).navigate(
                DecoderFragmentDirections.actionDecoderToPermissions()
            )
        }
        callback.isEnabled = true
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.video_player, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        videoproc = view.findViewById(R.id.video_analyser) //Processing...
        runtime = view.findViewById(R.id.run_time) //Run time:
        totalframes = view.findViewById(R.id.total_frames) //Total frames:

        //Added by Miguel 01/09 - Added to use ZXing QRCodeReader
        hints[DecodeHintType.CHARACTER_SET] = "utf-8"
        hints[DecodeHintType.TRY_HARDER] = true
        hints[DecodeHintType.POSSIBLE_FORMATS] = BarcodeFormat.QR_CODE

        //Initialise ResultPoint array
        //ROI = listOf(ResultPoint(0.0F, 0.0F), ResultPoint(0.0F, 0.0F),
        //    ResultPoint(0.0F, 0.0F))

        //Button to start processing
        processButton = view.findViewById(R.id.process_button)
        processButton.setOnClickListener(){//Button click listener sets some variables
            processButton.isEnabled = false
            videoproc.visibility = View.VISIBLE
            runtime.text = getString(R.string.runtime)
            totalframes.text = getString(R.string.totalframes)

            //Get the ID of the radio button to select processing
            when(view.findViewById<RadioGroup>(R.id.dsp_selection).checkedRadioButtonId){
                R.id.no_dsp -> radio = 1
                R.id.use_neg -> radio = 2
                R.id.use_eqpos -> radio = 3
                R.id.use_eqneg -> radio = 4
            }
            //Log.i("mact","RadioID = $radio")
            scope.async {
                //var detected = detect(args.videoname)
                if (detect(args.videoname)) {
                    decode(args.videoname)
                } else {
                    Log.i("Main","No QR detected")
                }
            }
        }
    }

    //Added by Miguel 31/08
    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }

    companion object {
        private val converterToMat : OpenCVFrameConverter.ToMat = OpenCVFrameConverter.ToMat()
        private val converterAndroid : AndroidFrameConverter = AndroidFrameConverter()
    }
}