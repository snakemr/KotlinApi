package my.example.plugins

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ktor.server.application.Application
import io.ktor.server.application.call
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

        get("users") {
            val users = database.usersQueries.allUsers().executeAsList()
            call.respond(users)
        }

        post("add") {
            val name = call.receiveParameters()["name"] ?: return@post
            database.usersQueries.insert(name)
            call.respondText("Пользователь добавлен")
        }

        get("create") {
            Database.Schema.create(driver)
            call.respondText("База данных успешно создана")
        }

    }
}
