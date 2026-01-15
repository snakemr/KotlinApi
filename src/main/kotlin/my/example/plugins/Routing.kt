package my.example.plugins

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.swagger.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import my.example.Database
import my.example.User
import kotlin.random.Random
import kotlin.random.nextULong

fun Application.configureRouting() {
    val driver = JdbcSqliteDriver("jdbc:sqlite:database.s3db")
    val database = Database(driver)

    routing {

        // При первом обращении к корню / сервиса создаётся база данных
        get("/") {
            try {
                database.userQueries.all().executeAsList()
                call.respondText("""API готов к работе.
                    |POST /collections/users/records: Добавить пользователя (json)
                    |GET /collections/users/records/№: Сведения о пользователе
                    |POST /collections/users/auth-with-password Авторизация
                """.trimMargin())
            } catch (_: Exception) {
                Database.Schema.create(driver)
                call.respondText("База данных успешно создана")
            }
        }

        swaggerUI(path = "swagger", swaggerFile = "openapi/api.yaml")

        // При отправке json-объекта "User" пользователь добавляется в таблицу
        post("/collections/users/records") {
            runCatching {
                val last = database.userQueries.last().executeAsOne().last ?: 0
                val user = call.receive<User>().copy(id = last + 1)
                database.userQueries.add(user)
                call.respond(user)
            }.onFailure {
                call.error("Failed to create record.")
            }
        }

        // При обращении к /user/№ выдаётся объект "пользователь" виде JSON
        get("collections/users/records/{id}") {
            runCatching {
                val id = call.parameters["id"]?.toLongOrNull() ?: throw Exception()
                val user = database.userQueries.user(id).executeAsOne()
                call.respond(user)
            }.onFailure {
                call.error("Failed to get record.")
            }
        }

        post("/collections/users/auth-with-password") {
            runCatching {
                val auth = call.receive<Auth>()
                val user = database.userQueries.auth(auth.identity, auth.password).executeAsOne()
                call.respond(AuthResponse(user, Random.nextULong().toString()))
            }.onFailure {
                call.error("Failed to auth.")
            }
        }
        ///////////////////

        // При обращении к /users выдаётся полный список пользователей в виде JSON
//        get("users") {
//            val users = database.userQueries.all().executeAsList()
//            call.respond(users)
//        }

        // При запросе удаления по адресу /user/№ пользователь удаляется из таблицы
//        delete("user/{id}") {
//            val id = call.parameters["id"]?.toLongOrNull() ?: return@delete
//            database.userQueries.delete(id)
//            call.respondText("Пользователь удалён")
//        }
    }
}

private suspend fun ApplicationCall.error(message: String) = respond(HttpStatusCode.BadRequest, ErrorResponse(
    400, message, object {}
))

private class ErrorResponse(val status: Int, val message: String, val data: Any?)