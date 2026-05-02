package com.screentranslate.core.capture.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.annotation.TargetApi
import android.os.Build
import com.screentranslate.core.common.result.Result
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

class AudioPlaybackProbe @Inject constructor() {
    suspend fun probe(
        mediaProjection: MediaProjection,
        durationMillis: Long = DEFAULT_PROBE_DURATION_MS,
    ): Result<AudioProbeResult> =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Result.Success(unavailableResult())
        } else {
            withContext(Dispatchers.IO) {
                runCatching { probePlaybackAudio(mediaProjection, durationMillis) }
                    .fold(
                        onSuccess = { Result.Success(it) },
                        onFailure = { exception -> Result.Error(exception, exception.message) },
                    )
            }
        }

    @TargetApi(Build.VERSION_CODES.Q)
    private fun probePlaybackAudio(
        mediaProjection: MediaProjection,
        durationMillis: Long,
    ): AudioProbeResult {
        val startedAt = System.currentTimeMillis()
        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE_HZ)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .build()
        val minBufferSizeBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(MIN_BUFFER_SIZE_BYTES)
        val bufferSizeBytes = max(minBufferSizeBytes, SAMPLE_RATE_HZ / 2 * BYTES_PER_SAMPLE)
        val sampleBuffer = ShortArray(bufferSizeBytes / BYTES_PER_SAMPLE)
        val audioRecord = AudioRecord.Builder()
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufferSizeBytes)
            .setAudioPlaybackCaptureConfig(playbackConfig)
            .build()

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            return unavailableResult(System.currentTimeMillis() - startedAt)
        }

        var samplesRead = 0
        var readCalls = 0
        var zeroReadCalls = 0
        var peakAmplitude = 0
        var squareSum = 0.0

        try {
            audioRecord.startRecording()
            val deadline = startedAt + durationMillis.coerceAtLeast(MIN_PROBE_DURATION_MS)
            while (System.currentTimeMillis() < deadline) {
                val read = audioRecord.read(sampleBuffer, 0, sampleBuffer.size, AudioRecord.READ_BLOCKING)
                readCalls += 1
                if (read <= 0) {
                    zeroReadCalls += 1
                    continue
                }

                samplesRead += read
                for (index in 0 until read) {
                    val amplitude = abs(sampleBuffer[index].toInt())
                    peakAmplitude = max(peakAmplitude, amplitude)
                    squareSum += amplitude.toDouble() * amplitude.toDouble()
                }
            }
        } finally {
            runCatching { audioRecord.stop() }
            audioRecord.release()
        }

        val rms = if (samplesRead == 0) 0.0 else sqrt(squareSum / samplesRead)
        val signalDetected = samplesRead > 0 &&
            (peakAmplitude >= SIGNAL_PEAK_THRESHOLD || rms >= SIGNAL_RMS_THRESHOLD)
        return AudioProbeResult(
            supported = true,
            signalDetected = signalDetected,
            durationMillis = System.currentTimeMillis() - startedAt,
            sampleRateHz = SAMPLE_RATE_HZ,
            channelCount = CHANNEL_COUNT,
            samplesRead = samplesRead,
            readCalls = readCalls,
            zeroReadCalls = zeroReadCalls,
            peakAmplitude = peakAmplitude,
            rmsAmplitude = rms,
        )
    }

    private fun unavailableResult(durationMillis: Long = 0L): AudioProbeResult =
        AudioProbeResult(
            supported = false,
            signalDetected = false,
            durationMillis = durationMillis,
            sampleRateHz = SAMPLE_RATE_HZ,
            channelCount = CHANNEL_COUNT,
            samplesRead = 0,
            readCalls = 0,
            zeroReadCalls = 0,
            peakAmplitude = 0,
            rmsAmplitude = 0.0,
        )

    private companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val CHANNEL_COUNT = 1
        const val BYTES_PER_SAMPLE = 2
        const val MIN_BUFFER_SIZE_BYTES = 4_096
        const val DEFAULT_PROBE_DURATION_MS = 2_500L
        const val MIN_PROBE_DURATION_MS = 1_000L
        const val SIGNAL_PEAK_THRESHOLD = 250
        const val SIGNAL_RMS_THRESHOLD = 18.0
    }
}
