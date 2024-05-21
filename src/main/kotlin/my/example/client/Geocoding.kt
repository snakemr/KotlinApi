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
            response.body<Response>()
        } catch (_: Exception) {
            return null
        }
        return result.results.firstOrNull()?.geometry?.location
    }

    data class Response(val results: List<Result>)
    data class Result(val geometry: Geometry)
    data class Geometry(val location: Location?)
    data class Location(val lat: Double, val lng: Double)

    companion object {
        private const val API_KEY = "AIzaSyAk68rqRvfa7NYt0rm3XspguSASPFOA3jE"
        private const val API_URL = "https://maps.googleapis.com/"
        private const val API_GEO = API_URL + "maps/api/geocode/json"
    }
}