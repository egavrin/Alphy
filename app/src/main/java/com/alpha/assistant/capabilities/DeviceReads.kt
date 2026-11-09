package com.alpha.assistant.capabilities

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.alpha.assistant.device.AndroidDeviceStatusSource
import com.alpha.assistant.device.DeviceLocation
import com.alpha.assistant.device.DeviceReading
import com.alpha.assistant.device.DeviceSensor
import com.alpha.assistant.device.DeviceSensorSample
import com.alpha.assistant.device.DeviceStatusSnapshot
import com.alpha.assistant.device.DeviceStatusSnapshot.ConnectivityStatus
import org.json.JSONArray
import org.json.JSONObject

internal object DeviceReadContract {
    const val STATUS = "device.context"
    const val LOCATION = "location.once"
    const val SENSOR = "sensor.sample"
    const val FORMAT = "device.formatPreferences.read"
    val ids = setOf(STATUS, LOCATION, SENSOR, FORMAT)
    val statuses = setOf("succeeded", "denied", "unavailable", "failed", "cancelled")
    private const val MAX_EXACT_INTEGER = 9_007_199_254_740_991L
    val sensors = mapOf("accelerometer" to DeviceSensor.ACCELEROMETER, "gyroscope" to DeviceSensor.GYROSCOPE,
        "ambientLight" to DeviceSensor.AMBIENT_LIGHT)
    private val batteryStatuses = DeviceStatusSnapshot.BatteryStatus.entries.associateWith { status ->
        if (status == DeviceStatusSnapshot.BatteryStatus.NOT_CHARGING) "notCharging" else status.name.lowercase(java.util.Locale.ROOT)
    }
    private val localeTag = Regex("^(?!und(?:-|$))[a-z]{2,8}(?:-[A-Z][a-z]{3})?(?:-(?:[A-Z]{2}|[0-9]{3}))?$")
    private val connectivityStatuses = mapOf(
        ConnectivityStatus.DEFAULT_NETWORK_PRESENT to "defaultNetworkPresent",
        ConnectivityStatus.NO_ACCESSIBLE_DEFAULT_NETWORK to "noAccessibleDefaultNetwork",
        ConnectivityStatus.UNAVAILABLE to "unavailable",
    )
    val definitions = listOf(
        CapabilityDefinition(FORMAT, "Local date and time format", CapabilityAction.DEVICE_READ, emptyMap(),
            "Read the Android 12 or 24-hour setting and the effective locale first weekday.", "succeeded",
            "Returns is24Hour and localeFirstDayOfWeekIso from Monday 1 to Sunday 7.", foreground = true),
        CapabilityDefinition(STATUS, "Состояние устройства", CapabilityAction.DEVICE_READ, emptyMap(),
            "Read current time, battery, power, primary locale and Android default-network visibility from an explicit foreground action.",
            "succeeded", "Capture time uses epoch milliseconds. Unknown battery level, power or locale is null. Unknown battery status is unknown. Locale contains only language, script and region when available. Connectivity reports Android default-network visibility or unavailable. Presence gives no information about internet reachability or local-network access.",
            foreground = true),
        CapabilityDefinition(LOCATION, "Одно текущее местоположение", CapabilityAction.DEVICE_READ, emptyMap(),
            "Read one current Android location from an explicit foreground action. Android approximate location is enough. No background or historical location.",
            "succeeded", "Returns WGS84 degrees and horizontal accuracy in meters or null. Android denial returns denied. No fix or timeout returns unavailable.",
            permission = Manifest.permission.ACCESS_COARSE_LOCATION, foreground = true),
        CapabilityDefinition(SENSOR, "Одно измерение датчика", CapabilityAction.DEVICE_READ,
            mapOf("sensor" to CapabilityArgument("string", maxLength = 13, choices = sensors.keys)),
            "Read one sample of the requested sensor from an explicit foreground action. No continuous monitoring.",
            "succeeded", "Returns accelerometer x/y/z in m/s², gyroscope x/y/z in rad/s, or ambient light in lux. Missing hardware or timeout is unavailable. Invalid samples fail.",
            foreground = true),
    )

