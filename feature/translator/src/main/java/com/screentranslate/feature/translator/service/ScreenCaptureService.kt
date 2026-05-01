package com.screentranslate.feature.translator.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Rect
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.screentranslate.core.capture.ScreenCaptureManager
import com.screentranslate.core.common.result.Result
import com.screentranslate.core.ocr.model.TextBlock
import com.screentranslate.core.ocr.repository.OcrRepository
import com.screentranslate.core.overlay.OverlayManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class ScreenCaptureService : Service() {
    @Inject lateinit var captureManager: ScreenCaptureManager
    @Inject lateinit var ocrRepository: OcrRepository
    @Inject lateinit var overlayManager: OverlayManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }
    private var frameCollectorJob: Job? = null
    private var ocrJob: Job? = null
    private var capturedFrameCount = 0
    private var lastOcrMillis = 0L
    private var captureActive = false
    private var foregroundStarted = false
    private var lastStatusMessage = "Screen capture is stopped"
    private var overlayAvailable = false
    private var lastOverlayEntries: List<Pair<String, Rect>> = emptyList()
    private var lastOverlaySourceWidth = 0
    private var lastOverlaySourceHeight = 0
    private var lastOverlayUpdateMillis = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureNotificationChannel()
        Log.d(TAG, "onStartCommand action=${intent?.action}")

        return when (intent?.action) {
            ACTION_START -> startCapture(intent)
            ACTION_QUERY_STATUS -> {
                publishStatus(lastStatusMessage, isRunning = captureActive || foregroundStarted)
                if (!captureActive && !foregroundStarted) {
                    stopSelf(startId)
                }
                START_NOT_STICKY
            }
            ACTION_STOP -> {
                stopCapture("Screen capture stopped")
                stopSelf()
                START_NOT_STICKY
            }
            else -> {
                stopSelf()
                START_NOT_STICKY
            }
        }
    }

    override fun onDestroy() {
        stopCapture("Screen capture stopped")
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "Task removed; stopping screen capture")
        stopCapture("App was removed from recents; screen capture stopped")
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    private fun startCapture(intent: Intent): Int {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val projectionData = intent.projectionData()
        if (resultCode != Activity.RESULT_OK || projectionData == null) {
            Log.w(TAG, "Missing MediaProjection permission result. resultCode=$resultCode")
            publishStatus("Screen capture permission was not granted", isRunning = false)
            stopSelf()
            return START_NOT_STICKY
        }

        Log.d(TAG, "Starting foreground service for MediaProjection")
        publishStatus("Starting screen capture", isRunning = false)
        startForegroundForMediaProjection("Preparing screen translation")
        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        val projection = projectionManager.getMediaProjection(resultCode, projectionData)
        when (val result = captureManager.startCapture(projection)) {
            is Result.Success -> {
                Log.d(TAG, "Screen capture started")
                captureActive = true
                capturedFrameCount = 0
                lastOcrMillis = 0L
                overlayAvailable = false
                observeCapturedFrames()
                updateNotification("Screen capture is active")
                publishStatus("Screen capture is active", isRunning = true)
            }
            is Result.Error -> {
                Log.e(TAG, "Screen capture failed", result.exception)
                publishStatus(
                    result.message ?: result.exception.message ?: "Screen capture failed",
                    isRunning = false,
                )
                stopSelf()
            }
            Result.Loading -> updateNotification("Screen capture is starting")
        }
        return START_NOT_STICKY
    }

    private fun stopCapture(message: String) {
        Log.d(TAG, "Stopping screen capture")
        frameCollectorJob?.cancel()
        frameCollectorJob = null
        ocrJob?.cancel()
        ocrJob = null
        captureManager.stopCapture()
        overlayManager.hide()
        captureActive = false
        overlayAvailable = false
        lastOverlayEntries = emptyList()
        lastOverlaySourceWidth = 0
        lastOverlaySourceHeight = 0
        lastOverlayUpdateMillis = 0L
        publishStatus(message, isRunning = false)
        stopForegroundAndRemoveNotification()
    }

    private fun observeCapturedFrames() {
        frameCollectorJob?.cancel()
        frameCollectorJob = serviceScope.launch {
            captureManager.frames.collect { frame ->
                capturedFrameCount += 1
                if (capturedFrameCount == 1) {
                    Log.d(TAG, "First captured frame: ${frame.bitmap.width}x${frame.bitmap.height}")
                }
                if (capturedFrameCount == 1 || capturedFrameCount % NOTIFICATION_FRAME_INTERVAL == 0) {
                    val message = "Captured $capturedFrameCount frames"
                    Log.d(TAG, message)
                    updateNotification(message)
                    publishStatus(message, isRunning = true)
                }
                val now = System.currentTimeMillis()
                val ocrIsRunning = ocrJob?.isActive == true
                if (!ocrIsRunning && now - lastOcrMillis >= MIN_OCR_INTERVAL_MS) {
                    lastOcrMillis = now
                    ocrJob = serviceScope.launch {
                        recognizeAndRender(frame.bitmap)
                    }
                }
            }
        }
    }

    private suspend fun recognizeAndRender(bitmap: android.graphics.Bitmap) {
        val startedAt = System.currentTimeMillis()
        Log.d(TAG, "OCR started for ${bitmap.width}x${bitmap.height}")
        when (val result = ocrRepository.recognize(bitmap)) {
            is Result.Success -> {
                val rawBlocks = result.data
                val blocks = rawBlocks.filterReadableContent(bitmap.width, bitmap.height)
                Log.d(
                    TAG,
                    "OCR raw=${rawBlocks.size} filtered=${blocks.size}; filtered lines: ${
                        blocks.take(MAX_DEBUG_OCR_LINES).joinToString(" | ") { block ->
                            "\"${block.text.take(MAX_DEBUG_TEXT_LENGTH)}\" @ ${block.boundingBox}"
                        }
                    }",
                )
                val elapsedMillis = System.currentTimeMillis() - startedAt
                val message = "OCR detected ${blocks.size} text lines in ${elapsedMillis}ms"
                Log.d(TAG, message)
                updateNotification(message)
                publishStatus(message, isRunning = true)
                updateOcrOverlay(blocks.toOverlayEntries(), bitmap.width, bitmap.height)
            }
            is Result.Error -> {
                val elapsedMillis = System.currentTimeMillis() - startedAt
                val message = result.message ?: result.exception.message ?: "OCR failed after ${elapsedMillis}ms"
                Log.e(TAG, message, result.exception)
                updateNotification(message)
                publishStatus(message, isRunning = true)
            }
            Result.Loading -> updateNotification("OCR is running")
        }
    }

    private fun List<TextBlock>.toOverlayEntries(): List<Pair<String, Rect>> {
        if (isEmpty()) {
            return emptyList()
        }
        return take(MAX_OVERLAY_BLOCKS).map { block ->
            block.text.take(MAX_OVERLAY_TEXT_LENGTH) to block.boundingBox.toRect()
        }
    }

    private fun updateOcrOverlay(entries: List<Pair<String, Rect>>, sourceWidth: Int, sourceHeight: Int) {
        val now = System.currentTimeMillis()
        if (entries.isNotEmpty()) {
            lastOverlayEntries = entries
            lastOverlaySourceWidth = sourceWidth
            lastOverlaySourceHeight = sourceHeight
            lastOverlayUpdateMillis = now
            ensureOverlayVisible()
            overlayManager.update(entries, sourceWidth, sourceHeight)
            return
        }

        if (lastOverlayEntries.isNotEmpty() && now - lastOverlayUpdateMillis < EMPTY_OVERLAY_CLEAR_DELAY_MS) {
            Log.d(TAG, "Keeping last OCR overlay because current OCR result is empty")
            ensureOverlayVisible()
            overlayManager.update(lastOverlayEntries, lastOverlaySourceWidth, lastOverlaySourceHeight)
            return
        }

        lastOverlayEntries = emptyList()
        overlayManager.hide()
        overlayAvailable = false
    }

    private fun ensureOverlayVisible() {
        if (overlayAvailable) return
        overlayAvailable = overlayManager.show()
        if (!overlayAvailable) {
            Log.w(TAG, "Overlay window is not available for update")
        }
    }

    private fun List<TextBlock>.filterReadableContent(sourceWidth: Int, sourceHeight: Int): List<TextBlock> {
        val topIgnoredPx = (sourceHeight * TOP_SYSTEM_AREA_RATIO).toInt()
        val bottomIgnoredPx = (sourceHeight * BOTTOM_SYSTEM_AREA_RATIO).toInt()
        return asSequence()
            .filter { block ->
                val rect = block.boundingBox.toRect()
                val text = block.text.trim()
                val verticalCenter = rect.centerY()
                val width = rect.width()
                val height = rect.height()
                verticalCenter > topIgnoredPx &&
                    verticalCenter < sourceHeight - bottomIgnoredPx &&
                    width >= sourceWidth * MIN_LINE_WIDTH_RATIO &&
                    height >= MIN_LINE_HEIGHT_PX &&
                    text.length >= MIN_TEXT_LENGTH &&
                    text.any { it.isLetter() }
            }
            .sortedWith(compareBy<TextBlock> { it.boundingBox.top }.thenBy { it.boundingBox.left })
            .toList()
    }

    private fun updateNotification(message: String) {
        lastStatusMessage = message
        notificationManager.notify(NOTIFICATION_ID, buildNotification(message))
    }

    private fun buildNotification(message: String): Notification {
        val pendingIntent = packageManager
            .getLaunchIntentForPackage(packageName)
            ?.let { launchIntent ->
                PendingIntent.getActivity(
                    this,
                    0,
                    launchIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("ScreenTranslate")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .apply {
                if (pendingIntent != null) {
                    setContentIntent(pendingIntent)
                }
            }
            .setOngoing(true)
            .build()
    }

    private fun startForegroundForMediaProjection(message: String) {
        val notification = buildNotification(message)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foregroundStarted = true
        lastStatusMessage = message
    }

    private fun stopForegroundAndRemoveNotification() {
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        notificationManager.cancel(NOTIFICATION_ID)
    }

    private fun publishStatus(
        message: String,
        isRunning: Boolean = captureActive,
    ) {
        lastStatusMessage = message
        sendBroadcast(
            Intent(ACTION_STATUS).apply {
                setPackage(packageName)
                putExtra(EXTRA_STATUS_MESSAGE, message)
                putExtra(EXTRA_IS_RUNNING, isRunning)
                putExtra(EXTRA_CAPTURED_FRAME_COUNT, capturedFrameCount)
                putExtra(EXTRA_OVERLAY_AVAILABLE, overlayAvailable)
            },
        )
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Screen capture",
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    @Suppress("DEPRECATION")
    private fun Intent.projectionData(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
        } else {
            getParcelableExtra(EXTRA_PROJECTION_DATA)
        }

    companion object {
        const val ACTION_START = "com.screentranslate.feature.translator.service.START"
        const val ACTION_STOP = "com.screentranslate.feature.translator.service.STOP"
        const val ACTION_QUERY_STATUS = "com.screentranslate.feature.translator.service.QUERY_STATUS"
        const val ACTION_STATUS = "com.screentranslate.feature.translator.service.STATUS"
        const val EXTRA_STATUS_MESSAGE = "extra_status_message"
        const val EXTRA_IS_RUNNING = "extra_is_running"
        const val EXTRA_CAPTURED_FRAME_COUNT = "extra_captured_frame_count"
        const val EXTRA_OVERLAY_AVAILABLE = "extra_overlay_available"
        private const val TAG = "ScreenCaptureService"
        private const val EXTRA_RESULT_CODE = "extra_result_code"
        private const val EXTRA_PROJECTION_DATA = "extra_projection_data"
        private const val CHANNEL_ID = "screen_capture"
        private const val NOTIFICATION_ID = 1001
        private const val MIN_OCR_INTERVAL_MS = 1_000L
        private const val NOTIFICATION_FRAME_INTERVAL = 5
        private const val MAX_OVERLAY_BLOCKS = 24
        private const val MAX_OVERLAY_TEXT_LENGTH = 120
        private const val MAX_DEBUG_OCR_LINES = 8
        private const val MAX_DEBUG_TEXT_LENGTH = 48
        private const val EMPTY_OVERLAY_CLEAR_DELAY_MS = 3_000L
        private const val TOP_SYSTEM_AREA_RATIO = 0.08f
        private const val BOTTOM_SYSTEM_AREA_RATIO = 0.06f
        private const val MIN_LINE_WIDTH_RATIO = 0.04f
        private const val MIN_LINE_HEIGHT_PX = 12
        private const val MIN_TEXT_LENGTH = 2

        fun createStartIntent(context: Context, resultCode: Int, data: Intent): Intent =
            Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_PROJECTION_DATA, data)
            }

        fun createStopIntent(context: Context): Intent =
            Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_STOP
            }

        fun createQueryStatusIntent(context: Context): Intent =
            Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_QUERY_STATUS
            }
    }
}
