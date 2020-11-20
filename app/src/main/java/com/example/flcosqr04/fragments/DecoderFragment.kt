package com.example.flcosqr04.fragments

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.addCallback
import androidx.fragment.app.Fragment
import androidx.navigation.Navigation
import androidx.navigation.fragment.navArgs
import com.example.flcosqr04.R
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlinx.coroutines.*
import org.bytedeco.javacv.*
import org.bytedeco.opencv.global.opencv_core.*
import org.bytedeco.opencv.global.opencv_imgproc.*
import org.bytedeco.opencv.opencv_core.Context
import org.bytedeco.opencv.opencv_core.Mat
import org.bytedeco.opencv.opencv_core.MatVector
import org.bytedeco.opencv.opencv_core.Rect
import java.lang.StringBuilder
import java.util.*
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentLinkedDeque
import com.backblaze.erasure.ReedSolomon
import java.nio.charset.StandardCharsets

//Sizes for Reed Solomon Encoder
private const val RS_DATA_SIZE = 130
private const val RS_PARITY_SIZE = 45

private const val RS_TOTAL_SIZE = 175
//Number of bytes in a QR code, version 1: 17 bytes
private const val QR_BYTES = 17

class DecoderFragment : Fragment() {
    /** AndroidX navigation arguments */
    private val args: DecoderFragmentArgs by navArgs()
    //Variables which represent the layout elements.
    private lateinit var videoproc : TextView
    private lateinit var runtime : TextView
    private lateinit var totalframes : TextView
    private lateinit var totalQRs : TextView
    private lateinit var processButton: Button
    private lateinit var results : TextView
    //ROI variable.
    private val crop : IntArray = IntArray(size = 4) /*[w:width,h:height,x:left coordinate,y:top
    coordinate]*/

    //Zxing QR reader and hints for its configuration, unique for each thread.
    private val qrReader1 = QRCodeReader()
    private val qrReader2 = QRCodeReader()
    private val hints = Hashtable<DecodeHintType, Any>()
    //Added by Miguel 29/09
    private val qrReaderGray = QRCodeReader()
    private val qrReaderNeg = QRCodeReader()
    private val qrReaderEqGray = QRCodeReader()
    private val qrReaderEqNeg = QRCodeReader()
    private val qrReaderMean = QRCodeReader()
    private val qrReaderDiff = QRCodeReader()
    private val qrReaderMeanNeg = QRCodeReader()
    private val qrReaderDiffNeg = QRCodeReader()
    private val qrReaderEqMean = QRCodeReader()
    private val qrReaderEqDiff = QRCodeReader()
    private val qrReaderEqMeanNeg = QRCodeReader()
    private val qrReaderEqDiffNeg = QRCodeReader()

    //Added by Miguel 31/08: Thread handling
    private val job = Job()
    private val scope = CoroutineScope(job + Dispatchers.Main)
    //Added by Miguel 15/09: DispatcherIO for grabFrame
    private val jobIO = Job()
    private val scopeGrab = CoroutineScope(jobIO + Dispatchers.IO)
    //Added by Miguel 24/10: Dispatcher for launching decode coroutines
    /*private val jobDecode = Job()
    private val scopeDecode = CoroutineScope(jobDecode + Dispatchers.Default)*/

    //Added by Miguel 10/09: Variables for unique QR counting
    private lateinit var qrString : String
    private var qrCount : Int = 0
    //Saving the QR data in a concurrent String list.
    private val rxData = ConcurrentLinkedDeque<String>()
    private val data : MutableList<String> = mutableListOf()
    //Saving the QR data in a concurrent Byte list. Added 11/11
    /*private lateinit var qrBytes : ByteArray
    private val rxBytes = ConcurrentLinkedDeque<ByteArray>()*/

    //Reed Solomon variables: Byte Array for data and Boolean Array for erasures
    private val rs = ReedSolomon.create(RS_DATA_SIZE, RS_PARITY_SIZE)
    private val byteMsg = Array(RS_TOTAL_SIZE) {ByteArray(QR_BYTES-1) {0} }
    private val erasure = BooleanArray(RS_TOTAL_SIZE){false}

