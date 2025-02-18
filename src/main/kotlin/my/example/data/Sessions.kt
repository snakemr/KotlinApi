package my.example.data

import io.ktor.server.auth.*
import kotlinx.serialization.Serializable

@Serializable
data class UserSession(val name: String, val id: String) : Principal
