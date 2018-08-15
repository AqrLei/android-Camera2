package com.example.android.camera2raw.camera2

import android.os.Handler
import android.os.HandlerThread
import android.support.annotation.CallSuper

/**
 * @author  aqrLei on 2018/8/15
 */
abstract class CameraImpl {

    protected val stateClock = Any()
    protected var mBackgroundThread: HandlerThread? = null
    protected var mBackgroundHandler: Handler? = null

    private fun startBackgroundThread() {
        mBackgroundThread = HandlerThread("CameraBackground")
        mBackgroundThread?.start()
        synchronized(stateClock) {
            mBackgroundHandler = Handler(mBackgroundThread?.looper)
        }

    }

    @CallSuper
    protected fun open() {
        startBackgroundThread()
    }

    abstract fun switchFacing()

    abstract fun takePicture()

    abstract fun release()

    abstract fun isLegacyLocked(): Boolean

    @CallSuper
    protected fun close() {
        stopBackgroundThread()
    }

    private fun stopBackgroundThread() {
        mBackgroundThread?.quitSafely()
        try {
            mBackgroundThread?.join()
            mBackgroundThread = null
            synchronized(stateClock) {
                mBackgroundHandler = null
            }

        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }


}