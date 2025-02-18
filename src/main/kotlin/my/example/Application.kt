package my.example

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.gson.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.respond
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie
import my.example.data.UserSession
import my.example.plugins.configureRouting

fun main() {
    embeddedServer(Netty, port = 80, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    val driver = JdbcSqliteDriver("jdbc:sqlite:database.s3db")
    val database = Database(driver)

    install(ContentNegotiation) {
        gson {
            setPrettyPrinting()
        }
    }

    install(Sessions) {
        cookie<UserSession>("user_session") {
            cookie.path = "/"
            cookie.maxAgeInSeconds = 3600
        }
    }

    install(Authentication) {
        form("auth-form") {
            userParamName = "mail"
            passwordParamName = "pass"
            validate { credentials ->
                val user = database.userQueries.user(credentials.name).executeAsOneOrNull()
                if (user?.pass == credentials.password)
                    UserIdPrincipal(credentials.name)
                else null
            }
            challenge {
                call.respond(HttpStatusCode.Unauthorized, "mail or pass are not valid")
            }
        }

        form("otp-form") {
            userParamName = "mail"
            passwordParamName = "otp"
            validate { credentials ->
                val user = database.userQueries.user(credentials.name).executeAsOneOrNull()
                if (user?.otp?.toString() == credentials.password)
                    UserIdPrincipal(credentials.name)
                else null
            }
            challenge {
                call.respond(HttpStatusCode.Unauthorized, "mail or otp code are not valid")
            }
        }

        session<UserSession>("auth-session") {
            validate { session ->
                val user = database.userQueries.user(session.name).executeAsOneOrNull()
                if (session.id == user?.session) session else null
            }
            challenge {
                call.respond(HttpStatusCode.Unauthorized, "Authorization needed")
            }
        }
    }

    configureRouting(driver, database)
}