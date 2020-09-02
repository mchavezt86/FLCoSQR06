package com.example.flcosqr04.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.content.DialogInterface
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.navigation.Navigation
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.android.camera.utils.GenericListAdapter
import com.example.flcosqr04.MainActivity
import com.example.flcosqr04.R
import kotlinx.coroutines.delay

/**
 * In this [Fragment] we let users pick a camera, size and FPS to use for high
 * speed video recording
 */

class SelectorFragment : Fragment() {

    //private lateinit var mainActivity : MainActivity //Added by Miguel 28/08

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = RecyclerView(requireContext())

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        //Added by Miguel 28/08
        //mainActivity = requireActivity() as MainActivity
        //Log.i("mact","Video string is: " + mainActivity.video)
        view as RecyclerView
        //Added by Miguel 28/08
        /*if (mainActivity.video.compareTo("Test")!=0){ //Added by Miguel 28/08. May change to check if empty
            Navigation.findNavController(requireActivity(),R.id.fragment_container)
                .navigate(SelectorFragmentDirections.actionSelectorToDecoder(mainActivity.video))
        }*/
        view.apply {
            layoutManager = LinearLayoutManager(requireContext())

            val cameraManager =
                requireContext().getSystemService(Context.CAMERA_SERVICE) as CameraManager

            val cameraList = enumerateHighSpeedCameras(cameraManager)

            val layoutId = android.R.layout.simple_list_item_1
            adapter = GenericListAdapter(cameraList, itemLayoutId = layoutId) { view, item, _ ->
                view.findViewById<TextView>(android.R.id.text1).text = item.title
                view.setOnClickListener {
                    Navigation.findNavController(requireActivity(), R.id.fragment_container)
                        .navigate(SelectorFragmentDirections.actionSelectorToCamera(
                            item.cameraId, item.size.width, item.size.height, item.fps,
                            item.zoom, item.aeLow))//Modified by Miguel 02/09
                }
            }
        }
    }

    companion object {

        private data class CameraInfo(
            val title: String,
            val cameraId: String,
            val size: Size,
            val fps: Int,
            val zoom: Rect, //Added by Miguel
            val aeLow: Int) //Added by Miguel 02/09

        /** Converts a lens orientation enum into a human-readable string */
        private fun lensOrientationString(value: Int) = when(value) {
            CameraCharacteristics.LENS_FACING_BACK -> "Back"
            CameraCharacteristics.LENS_FACING_FRONT -> "Front"
            CameraCharacteristics.LENS_FACING_EXTERNAL -> "External"
            else -> "Unknown"
        }

        /** Lists all high speed cameras and supported resolution and FPS combinations */
        @SuppressLint("InlinedApi")
        private fun enumerateHighSpeedCameras(cameraManager: CameraManager): List<CameraInfo> {
            val availableCameras: MutableList<CameraInfo> = mutableListOf()
            var zoom : Rect

            // Iterate over the list of cameras and add those with high speed video recording
            //  capability to our output. This function only returns those cameras that declare
            //  constrained high speed video recording, but some cameras may be capable of doing
            //  unconstrained video recording with high enough FPS for some use cases and they will
            //  not necessarily declare constrained high speed video capability.
            cameraManager.cameraIdList.forEach { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val orientation = lensOrientationString(
                    characteristics.get(CameraCharacteristics.LENS_FACING)!!)

                // Query the available capabilities and output formats
                val capabilities = characteristics.get(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)!!
                val cameraConfig = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!

                //Changed by Miguel 17-08-2020
                val w : Int
                val h : Int

                //Added by Mkiguel 02/09
                //val ae : Int
                val aeRange : Range<Int>

                // Return cameras that support constrained high video capability
                if (capabilities.contains(
                        CameraCharacteristics
                        .REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO)) {
                    //Changed by Miguel 17-08-2020
                    //Create a rectangle to get a 4x Zoom, compatible with API 21 onwards.
                    //API 30 has a different method for applying Zoom.
                    w = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)!!.
                    width()
                    h = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)!!.
                    height()
                    zoom = Rect(w*3/8,h*3/8,w*5/8,h*5/8)
                    //
                    //Added by Miguel 02/09
                    aeRange = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)!!
                    // For each camera, list its compatible sizes and FPS ranges
                    cameraConfig.highSpeedVideoSizes.forEach { size ->
                        cameraConfig.getHighSpeedVideoFpsRangesFor(size).forEach { fpsRange ->
                            val fps = fpsRange.upper
                            val info = CameraInfo(
                                "$orientation ($id) $size $fps FPS", id, size, fps, zoom,
                                    aeRange.lower)

                            // Only report the highest FPS in the range, avoid duplicates
                            //if (!availableCameras.contains(info)) availableCameras.add(info)
                            //Changed by Miguel 17-08-2020
                            if (!availableCameras.contains(info)) {
                                availableCameras.add(info)
                                /*if (characteristics.get(CameraCharacteristics.
                                    SENSOR_INFO_ACTIVE_ARRAY_SIZE)!=null) {
                                        w = characteristics.get(CameraCharacteristics.
                                        SENSOR_INFO_ACTIVE_ARRAY_SIZE)!!.width()
                                        h = characteristics.get(CameraCharacteristics.
                                        SENSOR_INFO_ACTIVE_ARRAY_SIZE)!!.height()
                                } else {
                                    w = 0
                                    h = 0
                                }*/
                                Log.i("mact / Camera width:", w.toString() )
                                Log.i("mact / Camera height:", h.toString() )

                                //zoom = Rect(w*3/8,h*3/8,w*5/8,h*5/8)
                                Log.i("mact / Camera zoom:", zoom.toString() )
                                Log.i("mact", "Camera AE Range min: ${aeRange.lower}")
                            }
                        }
                    }
                }

            }

            return availableCameras
        }
    }
}