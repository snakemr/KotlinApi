package my.example.plugins

import my.example.User

data class Auth(
    val identity: String,
    val password: String,
)

data class AuthResponse(
    val record: User,
    val token: String
)