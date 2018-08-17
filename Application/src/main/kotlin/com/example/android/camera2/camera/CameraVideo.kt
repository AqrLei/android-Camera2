package com.example.android.camera2.camera

import android.app.Activity
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.MediaRecorder
import android.util.Size
import android.view.Surface
import com.example.android.camera2.AutoFitTextureView

/**
 * @author  aqrLei on 2018/8/17
 */
class CameraVideo(textureView: AutoFitTextureView, activity: Activity) :
        CameraImpl(textureView, activity) {

    private var mMediaRecorder: MediaRecorder? = null
    override fun getSurfaceList(): List<Surface> {
        mMediaRecorder = MediaRecorder()
        return emptyList()
    }

    override fun configureCaptureRequest(builder: CaptureRequest.Builder) {
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
    }

    override fun setCameraTypeOutputs(map: StreamConfigurationMap) {}

    override fun getOutputSize(map: StreamConfigurationMap): Size {
        return Camera2Utils.chooseVideoSize(map.getOutputSizes(MediaRecorder::class.java))
    }
}