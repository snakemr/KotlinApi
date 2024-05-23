package my.example.client

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.gson.*

class Geocoding {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            gson()
        }
    }

    suspend fun geocode(address: String): Location? {
        val query = address.replace(" ", "")
        val response = client.get("$API_GEO?address=$query&key=$API_KEY")
        val result = try {
            response.body<GeoCode>()
        } catch (_: Exception) {
            return null
        }
        return result.results.firstOrNull()?.geometry?.location
    }

    suspend fun directions(from: Location, to: Location): List<Directions.Step> {
        val origin = "${from.lat},${from.lng}"
        val destination = "${to.lat},${to.lng}"
        val response = client.get("$API_DIR?origin=$origin&destination=$destination&key=$API_KEY")
        val result = try {
            response.body<Directions>()
        } catch (_: Exception) {
            return emptyList()
        }
        return result.routes.firstOrNull()?.legs?.firstOrNull()?.steps ?: emptyList()
    }

    data class Location(val lat: Double, val lng: Double)

    data class GeoCode(val results: List<Result>) {
        data class Result(val geometry: Geometry)
        data class Geometry(val location: Location?)
    }

    data class Directions(val routes: List<Route>) {
        data class Route(val legs: List<Path>)
        data class Path(val steps: List<Step>)
        data class Step(val start_location: Location, val end_location: Location)
    }

    companion object {
        private const val API_KEY = "---"
        private const val API_URL = "https://maps.googleapis.com/"
        private const val API_GEO = API_URL + "maps/api/geocode/json"
        private const val API_DIR = API_URL + "maps/api/directions/json"
    }
}