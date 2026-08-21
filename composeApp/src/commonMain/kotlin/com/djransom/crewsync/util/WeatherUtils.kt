package com.djransom.crewsync.util

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Current conditions for a project's job-site address, built from our own "geocode"
 * Cloud Function (address -> lat/lon, proxying the Census Bureau geocoder server-side
 * for its superior US/rural address coverage - Census itself never sends CORS headers,
 * so browsers can't call it directly, and OpenStreetMap/Nominatim's community-mapped
 * coverage is too sparse for many rural job sites) and the National Weather Service
 * (lat/lon -> forecast). NWS only covers US locations. */
data class LocalWeather(
    val currentTempF: Int,
    val shortForecast: String,
    val highF: Int?,
    val lowF: Int?
)

private val weatherHttpClient = HttpClient {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
}

// NWS requires an identifying User-Agent on every request - it has no API key mechanism,
// this is how they ask requesters to self-identify instead. See https://www.weather.gov/documentation/services-web-api
private const val NWS_USER_AGENT = "(Crewsync construction app, crewsync.support@example.com)"

private const val GEOCODE_FUNCTION_URL =
    "https://us-central1-gen-lang-client-0438127279.cloudfunctions.net/geocode"

suspend fun fetchLocalWeather(address: String): LocalWeather? {
    if (address.isBlank()) return null
    return try {
        val coordinates = geocodeAddress(address) ?: return null
        val forecastUrl = fetchNwsForecastUrl(coordinates) ?: return null
        val periods = fetchNwsForecastPeriods(forecastUrl)
        periodsToWeather(periods)
    } catch (_: Exception) {
        null
    }
}

private data class Coordinates(val latitude: Double, val longitude: Double)

@Serializable
private data class GeocodeFunctionResponse(val lat: Double? = null, val lon: Double? = null)

private suspend fun geocodeAddress(address: String): Coordinates? {
    val response: GeocodeFunctionResponse = weatherHttpClient
        .get(GEOCODE_FUNCTION_URL) {
            parameter("address", address)
        }.body()
    val lat = response.lat ?: return null
    val lon = response.lon ?: return null
    return Coordinates(latitude = lat, longitude = lon)
}

@Serializable
private data class NwsPointsResponse(val properties: NwsPointsProperties)

@Serializable
private data class NwsPointsProperties(val forecast: String)

private suspend fun fetchNwsForecastUrl(coordinates: Coordinates): String? {
    // NWS asks that coordinates be rounded to 4 decimal places.
    val lat = (kotlin.math.round(coordinates.latitude * 10000) / 10000)
    val lon = (kotlin.math.round(coordinates.longitude * 10000) / 10000)
    val response: NwsPointsResponse = weatherHttpClient
        .get("https://api.weather.gov/points/$lat,$lon") {
            header("User-Agent", NWS_USER_AGENT)
        }.body()
    return response.properties.forecast
}

@Serializable
private data class NwsForecastResponse(val properties: NwsForecastProperties)

@Serializable
private data class NwsForecastProperties(val periods: List<NwsPeriod> = emptyList())

@Serializable
private data class NwsPeriod(
    val temperature: Int,
    val isDaytime: Boolean,
    val shortForecast: String
)

private suspend fun fetchNwsForecastPeriods(forecastUrl: String): List<NwsPeriod> {
    val response: NwsForecastResponse = weatherHttpClient
        .get(forecastUrl) {
            header("User-Agent", NWS_USER_AGENT)
        }.body()
    return response.properties.periods
}

private fun periodsToWeather(periods: List<NwsPeriod>): LocalWeather? {
    val current = periods.firstOrNull() ?: return null
    val highPeriod = periods.firstOrNull { it.isDaytime }
    val lowPeriod = periods.firstOrNull { !it.isDaytime }
    return LocalWeather(
        currentTempF = current.temperature,
        shortForecast = current.shortForecast,
        highF = highPeriod?.temperature,
        lowF = lowPeriod?.temperature
    )
}
