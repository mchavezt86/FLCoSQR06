package com.example.flcosqr04.fragments

import android.content.Context
import android.content.DialogInterface
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.addCallback
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.navigation.NavController
import androidx.navigation.Navigation
import androidx.navigation.fragment.navArgs
import com.example.flcosqr04.MainActivity
import com.example.flcosqr04.R
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.delay
import net.sourceforge.zbar.*
import org.opencv.android.BaseLoaderCallback
import org.opencv.android.LoaderCallbackInterface
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
//import org.opencv.core.Mat
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import org.bytedeco.javacv.*
import org.bytedeco.javacv.OpenCVFrameConverter
import org.bytedeco.opencv.opencv_core.*
import org.bytedeco.opencv.opencv_imgproc.*
import org.bytedeco.opencv.global.opencv_core.*
import org.bytedeco.opencv.global.opencv_imgproc.*
import org.bytedeco.ffmpeg.avformat.*
import org.bytedeco.ffmpeg.global.avformat.*
import net.sourceforge.zbar.Config.*
import kotlinx.coroutines.*
import kotlin.concurrent.thread
import kotlin.system.*
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.detector.*
import com.google.zxing.qrcode.decoder.*
import java.util.*
import kotlin.Exception
import kotlin.reflect.typeOf


class DecoderFragment : Fragment() {
    /** AndroidX navigation arguments */
    private val args: DecoderFragmentArgs by navArgs()
    //Variables which represent the layout elements.
    private lateinit var videoproc : TextView
    private lateinit var runtime : TextView
    private lateinit var totalframes : TextView
    private lateinit var processButton: Button
    //private lateinit var frameImg : Image
    //Radio button value.
    private var radio = 0

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
        //Variables to hold Frame and Mat values.
        val frameG : FrameGrabber = FFmpegFrameGrabber(videoFile)  //FrameGrabber for the video
        var frame : Frame? // Frame grabbed.
        var frameMat = Mat() // Frame in Mat format.
        var matGray = Mat()// Frame converted to grayscale
        var matNeg = Mat() // Negative grayscale image
        var matEqP = Mat() // Equalised grayscale image
        var matEqN = Mat() // Equalised negative image
        var frameProc : Frame //Frame processed
        //Variables for total frame count and check if a QR is detected
        var totalFrames = 0
        var noQR : Boolean

        //Start of execution time
        val starTime = System.currentTimeMillis()

        try {//Start FrameGrabber
            frameG.start()
        } catch (e : FrameGrabber.Exception) {
            Log.e("javacv", "Failed to start FrameGrabber: $e")
        }
        frame = null
        do {//Loop to grab all frames
            try {
                frame = frameG.grabFrame()
                matGray.release()
                frameMat.release()
                //frameImg.destroy()
                if (frame != null){
                    //Log.i("javacv","Frame grabbed")
                    frameMat = converterToMat.convert(frame) //Frame to Mat
                    cvtColor(frameMat,matGray, COLOR_BGR2GRAY) //To Gray
                    Log.i("mact","Mat size: (${matGray.size().width()},${matGray.size().height()})")

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
            }
        } while (frame != null)
        try {//Stop FrameGrabber
            frameG.stop()
        } catch (e : FrameGrabber.Exception){
            Log.e("javacv", "Failed to stop FrameGrabber: $e")
        }
        //End of execution time.
        var endTime = System.currentTimeMillis()

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
        val binbitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width,
            bitmap.height,intData)))
        //Store result of QR detection
        val result : Result
        //Initialise as "no QR detected"
        var noQR = false

        try { //Detect QR and print result
            result = qrReader.decode(binbitmap,hints)
            Log.i("QR Reader)", result.text)
        } catch (e : NotFoundException){
            noQR = true //If not found, return true.
        }catch (e : Exception){
            Log.e("QR Reader", "Reader error: $e")
            noQR = true //If not found, return true.
        }
        return@withContext noQR
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
                decode(args.videoname)}
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
/*
//Added by Miguel 01/09
val alertDialog: AlertDialog? = activity?.let {
    val builder = AlertDialog.Builder(it)
    builder.apply {
        setPositiveButton(R.string.OK,
            DialogInterface.OnClickListener { dialog, id ->
                /*Navigation.findNavController(requireActivity(),R.id.fragment_container)
                    .navigate(SelectorFragmentDirections.actionSelectorToDecoder(mainActivity.video))*/
            })
        setMessage("Process will start soon...")
        setTitle("Decoding")
    }
    // Set other dialog properties
    // Create the AlertDialog
    builder.create()
}
alertDialog?.show()*/