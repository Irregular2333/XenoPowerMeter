package com.irregular.xenopowermeter.viewmodel

/**
 * Give each newly opened USB stream 100 ms to drain cached packets and finish
 * startup/control transfers before presenting a live waveform. This is based
 * on packet receipt time, not collector scheduling, signal amplitude or range.
 * Recording remains independent; clear/start-recording must not restart it.
 */
internal class WaveformStartupGate {
    private var firstReceivedUs: Long? = null
    private var ready = false

    fun acceptPacket(receivedAtUs: Long): Boolean {
        if (ready) return true
        val first = firstReceivedUs ?: receivedAtUs.also { firstReceivedUs = it }
        ready = receivedAtUs - first >= STARTUP_PREVIEW_DELAY_US
        return ready
    }

    fun reset() {
        firstReceivedUs = null
        ready = false
    }

    private companion object {
        const val STARTUP_PREVIEW_DELAY_US = 100_000L
    }
}
