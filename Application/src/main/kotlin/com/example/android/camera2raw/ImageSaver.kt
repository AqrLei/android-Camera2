package com.example.android.camera2raw

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.media.Image
import android.media.ImageReader
import java.io.File

/**
 * @author  aqrLei on 2018/8/13
 */
class ImageSaver private constructor(
        image: Image, file: File, reuslt: CaptureResult,
        characteristics: CameraCharacteristics, context: Context,
        reader: ImageReader) : Runnable {

    override fun run() {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    class ImageSaverBuilder(private val mContext: Context) {
        private var mImage: Image? = null
        private var mFile: File? = null
        private var mCaptureResult: CaptureResult? = null
        private var mCharacteristics: CameraCharacteristics? = null
        private lateinit var mReader: ImageReader

        val saveLocation: String
            @Synchronized get() = if (mFile == null) "Unknown" else mFile!!.toString()

        private val isComplete: Boolean
            get() = (mImage != null && mFile != null && mCaptureResult != null
                    && mCharacteristics != null)

        @Synchronized
        fun setRefCountedReader(
                reader: ImageReader): ImageSaverBuilder {
            mReader = reader
            return this
        }

        @Synchronized
        fun setImage(image: Image): ImageSaverBuilder {
            mImage = image
            return this
        }

        @Synchronized
        fun setFile(file: File): ImageSaverBuilder {
            mFile = file
            return this
        }

        @Synchronized
        fun setResult(result: CaptureResult): ImageSaverBuilder {
            mCaptureResult = result
            return this
        }

        @Synchronized
        fun setCharacteristics(
                characteristics: CameraCharacteristics?): ImageSaverBuilder {
            if (characteristics == null) throw NullPointerException()
            mCharacteristics = characteristics
            return this
        }

        @Synchronized
        fun buildIfComplete(): ImageSaver? {
            return if (!isComplete) {
                null
            } else ImageSaver(mImage!!, mFile!!, mCaptureResult!!, mCharacteristics!!, mContext,
                    mReader)
        }
    }
}