package com.alpha.assistant.capabilities

import android.content.pm.PackageManager

internal object TiltContract {
    const val ID = "sensor.accelerometer.stream"

    val definition = CapabilityDefinition(ID, "Наклон устройства", CapabilityAction.TILT_STREAM,
        mapOf("enabled" to CapabilityArgument("boolean")),
        "In an immersive app, the host streams tilt while the game plays, the menu is closed, and the app has focus: " +
            "declare the capability and add no button. Otherwise, start or stop tilt from an explicit foreground button. Use a drawing surface. " +
            "While started, input.event has type sensor, sensor accelerometer, sequence, monotonic timestampMillis, " +
            "and x, y, z in display-aligned m/s². Update only state, UI and drawing commands.",
        "succeeded", "Returns active. A native Stop control and foreground lifecycle own the listener.",
        feature = PackageManager.FEATURE_SENSOR_ACCELEROMETER, foreground = true,
        resultData = """{"active":{"type":"boolean"}}""")
}
