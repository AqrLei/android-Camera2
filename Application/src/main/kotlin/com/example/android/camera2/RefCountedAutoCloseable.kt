package com.example.android.camera2

/**
 * @author  aqrLei on 2018/8/14
 */
class RefCountedAutoCloseable<T : AutoCloseable>(mObject: T) : AutoCloseable {

    private var mObject: T? = mObject
    /**引用计数*/
    private var mRefCount: Long = 0L

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
        /**每次引用结束都要调用close,如此才会在最终close时释放掉[mObject]*/
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