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
    private lateinit var totalQRs : TextView
    private lateinit var processButton: Button
    //Radio button value.`
    //private var radio = 0
    private val crop : IntArray = IntArray(size = 6) /*[w:width,h:height,x:left coordinate,y:top
    coordinate,imgw:width of the original image,imgh:height of the original image]*/

    //Zxing QR reader and hints for its configuration, unique for each thread.
    private val qrReader1 = QRCodeReader()
    private val qrReader2 = QRCodeReader()
    private val hints = Hashtable<DecodeHintType, Any>()

    //Added by Miguel 31/08: Thread handling
    private var job = Job()
    private val scope = CoroutineScope(job + Dispatchers.Main)

    //Added by Miguel 10/09: Variables for unique QR counting
    private lateinit var qrString : String
    private var qrCount : Int = 0

    //Added by Miguel 15/09: Unique converters for each thread
    private val converterToMat1 : OpenCVFrameConverter.ToMat = OpenCVFrameConverter.ToMat()
    private val converterAndroid1 : AndroidFrameConverter = AndroidFrameConverter()
    private val converterToMat2 : OpenCVFrameConverter.ToMat = OpenCVFrameConverter.ToMat()
    private val converterAndroid2 : AndroidFrameConverter = AndroidFrameConverter()

    /*Function to decode sequence by grabbing frame by frame from a video file using the FFmpeg
    * package. Then, using JavaCV's OpenCV package, the frames are image-processed before calling
    * the QR decoder from the Zxing package
    * Input: String : video file path and name
    * Output: None : QR results are printed in the console */
    private suspend fun decode(videoFile : String, radio : Int) = withContext(Dispatchers.Default) {
        //Variables to get frames: FrameGrabber, FFmpegFrameFilter, Frame
        val frameG = FFmpegFrameGrabber(videoFile)
        /*val frameF = FFmpegFrameFilter("format=pix_fmts=bgr24, crop=w=${crop[0]}:h=${crop[1]}:x=${crop[2]}:y=${crop[3]}","",
            crop[4],crop[5],0)
        frameF.pixelFormat = frameG.pixelFormat*/
        var frame : Frame?
        //Variables for the OpenCV Mat
        var frameMat = Mat() // Frame in Mat format.
        val matGray = Mat()// Frame converted to grayscale
        val matNeg = Mat() // Negative grayscale image
        val matEqP = Mat() // Equalised grayscale image
        val matEqN = Mat() // Equalised negative image
        //Variable for the region of interest (ROI)
        //Variables for total frame count, check if a QR is detected and if the FLC is detected
        var totalFrames = 0
        var noQR : Boolean
        //New variables for multithreading
        //var noGray : Boolean
        //var noNeg : Boolean
        var flc = false

        //Start of execution time
        val starTime = System.currentTimeMillis()

        try {//Start FrameGrabber
            frameG.start()
        } catch (e : FrameGrabber.Exception) {
            Log.e("javacv", "Failed to start FrameGrabber: $e")
        }
        frame = null

        /*Find the FLC active area.
        * Main Logic:
        * The FLC active area stands out of the surface as a rectangle, despite of what the FLC is
        * displaying. During the first frames the code tries to find the best fit 'rectangle' which
        * surrounds the FLC. This will be used as the ROI. The code will loop in its attempt to find
        * a the FLC.
        * *********SUBJECT TO EVALUATION!!!!!!*********
        * The potential problem is that if no rectangle is found, the process will not start*/

        do { // Loop to grab frames.
            try {
                frame = frameG.grabFrame()
                if (frame != null){
                    // When the rectangle is detected break this loop.
                    flc = detect(frame)
                    if (flc) break
                }
            } catch (e : FrameGrabber.Exception){
                Log.e("javacv", "Failed to grab frame: $e")
            }
        } while (frame != null)

        //If the FLC is detected, continue to grab frames and decode.
        if (flc){
            //Array of ResultPoints for detect the ROI (x,y,w,h)
            val roi = Rect(crop[2],crop[3],crop[0],crop[1])
            Log.i("Decode","${roi.x()},${roi.y()},${roi.width()},${roi.height()}")
            scope.launch(Dispatchers.Main){
                videoproc.text = getString(R.string.processing)
            }

            do {//Loop to grab all frames
                try {
                    frame = frameG.grabFrame()
                    //Clear variables.
                    matGray.release()
                    frameMat.release()
                    if (frame != null){
                        //Conversions
                        frameMat = Mat(converterToMat1.convert(frame),roi) /*Frame to Mat with ROI
                        uses the firs converterToMat object*/
                        cvtColor(frameMat,matGray, COLOR_BGR2GRAY) //To Gray

                        /*
                        /*Main logic: COMMENTED TO IMPLEMENT THREAD LOGIC
                        * If no QR is detected, the code will continue to try to detect QRs
                        * according to user selection of the radio button*/

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
                        */

                        //NEW LOGIC TO BE IMPLEMENTED.
                        when (radio) {
                            1 -> {
                                noQR = decodeQR(matGray,qrReader1,converterToMat1,converterAndroid1)
                                if (!noQR) {Log.i("QR Reader","Detected by gray")}
                                else {Log.i("QR Reader","No QR detected")}
                            }
                            2 -> {
                                noQR = decodeQR(matGray,qrReader1,converterToMat1,converterAndroid1)
                                if (!noQR) {Log.i("QR Reader","Detected by gray")}
                                else {
                                    matNeg.release()
                                    bitwise_not(matGray,matNeg)
                                    noQR = decodeQR(matNeg,qrReader1,converterToMat1,converterAndroid1)
                                    if (!noQR) {Log.i("QR Reader","Detected by neg")}
                                    else {Log.i("QR Reader","No QR detected")}
                                }
                            }
                            3 -> {
                                matNeg.release()
                                bitwise_not(matGray,matNeg)
                                //val temp1 = async { decodeQR(matGray, qrReader1) }
                                val temp1 = (CoroutineScope(Dispatchers.Default + Job())).async {
                                    decodeQR(matGray, qrReader1,converterToMat1,converterAndroid1)
                                }
                                val temp2 = (CoroutineScope(Dispatchers.Default + Job())).async {
                                    decodeQR(matNeg, qrReader2,converterToMat2,converterAndroid2)
                                }
                                noQR = (temp1.await() ||  temp2.await())
                                if (!noQR) {
                                    Log.i("QR Reader","Detected by gray or neg")
                                } else {Log.i("QR Reader","No QR detected")}
                            }
                        }
                        totalFrames += 1
                    }
                } catch (e : FrameGrabber.Exception){
                    Log.e("javacv", "Failed to grab frame: $e")
                }
            } while (frame != null)
        } else {
            //if the display is not detected, display a message in the UI.
            Log.i("FLC","Display not detected")
            scope.launch(Dispatchers.Main){
                videoproc.text = getString(R.string.nonedetected)
            }
        }

        try {//Stop FrameGrabber
            frameG.stop()
        } catch (e : FrameGrabber.Exception){
            Log.e("javacv", "Failed to stop FrameGrabber: $e")
        }
        //End of execution time.
        val endTime = System.currentTimeMillis()

        //Display results in the UI.
        scope.launch(Dispatchers.Main){
            totalframes.text = getString(R.string.totalframes).plus(totalFrames.toString())
            runtime.text = getString(R.string.runtime).plus("${endTime-starTime} ms")
            totalQRs.text = getString(R.string.totalQRs).plus("$qrCount")
            videoproc.visibility = View.INVISIBLE
            processButton.isEnabled = true
        }
    }

    /*Function to decode QR based on a OpenCV Mat
    * Input: OpenCV Mat(), QRCodeReader, OpenCVFrameConverter, AndroidFrameConverter
    * Output: Booolean: true if success in detection, false otherwise
    * Note: runs in the Default Thread, not Main. Called from the decode() function*/
    private suspend fun decodeQR(gray: Mat, qrReader: QRCodeReader,
                                 converterToMat : OpenCVFrameConverter.ToMat,
                                 converterAndroid : AndroidFrameConverter) : Boolean
            = withContext(Dispatchers.Default) {
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
            if (qrString.compareTo(result.text)!=0){
                qrString = result.text
                qrCount += 1
            }
        } catch (e : NotFoundException){
            noQR = true //If not found, return true.
        }catch (e : Exception){
            Log.e("QR Reader", "Reader error: $e")
            noQR = true //If not found, return true.
        }
        return@withContext noQR
    }

    /*Function to decode QR based on a OpenCV Mat
    * Input: OpenCV Mat()
    * Output: Booolean: true if success in detection, false otherwise
    * Note: runs in the Default Thread, not Main. Called from the decode() function
    private suspend fun decodeQRNeg(gray: Mat, qrReader: QRCodeReader) : Boolean = withContext(Dispatchers.Default) {
        val matNeg = Mat()
        bitwise_not(gray,matNeg)
        return@withContext decodeQR(matNeg,qrReader)
    }*/

    /*Function to detect the active area of the FLC, as it reflects light it is brighter than the
    * 3D printed holder. Use of OpenCV contour detection and physical dimensions of the FLC. Main
    * logic: detect a rectangle that is not too big or too small and have an aspect ration smaller
    * than 16/9 or 4/3
    * Input: Frame
    * Output: Boolean (if the area is detected)
    * This function modifies a global array which has coordinates x, y and dimensions w, h of the
    * active area of the FLC*/
    private fun detect(frame : Frame) : Boolean{
        //Variables definition, convert frame to grayscale, uses the first converter object
        val mat = converterToMat1.convertToMat(frame)
        cvtColor(mat,mat, COLOR_BGR2GRAY)
        val binSize = mat.size().height()/2-1 //bin size for the binarisation
        //Log.i("Binarisation","Bin size: $binSize")
        //Constants for the area size
        val minArea = (mat.size().area()/50) //> 2% of total screen
        val maxArea = (mat.size().area()/12) //< 8% of total screen
        //Other variables
        val bin = Mat() //Mat for binarisation result
        val contours = MatVector() /*Contour detection returns a Mat Vector*/
        var found = false // will change to true if detects the FLC

        /*Image processing of the frame consists of:
        *- Adaptive Binarisation: large bin size allows to detect bigger features i.e. the FLC area
        *  so the bin size is half of the camera width.
        *  *********SUBJECT TO EVALUATION!!!!!!*********
        *- Contour extraction: we use only the contour variable which holds the detected contours in
        *  the form of a Mat vector*/
        adaptiveThreshold(mat,bin,255.0, ADAPTIVE_THRESH_GAUSSIAN_C, THRESH_BINARY,binSize,0.0)
        findContours(bin,contours,Mat(), RETR_EXTERNAL, CHAIN_APPROX_NONE) //Mat() is for hierarchy

        /*Log.i("Mat","Area=${mat.size().area()}")
        Log.i("Detect","min Area=${minArea}, max Area=${maxArea}")
        Log.i("Detect","Number of contours: ${contours.size()}")*/

        //Variable initialisation for the detected contour initialisation.
        var cnt : Mat
        var points : Mat
        var rect : Rect
        var aspect: Double

        /*Main for loop allows iteration. Contours.get(index) returns a Mat which holds the contour
        * points.*/
        for (i in 0 until contours.size()){
            cnt = contours.get(i)
            points = Mat() //Polygon points
            /*This function approximates each contour to a polygon. Parameters: source, destination,
            * epsilon, closed polygon?. Epsilon: approximation accuracy. */
            approxPolyDP(cnt,points, 0.01*arcLength(cnt,true),true)

            /*The polygon approximation returns a Mat (name 'points'). The structure of this Mat is
            * width = 1, height = number of vertices, channels = 2 (possibly coordinates).
            * The steps from here are:
            *- Select polygons with 4 corners
            *- Select only polygons which are large enough in size.
            *- Calculate a bounding rectangle for the polygon, for the FLC should be the actual FLC
            *  area, thus the aspect ratio of this rectangle should be known.
            *- Lastly, filter the rectangle based on its area not too big in size and have an aspect
            *  ration between 0.5 and 2. We cannot tell if the rectangle is rotated but the aspect
            *  ratio of the FLC is 16/9 = 1.7 or 9/16 = 0.56. However, if the FLC and the phone are
            *  not parallel, the FLC might be a square. */
            if (points.size().height()==4){
                if(contourArea(cnt) > minArea){ //Only large polygons.
                    rect = boundingRect(cnt)
                    aspect = (rect.width().toDouble()/rect.height().toDouble())
                    Log.i("Contour","Area=${contourArea(cnt)}")
                    Log.i("Contour","Aspect=${aspect}")
                    if (rect.area() < maxArea && aspect > 0.5 && aspect < 2.0){
                        //Save values of ROI for the decode function.
                        crop[0] = rect.width()
                        crop[1] = rect.height()
                        crop[2] = rect.x()
                        crop[3] = rect.y()
                        Log.i("FLC","w=${crop[0]},h=${crop[1]},x=${crop[2]},y=${crop[3]}")
                        found = true // Detected!
                        break // break 'contours' for loop.
                    }
                }
            }
        }
        return found
    }

    //COMMENTED AS NEW detect FUNCTION IS UNDER DEVELOPMENT.
    /*Function to detect the QR and save its coordinates. Uses de Zxing module to detect any QR in
    * The video but does not decode it.
    * Input: String : video file path and name
    * Output: Boolean : true if detected, false if not.
    * Modifies the global variable coordinates[] */
    /*private suspend fun detect(videoFile: String) : Boolean = withContext(Dispatchers.Default) {
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
    }*/

    /*Function to calculate the crop parameters for the Frame
    * Input: ResultPoint[] (the QR has only 3 ResultPoints)
    * Output: None
    * Modifies the global variable crop[]*/
    /*private suspend fun getCrop(r : Array<ResultPoint>) = withContext(Dispatchers.Default) {
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
    }*/

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
        totalQRs = view.findViewById(R.id.qrCount) //Unique QRs detected

        //Added by Miguel 01/09 - Added to use ZXing QRCodeReader
        hints[DecodeHintType.CHARACTER_SET] = "utf-8"
        hints[DecodeHintType.TRY_HARDER] = true
        hints[DecodeHintType.POSSIBLE_FORMATS] = BarcodeFormat.QR_CODE

        //Added by Miguel 10/09 - String var
        qrString = "Packet00000000000"

        //Button to start processing
        processButton = view.findViewById(R.id.process_button)
        processButton.setOnClickListener(){//Button click listener sets some variables
            var radio = 1 //Added by Miguel 10/09
            qrCount = 0 //Added by Miguel 10/09
            processButton.isEnabled = false
            videoproc.text = getString(R.string.detecting)
            videoproc.visibility = View.VISIBLE
            runtime.text = getString(R.string.runtime)
            totalframes.text = getString(R.string.totalframes)
            totalQRs.text = getString(R.string.totalQRs)

            //Get the ID of the radio button to select processing
            when(view.findViewById<RadioGroup>(R.id.dsp_selection).checkedRadioButtonId){
                R.id.no_dsp -> radio = 1
                R.id.use_neg -> radio = 2
                R.id.use_eqpos -> radio = 3
                R.id.use_eqneg -> radio = 4
            }
            scope.launch {
                /* The decode function is set to run on the Dispatcher.Default scope so it does not
                * block the Main Thread*/
                decode(args.videoname, radio)
            }
        }
    }

    //Added by Miguel 31/08
    override fun onDestroy() {
        super.onDestroy()
        job.cancel() //Clean the other scope (threads) before finishing the fragment.
    }
/*
    companion object {
        /*These instance static methods are used to convert between formats: Frame to Mat or Frame
        * Frame to Bitmap*/
        private val converterToMat : OpenCVFrameConverter.ToMat = OpenCVFrameConverter.ToMat()
        private val converterAndroid : AndroidFrameConverter = AndroidFrameConverter()
    }*/
}