    fun locationPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun resultSchema(id: String): JSONObject = when (id) {
        FORMAT -> closed("is24Hour" to JSONObject().put("type", "boolean"),
            "localeFirstDayOfWeekIso" to number("integer", 1, 7))
        STATUS -> closed(
            "capturedAtEpochMillis" to number("integer", 0, MAX_EXACT_INTEGER),
            "batteryLevelPercent" to number("integer", 0, 100, nullable = true),
            "batteryStatus" to JSONObject().put("type", "string").put("enum", JSONArray(batteryStatuses.values.toList())),
            "externalPowerConnected" to JSONObject().put("type", JSONArray(listOf("boolean", "null"))),
            "localeTag" to JSONObject().put("type", JSONArray(listOf("string", "null"))).put("pattern", localeTag.pattern),
            "connectivityStatus" to JSONObject().put("type", "string").put("enum", JSONArray(connectivityStatuses.values.toList())),
        )
        LOCATION -> closed(
            "latitudeDegrees" to number("number", -90, 90),
            "longitudeDegrees" to number("number", -180, 180),
            "horizontalAccuracyMeters" to number("number", 0, Float.MAX_VALUE.toDouble(), nullable = true),
        )
        SENSOR -> JSONObject().put("oneOf", JSONArray(sensors.keys.map { sensor ->
            val discriminator = "sensor" to JSONObject().put("type", "string").put("const", sensor)
            val component = number("number", -Float.MAX_VALUE.toDouble(), Float.MAX_VALUE.toDouble())
            if (sensor == "ambientLight") closed(discriminator, "lux" to number("number", 0, Float.MAX_VALUE.toDouble()))
            else closed(discriminator, "x" to component, "y" to component, "z" to component)
        }))
        else -> error("Unknown device read")
    }

    private fun closed(vararg fields: Pair<String, JSONObject>) = JSONObject().put("type", "object")
        .put("additionalProperties", false).put("required", JSONArray(fields.map { it.first }))
        .put("properties", JSONObject().apply { fields.forEach { (key, schema) -> put(key, schema) } })

    private fun number(type: String, min: Number, max: Number, nullable: Boolean = false) = JSONObject()
        .put("type", if (nullable) JSONArray(listOf(type, "null")) else type).put("minimum", min).put("maximum", max)

    fun validate(id: String, data: JSONObject) {
        fun keys(vararg expected: String) = require(data.keys().asSequence().toSet() == expected.toSet())
        fun bounded(key: String, min: Double, max: Double, integer: Boolean = false) {
            val value = data.get(key)
            require(value is Number && value.toDouble() in min..max && (!integer || value.toLong().toDouble() == value.toDouble()))
        }
        when (id) {
            FORMAT -> {
                keys("is24Hour", "localeFirstDayOfWeekIso")
                require(data.get("is24Hour") is Boolean)
                bounded("localeFirstDayOfWeekIso", 1.0, 7.0, integer = true)
            }
            STATUS -> {
                keys("capturedAtEpochMillis", "batteryLevelPercent", "batteryStatus", "externalPowerConnected", "localeTag", "connectivityStatus")
                bounded("capturedAtEpochMillis", 0.0, MAX_EXACT_INTEGER.toDouble(), integer = true)
                if (!data.isNull("batteryLevelPercent")) bounded("batteryLevelPercent", 0.0, 100.0, integer = true)
                require(data.get("batteryStatus") in batteryStatuses.values)
                require(data.isNull("externalPowerConnected") || data.get("externalPowerConnected") is Boolean)
                val locale = data.get("localeTag")
                require(locale === JSONObject.NULL || locale is String && localeTag.matches(locale))
                require(data.get("connectivityStatus") in connectivityStatuses.values)
            }
            LOCATION -> {
                keys("latitudeDegrees", "longitudeDegrees", "horizontalAccuracyMeters")
                bounded("latitudeDegrees", -90.0, 90.0)
                bounded("longitudeDegrees", -180.0, 180.0)
                if (!data.isNull("horizontalAccuracyMeters")) bounded("horizontalAccuracyMeters", 0.0, Float.MAX_VALUE.toDouble())
            }
            SENSOR -> when (data.get("sensor")) {
                "ambientLight" -> {
                    keys("sensor", "lux")
                    bounded("lux", 0.0, Float.MAX_VALUE.toDouble())
                }
                "accelerometer", "gyroscope" -> {
                    keys("sensor", "x", "y", "z")
                    for (axis in listOf("x", "y", "z")) bounded(axis, -Float.MAX_VALUE.toDouble(), Float.MAX_VALUE.toDouble())
                }
                else -> error("Unknown device sensor")
            }
            else -> error("Unknown device read")
        }
    }

