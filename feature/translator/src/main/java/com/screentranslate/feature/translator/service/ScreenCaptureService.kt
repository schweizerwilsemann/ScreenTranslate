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
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import android.media.projection.MediaProjection
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.screentranslate.core.capture.audio.AudioPlaybackProbe
import com.screentranslate.core.capture.ScreenCaptureManager
import com.screentranslate.core.common.result.Result
import com.screentranslate.core.ocr.model.BoundingBox
import com.screentranslate.core.ocr.model.TextBlock
import com.screentranslate.core.ocr.repository.OcrRepository
import com.screentranslate.core.overlay.OverlayManager
import com.screentranslate.core.translation.model.LanguagePair
import com.screentranslate.core.translation.repository.TranslationRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class ScreenCaptureService : Service() {
    @Inject lateinit var captureManager: ScreenCaptureManager
    @Inject lateinit var audioPlaybackProbe: AudioPlaybackProbe
    @Inject lateinit var ocrRepository: OcrRepository
    @Inject lateinit var overlayManager: OverlayManager
    @Inject lateinit var translationRepository: TranslationRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }
    private var frameCollectorJob: Job? = null
    private var ocrJob: Job? = null
    private var translationJob: Job? = null
    private var audioProbeJob: Job? = null
    private var translationRequestId = 0L
    private var capturedFrameCount = 0
    private var lastOcrMillis = 0L
    private var captureActive = false
    private var foregroundStarted = false
    private var lastStatusMessage = "Video subtitle translation is stopped"
    private var overlayAvailable = false
    private var currentSubtitleText = ""
    private var pendingSubtitleText = ""
    private var pendingSubtitleFirstSeenMillis = 0L
    private var pendingSubtitleLastSeenMillis = 0L
    private var lastSubtitleUpdateMillis = 0L
    private var lastDisplayedSubtitleText = ""
    private var translationModelMessageShown = false

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
                stopCapture("Video subtitle translation stopped")
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
        stopCapture("Video subtitle translation stopped")
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "Task removed; stopping screen capture")
        stopCapture("App was removed from recents; video subtitle translation stopped")
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
        publishStatus("Starting video subtitle translation", isRunning = false)
        startForegroundForMediaProjection("Preparing video subtitles")
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
                scheduleAudioProbes(projection)
                updateNotification("Video subtitle translation is active")
                publishStatus("Video subtitle translation is active", isRunning = true)
            }
            is Result.Error -> {
                Log.e(TAG, "Screen capture failed", result.exception)
                publishStatus(
                    result.message ?: result.exception.message ?: "Screen capture failed",
                    isRunning = false,
                )
                stopSelf()
            }
            Result.Loading -> updateNotification("Video subtitle translation is starting")
        }
        return START_NOT_STICKY
    }

    private fun stopCapture(message: String) {
        Log.d(TAG, "Stopping screen capture")
        frameCollectorJob?.cancel()
        frameCollectorJob = null
        ocrJob?.cancel()
        ocrJob = null
        translationJob?.cancel()
        translationJob = null
        audioProbeJob?.cancel()
        audioProbeJob = null
        captureManager.stopCapture()
        overlayManager.hide()
        captureActive = false
        overlayAvailable = false
        currentSubtitleText = ""
        pendingSubtitleText = ""
        pendingSubtitleFirstSeenMillis = 0L
        pendingSubtitleLastSeenMillis = 0L
        lastSubtitleUpdateMillis = 0L
        lastDisplayedSubtitleText = ""
        translationRequestId = 0L
        translationModelMessageShown = false
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
                if (captureManager.refreshForCurrentDisplayIfNeeded()) {
                    Log.d(TAG, "Display size changed; refreshed capture surface and skipped stale frame")
                    return@collect
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

    private fun scheduleAudioProbes(projection: MediaProjection) {
        audioProbeJob?.cancel()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.d(TAG, "Audio playback capture probe is not supported below Android 10")
            return
        }

        audioProbeJob = serviceScope.launch {
            delay(FIRST_AUDIO_PROBE_DELAY_MS)
            runAudioProbe(projection, "first")
            delay(SECOND_AUDIO_PROBE_DELAY_MS - FIRST_AUDIO_PROBE_DELAY_MS)
            runAudioProbe(projection, "second")
        }
    }

    private suspend fun runAudioProbe(projection: MediaProjection, label: String) {
        when (val result = audioPlaybackProbe.probe(projection, AUDIO_PROBE_DURATION_MS)) {
            is Result.Success -> {
                val probe = result.data
                val message = if (probe.supported) {
                    "Audio probe $label: signal=${probe.signalDetected} " +
                        "rms=${"%.1f".format(probe.rmsAmplitude)} peak=${probe.peakAmplitude} " +
                        "samples=${probe.samplesRead}"
                } else {
                    "Audio probe $label: playback capture unavailable"
                }
                Log.d(TAG, message)
                updateNotification(message)
                publishStatus(message, isRunning = true)
            }
            is Result.Error -> {
                val message = result.message ?: result.exception.message ?: "Audio probe $label failed"
                Log.w(TAG, message, result.exception)
                updateNotification("Audio probe failed")
                publishStatus(message, isRunning = true)
            }
            Result.Loading -> Unit
        }
    }

    private suspend fun recognizeAndRender(bitmap: Bitmap) {
        val startedAt = System.currentTimeMillis()
        val region = bitmap.subtitleRegion()
        Log.d(
            TAG,
            "OCR started for subtitle ROI ${region.width}x${region.height} " +
                "y=${region.boundingBox.top}..${region.boundingBox.bottom} " +
                "source=${bitmap.width}x${bitmap.height} mode=${region.mode}",
        )
        when (val result = ocrRepository.recognizeRegion(bitmap, region.boundingBox)) {
            is Result.Success -> {
                val rawLines = result.data
                val subtitleLines = rawLines.filterSubtitleCandidates(region.width, region.height)
                val subtitleText = subtitleLines.toSubtitleCandidate(region.height)
                val debugLines = if (subtitleLines.isNotEmpty()) subtitleLines else rawLines
                val debugLineType = if (subtitleLines.isNotEmpty()) "candidate" else "raw"
                Log.d(
                    TAG,
                    "OCR raw=${rawLines.size} subtitleCandidates=${subtitleLines.size} " +
                        "subtitle=\"${subtitleText.take(MAX_DEBUG_TEXT_LENGTH)}\"; $debugLineType lines: ${
                        debugLines.take(MAX_DEBUG_OCR_LINES).joinToString(" | ") { block ->
                            "\"${block.text.take(MAX_DEBUG_TEXT_LENGTH)}\" @ ${block.boundingBox}"
                        }
                    }",
                )
                val elapsedMillis = System.currentTimeMillis() - startedAt
                val message = "Subtitle OCR detected ${subtitleLines.size} lines in ${elapsedMillis}ms"
                Log.d(TAG, message)
                updateNotification(message)
                publishStatus(message, isRunning = true)
                updateSubtitleOverlay(subtitleText)
            }
            is Result.Error -> {
                if (result.exception is CancellationException) {
                    Log.d(TAG, "OCR cancelled")
                    return
                }
                val elapsedMillis = System.currentTimeMillis() - startedAt
                val message = result.message ?: result.exception.message ?: "OCR failed after ${elapsedMillis}ms"
                Log.e(TAG, message, result.exception)
                updateNotification(message)
                publishStatus(message, isRunning = true)
            }
            Result.Loading -> updateNotification("OCR is running")
        }
    }

    private fun updateSubtitleOverlay(candidateText: String) {
        val now = System.currentTimeMillis()
        val normalizedText = candidateText.normalizeSubtitle()
        if (normalizedText.isBlank()) {
            handleEmptySubtitle(now)
            return
        }

        if (!shouldCommitSubtitle(normalizedText, now)) {
            return
        }

        translateAndShowSubtitle(normalizedText)
    }

    private fun ensureOverlayVisible() {
        if (overlayAvailable) return
        overlayAvailable = overlayManager.show()
        if (!overlayAvailable) {
            Log.w(TAG, "Overlay window is not available for update")
        }
    }

    private fun shouldCommitSubtitle(candidateText: String, now: Long): Boolean {
        if (!candidateText.isWorthTranslating()) {
            Log.d(TAG, "Skipping unstable/short subtitle fragment: \"${candidateText.take(MAX_DEBUG_TEXT_LENGTH)}\"")
            clearSubtitleIfStale(now)
            return false
        }

        if (candidateText == currentSubtitleText) {
            lastSubtitleUpdateMillis = now
            return false
        }

        if (currentSubtitleText.isBlank()) {
            return shouldAcceptPendingSubtitle(candidateText, now, "initial subtitle phrase")
        }

        val previousText = currentSubtitleText.normalizedForMatching()
        val nextText = candidateText.normalizedForMatching()
        val previousWords = previousText.wordsForMatching()
        val nextWords = nextText.wordsForMatching()
        val extendsCurrentSubtitle = previousText.isNotBlank() &&
            nextText.startsWith(previousText) &&
            nextText.length - previousText.length >= MIN_EXTENSION_CHARS
        val extendsByPhrase = nextWords.size - previousWords.size >= MIN_EXTENSION_WORDS ||
            candidateText.endsWithSentencePunctuation()
        if (
            extendsCurrentSubtitle &&
            extendsByPhrase &&
            now - lastSubtitleUpdateMillis >= STREAMING_SUBTITLE_UPDATE_INTERVAL_MS
        ) {
            Log.d(TAG, "Accepting streaming subtitle extension: \"${candidateText.take(MAX_DEBUG_TEXT_LENGTH)}\"")
            currentSubtitleText = candidateText
            pendingSubtitleText = ""
            pendingSubtitleFirstSeenMillis = 0L
            pendingSubtitleLastSeenMillis = 0L
            lastSubtitleUpdateMillis = now
            return true
        }

        val isLikelyNewSubtitle = previousText.isNotBlank() &&
            nextText.isNotBlank() &&
            !nextText.startsWith(previousText) &&
            !previousText.startsWith(nextText)
        if (
            isLikelyNewSubtitle &&
            candidateText.isImmediateSubtitlePhrase() &&
            now - lastSubtitleUpdateMillis >= NEW_SUBTITLE_IMMEDIATE_UPDATE_INTERVAL_MS
        ) {
            return shouldAcceptPendingSubtitle(candidateText, now, "new subtitle phrase")
        }

        return shouldAcceptPendingSubtitle(candidateText, now, "stable subtitle phrase")
    }

    private fun shouldAcceptPendingSubtitle(candidateText: String, now: Long, reason: String): Boolean {
        if (candidateText != pendingSubtitleText) {
            if (pendingSubtitleText.isNotBlank() && candidateText.isRelatedSubtitleCandidate(pendingSubtitleText)) {
                pendingSubtitleText = candidateText
                pendingSubtitleLastSeenMillis = now
            } else {
                pendingSubtitleText = candidateText
                pendingSubtitleFirstSeenMillis = now
                pendingSubtitleLastSeenMillis = now
                return false
            }
        } else {
            pendingSubtitleLastSeenMillis = now
        }

        if (now - pendingSubtitleFirstSeenMillis >= SUBTITLE_MAX_PENDING_MS) {
            Log.d(TAG, "Accepting rolling $reason: \"${candidateText.take(MAX_DEBUG_TEXT_LENGTH)}\"")
            currentSubtitleText = candidateText
            pendingSubtitleText = ""
            pendingSubtitleFirstSeenMillis = 0L
            pendingSubtitleLastSeenMillis = 0L
            lastSubtitleUpdateMillis = now
            return true
        }

        val requiredStabilityMs = if (candidateText.isImmediateSubtitlePhrase()) {
            SUBTITLE_FAST_STABILITY_MS
        } else {
            SUBTITLE_STABILITY_MS
        }
        if (now - pendingSubtitleFirstSeenMillis < requiredStabilityMs) {
            return false
        }

        Log.d(TAG, "Accepting $reason: \"${candidateText.take(MAX_DEBUG_TEXT_LENGTH)}\"")
        currentSubtitleText = candidateText
        pendingSubtitleText = ""
        pendingSubtitleFirstSeenMillis = 0L
        pendingSubtitleLastSeenMillis = 0L
        lastSubtitleUpdateMillis = now
        return true
    }

    private fun handleEmptySubtitle(now: Long) {
        pendingSubtitleText = ""
        pendingSubtitleFirstSeenMillis = 0L
        pendingSubtitleLastSeenMillis = 0L
        if (currentSubtitleText.isNotBlank() && now - lastSubtitleUpdateMillis < SUBTITLE_EMPTY_CLEAR_DELAY_MS) {
            return
        }
        currentSubtitleText = ""
        lastDisplayedSubtitleText = ""
        overlayManager.hide()
        overlayAvailable = false
    }

    private fun clearSubtitleIfStale(now: Long) {
        if (currentSubtitleText.isBlank() || now - lastSubtitleUpdateMillis < SUBTITLE_EMPTY_CLEAR_DELAY_MS) {
            return
        }
        currentSubtitleText = ""
        pendingSubtitleText = ""
        pendingSubtitleFirstSeenMillis = 0L
        pendingSubtitleLastSeenMillis = 0L
        lastDisplayedSubtitleText = ""
        overlayManager.hide()
        overlayAvailable = false
    }

    private fun translateAndShowSubtitle(sourceText: String) {
        translationRequestId += 1
        val requestId = translationRequestId
        translationJob?.cancel()
        translationJob = serviceScope.launch {
            val translatedText = translateSubtitle(sourceText)
            if (requestId == translationRequestId && sourceText == currentSubtitleText) {
                showSubtitle(translatedText)
            } else {
                Log.d(TAG, "Dropping stale subtitle translation for \"${sourceText.take(MAX_DEBUG_TEXT_LENGTH)}\"")
            }
        }
    }

    private suspend fun translateSubtitle(sourceText: String): String {
        val sourceLanguage = sourceText.detectLikelySourceLanguage()
        if (sourceLanguage == TARGET_LANGUAGE) {
            Log.d(TAG, "Subtitle appears to be Vietnamese; showing source text")
            return sourceText
        }

        if (!translationModelMessageShown) {
            val message = "Preparing offline subtitle translation model"
            Log.d(TAG, message)
            updateNotification(message)
            publishStatus(message, isRunning = true)
            showSubtitle("Preparing subtitle translation...")
            translationModelMessageShown = true
        }

        val pair = LanguagePair(sourceLanguage, TARGET_LANGUAGE)
        return when (val result = translationRepository.translate(sourceText, pair)) {
            is Result.Success -> {
                val translatedText = result.data.translatedText.ifBlank { sourceText }
                Log.d(
                    TAG,
                    "Translated subtitle $sourceLanguage->$TARGET_LANGUAGE: " +
                        "\"${translatedText.take(MAX_DEBUG_TEXT_LENGTH)}\"",
                )
                translatedText
            }
            is Result.Error -> {
                Log.w(TAG, "Translation failed for $sourceLanguage->$TARGET_LANGUAGE; showing source text", result.exception)
                sourceText
            }
            Result.Loading -> sourceText
        }
    }

    private fun showSubtitle(text: String) {
        lastSubtitleUpdateMillis = System.currentTimeMillis()
        ensureOverlayVisible()
        if (overlayAvailable) {
            lastDisplayedSubtitleText = text
            overlayManager.updateSubtitle(text)
        }
    }

    private fun Bitmap.subtitleRegion(): SubtitleRegion {
        val isLandscape = width > height
        val topRatio = if (isLandscape) {
            LANDSCAPE_SUBTITLE_REGION_TOP_RATIO
        } else {
            PORTRAIT_SUBTITLE_REGION_TOP_RATIO
        }
        val top = (height * topRatio).toInt().coerceIn(0, height - 1)
        val bottom = (height * SUBTITLE_REGION_BOTTOM_RATIO).toInt().coerceIn(top + 1, height)
        return SubtitleRegion(
            boundingBox = BoundingBox(0, top, width, bottom),
            width = width,
            height = bottom - top,
            mode = if (isLandscape) "landscape" else "portrait",
        )
    }

    private fun List<TextBlock>.filterSubtitleCandidates(sourceWidth: Int, sourceHeight: Int): List<TextBlock> {
        val internalTopCutoff = if (sourceWidth > sourceHeight) {
            LANDSCAPE_SUBTITLE_REGION_INTERNAL_TOP_CUTOFF
        } else {
            PORTRAIT_SUBTITLE_REGION_INTERNAL_TOP_CUTOFF
        }
        return asSequence()
            .filter { block ->
                val rect = block.boundingBox.toRect()
                val text = block.text.trim()
                val verticalCenter = rect.centerY()
                val horizontalCenterRatio = rect.centerX().toFloat() / sourceWidth
                val width = rect.width()
                val height = rect.height()
                verticalCenter > sourceHeight * internalTopCutoff &&
                    horizontalCenterRatio > SUBTITLE_HORIZONTAL_MIN_CENTER_RATIO &&
                    horizontalCenterRatio < SUBTITLE_HORIZONTAL_MAX_CENTER_RATIO &&
                    width >= sourceWidth * MIN_SUBTITLE_LINE_WIDTH_RATIO &&
                    height >= MIN_SUBTITLE_LINE_HEIGHT_PX &&
                    text.length >= MIN_TEXT_LENGTH &&
                    text.any { it.isLetter() } &&
                    !text.isLikelyControlText() &&
                    !text.isLikelyMixedScriptGarbage() &&
                    !text.isLikelyOwnOverlayText()
            }
            .sortedWith(compareBy<TextBlock> { it.boundingBox.top }.thenBy { it.boundingBox.left })
            .toList()
    }

    private fun List<TextBlock>.toSubtitleCandidate(sourceHeight: Int): String {
        if (isEmpty()) return ""
        val clusters = mutableListOf<MutableList<TextBlock>>()
        forEach { block ->
            val currentCluster = clusters.lastOrNull()
            val lastLine = currentCluster?.lastOrNull()
            val gap = if (lastLine == null) Int.MAX_VALUE else block.boundingBox.top - lastLine.boundingBox.bottom
            if (currentCluster == null || gap > sourceHeight * SUBTITLE_CLUSTER_GAP_RATIO) {
                clusters += mutableListOf(block)
            } else {
                currentCluster += block
            }
        }

        return clusters
            .maxWithOrNull(compareBy<MutableList<TextBlock>> { cluster ->
                cluster.maxOf { it.boundingBox.bottom }
            }.thenBy { cluster ->
                cluster.sumOf { it.text.length }
            })
            .orEmpty()
            .takeLast(MAX_SUBTITLE_LINES)
            .joinToString("\n") { block -> block.text.trim() }
    }

    private fun String.normalizeSubtitle(): String =
        lineSequence()
            .map { line ->
                line.trim()
                    .cleanMostlyLatinOcrNoise()
                    .replace(WHITESPACE_REGEX, " ")
            }
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_SUBTITLE_LINES)
            .joinToString("\n")
            .take(MAX_SUBTITLE_TEXT_LENGTH)

    private fun String.isWorthTranslating(): Boolean {
        val words = wordsForMatching()
        if (lineSequence().count { it.isNotBlank() } >= 2 && words.size >= MIN_TRANSLATABLE_WORDS_FOR_MULTILINE) {
            return true
        }
        return words.size >= MIN_TRANSLATABLE_WORDS ||
            (length >= MIN_TRANSLATABLE_CHARS && endsWithSentencePunctuation())
    }

    private fun String.isImmediateSubtitlePhrase(): Boolean {
        val words = wordsForMatching()
        return endsWithSentencePunctuation() ||
            words.size >= MIN_IMMEDIATE_TRANSLATABLE_WORDS ||
            (lineSequence().count { it.isNotBlank() } >= 2 && words.size >= MIN_IMMEDIATE_MULTILINE_WORDS)
    }

    private fun String.isLikelyControlText(): Boolean =
        normalizedForMatching().let { normalized ->
            CONTROL_TEXT_NOISE.any { noise -> normalized == noise || normalized.contains(noise) } ||
                YOUTUBE_TIME_TEXT_REGEX.containsMatchIn(normalized)
        }

    private fun String.isLikelyMixedScriptGarbage(): Boolean {
        val latinLetters = count { it.isLatinLetter() }
        val cjkCharacters = count { it.isCjkCharacter() }
        return latinLetters >= MIN_LATIN_LETTERS_FOR_MIXED_GARBAGE &&
            cjkCharacters in 1..MAX_CJK_NOISE_CHARS_IN_LATIN_LINE
    }

    private fun String.isLikelyOwnOverlayText(): Boolean {
        val displayed = lastDisplayedSubtitleText.normalizedForMatching()
        val candidate = normalizedForMatching()
        if (displayed.isBlank() || candidate.isBlank()) return false
        return displayed.contains(candidate) ||
            candidate.contains(displayed) ||
            candidate.wordOverlapRatio(displayed) >= OWN_OVERLAY_WORD_OVERLAP_RATIO
    }

    private fun String.isRelatedSubtitleCandidate(other: String): Boolean {
        val current = normalizedForMatching()
        val previous = other.normalizedForMatching()
        if (current.isBlank() || previous.isBlank()) return false
        return current.startsWith(previous) ||
            previous.startsWith(current) ||
            current.wordOverlapRatio(previous) >= RELATED_SUBTITLE_WORD_OVERLAP_RATIO ||
            previous.wordOverlapRatio(current) >= RELATED_SUBTITLE_WORD_OVERLAP_RATIO
    }

    private fun String.normalizedForMatching(): String =
        lowercase()
            .replace(CONTROL_TEXT_NORMALIZER_REGEX, " ")
            .replace(WHITESPACE_REGEX, " ")
            .trim()

    private fun String.cleanMostlyLatinOcrNoise(): String {
        val latinLetters = count { it.isLatinLetter() }
        val cjkCharacters = count { it.isCjkCharacter() }
        if (
            latinLetters < MIN_LATIN_LETTERS_FOR_MIXED_GARBAGE ||
            cjkCharacters !in 1..MAX_CJK_NOISE_CHARS_IN_LATIN_LINE
        ) {
            return this
        }
        return filterNot { it.isCjkCharacter() }
            .trimStart { it in SUSPICIOUS_LEADING_MARKS }
            .trim()
    }

    private fun String.wordOverlapRatio(other: String): Double {
        val words = wordsForMatching().toSet()
        if (words.isEmpty()) return 0.0
        val otherWords = other.wordsForMatching().toSet()
        if (otherWords.isEmpty()) return 0.0
        return words.intersect(otherWords).size.toDouble() / words.size
    }

    private fun String.wordsForMatching(): List<String> =
        normalizedForMatching()
            .split(' ')
            .filter { it.length >= MIN_MATCH_WORD_LENGTH }

    private fun String.endsWithSentencePunctuation(): Boolean =
        trim().lastOrNull() in SENTENCE_ENDING_CHARS

    private fun String.detectLikelySourceLanguage(): String =
        when {
            count { it.isHangulCharacter() } >= MIN_NON_LATIN_SOURCE_CHARS -> "ko"
            count { it.isKanaCharacter() } >= MIN_NON_LATIN_SOURCE_CHARS -> "ja"
            count { it.isCjkCharacter() } >= MIN_NON_LATIN_SOURCE_CHARS &&
                count { it.isCjkCharacter() } * CJK_SOURCE_DOMINANCE_FACTOR >= count { it.isLatinLetter() } -> "zh"
            any { it in VIETNAMESE_MARKS } -> "vi"
            else -> DEFAULT_SOURCE_LANGUAGE
        }

    private fun Char.isLatinLetter(): Boolean =
        this in 'A'..'Z' || this in 'a'..'z'

    private fun Char.isCjkCharacter(): Boolean =
        this in '\u4e00'..'\u9fff'

    private fun Char.isKanaCharacter(): Boolean =
        this in '\u3040'..'\u30ff'

    private fun Char.isHangulCharacter(): Boolean =
        this in '\uac00'..'\ud7af'

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

    private data class SubtitleRegion(
        val boundingBox: BoundingBox,
        val width: Int,
        val height: Int,
        val mode: String,
    )

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
        private const val MIN_OCR_INTERVAL_MS = 450L
        private const val NOTIFICATION_FRAME_INTERVAL = 5
        private const val FIRST_AUDIO_PROBE_DELAY_MS = 6_000L
        private const val SECOND_AUDIO_PROBE_DELAY_MS = 20_000L
        private const val AUDIO_PROBE_DURATION_MS = 2_500L
        private const val MAX_DEBUG_OCR_LINES = 8
        private const val MAX_DEBUG_TEXT_LENGTH = 48
        private const val PORTRAIT_SUBTITLE_REGION_TOP_RATIO = 0.38f
        private const val LANDSCAPE_SUBTITLE_REGION_TOP_RATIO = 0.50f
        private const val SUBTITLE_REGION_BOTTOM_RATIO = 0.96f
        private const val LANDSCAPE_SUBTITLE_REGION_INTERNAL_TOP_CUTOFF = 0.52f
        private const val PORTRAIT_SUBTITLE_REGION_INTERNAL_TOP_CUTOFF = 0.30f
        private const val SUBTITLE_HORIZONTAL_MIN_CENTER_RATIO = 0.12f
        private const val SUBTITLE_HORIZONTAL_MAX_CENTER_RATIO = 0.88f
        private const val SUBTITLE_CLUSTER_GAP_RATIO = 0.08f
        private const val SUBTITLE_STABILITY_MS = 450L
        private const val SUBTITLE_FAST_STABILITY_MS = 220L
        private const val SUBTITLE_MAX_PENDING_MS = 1_050L
        private const val STREAMING_SUBTITLE_UPDATE_INTERVAL_MS = 950L
        private const val NEW_SUBTITLE_IMMEDIATE_UPDATE_INTERVAL_MS = 700L
        private const val SUBTITLE_EMPTY_CLEAR_DELAY_MS = 1_600L
        private const val MIN_SUBTITLE_LINE_WIDTH_RATIO = 0.025f
        private const val MIN_SUBTITLE_LINE_HEIGHT_PX = 10
        private const val MIN_TEXT_LENGTH = 2
        private const val MIN_MATCH_WORD_LENGTH = 3
        private const val MIN_TRANSLATABLE_WORDS = 4
        private const val MIN_TRANSLATABLE_WORDS_FOR_MULTILINE = 3
        private const val MIN_IMMEDIATE_TRANSLATABLE_WORDS = 7
        private const val MIN_IMMEDIATE_MULTILINE_WORDS = 5
        private const val MIN_TRANSLATABLE_CHARS = 16
        private const val MIN_EXTENSION_CHARS = 12
        private const val MIN_EXTENSION_WORDS = 2
        private const val MAX_SUBTITLE_LINES = 3
        private const val MAX_SUBTITLE_TEXT_LENGTH = 220
        private const val OWN_OVERLAY_WORD_OVERLAP_RATIO = 0.55
        private const val RELATED_SUBTITLE_WORD_OVERLAP_RATIO = 0.45
        private const val MIN_LATIN_LETTERS_FOR_MIXED_GARBAGE = 4
        private const val MAX_CJK_NOISE_CHARS_IN_LATIN_LINE = 4
        private const val MIN_NON_LATIN_SOURCE_CHARS = 2
        private const val CJK_SOURCE_DOMINANCE_FACTOR = 2
        private const val DEFAULT_SOURCE_LANGUAGE = "en"
        private const val TARGET_LANGUAGE = "vi"
        private val WHITESPACE_REGEX = Regex("\\s+")
        private val CONTROL_TEXT_NORMALIZER_REGEX = Regex("[^\\p{L}\\p{Nd}]+")
        private val YOUTUBE_TIME_TEXT_REGEX = Regex("\\b\\d{1,2}\\s\\d{2}\\s\\d{1,2}\\s\\d{2}\\b")
        private val SENTENCE_ENDING_CHARS = setOf('.', '!', '?', '。', '！', '？')
        private val SUSPICIOUS_LEADING_MARKS = setOf('「', '」', '『', '』', '丨', '|', '一')
        private val CONTROL_TEXT_NOISE = setOf(
            "gif",
            "cc",
            "english",
            "follow",
            "like",
            "share",
            "comment",
            "comments",
            "subscribe",
            "search",
            "open app",
            "more videos",
            "more vide",
            "in this video",
            "install",
            "learn more",
            "sponsored",
            "skip",
            "google play",
            "app store",
            "my ad center",
            "dismiss",
            "tiktok",
            "close others",
            "close all",
            "tong ket",
            "tống ket",
        )
        private val VIETNAMESE_MARKS =
            "ăâđêôơưáàảãạắằẳẵặấầẩẫậéèẻẽẹếềểễệíìỉĩịóòỏõọốồổỗộớờởỡợúùủũụứừửữựýỳỷỹỵ" +
                "ĂÂĐÊÔƠƯÁÀẢÃẠẮẰẲẴẶẤẦẨẪẬÉÈẺẼẸẾỀỂỄỆÍÌỈĨỊÓÒỎÕỌỐỒỔỖỘỚỜỞỠỢÚÙỦŨỤỨỪỬỮỰÝỲỶỸỴ"

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
