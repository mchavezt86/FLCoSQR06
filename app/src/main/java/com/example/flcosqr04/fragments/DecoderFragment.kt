package com.example.flcosqr04.fragments

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.NavController
import androidx.navigation.Navigation
import androidx.navigation.fragment.navArgs
import com.example.flcosqr04.R
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
import org.bytedeco.opencv.opencv_core.*
import org.bytedeco.opencv.opencv_imgproc.*
import org.bytedeco.opencv.global.opencv_core.*
import org.bytedeco.opencv.global.opencv_imgproc.*
import org.bytedeco.ffmpeg.avformat.*
import org.bytedeco.ffmpeg.global.avformat.*

import net.sourceforge.zbar.Config.*

class DecoderFragment : Fragment() {

    /** AndroidX navigation arguments */
    private val args: DecoderFragmentArgs by navArgs()
    private lateinit var mainView : TextView
    private var textFrames = "Number of frames: "
    private lateinit var frameImg : Image
    private val converterToMat : OpenCVFrameConverter.ToMat = OpenCVFrameConverter.ToMat()
    //private var scanner = ImageScanner()

    private var result = 0// Results of the QR scanner

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.video_player, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        mainView = view.findViewById<TextView>(R.id.video_analyser) //MainView
        mainView.text = getString(R.string.processing)
        val frameG : FrameGrabber = FFmpegFrameGrabber(args.videoname)  //FrameGrabber for the video
        var frame : Frame? // Frame grabbed.
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
                    frameImg = Image(frameGray.size().width(),frameGray.size().height(), "GREY")
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
    }

}