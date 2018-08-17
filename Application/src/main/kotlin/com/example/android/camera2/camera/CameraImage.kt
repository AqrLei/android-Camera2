package com.example.android.camera2.camera

import android.app.Activity
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.ImageReader
import android.os.Environment
import android.util.Size
import android.view.Surface
import com.example.android.camera2.AutoFitTextureView
import com.example.android.camera2.RefCountedAutoCloseable
import com.example.android.camera2.image.ImageSaver
import java.io.File
import java.util.*

/**
 * @author  aqrLei on 2018/8/17
 */
class CameraImage(textureView: AutoFitTextureView, activity: Activity)
    : CameraImpl(textureView, activity) {

    private var mPendingUserCaptures: Int = 0
    private var mNoAFRun: Boolean = false
    private var cameraFlashMode = CameraFlashMode.FLASH_OFF
    private var mJpegImageReader: RefCountedAutoCloseable<ImageReader>? = null
    private val mJpegResultQueue = TreeMap<Int, ImageSaver.ImageSaverBuilder>()
    private val mOnJpegImageAvailableListener = ImageReader.OnImageAvailableListener {
        //   dequeueAndSaveImage(mJpegResultQueue, mJpegImageReader)
    }
    private val mCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureStarted(session: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
            val currentDateTime = Camera2Utils.generateTimestamp()
            val jpegFile = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                    "JPEG_$currentDateTime.jpg")
            val requestId = request.tag as Int
            var jpegBuilder: ImageSaver.ImageSaverBuilder? = null
            synchronized(mCameraStateLock) {
                jpegBuilder = mJpegResultQueue[requestId]
            }
            jpegBuilder?.setFile(jpegFile)
        }

        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val requestId = request.tag as Int
            val sb = StringBuilder()
            var jpegBuilder: ImageSaver.ImageSaverBuilder?
            synchronized(mCameraStateLock) {
                jpegBuilder = mJpegResultQueue[requestId]
                jpegBuilder?.let {
                    sb.append("Saving JPEG as: ")
                    sb.append(it.saveLocation)
                }
                Camera2Utils.handleCompletionLocked(requestId, jpegBuilder, mJpegResultQueue)
                //  finishedCaptureLocked()

            }
        }

        override fun onCaptureFailed(session: CameraCaptureSession?, request: CaptureRequest, failure: CaptureFailure?) {
            val requestId = request.tag as Int
            synchronized(mCameraStateLock) {
                mJpegResultQueue.remove(requestId)
                //     finishedCaptureLocked()
            }
        }

    }

    override fun setCameraTypeOutputs(map: StreamConfigurationMap) {
        val largestJpeg = getOutputSize(map)
        if (mJpegImageReader?.getAndRetain() == null) {
            mJpegImageReader = RefCountedAutoCloseable(ImageReader.newInstance(largestJpeg.width,
                    largestJpeg.height, ImageFormat.JPEG, 5))
        }
        mJpegImageReader?.get()?.setOnImageAvailableListener(
                mOnJpegImageAvailableListener,
                mBackgroundHandler)

    }

    override fun getSurfaceList(): List<Surface> {
        val surface = mJpegImageReader?.get()?.surface
        return if (surface == null) emptyList() else listOf(surface)
    }

    override fun configureCaptureRequest(builder: CaptureRequest.Builder) {
        /**overall of 3A mode*/
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)

        val minFocusDist = mCharacteristics?.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
        /**If the lens is fixed-focus, this will be true*/
        mNoAFRun = (minFocusDist == null || minFocusDist == 0F)
        if (!mNoAFRun) {
            if (Camera2Utils.contains(mCharacteristics?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES),
                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            } else {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            }
        }

       // configureAE(builder)
        val flashMode = when (cameraFlashMode) {
            CameraFlashMode.FLASH_AUTO -> {
                CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
            }
            CameraFlashMode.FLASH_OFF -> {
                CaptureRequest.FLASH_MODE_OFF

            }
            CameraFlashMode.FLASH_ON -> {
                CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH
            }
        }
        if (Camera2Utils.contains(mCharacteristics?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES),
                        flashMode)) {
            if (flashMode == CaptureRequest.FLASH_MODE_OFF) {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }
            builder.set(CaptureRequest.CONTROL_AE_MODE, flashMode)
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON)
        }

        /*auto-white-balance*/
        if (Camera2Utils.contains(mCharacteristics?.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES),
                        CaptureRequest.CONTROL_AWB_MODE_AUTO)) {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        }    }


    override fun getOutputSize(map: StreamConfigurationMap): Size {
        return Collections.max(map.getOutputSizes(ImageFormat.JPEG).toList(), Camera2Utils.comparator)
    }


    enum class CameraFlashMode {
        FLASH_OFF, FLASH_ON, FLASH_AUTO
    }
}