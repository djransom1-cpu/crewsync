package com.example.crewsync.util

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Current conditions for a project's job-site address, built from two free/keyless
 * APIs: the OpenStreetMap Nominatim geocoder (address -> lat/lon) and the National
 * Weather Service (lat/lon -> forecast). NWS only covers US locations.
 * Nominatim is used instead of the Census Bureau geocoder because Census does not
 * send CORS headers, which silently fails every request from the web target. */
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

// Nominatim's usage policy also asks for a self-identifying User-Agent. See
// https://operations.osmfoundation.org/policies/nominatim/
private const val NOMINATIM_USER_AGENT = "Crewsync construction app (crewsync.support@example.com)"

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
private data class NominatimMatch(val lat: String, val lon: String)

private suspend fun geocodeAddress(address: String): Coordinates? {
    val matches: List<NominatimMatch> = weatherHttpClient
        .get("https://nominatim.openstreetmap.org/search") {
            parameter("q", address)
            parameter("format", "json")
            parameter("limit", "1")
            header("User-Agent", NOMINATIM_USER_AGENT)
        }.body()
    val match = matches.firstOrNull() ?: return null
    return Coordinates(latitude = match.lat.toDouble(), longitude = match.lon.toDouble())
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