    fun snapshot(value: DeviceStatusSnapshot) = JSONObject()
        .put("capturedAtEpochMillis", value.capturedAtEpochMillis)
        .put("batteryLevelPercent", value.batteryLevelPercent ?: JSONObject.NULL)
        .put("batteryStatus", batteryStatuses.getValue(value.batteryStatus))
        .put("externalPowerConnected", value.externalPowerConnected ?: JSONObject.NULL)
        .put("localeTag", value.localeTag ?: JSONObject.NULL)
        .put("connectivityStatus", connectivityStatuses.getValue(value.connectivityStatus))

    fun cancelled() = CapabilityResult("cancelled", "Чтение отменено. Откройте мини-приложение и повторите действие.")
    fun denied(id: String) = CapabilityResult("denied", if (id == LOCATION)
        "Android не разрешил доступ к местоположению." else "Android не разрешил чтение данных устройства.")
    fun invalid() = CapabilityResult("failed", "Android вернул недействительные данные устройства.")
}

/** Converts host readings to the local capability result. It does not own permission or session authority. */
internal class DeviceReadAdapter(
    private val context: Context,
    private val capture: (() -> DeviceStatusSnapshot)? = null,
    private val location: (suspend () -> DeviceReading<DeviceLocation>)? = null,
    private val sensor: (suspend (DeviceSensor) -> DeviceReading<DeviceSensorSample>)? = null,
) {
    private val source = AndroidDeviceStatusSource()

    suspend fun read(request: CapabilityRequest): CapabilityResult = when (request.capability) {
        DeviceReadContract.FORMAT -> validatedSuccess(request.capability) {
            val locale = context.resources.configuration.locales[0]
            val first = android.icu.util.Calendar.getInstance(locale).firstDayOfWeek
            JSONObject().put("is24Hour", android.text.format.DateFormat.is24HourFormat(context))
                .put("localeFirstDayOfWeekIso", (first + 5) % 7 + 1)
        }
        DeviceReadContract.STATUS -> validatedSuccess(request.capability) { DeviceReadContract.snapshot(capture?.invoke() ?: source.capture(context)) }
        DeviceReadContract.LOCATION -> (location?.invoke() ?: source.currentLocation(context)).toCapabilityResult(request.capability) { value ->
            JSONObject().put("latitudeDegrees", value.latitudeDegrees).put("longitudeDegrees", value.longitudeDegrees)
                .put("horizontalAccuracyMeters", value.horizontalAccuracyMeters?.toDouble() ?: JSONObject.NULL)
        }
        DeviceReadContract.SENSOR -> {
            val name = request.args.getString("sensor")
            val kind = DeviceReadContract.sensors.getValue(name)
            (sensor?.invoke(kind) ?: source.readSensor(context, kind)).toCapabilityResult(request.capability) { sample ->
                val data = JSONObject().put("sensor", name)
                fun vector(x: Float, y: Float, z: Float) = data.put("x", x.toDouble()).put("y", y.toDouble()).put("z", z.toDouble())
                when (sample) {
                    is DeviceSensorSample.Acceleration -> { require(name == "accelerometer"); vector(sample.x, sample.y, sample.z) }
                    is DeviceSensorSample.AngularVelocity -> { require(name == "gyroscope"); vector(sample.x, sample.y, sample.z) }
                    is DeviceSensorSample.AmbientLight -> { require(name == "ambientLight"); data.put("lux", sample.lux.toDouble()) }
                }
            }
        }
        else -> error("Unknown device read")
    }

    private fun <T> DeviceReading<T>.toCapabilityResult(id: String, encode: (T) -> JSONObject): CapabilityResult = when (this) {
        is DeviceReading.Available -> validatedSuccess(id) { encode(value) }
        DeviceReading.PermissionDenied -> DeviceReadContract.denied(id)
        DeviceReading.Unavailable -> CapabilityResult("unavailable", "Android не предоставил измерение.")
        DeviceReading.Unknown -> DeviceReadContract.invalid()
    }

    private fun validatedSuccess(id: String, encode: () -> JSONObject): CapabilityResult = try {
        val data = encode()
        DeviceReadContract.validate(id, data)
        CapabilityResult("succeeded", "Данные получены от Android.", data)
    } catch (_: IllegalArgumentException) { DeviceReadContract.invalid() }
      catch (_: org.json.JSONException) { DeviceReadContract.invalid() }
}
