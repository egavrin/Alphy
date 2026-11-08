package com.alpha.assistant.weather

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import android.os.SystemClock
import com.alpha.assistant.cloud.CloudFailure
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.round

/** Only the resolved place crosses the cloud boundary. Coordinates never enter a chat request. */
data class WeatherPlace(val place: String, val acquiredAt: Long) {
    fun isFresh(now: Long = System.currentTimeMillis()) =
        place.isNotBlank() && place.length <= 200 && place.none(Char::isISOControl) && now - acquiredAt in 0..120000
}

object WeatherRequest {
    const val MANUAL_CITY = "Укажите город для прогноза погоды. Не удалось определить текущее примерное местоположение."
}

interface WeatherLocator {
    fun hasPermission(): Boolean
    suspend fun locate(): WeatherPlace
}

class AndroidWeatherLocator(private val context: Context) : WeatherLocator {
    override fun hasPermission() = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    override suspend fun locate(): WeatherPlace {
        try {
            if(context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                fail("Нет разрешения на примерную геопозицию.")
            val manager = context.getSystemService(LocationManager::class.java)
            if(manager == null || !manager.isLocationEnabled) fail("Геопозиция устройства выключена или недоступна.")
            return withTimeout(18000) {
                val location = suspendCancellableCoroutine { continuation ->
                    val cancellation = CancellationTokenSource()
                    continuation.invokeOnCancellation { cancellation.cancel() }
                    val request = CurrentLocationRequest.Builder()
                        .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                        .setGranularity(Granularity.GRANULARITY_COARSE)
                        .setMaxUpdateAgeMillis(60000).setDurationMillis(10000).build()
                    LocationServices.getFusedLocationProviderClient(context).getCurrentLocation(request, cancellation.token)
                        .addOnSuccessListener { value ->
                            if(continuation.isActive) {
                                if(value == null) continuation.resumeWithException(CloudFailure("weather_location", "Текущая примерная геопозиция недоступна."))
                                else continuation.resume(value)
                            }
                        }.addOnFailureListener { failure -> if(continuation.isActive) continuation.resumeWithException(failure) }
                }
                val age = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1000000
                val acquiredAt = System.currentTimeMillis() - age
                if(!hasPermission()) fail("Разрешение на примерную геопозицию отозвано.")
                if(age !in 0..120000 || !location.hasAccuracy() || location.accuracy !in 0f..50000f)
                    fail("Геопозиция устарела или недостаточно точна для выбора города.")
                if(!Geocoder.isPresent()) fail("Определение названия места недоступно на этом устройстве.")
                val place = suspendCancellableCoroutine<String> { continuation ->
                    // The platform's older synchronous geocoder has no cancellation API. Ignore its late result.
                    Thread {
                        try {
                            @Suppress("DEPRECATION")
                            val address = Geocoder(context, Locale.getDefault()).getFromLocation(
                                round(location.latitude * 100) / 100, round(location.longitude * 100) / 100, 1)?.firstOrNull()
                            val locality = address?.locality?.takeIf { it.isNotBlank() }
                                ?: address?.subAdminArea?.takeIf { it.isNotBlank() }
                                ?: throw CloudFailure("weather_location", "Не удалось определить название места по примерной геопозиции.")
                            val value = listOfNotNull(locality, address?.adminArea, address?.countryName)
                                .filter { it.isNotBlank() }.distinct().joinToString(", ")
                            if(continuation.isActive) continuation.resume(value)
                        } catch(failure: Exception) { if(continuation.isActive) continuation.resumeWithException(failure) }
                    }.apply { name = "weather-place"; isDaemon = true }.start()
                }
                if(!hasPermission()) fail("Разрешение на примерную геопозицию отозвано.")
                WeatherPlace(place, acquiredAt).also { if(!it.isFresh()) fail("Не удалось получить подходящее название места.") }
            }
        } catch(timeout: TimeoutCancellationException) { fail("Время ожидания примерной геопозиции истекло.") }
        catch(cancel: CancellationException) { throw cancel }
        catch(failure: Exception) { fail((failure as? CloudFailure)?.message ?: "Примерная геопозиция недоступна.") }
    }
    private fun fail(reason: String): Nothing = throw CloudFailure("weather_location", if(reason.endsWith(WeatherRequest.MANUAL_CITY)) reason else "$reason ${WeatherRequest.MANUAL_CITY}")
}
