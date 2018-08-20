package com.example.android.camera2.util

import java.io.File

/**
 * @author aqrlei on 2018/8/20
 */
fun File.existOrCreate(): File {
    if (!exists()){
        mkdirs()
    }
    return this
}