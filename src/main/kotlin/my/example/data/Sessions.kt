package my.example.data

import io.ktor.server.auth.*
import kotlinx.serialization.Serializable

@Serializable
data class UserSession(val u: Long, val i: String) : Principal
