package com.irregular.xenopowermeter.viewmodel

/** Sample mean since the last clear. The caller owns synchronization. */
internal class PowerStatistics {
    private var sumWatts = 0.0
    private var count = 0L

    fun add(voltage: Float, currentUa: Float) {
        if (!voltage.isFinite() || !currentUa.isFinite()) return
        sumWatts += voltage.toDouble() * currentUa.toDouble() / 1_000_000.0
        count++
    }

    val averageWatts: Float
        get() = if (count == 0L) 0f else (sumWatts / count).toFloat()

    fun clear() {
        sumWatts = 0.0
        count = 0L
    }
}
