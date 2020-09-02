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
import android.widget.TextView
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
    private lateinit var mainView : TextView
    //private var textFrames = "Number of frames: "
    private lateinit var frameImg : Image
    //private val converterToMat : OpenCVFrameConverter.ToMat = OpenCVFrameConverter.ToMat()
    //private var scanner = ImageScanner()

    //private var result = 0// Results of the QR scanner
    private lateinit var mainActivity : MainActivity //Added by Miguel 28/08

    //Added by Miguel 31/08
    private var job = Job()
    private val scope = CoroutineScope(job + Dispatchers.Main)

    //Added by Miguel 01/09 - Added to use ZXing QRCodeReader
    private val hints = Hashtable<DecodeHintType, Any>()

    private suspend fun decode(videoFile : String) = withContext(Dispatchers.Default) {
        //var mainView = view.findViewById<TextView>(R.id.video_analyser)
        val frameG : FrameGrabber = FFmpegFrameGrabber(videoFile)  //FrameGrabber for the video
        var frame : Frame? // Frame grabbed.
        var frameMat = Mat() // Frame in Mat format.
        var matGray = Mat()// Frame converted to Grayscale
        var frameProc : Frame //Frame processed
        var totalFrames = 0
        var frameImg : Image // Image for zbar QR scanner
        //var result : Int// Results of the QR scanner
        var result : Result // Results of the QR scanner
        var syms : SymbolSet
        var bitmap : Bitmap
        var binbitmap : BinaryBitmap

        //Added by Miguel 01/09 - Added to use ZXing QRCodeReader
        hints[DecodeHintType.CHARACTER_SET] = "utf-8"
        hints[DecodeHintType.TRY_HARDER] = true
        hints[DecodeHintType.POSSIBLE_FORMATS] = BarcodeFormat.QR_CODE

        var qrReader = QRCodeReader()

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
                    Log.i("javacv","Frame grabbed")
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
                    //ZXing reader
                    try {
                        result = qrReader.decode(binbitmap,hints)
                        Log.i("QR Reader", result.text)
                    } catch (e : Exception){
                        Log.e("QR Reader", "Reader error: $e")
                    }
                    /*Log.i("javacv","Gray frame size: (" + frameMat.size().width().toString()
                    + "," + frameMat.size().height().toString() + ")")*/
                    // Create ZBar Image and set it with Gray image.
                    /*frameImg = Image(frameGray.size().width(),frameGray.size().height(), "Y800")
                    frameImg.setData(frameGray.data().stringBytes)
                    Log.i("javacv","ImageZbar frame size: (" + frameImg.width.toString()
                            + "," + frameImg.height.toString() + ")")*/

                    /*val scanner = ImageScanner()
                    scanner.setConfig(Symbol.NONE,ENABLE,0) // Disable all codes
                    scanner.setConfig(Symbol.QRCODE,ENABLE,1) //Enable only QR codes
                    result = scanner.scanImage(frameImg) // Result of the scanner
                    Log.i("QR",result.toString())*/
                    /*if (result != 0){
                        /*syms = scanner.results
                        for (sym in syms) {
                            Log.i("QR-data :",sym.data)
                        }*/
                        Log.i("QR","QR detected")
                    }*/
                    totalFrames += 1
                    //frameImg.destroy()
                    //scanner.destroy()

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
        scope.launch(Dispatchers.Main){
            mainView.text = textFrames.plus(totalFrames.toString())
        }
        //return@withContext totalFrames
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.video_player, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        mainView = view.findViewById(R.id.video_analyser) //MainView
        mainView.text = getString(R.string.processing)
        //Added by Miguel 31/08
        //job = Job()
        /*
        val frameG : FrameGrabber = FFmpegFrameGrabber(args.videoname)  //FrameGrabber for the video
        var frame : Frame? // Frame gra bbed.
        var frameMat = Mat() // Frame in Mat format.
        var frameGray = Mat()// Frame converted to Grayscale
        var totalFrames = 0
        var frameImg : Image // Image for zbar QR scanner
        //var result : Int// Results of the QR scanner
        var syms : SymbolSet
        //Configure scanner
        //scanner.setConfig(Symbol.NONE,ENABLE,0) // Disable all codes
        //scanner.setConfig(Symbol.QRCODE,ENABLE,1) //Enable only QR codes

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
                frameGray.release()
                frameMat.release()
                //frameImg.destroy()
                if (frame != null){
                    Log.i("javacv","Frame grabbed")
                    frameMat = converterToMat.convert(frame) //Frame to Mat
                    cvtColor(frameMat,frameGray, COLOR_BGR2GRAY) //To Gray
                    /*Log.i("javacv","Gray frame size: (" + frameMat.size().width().toString()
                    + "," + frameMat.size().height().toString() + ")")*/
                    // Create ZBar Image and set it with Gray image.
                    frameImg = Image(frameGray.size().width(),frameGray.size().height(), "Y800")
                    frameImg.setData(frameGray.data().stringBytes)
                    Log.i("javacv","ImageZbar frame size: (" + frameImg.width.toString()
                            + "," + frameImg.height.toString() + ")")

                    val scanner = ImageScanner()
                    scanner.setConfig(Symbol.NONE,ENABLE,0) // Disable all codes
                    scanner.setConfig(Symbol.QRCODE,ENABLE,1) //Enable only QR codes
                    result = scanner.scanImage(frameImg) // Result of the scanner
                    Log.i("QR",result.toString())
                    /*if (result != 0){
                        /*syms = scanner.results
                        for (sym in syms) {
                            Log.i("QR-data :",sym.data)
                        }*/
                        Log.i("QR","QR detected")
                    }*/
                    totalFrames += 1
                    frameImg.destroy()
                    scanner.destroy()
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

        mainView.text = textFrames.plus(totalFrames.toString())
        */
        //thread { decode(args.videoname,view) }
        mainActivity = requireActivity() as MainActivity
        mainActivity.video = "Test"


        /*val result = runBlocking(Dispatchers.Default){
            decode(args.videoname)
        }*/
        /*val result = scope.async {
            decode(args.videoname)
        }.toString()*/

        /*mainView.text = textFrames.plus(
            scope.async {
            decode(args.videoname)
        }.toString()
        )*/

        scope.async {
            decode(args.videoname)}

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

        //private var result = 0// Results of the QR scanner
        private val converterToMat : OpenCVFrameConverter.ToMat = OpenCVFrameConverter.ToMat()
        private val converterAndroid : AndroidFrameConverter = AndroidFrameConverter()
        //private val converterToBitmap : Java2DFrameConverter
        //private var mainView = mainView = View.findViewById<TextView>(R.id.video_analyser) //MainView
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