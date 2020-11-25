package com.example.flcosqr04

import android.graphics.Bitmap
import android.media.Image
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.PixelCopy
import com.example.android.camera.utils.AutoFitSurfaceView
import org.bytedeco.opencv.global.opencv_core.CV_8UC1
import org.bytedeco.opencv.global.opencv_imgproc.*
import org.bytedeco.opencv.opencv_core.Mat
import org.bytedeco.opencv.opencv_core.MatVector
import org.bytedeco.opencv.opencv_core.Rect
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer
import org.bytedeco.javacv.AndroidFrameConverter
import org.bytedeco.javacv.OpenCVFrameConverter

//Sizes for Reed Solomon Encoder
private const val RS_DATA_SIZE = 130
private const val RS_PARITY_SIZE = 45
private const val RS_TOTAL_SIZE = 175
//Number of bytes in a QR code, version 1: 17 bytes
private const val QR_BYTES = 17

class ImageProcess {

    companion object {
        /**Function which gets an Image from the ImageReader and returns it in the (Bytedeco) Mat format
         * for further processing.
         * Input: Image (from ImageReader)
         * Output: Mat (Bytedeco)*/
        @JvmStatic
        fun imageToMat(img : Image) : Mat {
            /* This function may be improved (image formats and padding may cause an issue) but this
            * implementation does not take that into account. The post which address that issue is in
            * StackOverflow:
            * https://stackoverflow.com/questions/52726002/camera2-captured-picture-conversion-from-yuv-420-888-to-nv21/52740776#52740776
            * But it is also stated that is not an issue for preview (which I understand as the
            * ImageReader).
            * [Keep in mind for future improvement]*/

            val nv21 : ByteArray
            val yBuffer: ByteBuffer = img.planes[0].buffer
            val uBuffer: ByteBuffer = img.planes[1].buffer
            val vBuffer: ByteBuffer = img.planes[2].buffer

            val ySize: Int = yBuffer.remaining()
            val uSize: Int = uBuffer.remaining()
            val vSize: Int = vBuffer.remaining()

            nv21 = ByteArray(ySize + uSize + vSize)

            yBuffer.get(nv21, 0, ySize)
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)

            return getYUV2Mat(nv21, img)
        }

        /** Convert YUV image to the OpenCV Mat format */
        private fun getYUV2Mat(data: ByteArray?, image: Image): Mat {
            val mYuv = Mat(image.height + image.height / 2, image.width, CV_8UC1)
            /*Bytedeco Mat does not implement a put() function with the parameters ByteArray, column,
            * and row. Instead, try to use Bytedeco Mat.data().put(ByteArray, offset, length)*/
            mYuv.data().put(data,0, data!!.size)
            val mRGB = Mat()
            cvtColor(mYuv, mRGB, Imgproc.COLOR_YUV2RGB_NV21, 3)
            return mRGB
        }

        /**Function to detect the active area of the FLC, as it reflects light it is brighter than the
         * 3D printed holder. Use of OpenCV contour detection and physical dimensions of the FLC. Main
         * logic: detect a rectangle that is not too big or too small and have an aspect ration smaller
         * than 16/9 or 4/3
         * Input: Mat
         * Output: Rect (if the area is detected) or null if nothing is detected.*/
        fun detect(img : Image) : Rect? {
            val mat = imageToMat(img)
            cvtColor(mat,mat, COLOR_BGR2GRAY) //Convert to GrayScale
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
            adaptiveThreshold(mat,bin,255.0,ADAPTIVE_THRESH_GAUSSIAN_C,THRESH_BINARY,binSize,0.0)
            findContours(bin,contours,Mat(),RETR_LIST,CHAIN_APPROX_NONE) //Mat() is for hierarchy [RETR_EXTERNAL]

            //Variable initialisation for the detected contour initialisation.
            var cnt : Mat
            var points : Mat
            var rect : Rect
            var aspect: Double
            //Final variable to return
            var finalRect : Rect? = null

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
                            finalRect = rect
                            Log.i("FLC","w=${finalRect.width()},h=${finalRect.height()}," +
                                    "x=${finalRect.x()},y=${finalRect.y()}")
                            break // break 'contours' for loop.
                        }
                    }
                }
            }
            return finalRect
        }

        /**Function to detect the active area of the FLC, as it reflects light it is brighter than the
         * 3D printed holder. Use of OpenCV contour detection and physical dimensions of the FLC. Main
         * logic: detect a rectangle that is not too big or too small and have an aspect ration smaller
         * than 16/9 or 4/3
         * Input: Bitmap
         * Output: Rect (if the area is detected) or null if nothing is detected.*/
        fun detectROI(bmp : Bitmap?) : Rect? {
            //Log.i("detectROI","I was called.")
            val frameConvert = AndroidFrameConverter()
            val matConvert = OpenCVFrameConverter.ToMat()
            val mat = matConvert.convertToMat(frameConvert.convert(bmp))
            cvtColor(mat,mat, COLOR_BGR2GRAY) //Convert to GrayScale
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
            adaptiveThreshold(mat,bin,255.0,ADAPTIVE_THRESH_GAUSSIAN_C,THRESH_BINARY,binSize,0.0)
            findContours(bin,contours,Mat(),RETR_LIST,CHAIN_APPROX_NONE) //Mat() is for hierarchy [RETR_EXTERNAL]

            //Variable initialisation for the detected contour initialisation.
            var cnt : Mat
            var points : Mat
            var rect : Rect
            var aspect: Double
            //Final variable to return
            var finalRect : Rect? = null

            /*Main for loop allows iteration. Contours.get(index) returns a Mat which holds the contour
            * points.*/
            loop@ for (i in 0 until contours.size()){
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
                            finalRect = rect
                            Log.i("FLC","w=${finalRect.width()},h=${finalRect.height()}," +
                                    "x=${finalRect.x()},y=${finalRect.y()}")
                            break@loop // break 'contours' for loop.
                        }
                    }
                }
            }
            return finalRect
        }

        /**Function to:
         * Pixel copy to copy SurfaceView/VideoView into BitMap
         * Work with Surface View, Video View
         * Won't work on Normal View
         * Modified from:
         * https://medium.com/@hiteshkrsahu/a-complete-guide-for-taking-screenshot-in-android-28-bcb9a19a2b6e
         * Input: AutoFitSurfaceView (preview from the camera), Bitmap (same size as the camera
         * resolution, thus same size as resulting Mat from it.
         * Output: None
         * Callback: Do something with the Bitmap.
         */
        fun getBitMapFromSurfaceView(videoView: AutoFitSurfaceView, bitmap: Bitmap, callback: (Bitmap?) -> Unit) {
            /*val bitmap: Bitmap = Bitmap.createBitmap(
                videoView.width,
                videoView.height,
                Bitmap.Config.ARGB_8888
            );*/
            try {
                // Create a handler thread to offload the processing of the image.
                val handlerThread = HandlerThread("PixelCopier");
                handlerThread.start();
                PixelCopy.request(
                    videoView.holder.surface, bitmap,
                    { copyResult ->
                        if (copyResult == PixelCopy.SUCCESS) {
                            callback(bitmap)
                        }
                        handlerThread.quitSafely();
                    },
                    Handler(handlerThread.looper)
                )
            } catch (e: IllegalArgumentException) {
                callback(null)
                // PixelCopy may throw IllegalArgumentException, make sure to handle it
                e.printStackTrace()
            }
        }
    }
}