    /*Function to decode sequence by grabbing frame by frame from a video file using the FFmpeg
    * package. Then, using JavaCV's OpenCV package, the frames are image-processed before calling
    * the QR decoder from the Zxing package
    * Input: String : video file path and name
    * Output: None : QR results are printed in the console */
    private suspend fun decode(videoFile : String, radio : Int) = withContext(Dispatchers.Default) {
        //Variables to get frames: FrameGrabber, Frame
        val frameG = FFmpegFrameGrabber(videoFile)
        var frame : Frame?
        val converterToMat : OpenCVFrameConverter.ToMat = OpenCVFrameConverter.ToMat()
        //Variables for the OpenCV Mat
        var frameMat = Mat() // Frame in Mat format.
        val matGray = Mat()// Frame converted to grayscale
        val matNeg = Mat() // Negative grayscale image
        val matEqP = Mat() // Equalised grayscale image
        val matEqN = Mat() // Equalised negative image
        val matBi = Mat() // Equalised negative image
        var preMat = Mat() // Previous frame in Mat format
        val matMean = Mat() //Mean of consecutive frames in Mat format
        val matDiff = Mat() //Difference of consecutive frames in Mat format.
        var halfMat = Mat() //Current Mat divided by 2.
        //Added by Miguel 29/09
        val matMeanNeg = Mat()
        val matDiffNeg = Mat()
        val matEqMean = Mat()
        val matEqDiff = Mat()
        val matEqMeanNeg = Mat()
        val matEqDiffNeg = Mat()
        //Variables for total frame count, check if a QR is detected and if the FLC is detected
        var totalFrames = 0
        var noQR : Boolean
        var flc = false

        //Start of execution time
        //val starTime = System.currentTimeMillis()

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
                    if (flc) {
                        break
                    }
                }
            } catch (e : FrameGrabber.Exception){
                Log.e("javacv", "Failed to grab frame: $e")
            }
        } while (frame != null)

        //Start of execution time
        val starTime = System.currentTimeMillis()

        //If the FLC is detected, continue to grab frames and decode.
        if (flc){
            //Array of ResultPoints for detect the ROI (x,y,w,h)
            val roi = Rect(crop[2],crop[3],crop[0],crop[1])
            Log.i("Decode","ROI: ${roi.x()},${roi.y()},${roi.width()},${roi.height()}")
            /*Save previous frame value, check for the divide operator*/
            //preMat.release()
            preMat = Mat(converterToMat.convert(frame),roi)
            cvtColor(preMat,preMat,COLOR_BGR2GRAY)
            preMat = multiplyPut(preMat,0.5)
            Log.i("Preframe","preMat size: ${preMat.size().width()},${preMat.size().height()},${preMat.channels()}")
            /*Launch text change in the UI*/
            scope.launch{
                videoproc.text = getString(R.string.processing)
            }
            /*Add the creation of the previous frame. IMPORTANT: this copies the data in frame
            * so if the process will be restarted (start from frame 0) take this into account.*/

            do {//Loop to grab all frames
                try {
                    frame = frameG.grabFrame()
                    //Clear variables.
                    //matGray.release()
                    //frameMat.release()
                    if (frame != null){
                        //Conversions
                        frameMat = Mat(converterToMat.convert(frame),roi) /*Frame to Mat with ROI
                        uses the first converterToMat object*/
                        cvtColor(frameMat,matGray,COLOR_BGR2GRAY) //To Gray

                        when (radio) {
                            1 -> {
                                val temp1 = (CoroutineScope(Dispatchers.Default + Job())).async {
                                    decodeQR(matGray,qrReader1)
                                }
                                val temp2 = (CoroutineScope(Dispatchers.Default + Job())).async {
                                    bitwise_not(matGray,matNeg)
                                    decodeQR(matNeg,qrReader2)
                                }
                                noQR = (temp1.await() && temp2.await())
                                if (!noQR) {
                                    Log.i("QR Reader","Detected by gray or negative")
                                }
                            }
                            2 -> {
                                val temp1 = (CoroutineScope(Dispatchers.Default + Job())).async {
                                    equalizeHist(matGray,matEqP)
                                    decodeQR(matEqP,qrReader1)
                                }
                                val temp2 = (CoroutineScope(Dispatchers.Default + Job())).async {
                                    bitwise_not(matGray,matNeg)
                                    equalizeHist(matNeg,matEqN)
                                    decodeQR(matEqN,qrReader2)
                                }
                                noQR = (temp1.await() && temp2.await())
                                if (!noQR) {
                                    Log.i("QR Reader","Detected by equalised or equalised negative")
                                }
                            }
                            3 -> {
                                bilateralFilter(matGray,matBi,5,150.0,150.0)
                                val temp1 = (CoroutineScope(Dispatchers.Default + Job())).async {
                                    decodeQR(matBi,qrReader1)
                                }
                                val temp2 = (CoroutineScope(Dispatchers.Default + Job())).async {
                                    bitwise_not(matBi,matNeg)
                                    decodeQR(matNeg,qrReader2)
                                }
                                noQR = (temp1.await() && temp2.await())
                                if (!noQR) {
                                    Log.i("QR Reader","Detected by bilateral")
                                }
                            }
                            4 -> {
                                halfMat = multiplyPut(matGray,0.5)
                                val temp1 = (CoroutineScope(Dispatchers.Default + Job())).async {
                                    add(preMat,halfMat,matMean)
                                    decodeQR(matMean,qrReader1)
                                }
                                val temp2 = (CoroutineScope(Dispatchers.Default + Job())).async {
                                    subtract(preMat,halfMat,matDiff)
                                    decodeQR(matDiff,qrReader2)
                                }
                                noQR = (temp1.await() &&  temp2.await())
                                if (!noQR) {
                                    Log.i("QR Reader","Detected by mean/diff")
                                }
                                preMat = multiplyPut(matGray,0.5)
                            }
                            5 -> {
                                halfMat = multiplyPut(matGray,0.5)
                                val temp1 = (CoroutineScope(Dispatchers.Default + Job())).async {
                                    add(preMat,halfMat,matMean)
                                    bitwise_not(matMean,matMean)
                                    decodeQR(matMean,qrReader1)
                                }
                                val temp2 = (CoroutineScope(Dispatchers.Default + Job())).async {
                                    subtract(preMat,halfMat,matDiff)
                                    bitwise_not(matDiff,matDiff)
                                    decodeQR(matDiff,qrReader2)
                                }
                                noQR = (temp1.await() && temp2.await())
                                if (!noQR) {
                                    Log.i("QR Reader","Detected by negative mean/diff")
                                }
                                preMat = multiplyPut(matGray,0.5)
                            }
                            6 -> {
                                halfMat = multiplyPut(matGray,0.5)
                                val temp1 = (CoroutineScope(Dispatchers.Default + Job())).async {
                                    add(preMat,halfMat,matMean)
                                    equalizeHist(matMean,matMean)
                                    decodeQR(matMean,qrReader1)
                                }
                                val temp2 = (CoroutineScope(Dispatchers.Default + Job())).async {
                                    subtract(preMat,halfMat,matDiff)
                                    equalizeHist(matDiff,matDiff)
                                    decodeQR(matDiff,qrReader2)
                                }
                                noQR = (temp1.await() && temp2.await())
                                if (!noQR) {
                                    Log.i("QR Reader","Detected by equalised mean/diff")
                                }
                                preMat = multiplyPut(matGray,0.5)
                            }
                            7 -> {
                                halfMat = multiplyPut(matGray,0.5)
                                val temp1 = (CoroutineScope(Dispatchers.Default + Job())).async {
                                    add(preMat,halfMat,matMean)
                                    bitwise_not(matMean,matMean)
                                    equalizeHist(matMean,matMean)
                                    decodeQR(matMean,qrReader1)
                                }
                                val temp2 = (CoroutineScope(Dispatchers.Default + Job())).async {
                                    subtract(preMat,halfMat,matDiff)
                                    bitwise_not(matDiff,matDiff)
                                    equalizeHist(matDiff,matDiff)
                                    decodeQR(matDiff,qrReader2)
                                }
                                noQR = (temp1.await() && temp2.await())
                                if (!noQR) {
                                    Log.i("QR Reader","Detected by equalised-negative mean/diff")
                                }
                                preMat = multiplyPut(matGray,0.5)
                            }
                            8 -> {
                                noQR =
                                    withContext(Dispatchers.Default) {
                                        val temp1 = async {
                                            decodeQR(matGray,qrReaderGray)
                                        }
                                        val temp2 = async {
                                            bitwise_not(matGray, matNeg)
                                            decodeQR(matNeg,qrReaderNeg)
                                        }
                                        val temp3 = async {
                                            equalizeHist(matGray, matEqP)
                                            decodeQR(matEqP,qrReaderEqGray)
                                        }
                                        val temp4 = async {
                                            bitwise_not(matGray, matEqN)
                                            equalizeHist(matEqN, matEqN)
                                            decodeQR(matEqN,qrReaderEqNeg)
                                        }
                                        (temp1.await() && temp2.await() && temp3.await() && temp4.await())
                                    }
                                if (!noQR) {
                                    Log.i("QR Reader","Detected by normal/equalised")
                                }
                            }
                            9 -> {
                                halfMat = multiplyPut(matGray.clone(), 0.5)
                                noQR =
                                    withContext(Dispatchers.Default) {//Added by Miguel 07/10
                                        val temp1 = async {
                                            decodeQR(matGray,qrReaderGray)
                                        }
                                        val temp2 = async {
                                            bitwise_not(matGray, matNeg)
                                            decodeQR(matNeg,qrReaderNeg)
                                        }
                                        val temp3 = async {
                                            equalizeHist(matGray, matEqP)
                                            decodeQR(matEqP,qrReaderEqGray)
                                        }
                                        val temp4 = async {
                                            bitwise_not(matGray, matEqN)
                                            equalizeHist(matEqN, matEqN)
                                            decodeQR(matEqN,qrReaderEqNeg)
                                        }
                                        val temp5 = async {
                                            add(preMat, halfMat, matMean)
                                            decodeQR(matMean,qrReaderMean)
                                        }
                                        val temp6 = async {
                                            //subtract(preMat, halfMat, matDiff) //Changed by Miguel 13/10
                                            subtract(halfMat, preMat, matDiff)
                                            decodeQR(matDiff,qrReaderDiff)
                                        }
                                        val temp7 = async {
                                            add(preMat, halfMat, matMeanNeg)
                                            bitwise_not(matMeanNeg, matMeanNeg)
                                            decodeQR(matMeanNeg,qrReaderMeanNeg)
                                        }
                                        val temp8 = async {
                                            //subtract(preMat, halfMat, matDiffNeg) //Changed by Miguel 13/10
                                            subtract(halfMat, preMat, matDiffNeg)
                                            bitwise_not(matDiffNeg, matDiffNeg)
                                            decodeQR(matDiffNeg,qrReaderDiffNeg)
                                        }
                                        val temp9 = async {
                                            add(preMat, halfMat, matEqMean)
                                            equalizeHist(matEqMean, matEqMean)
                                            decodeQR(matEqMean,qrReaderEqMean)
                                        }
                                        val temp10 = async {
                                            //subtract(preMat, halfMat, matDiffNeg) //Changed by Miguel 13/10
                                            subtract(halfMat, preMat, matEqDiff)
                                            equalizeHist(matEqDiff, matEqDiff)
                                            decodeQR(matEqDiff,qrReaderEqDiff)
                                        }
                                        val temp11 = async {
                                            add(preMat, halfMat, matEqMeanNeg)
                                            bitwise_not(matEqMeanNeg, matEqMeanNeg)
                                            equalizeHist(matEqMeanNeg, matEqMeanNeg)
                                            decodeQR(matEqMeanNeg,qrReaderEqMeanNeg)
                                        }
                                        val temp12 = async {
                                            //subtract(preMat, halfMat, matDiffNeg) //Changed by Miguel 13/10
                                            subtract(halfMat, preMat, matEqDiffNeg)
                                            bitwise_not(matEqDiffNeg, matEqDiffNeg)
                                            equalizeHist(matEqDiffNeg, matEqDiffNeg)
                                            decodeQR(matEqDiffNeg,qrReaderEqDiffNeg)
                                        }
                                        (temp1.await() && temp2.await() && temp3.await() &&
                                                temp4.await() && temp5.await() && temp6.await() &&
                                                temp7.await() && temp8.await() && temp9.await() &&
                                                temp10.await() && temp11.await() && temp12.await())
                                    } //Added by Miguel 7/10
                                if (!noQR) {
                                    Log.i("QR Reader","Detected by any")
                                }
                                preMat = multiplyPut(matGray,0.5)
                            }
                            10 -> {
                                val jobDecode = Job()
                                val scopeDecode = CoroutineScope(jobDecode + Dispatchers.Default)
                                halfMat = multiplyPut(matGray.clone(), 0.5)
                                val tmp1 = scopeDecode.async {
                                    decodeqr(matGray,qrReaderGray,this)
                                }
                                val tmp2 = scopeDecode.async {
                                    bitwise_not(matGray, matNeg)
                                    decodeqr(matNeg,qrReaderNeg,this)
                                }
                                val tmp3 = scopeDecode.async {
                                    equalizeHist(matGray, matEqP)
                                    decodeqr(matEqP,qrReaderEqGray,this)
                                }
                                val tmp4 = scopeDecode.async {
                                    bitwise_not(matGray, matEqN)
                                    equalizeHist(matEqN, matEqN)
                                    decodeqr(matEqN,qrReaderEqNeg,this)
                                }
                                val tmp5 =scopeDecode.async {
                                    add(preMat, halfMat, matMean)
                                    decodeqr(matMean,qrReaderMean,this)
                                }
                                /*val tmp6 = scopeDecode.async {
                                    subtract(halfMat, preMat, matDiff)
                                    decodeqr(matDiff,qrReaderDiff,this)
                                }*/
                                val tmp7 = scopeDecode.async {
                                    add(preMat, halfMat, matMeanNeg)
                                    bitwise_not(matMeanNeg, matMeanNeg)
                                    decodeqr(matMeanNeg,qrReaderMeanNeg,this)
                                }  //Here app crashes for the OpenCV arithmetic
                                /*val tmp8 = scopeDecode.async {
                                    subtract(halfMat, preMat, matDiffNeg)
                                    bitwise_not(matDiffNeg, matDiffNeg)
                                    decodeqr(matDiffNeg,qrReaderDiffNeg,this)
                                }*/
                                val tmp9 = scopeDecode.async {
                                    add(preMat, halfMat, matEqMean)
                                    equalizeHist(matEqMean, matEqMean)
                                    decodeqr(matEqMean,qrReaderEqMean,this)
                                }
                                /*val tmp10 = scopeDecode.async {
                                    subtract(halfMat, preMat, matEqDiff)
                                    equalizeHist(matEqDiff, matEqDiff)
                                    decodeqr(matEqDiff,qrReaderEqDiff,this)
                                }*/
                                val tmp11 = scopeDecode.async {
                                    add(preMat, halfMat, matEqMeanNeg)
                                    bitwise_not(matEqMeanNeg, matEqMeanNeg)
                                    equalizeHist(matEqMeanNeg, matEqMeanNeg)
                                    decodeqr(matEqMeanNeg,qrReaderEqMeanNeg,this)
                                }
                                /*val tmp12 = scopeDecode.async {
                                    subtract(halfMat, preMat, matEqDiffNeg)
                                    bitwise_not(matEqDiffNeg, matEqDiffNeg)
                                    equalizeHist(matEqDiffNeg, matEqDiffNeg)
                                    decodeqr(matEqDiffNeg,qrReaderEqDiffNeg,this)
                                }*/
                                preMat = multiplyPut(matGray,0.5)
                                try {
                                    noQR = (tmp1.await() && tmp2.await() && tmp3.await() &&
                                            tmp4.await() && tmp5.await() /*&& tmp6.await()*/ &&
                                            tmp7.await() /*&& tmp8.await()*/ && tmp9.await() &&
                                            /*tmp10.await() &&*/ tmp11.await() /*&& tmp12.await()*/)
                                } catch (e: CancellationException) {
                                    //Log.i("decodeScope","Exception: $e")
                                }
                                /*while (scopeDecode.isActive) {
                                    Log.i("ScopeDecode","Active")
                                }*/
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

        /*Print length of the data and tge data received. Received is in a ConcurrentLinkedQueue
        * (rxData) which is added to a MutableList (data)*/
        Log.i("Data","Length ${rxData.size}")
        try {
            data.addAll(rxData.toList())
        } catch (e: Exception) {
            Log.e("Data","$e")
        }

        //End of execution time.
        val endTime = System.currentTimeMillis()

        //Variables and processes to create results.
        val resultString = StringBuilder()
        data.sort()

        //Order binary data.
        orderByteData()
        /*Apply Reed Solomon, inside a try-catch:
        * Try to perform the Reed Solomon decoding and modify the text using the StringBuilder. If
        * the decoding fails the StringBuilder shows an error message.*/
        try {
            rs.decodeMissing(byteMsg, erasure, 0, QR_BYTES - 1)
            for (i in 0 until RS_DATA_SIZE){
                resultString.append(byteMsg[i].toString(Charsets.ISO_8859_1))
            }
        } catch (e: Exception){
            Log.e("RS",e.message)
            resultString.append("Error during Reed Solomon decoding.")
        }
        //Display results in the UI.
        scope.launch(Dispatchers.Main){
            totalframes.text = getString(R.string.totalframes).plus(totalFrames.toString())
            runtime.text = getString(R.string.runtime).plus("${endTime-starTime} ms")
            totalQRs.text = getString(R.string.totalQRs).plus(data.size.toString())
            videoproc.visibility = View.INVISIBLE
            processButton.isEnabled = true
            results.text = resultString.toString()
        }
    }

    /*Function to decode QR based on a OpenCV Mat
    * Input: OpenCV Mat(), QRCodeReader, OpenCVFrameConverter, AndroidFrameConverter
    * Output: Boolean: true if success in detection, false otherwise
    * Note: runs in the Default Thread, not Main. Called from the decode() function*/
    private /*suspend*/ fun decodeQR(gray: Mat, qrReader: QRCodeReader) : Boolean
            /*= withContext(Dispatchers.Default)*/ {
        //Conversion from Mat -> Frame -> Bitmap -> IntArray -> BinaryBitmap
        //New: convert to RGBA first
        val rgba = Mat()
        cvtColor(gray,rgba, COLOR_GRAY2RGBA) // End of added code.
        val converterMat = OpenCVFrameConverter.ToMat()
        val frame = converterMat.convert(rgba)
        val converterAnd = AndroidFrameConverter()
        val bitmap = converterAnd.convert(frame)
        val intData = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(intData,0,bitmap.width,0,0,bitmap.width,bitmap.height)
        /*val binBitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width,
            bitmap.height,intData)))*/
        val lumSource : LuminanceSource = RGBLuminanceSource(bitmap.width, bitmap.height,intData)
        val binBitmap = BinaryBitmap(HybridBinarizer(lumSource))
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
            //Get the data in String and try to add it to the ConcurrentLinkedQueue: rxData
            /*qrString = result.text
            if (!rxData.contains(qrString)){
                rxData.add(qrString)
            }*/
        } catch (e : Exception){//NotFoundException){
            noQR = true //If not found, return true.
        }/*catch (e : Exception){
            Log.e("QR Reader", "Reader error: $e")
            noQR = true //If not found, return true.
        }*/
        return/*@withContext*/ noQR
    }

    /*Function to decode QR based on a OpenCV Mat
    * Input: OpenCV Mat(), QRCodeReader, OpenCVFrameConverter, AndroidFrameConverter
    * Output: No output. Prints the QR and kill other process trying to decode.
    * Note: runs in the Default Thread, not Main. Called from the decode() function*/
    private suspend fun decodeqr(gray: Mat, qrReader: QRCodeReader, scopeDecode : CoroutineScope) : Boolean {
        //Conversion from Mat -> Frame -> Bitmap -> IntArray -> BinaryBitmap
        val rgba = Mat()
        cvtColor(gray,rgba, COLOR_GRAY2RGBA)
        val converterMat = OpenCVFrameConverter.ToMat()
        val frame = converterMat.convert(rgba)
        val converterAnd = AndroidFrameConverter()
        val bitmap = converterAnd.convert(frame)
        val intData = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(intData,0,bitmap.width,0,0,bitmap.width,bitmap.height)
        val lumSource : LuminanceSource = RGBLuminanceSource(bitmap.width, bitmap.height,intData)
        val binBitmap = BinaryBitmap(HybridBinarizer(lumSource))
        //Store result of QR detection
        val result : Result

        try { //Detect QR and print result
            result = qrReader.decode(binBitmap,hints)
            //Get the data in String and try to add it to the ConcurrentLinkedQueue: rxData
            qrString = result.text
            /*Get the data in ByteArray and try to add it to the ConcurrentLinkedQueue: rxBytes.
            * Added 11-11. Problem: ByteArray contains raw bytes and are not the same as the decoded
            * data. */
            //qrBytes = result.rawBytes
            //Fully sync check and add data to the rxData.
            synchronized(rxData){
                if (!rxData.contains(qrString)){
                    rxData.add(qrString)
                    Log.i("RS","String: $qrString")
                    Log.i("RS","Bytes ISO: ${qrString.toByteArray(Charsets.ISO_8859_1).contentToString()}")
                    //Log.i("RS","Bytes UTF: ${qrString.toByteArray(Charsets.UTF_8).contentToString()}")
                    //Log.i("RS","Byte number: ${qrString.toByteArray(charset = Charsets.ISO_8859_1)[0].toUByte()}")
                }
            }
            scopeDecode.cancel("QR detected")
            return false
        } catch (e : NotFoundException){//NotFoundException){
            //Log.i("QR Reader","Not found")
            }
        catch (e: ChecksumException){
            //Log.i("QR Reader","Checksum failed")
        }
        catch (e: FormatException){
            //Log.i("QR Reader","Format error")
        }
        return true
    }

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
        val converterToMat : OpenCVFrameConverter.ToMat = OpenCVFrameConverter.ToMat()
        val mat = converterToMat.convertToMat(frame)
        cvtColor(mat,mat, COLOR_BGR2GRAY)
        val binSize = mat.size().height()/2-1 //bin size for the binarisation
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
        findContours(bin,contours,Mat(), RETR_LIST, CHAIN_APPROX_NONE) //Mat() is for hierarchy [RETR_EXTERNAL]

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

    /*Function to order the data stored in the array rxData (size: TOTAL_SIZE) of each QR read (17
    * bytes for version 1, stored as a String).
    * Input: None
    * Output: None
    * This function uses a global ConcurrentLinkedDeque (rxData) which holds the byte data of each
    * read QR code, stored as a String.*/
    private fun orderByteData() {
        //Array of bytes to store the data within the QR.
        val dataBytes = ByteArray(QR_BYTES)
        rxData.forEach {
            /*Convert the QR data, stored as a String and copy it into a Byte Array. The charset is
            * not mandatory but advisable */
            it.toByteArray(charset=Charsets.ISO_8859_1).copyInto(dataBytes)
            /*Copy and set data into the arrays needed to decode the using RS. These bytes may be
            * interpreted as signed integers. The first byte contains the index in which the 16
            * bytes of data should be stored and as the index must be Int (and does not accept an
            * UInt) we mask the first byte using a bitwise AND with 0xFF (first byte). The index
            * is also use to fill the erasure array, as they state which QRs are present. */
            dataBytes.copyInto(byteMsg[dataBytes[0].toInt() and 0xFF],0,1)
            erasure[it[0].toInt() and 0xFF]=true
            Log.i("RS","${dataBytes[0].toInt() and 0xFF}: " +
                    byteMsg[dataBytes[0].toInt() and 0xFF].contentToString()
            )
        }
        //Log.i("RS","erasure: ${erasure.contentToString()}")
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
        totalQRs = view.findViewById(R.id.qrCount) //Unique QRs detected
        results = view.findViewById(R.id.Result)

        //Added by Miguel 01/09 - Added to use ZXing QRCodeReader
        hints[DecodeHintType.CHARACTER_SET] = StandardCharsets.ISO_8859_1.name()//"utf-8"
        hints[DecodeHintType.TRY_HARDER] = true
        hints[DecodeHintType.POSSIBLE_FORMATS] = BarcodeFormat.QR_CODE

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
            //Added 11-11
            rxData.clear()
            data.clear()
            //rxBytes.clear()

            //Get the ID of the radio button to select processing
            when(view.findViewById<RadioGroup>(R.id.dsp_selection).checkedRadioButtonId){
                R.id.radio01 -> radio = 1
                R.id.radio02 -> radio = 2
                R.id.radio03 -> radio = 3
                R.id.radio04 -> radio = 4
                R.id.radio05 -> radio = 5
                R.id.radio06 -> radio = 6
                R.id.radio07 -> radio = 7
                R.id.radio08 -> radio = 8
                R.id.radio09 -> radio = 9
                R.id.radio10 -> radio = 10
            }
            scope.launch {
                /* The decode function is set to run on the Dispatcher.Default scope so it does not
                * block the Main Thread*/
                decode(args.videoname, 10)//radio)
            }
        }
    }

    //Added by Miguel 31/08
    override fun onDestroy() {
        super.onDestroy()
        job.cancel() //Clean the other scope (threads) before finishing the fragment.
        jobIO.cancel() //Clean the other scope (threads) before finishing the fragment.
    }
}