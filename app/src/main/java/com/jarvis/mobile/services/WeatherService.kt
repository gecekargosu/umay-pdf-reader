package com.jarvis.mobile.services

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import com.jarvis.mobile.models.WeatherData
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Fetches weather data from Open-Meteo API (free, no API key required).
 * Uses device GPS location for coordinates.
 */
class WeatherService(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private var lastWeatherData: WeatherData? = null
    private var lastFetchTime: Long = 0

    companion object {
        private const val CACHE_DURATION_MS = 10 * 60 * 1000L // 10 minutes
        // Default: Istanbul, Turkey
        private const val DEFAULT_LAT = 41.0082
        private const val DEFAULT_LON = 28.9784
    }

    /**
     * Get weather data. Returns cached data if fresh enough, otherwise fetches new data.
     */
    fun getWeather(callback: (WeatherData) -> Unit) {
        // Return cached data if fresh
        if (lastWeatherData != null && System.currentTimeMillis() - lastFetchTime < CACHE_DURATION_MS) {
            callback(lastWeatherData!!)
            return
        }

        Thread {
            try {
                val location = getLastKnownLocation()
                val lat = location?.latitude ?: DEFAULT_LAT
                val lon = location?.longitude ?: DEFAULT_LON

                val url = "https://api.open-meteo.com/v1/forecast?" +
                    "latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,relative_humidity_2m,apparent_temperature,precipitation_probability,weather_code,wind_speed_10m" +
                    "&daily=temperature_2m_max,temperature_2m_min" +
                    "&timezone=auto&forecast_days=1"

                val request = Request.Builder()
                    .url(url)
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: run {
                    response.close()
                    return@Thread
                }

                val json = JSONObject(body)
                val current = json.getJSONObject("current")
                val daily = json.getJSONObject("daily")

                val weatherData = WeatherData(
                    temperature = current.getDouble("temperature_2m").toFloat(),
                    feelsLike = current.getDouble("apparent_temperature").toFloat(),
                    humidity = current.getInt("relative_humidity_2m"),
                    weatherCode = current.getInt("weather_code"),
                    precipitationProbability = current.getInt("precipitation_probability"),
                    dailyMaxTemp = daily.getJSONArray("temperature_2m_max").getDouble(0).toFloat(),
                    dailyMinTemp = daily.getJSONArray("temperature_2m_min").getDouble(0).toFloat(),
                    windSpeed = current.getDouble("wind_speed_10m").toFloat(),
                    isLoaded = true
                )

                lastWeatherData = weatherData
                lastFetchTime = System.currentTimeMillis()
                callback(weatherData)

            } catch (_: Exception) {
                callback(WeatherData(isLoaded = false))
            }
        }.start()
    }

    @SuppressLint("MissingPermission")
    private fun getLastKnownLocation(): Location? {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // Try GPS first
        val gpsLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        if (gpsLocation != null) return gpsLocation

        // Try network
        val networkLocation = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        if (networkLocation != null) return networkLocation

        // Try passive
        return locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
    }
}
