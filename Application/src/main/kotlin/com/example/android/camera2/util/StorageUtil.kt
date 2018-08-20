package com.example.android.camera2.util

import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * @author aqrlei on 2018/8/20
 */

object StorageUtil {

    fun getStorageFile(type: FileType): File {
        val stamp = generateTimestamp()
        return when (type) {
            FileType.PICTURE -> {
                val file = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                        .existOrCreate()
                File(file, "_$stamp.jpg")

            }
            FileType.VIDEO -> {
                val file = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                        .existOrCreate()
                File(file, "_$stamp.mp4")
            }
        }
    }

    private fun generateTimestamp(): String {
        val sdf = SimpleDateFormat("yyyyMMdd_HHmmssSSS", Locale.CHINA)
        return sdf.format(Date())
    }

    enum class FileType {
        PICTURE, VIDEO
    }
}