package com.example.android.camera2.camera

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Matrix
import android.graphics.Point
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.SensorManager
import android.hardware.camera2.*
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Handler
import android.os.HandlerThread
import android.support.annotation.CallSuper
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import com.example.android.camera2.AutoFitTextureView
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.launch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * @author  aqrLei on 2018/8/17
 */
abstract class CameraImpl(protected val textureView: AutoFitTextureView,
                          private val activity: Activity) {
    companion object {
        private const val MAX_PREVIEW_WIDTH = 1920
        private const val MAX_PREVIEW_HEIGHT = 1080
        private const val STATE_CLOSED = 0
        private const val STATE_OPENED = 1
        private const val STATE_PREVIEW = 2
    }

    private var mCallback: CameraImpl.Callback? = null

    protected val mCameraStateLock = Any()
    private val mCameraOpenCloseLock = Semaphore(1)
    private var mState: Int = STATE_CLOSED


    private var mBackgroundThread: HandlerThread? = null
    protected var mBackgroundHandler: Handler? = null


    private var mCameraFacing = CameraFacing.CAMERA_FACING_BACK
    private var mCameraDevice: CameraDevice? = null
    private val mStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice?) {
            synchronized(mCameraStateLock) {
                mState = STATE_OPENED
                mCameraOpenCloseLock.release()
                mCameraDevice = camera
                if (mPreviewSize != null && textureView.isAvailable) {
                    createCameraPreviewSessionLocked()
                }
            }
        }

        override fun onDisconnected(camera: CameraDevice?) {
            synchronized(mCameraStateLock) {
                mState = STATE_CLOSED
                mCameraOpenCloseLock.release()
                camera?.close()
                mCameraDevice = null
            }
        }

        override fun onError(camera: CameraDevice?, error: Int) {
            synchronized(mCameraStateLock) {
                mState = STATE_CLOSED
                mCameraOpenCloseLock.release()
                camera?.close()
                mCameraDevice = null
            }
            activity.finish()
        }
    }

    protected var mCharacteristics: CameraCharacteristics? = null
    private var mCaptureSession: CameraCaptureSession? = null


    private var mOutputSize: Size? = null
    private var mSensorOrientation: Int = 0


    private var mPreviewSize: Size? = null
    private var mCaptureRequestBuilder: CaptureRequest.Builder? = null
    private val mSurfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture?, width: Int, height: Int) {
            configureTransform(width, height)
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture?, width: Int, height: Int) {
            configureTransform(width, height)
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture?): Boolean {
            synchronized(mCameraStateLock) {
                mPreviewSize = null
            }
            return true
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture?) {}
    }
    private val mPreCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        private fun process(result: CaptureResult) {

        }

        override fun onCaptureProgressed(session: CameraCaptureSession?, request: CaptureRequest?, partialResult: CaptureResult) {
            process(partialResult)
        }

        override fun onCaptureCompleted(session: CameraCaptureSession?, request: CaptureRequest?, result: TotalCaptureResult) {
            process(result)
        }
    }

    private var mOrientationListener: OrientationEventListener? = null

    private fun startBackgroundThread() {
        mBackgroundThread = HandlerThread("CameraBackground")
        mBackgroundThread?.start()
        synchronized(mCameraStateLock) {
            mBackgroundHandler = Handler(mBackgroundThread?.looper)
        }

    }


    @SuppressLint("ClickableViewAccessibility")
    fun start() {
        startBackgroundThread()
        openCamera()
        mOrientationListener = object : OrientationEventListener(activity, SensorManager.SENSOR_DELAY_NORMAL) {
            override fun onOrientationChanged(orientation: Int) {
                textureView.let {
                    if (it.isAvailable) {
                        configureTransform(textureView.width, textureView.height)
                    }
                }
            }
        }
    }

    private fun openCamera() {
        /**
         * CaptureSession的释放需要一定时间，此处需要加一个线程锁，在之前的CameraDevice相关的东西释放后，才能
         * 切换摄像头，重新打开
         * */
        synchronized(mCameraStateLock) {
            configureOpen()
            if (textureView.isAvailable) {
                configureTransform(textureView.width, textureView.height)
            } else {
                textureView.surfaceTextureListener = mSurfaceTextureListener
            }
            mOrientationListener?.let {
                if (it.canDetectOrientation()) it.enable()
            }
        }
    }

    abstract fun setCameraTypeOutputs(map: StreamConfigurationMap)
    @SuppressLint("MissingPermission")
    private fun configureOpen() {
        if (!setUpCameraOutputs()) {
            return
        }
        val manager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            if (!mCameraOpenCloseLock.tryAcquire(2500L, TimeUnit.MILLISECONDS)) {
                throw  RuntimeException("Time out waiting to lock camera opening")
            }
            synchronized(mCameraStateLock) {
                manager.openCamera(mCameraFacing.facing, mStateCallback, mBackgroundHandler)
            }
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        } catch (e: InterruptedException) {
            throw RuntimeException(e.message)
        }

    }

    private fun setUpCameraOutputs(): Boolean {
        val manager = activity.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        if (manager == null) {
            launch(UI) {
                Toast.makeText(activity, "This device doesn't support Camera2 API.", Toast.LENGTH_SHORT).show()
            }
            return false
        }
        try {
            return if (manager.cameraIdList.isNotEmpty() && manager.cameraIdList.size > 1) {
                synchronized(mCameraStateLock) {
                    val characteristics = manager.getCameraCharacteristics(mCameraFacing.facing)

                    val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    setCameraTypeOutputs(map)
                    mCharacteristics = characteristics
                }
                true
            } else {
                false
            }
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
        return false

    }

    abstract fun getSurfaceList(): List<Surface>
    abstract fun configureCaptureRequest(builder: CaptureRequest.Builder)
    private fun createCameraPreviewSessionLocked() {
        try {
            val surfaceTexture = textureView.surfaceTexture
            mPreviewSize?.let {
                surfaceTexture.setDefaultBufferSize(it.width, it.height)
                val surface = Surface(surfaceTexture)
                val surfaceList = arrayListOf(surface).apply {
                    addAll(getSurfaceList())
                }
                if (mCameraDevice != null) {
                    mCaptureRequestBuilder = mCameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                    mCaptureRequestBuilder?.addTarget(surface)

                    mCameraDevice!!.createCaptureSession(
                            surfaceList,
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    synchronized(mCameraStateLock) {
                                        if (null == mCameraDevice) {
                                            return
                                        }
                                        try {

                                            configureCaptureRequest(mCaptureRequestBuilder!!)
                                            session.setRepeatingRequest(
                                                    mCaptureRequestBuilder?.build(),
                                                    mPreCaptureCallback, mBackgroundHandler)
                                            mState = STATE_PREVIEW
                                        } catch (e: CameraAccessException) {
                                            e.printStackTrace()
                                            return
                                        } catch (e: InterruptedException) {
                                            e.printStackTrace()
                                            return
                                        }
                                        mCaptureSession = session
                                    }
                                }

                                override fun onConfigureFailed(session: CameraCaptureSession) {}
                            }, mBackgroundHandler)
                }
            }
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }


    abstract fun getOutputSize(map: StreamConfigurationMap): Size
    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        synchronized(mCameraStateLock) {
            mCharacteristics?.let {
                val map = it.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)


                mOutputSize = getOutputSize(map)

                val deviceRotation = activity.windowManager.defaultDisplay.rotation
                val displaySize = Point()
                activity.windowManager.defaultDisplay.getSize(displaySize)
                val sensorOrientation = it.get(CameraCharacteristics.SENSOR_ORIENTATION)

                mSensorOrientation = sensorOrientation

                val facing = it.get(CameraCharacteristics.LENS_FACING)
                val totalRotation = Camera2Utils.getOrientation(facing, sensorOrientation, deviceRotation)
                val swappedDimensions = (totalRotation == 90 || totalRotation == 270)
                var rotatedViewWidth = viewWidth
                var rotatedViewHeight = viewHeight
                var maxPreviewWidth = displaySize.x
                var maxPreviewHeight = displaySize.y

                if (swappedDimensions) {
                    rotatedViewHeight = viewWidth
                    rotatedViewWidth = viewHeight
                    maxPreviewHeight = displaySize.x
                    maxPreviewWidth = displaySize.y
                }
                maxPreviewHeight = Math.min(maxPreviewHeight, MAX_PREVIEW_HEIGHT)
                maxPreviewWidth = Math.min(maxPreviewWidth, MAX_PREVIEW_WIDTH)

                val previewSize = Camera2Utils.chooseOptimalSize(
                        map.getOutputSizes(SurfaceTexture::class.java),
                        rotatedViewWidth, rotatedViewHeight, maxPreviewWidth, maxPreviewHeight, mOutputSize!!)

                if (swappedDimensions) {
                    textureView.setAspectRatio(previewSize.height, previewSize.width)
                } else {
                    textureView.setAspectRatio(previewSize.width, previewSize.height)
                }
                val rotation =
                        if (it.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT)
                            (360 + Camera2Utils.orientations.get(deviceRotation)) % 360F
                        else
                            (360 - Camera2Utils.orientations.get(deviceRotation)) % 360F

                val matrix = Matrix()
                val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
                val bufferRect = RectF(0f, 0f, previewSize.height.toFloat(), previewSize.width.toFloat())
                val centerX = viewRect.centerX()
                val centerY = viewRect.centerY()
                if (Surface.ROTATION_90 == deviceRotation || Surface.ROTATION_270 == deviceRotation) {
                    bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
                    matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
                    val scale = Math.max(
                            viewHeight.toFloat() / previewSize.height,
                            viewWidth.toFloat() / previewSize.width)
                    matrix.postScale(scale, scale, centerX, centerY)
                }
                matrix.postRotate(rotation, centerX, centerY)
                textureView.setTransform(matrix)
                if (mPreviewSize == null || !Camera2Utils.checkAspectsEqual(previewSize, mPreviewSize!!)) {
                    mPreviewSize = previewSize
                    if (mState != STATE_CLOSED) {
                        createCameraPreviewSessionLocked()
                    }
                }
            }
        }
    }

    @CallSuper
    protected fun release() {
        mOrientationListener?.disable()
        try {
            mCameraOpenCloseLock.acquire()
            synchronized(mCameraStateLock) {
                mState = STATE_CLOSED
                if (null != mCaptureSession) {
                    mCaptureSession?.close()
                    mCaptureSession = null
                }
                if (null != mCameraDevice) {
                    mCameraDevice?.close()
                    mCameraDevice = null
                }
            }
        } catch (e: InterruptedException) {
            throw RuntimeException(e.message)
        } finally {
            mCameraOpenCloseLock.release()
        }

    }

    fun setCallback(callBack: CameraImpl.Callback) {
        mCallback = callBack
    }

    fun stop() {
        release()
        stopBackgroundThread()
    }

    private fun stopBackgroundThread() {
        mBackgroundThread?.quitSafely()
        try {
            mBackgroundThread?.join()
            mBackgroundThread = null
            synchronized(mCameraStateLock) {
                mBackgroundHandler = null
            }

        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    enum class CameraFacing(val facing: String) {
        CAMERA_FACING_BACK("0"), CAMERA_FACING_FRONT("1")
    }

    interface Callback {
        fun onCallbackByteArray(byteArray: ByteArray)
        fun onCallbackFilePath(filePath: String)
    }

}