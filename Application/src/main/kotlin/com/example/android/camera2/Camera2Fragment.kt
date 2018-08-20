package com.example.android.camera2

import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Bundle
import android.support.v4.app.Fragment
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.example.android.camera2.camera.Camera2
import com.example.android.camera2.image.ImageSaver
import com.example.android.camera2.permission.CameraPermission
import kotlinx.android.synthetic.main.fragment_camera2_basic.*

/**
 * @author  aqrLei on 2018/8/13
 */
class Camera2Fragment : Fragment(), View.OnClickListener, ImageSaver.Callback {
    companion object {
        fun newInstance() = Camera2Fragment()
    }

    private lateinit var mCameraPermission: CameraPermission
    private var mCamera2: Camera2? = null
    private var flashModeCount: Int = 0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_camera2_basic, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        picture.setOnClickListener(this)
        facing.setOnClickListener(this)
        flash.setOnClickListener(this)
        mCameraPermission = CameraPermission(this)
        activity?.let {
            mCamera2 = Camera2(texture, it)
            mCamera2?.setCallback(this)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!mCameraPermission.hasAllPermissionsGranted()) {
            mCameraPermission.requestPermissions()
        } else {
            mCamera2?.start()
        }
    }

    override fun onGetByteArray(byteArray: ByteArray) {

    }
    override fun onSaveCompleted(path: String?) {
        val options = BitmapFactory.Options()
        options.inJustDecodeBounds = true
       BitmapFactory.decodeFile(path,options)
    }

    override fun onPause() {
        mCamera2?.stop()
        super.onPause()
    }


    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == CameraPermission.REQUEST_CAMERA_PERMISSIONS) {
            for (result in grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    mCameraPermission.showMissingPermissionError()
                    return
                }
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }


    override fun onClick(v: View?) {
        when (v?.id) {
            R.id.picture -> {
                mCamera2?.takePicture()
            }
            R.id.facing -> {
               // mCamera2?.startRecordingVideo()
               mCamera2?.switchFacing()
            }
            R.id.flash -> {
                flashModeCount++
                val mode = Camera2.CameraFlashMode.values()[flashModeCount % 3]
                mCamera2?.switchFlash(mode)
            }
        }
    }
}