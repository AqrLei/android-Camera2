package com.example.android.camera2raw

/**
 * @author  aqrLei on 2018/8/14
 */
class RefCountedAutoCloseable<T : AutoCloseable> : AutoCloseable {

    private var mObject: T? = null
    private var mRefCount: Long = 0L

    constructor(mObject: T) {
        this.mObject = mObject
    }

    @Synchronized
    fun getAndRetain(): T? {
        if (mRefCount < 0) {
            return null
        }
        mRefCount++
        return mObject
    }

    @Synchronized
    fun get(): T? {
        return mObject
    }

    override fun close() {
        if (mRefCount >= 0) {
            mRefCount--
            if (mRefCount < 0) {
                try {
                    mObject?.close()
                } catch (e: Exception) {
                    throw RuntimeException(e)
                } finally {
                    mObject = null
                }
            }
        }
    }
}