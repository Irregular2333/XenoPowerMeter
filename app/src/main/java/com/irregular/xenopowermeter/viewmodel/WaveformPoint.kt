package com.irregular.xenopowermeter.viewmodel

/** A displayed sample; points in different segments must never be joined. */
data class WaveformPoint(
    val voltage: Float,
    val currentUa: Float,
    val timeMs: Long,
    val segmentId: Long
)
