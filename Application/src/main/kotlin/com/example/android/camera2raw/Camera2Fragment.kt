package com.example.android.camera2raw

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
import android.util.Size
import android.view.*
import android.widget.Toast
import kotlinx.android.synthetic.main.fragment_camera2_basic.*
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.launch
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
        @JvmStatic
        fun newInstance() = Camera2Fragment()
    }

    private val mCameraStateLock = Any()
    private var mState: Int = STATE_CLOSED
    private var mCaptureTimer: Long = 0L
    private var mPendingUserCaptures = 0
    private val mCameraOpenCloseLock = Semaphore(1)
    private var mNoAFRun: Boolean = false
    private val mRequestCounter = AtomicInteger()


    private var mBackgroundThread: HandlerThread? = null
    private var mBackgroundHandler: Handler? = null

    private var mJpegImageReader: RefCountedAutoCloseable<ImageReader>? = null
    private val mJpegResultQueue = TreeMap<Int, ImageSaver.ImageSaverBuilder>()

    private var mCameraId: String = ""
    private var mCameraDevice: CameraDevice? = null
    private var mPreviewSize: Size? = null
    private lateinit var mPreviewRequestBuilder: CaptureRequest.Builder
    private var mCaptureSession: CameraCaptureSession? = null
    private var mCharacteristics: CameraCharacteristics? = null

    private var mOrientationListener: OrientationEventListener? = null

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
        dequeueAndSaveImage(mJpegResultQueue, mJpegImageReader)
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
            var jpegBuilder: ImageSaver.ImageSaverBuilder?
            synchronized(mCameraStateLock) {
                jpegBuilder = mJpegResultQueue[requestId]
                jpegBuilder?.let {
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
                maxPreviewHeight = Math.min(maxPreviewHeight, MAX_PREVIEW_HEIGHT)
                maxPreviewWidth = Math.min(maxPreviewWidth, MAX_PREVIEW_WIDTH)
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
                            arrayListOf(surface, mJpegImageReader?.get()?.surface),
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
            if (CameraUtils.contains(mCharacteristics?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES),
                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            } else {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            }
        }
        if (CameraUtils.contains(mCharacteristics?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES),
                        CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)) {
            builder.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON)
        }
        if (CameraUtils.contains(mCharacteristics?.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES),
                        CaptureRequest.CONTROL_AWB_MODE_AUTO)) {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
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
            launch(UI) {
                Toast.makeText(activity, "This device doesn't support Camera2 API.", Toast.LENGTH_SHORT).show()
            }
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
                    if (mJpegImageReader?.getAndRetain() == null) {
                        mJpegImageReader = RefCountedAutoCloseable(ImageReader.newInstance(largestJpeg.width,
                                largestJpeg.height, ImageFormat.JPEG, 5))
                    }
                    mJpegImageReader?.get()?.setOnImageAvailableListener(
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
        CameraUtils.CAMERA_PERMISSIONS.forEach {
            if (ActivityCompat.checkSelfPermission(context!!, it) != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }
        return true

    }

    private fun requestCameraPermissions() {
        if (shouldShowRationale()) {
            PermissionConfirmationDialog.newInstance().show(childFragmentManager, "dialog")
        } else {
            requestPermissions(CameraUtils.CAMERA_PERMISSIONS, CameraUtils.REQUEST_CAMERA_PERMISSIONS)
        }
    }

    private fun shouldShowRationale(): Boolean {
        for (permission in CameraUtils.CAMERA_PERMISSIONS) {
            if (shouldShowRequestPermissionRationale(permission)) {
                return true
            }
        }
        return false
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
           throw RuntimeException(e.message)
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
        if (requestCode == CameraUtils.REQUEST_CAMERA_PERMISSIONS) {
            for (result in grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    showMissingPermissionError()
                    return
                }
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    private fun showMissingPermissionError() {
        //TODO to app setting layout
        activity?.let {
            Toast.makeText(it, R.string.request_permission, Toast.LENGTH_SHORT).show()
            it.finish()
        }
    }

    override fun onClick(v: View?) {
        when (v?.id) {
            R.id.picture -> {
                takePicture()
            }
            R.id.info ->{

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

    private fun captureStillPictureLocked() {
        try {
            if (null == activity || null == mCameraDevice) {
                return
            }
            mCharacteristics?.let {
                val captureBuilder = mCameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                /**将ImageReader的surface添加到这次的captureRequest中*/
                captureBuilder.addTarget(mJpegImageReader?.get()?.surface)
                setup3AControlsLocked(captureBuilder)
                val rotation = activity!!.windowManager.defaultDisplay.rotation
                val facing = it.get(CameraCharacteristics.LENS_FACING)
                val sensorRotation = it.get(CameraCharacteristics.SENSOR_ORIENTATION)
                captureBuilder.set(CaptureRequest.JPEG_ORIENTATION,
                        CameraUtils.getOrientation(facing, sensorRotation, rotation))
                captureBuilder.setTag(mRequestCounter.getAndIncrement())
                val request = captureBuilder.build()

                val jpegBuilder = ImageSaver.ImageSaverBuilder(activity!!)
                mJpegResultQueue[request.tag as Int] = jpegBuilder
                /**调用 Capture 时 会触发 ImageReader 的 OnImageAvailableListener*/
                mCaptureSession?.capture(request, mCaptureCallback, mBackgroundHandler)
            }
        } catch (e: CameraAccessException) {
            e.printStackTrace()
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
                CameraUtils.handleCompletionLocked(entry.key, builder, pendingQueue)
            }
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

    private fun startTimerLocked() {
        mCaptureTimer = SystemClock.elapsedRealtime()
    }

    private fun hitTimeoutLocked(): Boolean {
        return (SystemClock.elapsedRealtime() - mCaptureTimer) > PRE_CAPTURE_TIMEOUT_MS
    }
}