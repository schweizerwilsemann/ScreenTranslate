package com.screentranslate.core.capture.audio

data class AudioProbeResult(
    val supported: Boolean,
    val signalDetected: Boolean,
    val durationMillis: Long,
    val sampleRateHz: Int,
    val channelCount: Int,
    val samplesRead: Int,
    val readCalls: Int,
    val zeroReadCalls: Int,
    val peakAmplitude: Int,
    val rmsAmplitude: Double,
)
