package com.example.android.camera2raw

import android.content.Context
import android.util.AttributeSet
import android.view.TextureView

/**
 * @author  aqrLei on 2018/8/14
 */
class AutoFitTextureView @JvmOverloads constructor(context: Context,
                                                   attrs: AttributeSet? = null,
                                                   defStyle: Int = 0) : TextureView(context, attrs, defStyle) {
    private var mRatioWidth = 0
    private var mRatioHeight = 0
    fun setAspectRatio(width: Int, height: Int) {
        if (width < 0 || height < 0) {
            throw IllegalArgumentException("Size cannot be negative")
        }
        if (mRatioWidth == width && mRatioHeight == height) {
            return
        }
        mRatioHeight = height
        mRatioWidth = width
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        if (0 == mRatioHeight || 0 == mRatioWidth) {
            setMeasuredDimension(width, height)
        } else {
            if (width < height * mRatioWidth / mRatioHeight) {
                setMeasuredDimension(width, width * mRatioHeight / mRatioWidth)
            } else {
                setMeasuredDimension(height * mRatioWidth / mRatioHeight, height)
            }
        }
    }
}