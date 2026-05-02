package com.screentranslate.core.capture

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.screentranslate.core.common.result.Result
import com.screentranslate.core.capture.model.CapturedFrame
import com.screentranslate.core.capture.model.RegionOfInterest
import com.screentranslate.core.capture.repository.CaptureRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject

class ScreenCaptureManager @Inject constructor(
    @ApplicationContext private val context: Context,
) : CaptureRepository {
    private val mutableFrames = MutableSharedFlow<CapturedFrame>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val frames: Flow<CapturedFrame> = mutableFrames.asSharedFlow()

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var imageThread: HandlerThread? = null
    private var imageHandler: Handler? = null
    private var latestFrame: CapturedFrame? = null
    private var lastEmitMillis: Long = 0L
    @Volatile private var reconfiguringSurface = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            releaseCaptureResources(stopProjection = false)
        }
    }

    fun createCaptureIntent(): Intent {
        val projectionManager = context.getSystemService(MediaProjectionManager::class.java)
        return projectionManager.createScreenCaptureIntent()
    }

    fun startCapture(projection: MediaProjection): Result<Unit> {
        stopCapture()

        return runCatching {
            val metrics = context.resources.displayMetrics
            val width = metrics.widthPixels.coerceAtLeast(1)
            val height = metrics.heightPixels.coerceAtLeast(1)
            val densityDpi = metrics.densityDpi

            mediaProjection = projection
            val handler = ensureImageHandler()
            projection.registerCallback(projectionCallback, handler)
            createCaptureSurface(projection, width, height, densityDpi, handler)
            Log.d(TAG, "VirtualDisplay created: ${width}x$height @ ${densityDpi}dpi")
        }.fold(
            onSuccess = { Result.Success(Unit) },
            onFailure = { exception ->
                releaseCaptureResources(stopProjection = true)
                Log.e(TAG, "Unable to start screen capture", exception)
                Result.Error(exception, exception.message)
            },
        )
    }

    fun stopCapture() {
        releaseCaptureResources(stopProjection = true)
    }

    fun refreshForCurrentDisplayIfNeeded(): Boolean {
        val projection = mediaProjection ?: return false
        val currentReader = imageReader ?: return false
        val metrics = context.resources.displayMetrics
        val width = metrics.widthPixels.coerceAtLeast(1)
        val height = metrics.heightPixels.coerceAtLeast(1)
        if (currentReader.width == width && currentReader.height == height) {
            return false
        }

        return runCatching {
            reconfiguringSurface = true
            imageReader?.setOnImageAvailableListener(null, null)
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
            latestFrame = null
            lastEmitMillis = 0L
            createCaptureSurface(projection, width, height, metrics.densityDpi, ensureImageHandler())
            Log.d(TAG, "VirtualDisplay recreated for display change: ${width}x$height @ ${metrics.densityDpi}dpi")
            true
        }.getOrElse { exception ->
            Log.e(TAG, "Unable to refresh screen capture surface", exception)
            false
        }.also {
            reconfiguringSurface = false
        }
    }

    private fun releaseCaptureResources(stopProjection: Boolean) {
        val projection = mediaProjection
        reconfiguringSurface = true
        imageReader?.setOnImageAvailableListener(null, null)
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        runCatching { projection?.unregisterCallback(projectionCallback) }
        if (stopProjection) {
            runCatching { projection?.stop() }
        }
        mediaProjection = null
        imageThread?.quitSafely()
        imageThread = null
        imageHandler = null
        latestFrame = null
        lastEmitMillis = 0L
        reconfiguringSurface = false
    }

    override suspend fun capture(region: RegionOfInterest?): Result<CapturedFrame> =
        latestFrame?.let { frame ->
            if (region == null) {
                Result.Success(frame)
            } else {
                Result.Success(frame.copy(bitmap = frame.bitmap.crop(region), region = region))
            }
        } ?: Result.Error(IllegalStateException("No captured frame is available yet."))

    override suspend fun clear() {
        stopCapture()
    }

    private fun onImageAvailable(reader: ImageReader) {
        val now = System.currentTimeMillis()
        val image = reader.acquireLatestImage() ?: return
        image.use { currentImage ->
            if (reconfiguringSurface) return
            if (now - lastEmitMillis < MIN_FRAME_INTERVAL_MS) return
            val bitmap = currentImage.toBitmap()
            val frame = CapturedFrame(bitmap = bitmap, timestampMillis = now)
            latestFrame = frame
            lastEmitMillis = now
            mutableFrames.tryEmit(frame)
        }
    }

    private fun ensureImageHandler(): Handler {
        imageHandler?.let { return it }
        val thread = HandlerThread(IMAGE_THREAD_NAME).apply { start() }
        imageThread = thread
        return Handler(thread.looper).also { handler ->
            imageHandler = handler
        }
    }

    private fun createCaptureSurface(
        projection: MediaProjection,
        width: Int,
        height: Int,
        densityDpi: Int,
        handler: Handler,
    ) {
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, MAX_IMAGES)
        imageReader?.setOnImageAvailableListener(
            ImageReader.OnImageAvailableListener { reader -> onImageAvailable(reader) },
            handler,
        )
        virtualDisplay = projection.createVirtualDisplay(
            VIRTUAL_DISPLAY_NAME,
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            handler,
        )
    }

    private fun Image.toBitmap(): Bitmap {
        val plane = planes.first()
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val paddedWidth = width + rowPadding / pixelStride

        val paddedBitmap = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        paddedBitmap.copyPixelsFromBuffer(buffer)

        return if (paddedWidth == width) {
            paddedBitmap
        } else {
            Bitmap.createBitmap(paddedBitmap, 0, 0, width, height).also {
                paddedBitmap.recycle()
            }
        }
    }

    private fun Bitmap.crop(region: RegionOfInterest): Bitmap {
        val rect = region.toRect()
        val left = rect.left.coerceIn(0, width)
        val top = rect.top.coerceIn(0, height)
        val right = rect.right.coerceIn(left, width)
        val bottom = rect.bottom.coerceIn(top, height)
        if (right - left <= 0 || bottom - top <= 0) return this
        return Bitmap.createBitmap(this, left, top, right - left, bottom - top)
    }

    private companion object {
        const val IMAGE_THREAD_NAME = "ScreenTranslateCapture"
        const val VIRTUAL_DISPLAY_NAME = "ScreenTranslateVirtualDisplay"
        const val TAG = "ScreenCaptureManager"
        const val MAX_IMAGES = 2
        const val MIN_FRAME_INTERVAL_MS = 500L
    }
}
