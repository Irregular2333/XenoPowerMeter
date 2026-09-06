package com.irregular.xenopowermeter.data.model

data class RecordEntry(
    val timestamp: Double,
    val voltage: Float,
    val current: Float
)

enum class RangeMode(val code: Int, val displayName: String) {
    AUTO(0, "Auto"),
    LOW(1, "Low (50\u03A9)"),
    MID(2, "Mid (0.5\u03A9)"),
    HIGH(3, "High (5m\u03A9)");

    companion object {
        fun fromCode(code: Int): RangeMode =
            entries.firstOrNull { it.code == code } ?: AUTO
    }
}

data class RangeStatus(
    val mode: RangeMode,
    val hardwareRange: RangeMode
)

enum class CmdType(val code: Int) {
    CAL_GET(0x10),
    RANGE_GET(0x11),
    RANGE_SET(0x12),
    CAL_SET(0x13),
    CAL_RESET(0x14),
    FW_UPDATE(0x15);

    companion object {
        fun fromCode(code: Int): CmdType? =
            entries.firstOrNull { it.code == code }
    }
}

data class CmdResponse(
    val status: Int,
    val payload: ByteArray
) {
    companion object {
        const val STATUS_OK = 0
    }
}
