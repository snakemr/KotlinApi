package my.example.plugins

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.html.respondHtml
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import my.example.Database

fun Application.configureRouting() {
    val driver = JdbcSqliteDriver("jdbc:sqlite:database.s3db")
    val database = Database(driver)

    routing {

        // При первом обращении к корню / сервиса создаётся база данных
        get("/") {
            try {
                database.usersQueries.allUsers().executeAsList()
                call.respondText("API готов к работе")
            } catch (_: Exception) {
                Database.Schema.create(driver)
                call.respondText("База данных успешно создана")
            }
        }

        // При обращении к /users выдаёт полный список пользователей в виде JSON
        get("users") {
            val users = database.usersQueries.allUsers().executeAsList()
            call.respond(users)
        }

        // При отправке поля "name" на адрес /add добавляет пользователя в таблицу
        post("add") {
            val name = call.receiveParameters()["name"] ?: return@post
            database.usersQueries.insert(name)
            call.respondText("Пользователь добавлен")
        }

    }
}
