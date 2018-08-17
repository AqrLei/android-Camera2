package com.example.android.camera2.camera

import android.hardware.camera2.CameraCharacteristics
import android.os.AsyncTask
import android.os.SystemClock
import android.util.Size
import android.util.SparseIntArray
import android.view.Surface
import com.example.android.camera2.image.ImageSaver
import java.text.SimpleDateFormat
import java.util.*
import kotlin.Comparator
import kotlin.collections.ArrayList
import kotlin.math.sign

/**
 * @author  aqrLei on 2018/8/10
 */
object Camera2Utils {
    private const val ASPECT_RATIO_TOLERANCE = 0.005
    private const val PRE_CAPTURE_TIMEOUT_MS = 1000
    val defaultOrientations = SparseIntArray().apply {
        append(Surface.ROTATION_0, 90)
        append(Surface.ROTATION_90, 0)
        append(Surface.ROTATION_180, 270)
        append(Surface.ROTATION_270, 180)
    }
    val inverseOrientations = SparseIntArray().apply {
        append(Surface.ROTATION_0, 270)
        append(Surface.ROTATION_90, 180)
        append(Surface.ROTATION_180, 90)
        append(Surface.ROTATION_270, 0)
    }
    val orientations = SparseIntArray().apply {
        append(Surface.ROTATION_0, 0)
        append(Surface.ROTATION_90, 90)
        append(Surface.ROTATION_180, 180)
        append(Surface.ROTATION_270, 270)
    }

    val comparator = Comparator<Size> { s1, s2 ->
        (s1.width * s1.height - s2.width * s2.height).sign
    }

    private var captureTimer: Long = 0

    fun chooseVideoSize(choices: Array<Size>): Size {
        choices.forEach {
            if (it.width == it.height * 4 / 3) return it
        }
        return choices[choices.size - 1]
    }

    fun contains(modes: IntArray?, mode: Int): Boolean {
        if (modes == null) return false
        for (i in modes) {
            if (i == mode) return true
        }
        return false
    }

    fun handleCompletionLocked(requestId: Int, builder: ImageSaver.ImageSaverBuilder?,
                               queue: TreeMap<Int, ImageSaver.ImageSaverBuilder>) {
        builder?.let {
            val saver = it.buildIfComplete()
            if (saver != null) {
                queue.remove(requestId)
                AsyncTask.THREAD_POOL_EXECUTOR.execute(saver)
            }
        }
    }

    fun generateTimestamp(): String {
        val sdf = SimpleDateFormat("yyyyMMdd_HHmmssSSS", Locale.CHINA)
        return sdf.format(Date())
    }

    fun checkAspectsEqual(a: Size, b: Size): Boolean {
        val aAspect = a.width / a.height.toDouble()
        val bAspect = b.width / b.height.toDouble()
        return Math.abs(aAspect - bAspect) <= ASPECT_RATIO_TOLERANCE
    }

    fun chooseOptimalSize(choices: Array<Size>, viewWidth: Int, viewHeight: Int,
                          maxWidth: Int, maxHeight: Int, aspectRatio: Size): Size {
        val bigEnough = ArrayList<Size>()
        val notBigEnough = ArrayList<Size>()
        val w = aspectRatio.width
        val h = aspectRatio.height
        choices.forEach {
            if (it.width <= maxWidth && it.height <= maxHeight && it.height == it.width * h / w) {
                if (it.width >= viewWidth && it.height >= viewHeight) {
                    bigEnough.add(it)
                } else {
                    notBigEnough.add(it)
                }
            }
        }
        return when {
            bigEnough.size > 0 -> {
                Collections.min(bigEnough, comparator)
            }
            notBigEnough.size > 0 -> {
                Collections.max(notBigEnough, comparator)
            }
            else -> {
                choices[0]
            }
        }
    }

    fun getOrientation(cameraFacing: Int, sensorOrientation: Int, deviceOrientation: Int): Int {
        var tempOrientation = orientations.get(deviceOrientation)
        if (cameraFacing == CameraCharacteristics.LENS_FACING_FRONT) {
            tempOrientation = -tempOrientation
        }
        return (sensorOrientation - tempOrientation + 360) % 360
    }

    fun startTimeLocked() {
        captureTimer = SystemClock.elapsedRealtime()
    }

    fun hitTimeoutLocked(): Boolean {
        return (SystemClock.elapsedRealtime() - captureTimer) > PRE_CAPTURE_TIMEOUT_MS
    }

    fun isLegacyLocked(characteristics: CameraCharacteristics?): Boolean {
        return characteristics?.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ==
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY
    }

}