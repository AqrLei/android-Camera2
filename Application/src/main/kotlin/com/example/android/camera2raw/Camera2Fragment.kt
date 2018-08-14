package com.example.android.camera2raw

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.SensorManager
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.*
import android.support.v4.app.ActivityCompat
import android.support.v4.app.Fragment
import android.util.Log
import android.util.Size
import android.view.*
import kotlinx.android.synthetic.main.fragment_camera2_basic.*
import java.io.File
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * @author  aqrLei on 2018/8/13
 */
class Camera2Fragment : Fragment(), View.OnClickListener {
    companion object {
        private const val PRE_CAPTURE_TIMEOUT_MS = 1000

        private const val MAX_PREVIEW_WIDTH = 1920
        private const val MAX_PREVIEW_HEIGHT = 1080

        private const val STATE_CLOSED = 0
        private const val STATE_OPENED = 1
        private const val STATE_PREVIEW = 2
        private const val STATE_WAITING_FOR_3A_CONVERGENCE = 3

        private val CAMERA_PERMISSIONS = arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
        )

        @JvmStatic
        fun newInstance() = Camera2Fragment()
    }

    private var mCaptureSession: CameraCaptureSession? = null
    private var mOrientationListener: OrientationEventListener? = null
    private val mCameraStateLock = Any()
    private var mCharacteristics: CameraCharacteristics? = null
    private var mPreviewSize: Size? = null
    private var mState: Int = STATE_CLOSED
    private var mPendingUserCaptures = 0

    private val mCameraOpenCloseLock = Semaphore(1)
    private var mNoAFRun: Boolean = false

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
    private var mCameraDevice: CameraDevice? = null
    private val mStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice?) {
            synchronized(mCameraStateLock) {
                mState = STATE_OPENED
                mCameraOpenCloseLock.release()
                mCameraDevice = camera
                if (mPreviewSize != null && texture.isAvailable) {
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
            if (null != activity) {
                activity?.finish()
            }
        }
    }
    private val mOnJpegImageAvailableListener = ImageReader.OnImageAvailableListener {
        Log.d("test", "image available")
        dequeueAndSaveImage(mJpegResultQueue, mJpegImageReader)
    }
    private val mPreCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        private fun process(result: CaptureResult) {
            synchronized(mCameraStateLock) {
                when (mState) {
                    STATE_WAITING_FOR_3A_CONVERGENCE -> {
                        var readyToCapture: Boolean
                        if (!mNoAFRun) {
                            val afState = result.get(CaptureResult.CONTROL_AF_STATE) ?: return

                            readyToCapture = (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                                    afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED)
                            if (!isLegacyLocked()) {
                                val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                                val awbState = result.get(CaptureResult.CONTROL_AWB_MODE)
                                if (aeState == null || awbState == null) {
                                    return
                                }
                                readyToCapture = readyToCapture &&
                                        aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED &&
                                        awbState == CaptureResult.CONTROL_AWB_STATE_CONVERGED
                            }
                            if (!readyToCapture && hitTimeoutLocked()) {
                                readyToCapture = true
                            }

                            if (readyToCapture && mPendingUserCaptures > 0) {
                                while (mPendingUserCaptures > 0) {
                                    captureStillPictureLocked()
                                    mPendingUserCaptures--
                                }
                            }
                            mState = STATE_PREVIEW


                        }

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
    private val mCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureStarted(session: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
            val currentDateTime = CameraUtils.generateTimestamp()
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
            var jpegBuilder: ImageSaver.ImageSaverBuilder? = null
            synchronized(mCameraStateLock) {
                jpegBuilder = mJpegResultQueue[requestId]
                jpegBuilder?.let {
                    it.setResult(result)
                    sb.append("Saving JPEG as: ")
                    sb.append(it.saveLocation)
                }
                CameraUtils.handleCompletionLocked(requestId, jpegBuilder, mJpegResultQueue)
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

    private var mBackgroundThread: HandlerThread? = null
    private var mBackgroundHandler: Handler? = null

    private var mJpegImageReader: ImageReader? = null


    private lateinit var mPreviewRequestBuilder: CaptureRequest.Builder
    private var mCameraId: String = ""

    private fun dequeueAndSaveImage(pendingQueue: TreeMap<Int, ImageSaver.ImageSaverBuilder>,
                                    reader: ImageReader?) {
        synchronized(mCameraStateLock) {
            val entry = pendingQueue.firstEntry()
            val builder = entry.value
            if (reader == null) {
                pendingQueue.remove(entry.key)
                return
            }
            val image: Image?
            try {
                image = reader.acquireNextImage()
            } catch (e: IllegalStateException) {
                pendingQueue.remove(entry.key)
                return
            }
            builder.setImage(image)
            CameraUtils.handleCompletionLocked(entry.key, builder, pendingQueue)

        }

    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_camera2_basic, container, false)
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        picture.setOnClickListener(this)
        info.setOnClickListener(this)
        mOrientationListener = object : OrientationEventListener(activity, SensorManager.SENSOR_DELAY_NORMAL) {
            override fun onOrientationChanged(orientation: Int) {
                texture?.let {
                    if (it.isAvailable) {
                        configureTransform(texture.width, texture.height)
                    }
                }
            }
        }
    }

    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        synchronized(mCameraStateLock) {
            if (texture == null || activity == null) {
                return
            }
            mCharacteristics?.let {
                val map = it.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val largestJpeg = Collections.max(
                        map.getOutputSizes(ImageFormat.JPEG).toList(),
                        CameraUtils.comparator)

                val deviceRotation = activity!!.windowManager.defaultDisplay.rotation
                val displaySize = Point()
                activity!!.windowManager.defaultDisplay.getSize(displaySize)
                val sensorOrientation = it.get(CameraCharacteristics.SENSOR_ORIENTATION)
                val facing = it.get(CameraCharacteristics.LENS_FACING)
                val totalRotation = CameraUtils.getOrientation(facing, sensorOrientation, deviceRotation)
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
                maxPreviewHeight = Math.max(maxPreviewHeight, MAX_PREVIEW_HEIGHT)
                maxPreviewWidth = Math.max(maxPreviewWidth, MAX_PREVIEW_WIDTH)
                val previewSize = CameraUtils.chooseOptimalSize(
                        map.getOutputSizes(SurfaceTexture::class.java),
                        rotatedViewWidth, rotatedViewHeight, maxPreviewWidth, maxPreviewHeight, largestJpeg)
                if (swappedDimensions) {
                    texture.setAspectRatio(previewSize.height, previewSize.width)
                } else {
                    texture.setAspectRatio(previewSize.width, previewSize.height)
                }
                val rotation =
                        if (it.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT)
                            (360 + CameraUtils.orientations.get(deviceRotation)) % 360F
                        else
                            (360 - CameraUtils.orientations.get(deviceRotation)) % 360F

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
                texture.setTransform(matrix)
                if (mPreviewSize == null || !CameraUtils.checkAspectsEqual(previewSize, mPreviewSize!!)) {
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
            val surfaceTexture = texture.surfaceTexture
            mPreviewSize?.let {
                surfaceTexture.setDefaultBufferSize(it.width, it.height)
                val surface = Surface(surfaceTexture)
                if (mCameraDevice != null) {
                    mPreviewRequestBuilder = mCameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                    mPreviewRequestBuilder.addTarget(surface)
                    mCameraDevice!!.createCaptureSession(
                            arrayListOf(surface, mJpegImageReader?.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    synchronized(mCameraStateLock) {
                                        if (null == mCameraDevice) {
                                            return
                                        }
                                        try {

                                            setup3AControlsLocked(mPreviewRequestBuilder)
                                            session.setRepeatingRequest(
                                                    mPreviewRequestBuilder.build(),
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

                    Log.d("test", "session add surface")
                }


            }

        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }

    }

    private fun setup3AControlsLocked(builder: CaptureRequest.Builder) {
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        val minFocusDist = mCharacteristics?.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
        mNoAFRun = (minFocusDist == null || minFocusDist == 0F)
        if (!mNoAFRun) {
            //TODO set "continuous picture"
            //TODO set auto-magical flash control mode
            //TODO set auto-magical white balance control model
        }
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        openCamera()

        if (texture.isAvailable) {
            configureTransform(texture.width, texture.height)
        } else {
            texture.surfaceTextureListener = mSurfaceTextureListener
        }
        mOrientationListener?.let {
            if (it.canDetectOrientation()) {
                it.enable()
            }
        }
    }

    private fun startBackgroundThread() {
        mBackgroundThread = HandlerThread("CameraBackground")
        mBackgroundThread?.start()
        synchronized(mCameraStateLock) {
            mBackgroundHandler = Handler(mBackgroundThread?.looper)
        }

    }

    @SuppressWarnings("MissingPermission")
    private fun openCamera() {
        if (!setUpCameraOutputs()) {
            return
        }
        if (!hasAllPermissionsGranted()) {
            requestCameraPermissions()
            return
        }
        val manager = activity?.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            if (!mCameraOpenCloseLock.tryAcquire(2500L, TimeUnit.MILLISECONDS)) {
                //TODO RuntimeException("Time out waiting to lock camera opening")
            }
            synchronized(mCameraStateLock) {
                manager.openCamera(mCameraId, mStateCallback, mBackgroundHandler)
            }
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        } catch (e: InterruptedException) {
            //todo throw RuntimeException
        }

    }

    private fun setUpCameraOutputs(): Boolean {
        val manager = activity?.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        if (manager == null) {
            Log.d("camera", "this device doesn't support Camera2 API")
            return false
        }
        try {
            if (manager.cameraIdList.isNotEmpty()) {
                val cameraId = manager.cameraIdList[0]
                val characteristics = manager.getCameraCharacteristics(cameraId)
                val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val largestJpeg = Collections.max(
                        map.getOutputSizes(ImageFormat.JPEG).toList(),
                        CameraUtils.comparator)
                synchronized(mCameraStateLock) {
                    if (mJpegImageReader == null) {
                        mJpegImageReader = ImageReader.newInstance(largestJpeg.width,
                                largestJpeg.height, ImageFormat.JPEG, 5)
                    }
                    mJpegImageReader?.setOnImageAvailableListener(
                            mOnJpegImageAvailableListener,
                            mBackgroundHandler)
                    mCharacteristics = characteristics
                    mCameraId = cameraId
                }
                return true
            }

        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
        return false

    }

    private fun hasAllPermissionsGranted(): Boolean {
        CAMERA_PERMISSIONS.forEach {
            if (ActivityCompat.checkSelfPermission(context!!, it) != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }
        return true

    }

    private fun requestCameraPermissions() {
        //TODO RequestPermission
    }

    override fun onPause() {

        mOrientationListener?.disable()
        closeCamera()
        stopBackgroundThread()
        super.onPause()

    }

    private fun closeCamera() {
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
            // TODO throw RuntimeException
        } finally {

            mCameraOpenCloseLock.release()
        }
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun onClick(v: View?) {
        when (v?.id) {
            R.id.picture -> {
                takePicture()
            }
        }
    }

    private fun takePicture() {
        synchronized(mCameraStateLock) {
            mPendingUserCaptures++
            if (mState != STATE_PREVIEW) {
                return
            }
            try {
                if (!mNoAFRun) {
                    mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                            CameraMetadata.CONTROL_AF_TRIGGER_START)
                }
                if (!isLegacyLocked()) {
                    mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                            CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START)
                }
                mState = STATE_WAITING_FOR_3A_CONVERGENCE
                startTimerLocked()
                mCaptureSession?.capture(mPreviewRequestBuilder.build(), mPreCaptureCallback, mBackgroundHandler)

            } catch (e: CameraAccessException) {
                e.printStackTrace()
            }
        }
    }

    private val mRequestCounter = AtomicInteger()
    private val mJpegResultQueue = TreeMap<Int, ImageSaver.ImageSaverBuilder>()
    private fun captureStillPictureLocked() {
        try {
            if (null == activity || null == mCameraDevice) {
                return
            }
            mCharacteristics?.let {
                val captureBuilder = mCameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                captureBuilder.addTarget(mJpegImageReader?.surface)
                Log.d("test", "capture add target surface")
                setup3AControlsLocked(captureBuilder)
                val rotation = activity!!.windowManager.defaultDisplay.rotation
                val facing = it.get(CameraCharacteristics.LENS_FACING)
                val sensorRotation = it.get(CameraCharacteristics.SENSOR_ORIENTATION)
                captureBuilder.set(CaptureRequest.JPEG_ORIENTATION,
                        CameraUtils.getOrientation(facing, sensorRotation, rotation))
                captureBuilder.setTag(mRequestCounter.getAndIncrement())
                val request = captureBuilder.build()

                val jpegBuilder = ImageSaver.ImageSaverBuilder(activity!!)
                        .setCharacteristics(mCharacteristics)
                mJpegResultQueue[request.tag as Int] = jpegBuilder
                mCaptureSession?.capture(request, mCaptureCallback, mBackgroundHandler)

                Log.d("test", "session capture ")
            }
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun finishedCaptureLocked() {
        try {
            if (!mNoAFRun) {
                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                        CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
                mCaptureSession?.capture(mPreviewRequestBuilder.build(), mPreCaptureCallback,
                        mBackgroundHandler)
                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                        CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
            }

        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun isLegacyLocked(): Boolean {
        return mCharacteristics?.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ==
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY
    }

    private var mCaptureTimer: Long = 0L
    private fun startTimerLocked() {
        mCaptureTimer = SystemClock.elapsedRealtime()
    }

    private fun hitTimeoutLocked(): Boolean {
        return (SystemClock.elapsedRealtime() - mCaptureTimer) > PRE_CAPTURE_TIMEOUT_MS
    }


}