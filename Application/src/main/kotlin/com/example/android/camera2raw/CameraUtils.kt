package com.example.android.camera2raw

import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.AsyncTask
import android.util.Size
import android.util.SparseIntArray
import android.view.Surface
import java.text.SimpleDateFormat
import java.util.*
import kotlin.Comparator
import kotlin.collections.ArrayList
import kotlin.math.sign

/**
 * @author  aqrLei on 2018/8/10
 * @description 针对竖屏
 */
object CameraUtils {

    private const val MIN_PREVIEW_PIXELS = 480 * 320
    private const val ASPECT_RATIO_TOLERANCE = 0.005
    private const val MAX_ASPECT_DISTORTION = 0.1

    val orientations = SparseIntArray().apply {
        append(Surface.ROTATION_0, 0)
        append(Surface.ROTATION_90, 90)
        append(Surface.ROTATION_180, 180)
        append(Surface.ROTATION_270, 270)
    }

    val comparator = Comparator<Size> { s1, s2 ->
        (s1.width * s1.height - s2.width * s2.height).sign
    }

    fun getSuitablePreviewSize(configMap: StreamConfigurationMap, viewHeight: Int, viewWidth: Int): Size {
        val sizes = configMap.getOutputSizes(SurfaceTexture::class.java)

        val viewAspectRatio = viewHeight.toDouble() / viewWidth.toDouble()
        val collectorSizes = ArrayList<Size>()
        run out@{
            sizes.forEach { size ->
                var currentWidth = size.width
                var currentHeight = size.height
                if (currentHeight == viewHeight && currentWidth == viewWidth) {
                    collectorSizes.add(size)
                    return@out
                }
                if (currentHeight * currentWidth < MIN_PREVIEW_PIXELS) {
                    return@forEach
                }
                if (currentWidth < currentHeight) {
                    currentWidth += currentHeight
                    currentHeight = currentWidth - currentHeight
                    currentWidth -= currentHeight
                }
                val currentAspectRatio = currentWidth / currentHeight
                val distortion = Math.abs(currentAspectRatio - viewAspectRatio)
                if (distortion < MAX_ASPECT_DISTORTION) {
                    collectorSizes.add(size)
                }
            }
        }

        return if (collectorSizes.size > 0) {
            Collections.max(collectorSizes, comparator)
        } else {
            sizes[0]
        }
    }

    fun getSuitablePictureSize(configMap: StreamConfigurationMap, screenWidth: Int, screenHeight: Int): Size {
        val screenAspectRatio = screenHeight.toDouble() / screenWidth.toDouble()
        val collectorSizes = ArrayList<Size>()
        val sizes = configMap.getOutputSizes(ImageFormat.JPEG)
        sizes.forEach {
            val currentWidth = maxOf(it.width, it.height)
            val currentHeight = minOf(it.width, it.height)
            val currentAspectRatio = currentWidth / currentHeight
            val distortion = Math.abs(currentAspectRatio - screenAspectRatio)
            if (distortion < MAX_ASPECT_DISTORTION) {
                collectorSizes.add(it)
            }
        }
        return if (collectorSizes.size > 0) {
            Collections.max(collectorSizes, comparator)
        } else sizes[0]
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
        val sdf = SimpleDateFormat("yyyyMMddHH_mmssSSS", Locale.US)
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
        return (sensorOrientation + tempOrientation + 360) % 360
    }
}