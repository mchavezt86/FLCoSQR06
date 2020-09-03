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
import java.lang.Exception
import java.util.*

class DecoderFragment : Fragment() {
    /** AndroidX navigation arguments */
    private val args: DecoderFragmentArgs by navArgs()
    //Variables
    private lateinit var videoproc : TextView
    private lateinit var runtime : TextView
    private lateinit var totalframes : TextView
    private lateinit var processButton: Button
    private lateinit var frameImg : Image

    private val qrReader = QRCodeReader()
    //private lateinit var mainActivity : MainActivity //Added by Miguel 28/08

    //Added by Miguel 31/08
    private var job = Job()
    private val scope = CoroutineScope(job + Dispatchers.Main)

    //Added by Miguel 01/09 - Added to use ZXing QRCodeReader
    private val hints = Hashtable<DecodeHintType, Any>()

    private suspend fun decode(videoFile : String) = withContext(Dispatchers.Default) {
        val frameG : FrameGrabber = FFmpegFrameGrabber(videoFile)  //FrameGrabber for the video
        var frame : Frame? // Frame grabbed.
        var frameMat = Mat() // Frame in Mat format.
        var matGray = Mat()// Frame converted to Grayscale
        var frameProc : Frame //Frame processed
        var totalFrames = 0
        var result : Result // Results of the QR scanner
        var bitmap : Bitmap
        var binbitmap : BinaryBitmap

        //Added by Miguel 01/09 - Added to use ZXing QRCodeReader
        /*hints[DecodeHintType.CHARACTER_SET] = "utf-8"
        hints[DecodeHintType.TRY_HARDER] = true
        hints[DecodeHintType.POSSIBLE_FORMATS] = BarcodeFormat.QR_CODE*/

        //val qrReader = QRCodeReader()
        //Attempt to get execution time
        val starTime = System.currentTimeMillis()

        try {//Start FrameGrabber
            //frameG.format = "mp4"
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
                    //Conversion to Bitmap
                    frame = converterToMat.convert(matGray)
                    bitmap = converterAndroid.convert(frame)
                    //Int Array for bitmap
                    var intData : IntArray = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(intData,0,bitmap.width,0,0,bitmap.width,bitmap.height)
                    binbitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width,
                    bitmap.height,intData)))
                    try {
                        result = qrReader.decode(binbitmap,hints)
                        Log.i("QR Reader", result.text)
                    } catch (e : Exception){
                        Log.e("QR Reader", "Reader error: $e")
                    }
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
        var endTime = System.currentTimeMillis()
        scope.launch(Dispatchers.Main){
            //videoproc.text = textFrames.plus(totalFrames.toString())
            totalframes.text = getString(R.string.totalframes).plus(totalFrames.toString())
            runtime.text = getString(R.string.runtime).plus("${endTime-starTime} ms")
            videoproc.visibility = View.INVISIBLE
            processButton.isEnabled = true
        }
        //return@withContext totalFrames
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
        //mainView.text = getString(R.string.processing)

        processButton = view.findViewById(R.id.process_button)
        processButton.setOnClickListener(){
            processButton.isEnabled = false
            videoproc.visibility = View.VISIBLE
            runtime.text = getString(R.string.runtime)
            totalframes.text = getString(R.string.totalframes)
            scope.async {
                decode(args.videoname)}
        }

        //Added by Miguel 01/09 - Added to use ZXing QRCodeReader
        hints[DecodeHintType.CHARACTER_SET] = "utf-8"
        hints[DecodeHintType.TRY_HARDER] = true
        hints[DecodeHintType.POSSIBLE_FORMATS] = BarcodeFormat.QR_CODE

        //mainActivity = requireActivity() as MainActivity
        //mainActivity.video = "Test"

        //scope.async {
        //    decode(args.videoname)}

        /*scope.launch(Dispatchers.Main) {
            val x : Int = withContext(Dispatchers.Default){
                decode(args.videoname)
            }
            mainView.text = textFrames.plus(x.toString())
        }*/
    }

    //Added by Miguel 31/08
    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }

    companion object {

        private val converterToMat : OpenCVFrameConverter.ToMat = OpenCVFrameConverter.ToMat()
        private val converterAndroid : AndroidFrameConverter = AndroidFrameConverter()
        private const val textFrames = "Number of frames: "
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