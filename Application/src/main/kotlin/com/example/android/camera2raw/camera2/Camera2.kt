package com.example.android.camera2raw.camera2

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.*
import android.hardware.SensorManager
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import android.media.Image
import android.media.ImageReader
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import com.example.android.camera2raw.AutoFitTextureView
import com.example.android.camera2raw.ImageSaver
import com.example.android.camera2raw.RefCountedAutoCloseable
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.launch
import java.io.File
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * @author  aqrLei on 2018/8/15
 */
class Camera2(private val textureView: AutoFitTextureView,
              private val activity: Activity) {

    companion object {
        private const val MAX_PREVIEW_WIDTH = 1920
        private const val MAX_PREVIEW_HEIGHT = 1080
        private const val STATE_CLOSED = 0
        private const val STATE_OPENED = 1
        private const val STATE_PREVIEW = 2
        private const val STATE_WAITING_FOR_3A_CONVERGENCE = 3
        private const val STATE_WAITING_FOR_AF = 4
    }

    private var mCallback: ImageSaver.Callback? = null

    private val mCameraStateLock = Any()
    private val mCameraOpenCloseLock = Semaphore(1)
    private val mRequestCounter = AtomicInteger(0)
    private var mState: Int = STATE_CLOSED
    private var mPendingUserCaptures: Int = 0
    private var mNoAFRun: Boolean = false

    private var mBackgroundThread: HandlerThread? = null
    private var mBackgroundHandler: Handler? = null

    private var cameraFlashMode = CameraFlashMode.FLASH_OFF

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

    private var mCharacteristics: CameraCharacteristics? = null
    private var mCaptureSession: CameraCaptureSession? = null
    private var mJpegImageReader: RefCountedAutoCloseable<ImageReader>? = null
    private val mJpegResultQueue = TreeMap<Int, ImageSaver.ImageSaverBuilder>()
    private val mOnJpegImageAvailableListener = ImageReader.OnImageAvailableListener {
        dequeueAndSaveImage(mJpegResultQueue, mJpegImageReader)
    }
    private val mCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureStarted(session: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
            val currentDateTime = Camera2Utils.generateTimestamp()
            val jpegFile = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                    "JPEG_$currentDateTime.jpg")
            val requestId = request.tag as Int
            var jpegBuilder: ImageSaver.ImageSaverBuilder? = null
            synchronized(mCameraStateLock) {
                jpegBuilder = mJpegResultQueue[requestId]
            }
            jpegBuilder?.setFile(jpegFile)
        }

        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val requestId = request.tag as Int
            val sb = StringBuilder()
            var jpegBuilder: ImageSaver.ImageSaverBuilder?
            synchronized(mCameraStateLock) {
                jpegBuilder = mJpegResultQueue[requestId]
                jpegBuilder?.let {
                    sb.append("Saving JPEG as: ")
                    sb.append(it.saveLocation)
                }
                Camera2Utils.handleCompletionLocked(requestId, jpegBuilder, mJpegResultQueue)
                finishedCaptureLocked()

            }
        }

        override fun onCaptureFailed(session: CameraCaptureSession?, request: CaptureRequest, failure: CaptureFailure?) {
            val requestId = request.tag as Int
            synchronized(mCameraStateLock) {
                mJpegResultQueue.remove(requestId)
                finishedCaptureLocked()
            }
        }

    }

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
            synchronized(mCameraStateLock) {
                when (mState) {
                    STATE_WAITING_FOR_3A_CONVERGENCE -> {
                        var readyToCapture = true
                        if (!mNoAFRun) {
                            val afState = result.get(CaptureResult.CONTROL_AF_STATE) ?: return

                            readyToCapture = (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                                    afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED)
                        }
                        if (!Camera2Utils.isLegacyLocked(mCharacteristics)) {
                            val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                            val awbState = result.get(CaptureResult.CONTROL_AWB_MODE)
                            if (aeState == null || awbState == null) {
                                return
                            }
                            readyToCapture = readyToCapture &&
                                    aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED &&
                                    awbState == CaptureResult.CONTROL_AWB_STATE_CONVERGED
                        }
                        if (!readyToCapture && Camera2Utils.hitTimeoutLocked()) {
                            readyToCapture = true
                        }

                        if (readyToCapture && mPendingUserCaptures > 0) {
                            while (mPendingUserCaptures > 0) {
                                captureStillPictureLocked()
                                mPendingUserCaptures--
                            }
                            mState = STATE_PREVIEW
                        }
                    }
                    STATE_WAITING_FOR_AF -> {
                        recoverAF(result)
                    }
                    else -> {
                        // do nothing
                    }
                }
            }

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
        textureView.setOnTouchListener { _, event ->
            autoFocus(event!!.x.toInt(), event.y.toInt())
            true
        }
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
            if (manager.cameraIdList.isNotEmpty() && manager.cameraIdList.size > 1) {
                val characteristics = manager.getCameraCharacteristics(mCameraFacing.facing)
                val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val largestJpeg = Collections.max(
                        map.getOutputSizes(ImageFormat.JPEG).toList(),
                        Camera2Utils.comparator)
                synchronized(mCameraStateLock) {
                    if (mJpegImageReader?.getAndRetain() == null) {
                        mJpegImageReader = RefCountedAutoCloseable(ImageReader.newInstance(largestJpeg.width,
                                largestJpeg.height, ImageFormat.JPEG, 5))
                    }
                    mJpegImageReader?.get()?.setOnImageAvailableListener(
                            mOnJpegImageAvailableListener,
                            mBackgroundHandler)
                    mCharacteristics = characteristics
                }
                return true
            } else {
                return false
            }
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
        return false

    }

    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        synchronized(mCameraStateLock) {
            mCharacteristics?.let {
                val map = it.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val largestJpeg = Collections.max(
                        map.getOutputSizes(ImageFormat.JPEG).toList(),
                        Camera2Utils.comparator)
                val deviceRotation = activity.windowManager.defaultDisplay.rotation
                val displaySize = Point()
                activity.windowManager.defaultDisplay.getSize(displaySize)
                val sensorOrientation = it.get(CameraCharacteristics.SENSOR_ORIENTATION)
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
                        rotatedViewWidth, rotatedViewHeight, maxPreviewWidth, maxPreviewHeight, largestJpeg)
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

    private fun createCameraPreviewSessionLocked() {
        try {
            val surfaceTexture = textureView.surfaceTexture
            mPreviewSize?.let {
                surfaceTexture.setDefaultBufferSize(it.width, it.height)
                val surface = Surface(surfaceTexture)
                if (mCameraDevice != null) {
                    mCaptureRequestBuilder = mCameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                    mCaptureRequestBuilder?.addTarget(surface)
                    mCameraDevice!!.createCaptureSession(
                            arrayListOf(surface, mJpegImageReader?.get()?.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    synchronized(mCameraStateLock) {
                                        if (null == mCameraDevice) {
                                            return
                                        }
                                        try {

                                            setup3AControlsLocked(mCaptureRequestBuilder!!)
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

    private fun setup3AControlsLocked(builder: CaptureRequest.Builder) {
        /**overall of 3A mode*/
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)

        val minFocusDist = mCharacteristics?.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
        /**If the lens is fixed-focus, this will be true*/
        mNoAFRun = (minFocusDist == null || minFocusDist == 0F)
        if (!mNoAFRun) {
            if (Camera2Utils.contains(mCharacteristics?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES),
                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            } else {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            }
        }


        configureAE(builder)

        /*auto-white-balance*/
        if (Camera2Utils.contains(mCharacteristics?.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES),
                        CaptureRequest.CONTROL_AWB_MODE_AUTO)) {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        }
    }

    private fun configureAE(builder: CaptureRequest.Builder) {
        val flashMode = when (cameraFlashMode) {
            CameraFlashMode.FLASH_AUTO -> {
                CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
            }
            CameraFlashMode.FLASH_OFF -> {
                CaptureRequest.FLASH_MODE_OFF

            }
            CameraFlashMode.FLASH_ON -> {
                CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH
            }
        }
        if (Camera2Utils.contains(mCharacteristics?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES),
                        flashMode)) {
            if (flashMode == CaptureRequest.FLASH_MODE_OFF) {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }
            builder.set(CaptureRequest.CONTROL_AE_MODE, flashMode)
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON)
        }
    }

    fun setCallback(callback: ImageSaver.Callback) {
        mCallback = callback
    }

    fun switchFacing() {
        mCameraFacing = when (mCameraFacing) {
            CameraFacing.CAMERA_FACING_BACK -> {
                CameraFacing.CAMERA_FACING_FRONT
            }
            CameraFacing.CAMERA_FACING_FRONT -> {
                CameraFacing.CAMERA_FACING_BACK
            }
        }
        release()
        openCamera()
    }

    fun switchFlash(flashMode: CameraFlashMode) {
        if (flashMode == cameraFlashMode) {
            return
        }
        val save = cameraFlashMode
        cameraFlashMode = flashMode
        synchronized(mCameraStateLock) {
            if (mCaptureRequestBuilder != null) {
                configureAE(mCaptureRequestBuilder!!)
                if (mCaptureSession != null) {
                    try {
                        mCaptureSession?.setRepeatingRequest(
                                mCaptureRequestBuilder?.build(),
                                mPreCaptureCallback,
                                mBackgroundHandler)
                    } catch (e: CameraAccessException) {
                        cameraFlashMode = save
                    }
                }
            }
        }

    }

    private fun autoFocus(rawX: Int, rawY: Int) {
        synchronized(mCameraStateLock) {
            if (mCaptureRequestBuilder != null) {
                val region = MeteringRectangle(rawX, rawY, 100, 100, 1000)
                mCaptureRequestBuilder?.set(CaptureRequest.CONTROL_AF_REGIONS,
                        arrayOf(region))
                mCaptureRequestBuilder?.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
                mCaptureRequestBuilder?.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
                mCaptureRequestBuilder?.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                mCaptureRequestBuilder?.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START)
                if (mCaptureSession != null) {
                    try {
                        mCaptureSession?.setRepeatingRequest(mCaptureRequestBuilder?.build(),
                                mPreCaptureCallback,
                                mBackgroundHandler)
                        mState = STATE_WAITING_FOR_AF
                    } catch (e: CameraAccessException) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    private fun recoverAF(result: CaptureResult) {
        val afState = result.get(CaptureResult.CONTROL_AF_STATE)
        if (CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED == afState ||
                CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED == afState) {
            mCaptureRequestBuilder?.let {
                it.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
                setup3AControlsLocked(it)
                try {
                    mCaptureSession?.setRepeatingRequest(mCaptureRequestBuilder?.build(), mPreCaptureCallback, mBackgroundHandler)
                } catch (e: CameraAccessException) {
                    e.printStackTrace()
                } finally {
                    mState = STATE_PREVIEW
                }
            }
        }
    }

    fun takePicture() {
        synchronized(mCameraStateLock) {
            mPendingUserCaptures++
            if (mState != STATE_PREVIEW) {
                return
            }
            try {
                if (!mNoAFRun) {
                    mCaptureRequestBuilder?.set(CaptureRequest.CONTROL_AF_TRIGGER,
                            CameraMetadata.CONTROL_AF_TRIGGER_START)
                }
                if (!Camera2Utils.isLegacyLocked(mCharacteristics)) {
                    mCaptureRequestBuilder?.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                            CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START)
                }
                mState = STATE_WAITING_FOR_3A_CONVERGENCE
                Camera2Utils.startTimeLocked()
                mCaptureSession?.capture(mCaptureRequestBuilder?.build(), mPreCaptureCallback, mBackgroundHandler)

            } catch (e: CameraAccessException) {
                e.printStackTrace()
            }
        }
    }

    private fun dequeueAndSaveImage(pendingQueue: TreeMap<Int, ImageSaver.ImageSaverBuilder>,
                                    reader: RefCountedAutoCloseable<ImageReader>?) {
        synchronized(mCameraStateLock) {
            val entry = pendingQueue.firstEntry()
            val builder = entry.value
            if (reader?.getAndRetain() == null) {
                pendingQueue.remove(entry.key)
                return
            }
            val image: Image?
            try {
                image = reader.get()?.acquireNextImage()
            } catch (e: IllegalStateException) {
                pendingQueue.remove(entry.key)
                return
            }
            image?.let {
                builder.setRefCountedReader(reader).setImage(it)
                Camera2Utils.handleCompletionLocked(entry.key, builder, pendingQueue)
            }
        }
    }

    private fun captureStillPictureLocked() {
        try {
            if (null == mCameraDevice) {
                return
            }
            mCharacteristics?.let {
                val captureBuilder = mCameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                /**将ImageReader的surface添加到这次的captureRequest中*/
                captureBuilder.addTarget(mJpegImageReader?.get()?.surface)
                setup3AControlsLocked(captureBuilder)
                val rotation = activity.windowManager.defaultDisplay.rotation
                val facing = it.get(CameraCharacteristics.LENS_FACING)
                val sensorRotation = it.get(CameraCharacteristics.SENSOR_ORIENTATION)
                captureBuilder.set(CaptureRequest.JPEG_ORIENTATION,
                        Camera2Utils.getOrientation(facing, sensorRotation, rotation))
                captureBuilder.setTag(mRequestCounter.getAndIncrement())
                val request = captureBuilder.build()

                val jpegBuilder = ImageSaver.ImageSaverBuilder(activity)
                jpegBuilder.setCallback(mCallback)
                mJpegResultQueue[request.tag as Int] = jpegBuilder
                /**调用 Capture 时 会触发 ImageReader 的 OnImageAvailableListener*/
                mCaptureSession?.capture(request, mCaptureCallback, mBackgroundHandler)
            }
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun finishedCaptureLocked() {
        try {
            if (!mNoAFRun) {
                mCaptureRequestBuilder?.set(CaptureRequest.CONTROL_AF_TRIGGER,
                        CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
                mCaptureSession?.capture(mCaptureRequestBuilder?.build(), mPreCaptureCallback,
                        mBackgroundHandler)
                mCaptureRequestBuilder?.set(CaptureRequest.CONTROL_AF_TRIGGER,
                        CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
            }

        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun release() {
        mOrientationListener?.disable()
        try {
            mCameraOpenCloseLock.acquire()
            synchronized(mCameraStateLock) {
                mPendingUserCaptures = 0
                mState = STATE_CLOSED
                if (null != mCaptureSession) {
                    mCaptureSession?.close()
                    mCaptureSession = null
                }
                if (null != mCameraDevice) {
                    mCameraDevice?.close()
                    mCameraDevice = null
                }
                if (null != mJpegImageReader) {
                    mJpegImageReader?.close()
                    mJpegImageReader = null
                }
            }
        } catch (e: InterruptedException) {
            throw RuntimeException(e.message)
        } finally {
            mCameraOpenCloseLock.release()
        }

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

    enum class CameraFlashMode {
        FLASH_OFF, FLASH_ON, FLASH_AUTO
    }
}