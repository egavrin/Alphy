package com.alpha.assistant.device

internal data class DeviceStatusSnapshot(
    val capturedAtEpochMillis: Long,
    val batteryLevelPercent: Int?,
    val batteryStatus: BatteryStatus,
    val externalPowerConnected: Boolean?,
    val localeTag: String? = null,
    val connectivityStatus: ConnectivityStatus = ConnectivityStatus.UNAVAILABLE,
) {
    enum class BatteryStatus {
        CHARGING,
        DISCHARGING,
        FULL,
        NOT_CHARGING,
        UNKNOWN,
    }

    /** Reports Android default network visibility at capture time. */
    enum class ConnectivityStatus {
        DEFAULT_NETWORK_PRESENT,
        NO_ACCESSIBLE_DEFAULT_NETWORK,
        UNAVAILABLE,
    }
}
