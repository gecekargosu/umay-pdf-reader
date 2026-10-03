package com.jarvis.mobile.models

/**
 * Data class representing weather information from Open-Meteo API.
 */
data class WeatherData(
    val temperature: Float = 0f,
    val feelsLike: Float = 0f,
    val humidity: Int = 0,
    val weatherCode: Int = 0,
    val precipitationProbability: Int = 0,
    val dailyMaxTemp: Float = 0f,
    val dailyMinTemp: Float = 0f,
    val windSpeed: Float = 0f,
    val isLoaded: Boolean = false
) {
    /**
     * Convert WMO weather code to human-readable description and icon.
     */
    fun getWeatherDescription(): String {
        return when (weatherCode) {
            0 -> "Clear Sky ☀️"
            1 -> "Mainly Clear 🌤️"
            2 -> "Partly Cloudy ⛅"
            3 -> "Overcast ☁️"
            45, 48 -> "Foggy 🌫️"
            51, 53, 55 -> "Drizzle 🌧️"
            56, 57 -> "Freezing Drizzle 🌧️❄️"
            61, 63, 65 -> "Rain 🌧️"
            66, 67 -> "Freezing Rain 🌧️❄️"
            71, 73, 75 -> "Snow ❄️"
            77 -> "Snow Grains ❄️"
            80, 81, 82 -> "Showers 🌦️"
            85, 86 -> "Snow Showers 🌨️"
            95 -> "Thunderstorm ⛈️"
            96, 99 -> "Thunderstorm + Hail ⛈️🧊"
            else -> "Unknown"
        }
    }

    /**
     * Get a weather emoji based on the code.
     */
    fun getWeatherEmoji(): String {
        return when (weatherCode) {
            0 -> "☀️"
            1, 2 -> "🌤️"
            3 -> "☁️"
            45, 48 -> "🌫️"
            51, 53, 55, 56, 57 -> "🌧️"
            61, 63, 65, 66, 67 -> "🌧️"
            71, 73, 75, 77, 85, 86 -> "❄️"
            80, 81, 82 -> "🌦️"
            95, 96, 99 -> "⛈️"
            else -> "🌡️"
        }
    }
}
