package com.example.android.camera2raw

import android.content.Context
import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.media.MediaScannerConnection
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * @author  aqrLei on 2018/8/13
 */
class ImageSaver private constructor(
        private val image: Image,
        private val file: File,
        private val context: Context,
        private val reader: ImageReader) : Runnable {

    override fun run() {
        var success = false
        val format = image.format
        when (format) {
            ImageFormat.JPEG -> {
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                var output: FileOutputStream? = null
                try {
                    output = FileOutputStream(file)
                    output.write(bytes)
                    success = true

                } catch (e: IOException) {
                    e.printStackTrace()
                } finally {
                    image.close()
                    closeOutput(output)

                }
            }
            else -> {

            }
        }
        reader.close()
        if (success) {
            MediaScannerConnection.scanFile(context, arrayOf(file.path), null,
                    object : MediaScannerConnection.MediaScannerConnectionClient {
                        override fun onMediaScannerConnected() {
                            // Do nothing
                        }

                        override fun onScanCompleted(path: String?, uri: Uri?) {
                            Log.d("Camera", "Scanned $path :")
                            Log.d("Camera", "-> uri=$uri")
                        }
                    })

        }
    }

    private fun closeOutput(output: FileOutputStream?) {
        output?.let {
            try {
                it.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    class ImageSaverBuilder(private val mContext: Context) {
        private var mImage: Image? = null
        private var mFile: File? = null
        private lateinit var mReader: ImageReader

        val saveLocation: String
            @Synchronized get() = if (mFile == null) "Unknown" else mFile!!.toString()

        private val isComplete: Boolean
            get() = (mImage != null && mFile != null)

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
        fun buildIfComplete(): ImageSaver? {
            return if (!isComplete) {
                null
            } else ImageSaver(mImage!!, mFile!!, mContext,
                    mReader)
        }
    }
}