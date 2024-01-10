package my.example.plugins

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import my.example.Database
import my.example.User

fun Application.configureRouting() {
    val driver = JdbcSqliteDriver("jdbc:sqlite:database.s3db")
    val database = Database(driver)

    routing {

        // При первом обращении к корню / сервиса создаётся база данных
        get("/") {
            try {
                database.userQueries.all().executeAsList()
                call.respondText("""API готов к работе.
                    |GET users: Вывод всех пользователей (json)
                    |GET user/№: Вывод пользователя № (json)
                    |POST add: Добавить пользователя (поле name)
                    |POST new: Добавить пользователя (json)
                    |DELETE user/№: Удалить пользователя №
                """.trimMargin())
            } catch (_: Exception) {
                Database.Schema.create(driver)
                call.respondText("База данных успешно создана")
            }
        }

        // При обращении к /users выдаётся полный список пользователей в виде JSON
        get("users") {
            val users = database.userQueries.all().executeAsList()
            call.respond(users)
        }

        // При обращении к /user/№ выдаётся объект "пользователь" виде JSON
        get("user/{id}") {
            val id = call.parameters["id"]?.toLongOrNull() ?: return@get
            val user = database.userQueries.user(id).executeAsOneOrNull()
            if (user != null) call.respond(user)
        }

        // При обращении к /name?id=№ выдаётся имя пользователя
        get("name") {
            val id = call.request.queryParameters["id"]?.toLongOrNull() ?: return@get
            val user = database.userQueries.user(id).executeAsOneOrNull()
            if (user != null) call.respondText(user.name)
        }

        // При отправке поля "name" на адрес /add пользователь добавляется в таблицу
        post("add") {
            val name = call.receiveParameters()["name"] ?: return@post
            database.userQueries.insert(name)
            call.respondText("Пользователь добавлен")
        }

        // При отправке json-объекта "User" на адрес /new пользователь добавляется в таблицу
        post("new") {
            val user = call.receive<User>()
            database.userQueries.add(user)
            call.respondText("Пользователь добавлен")
        }

        // При запросе удаления по адресу /user/№ пользователь удаляется из таблицы
        delete("user/{id}") {
            val id = call.parameters["id"]?.toLongOrNull() ?: return@delete
            database.userQueries.delete(id)
            call.respondText("Пользователь удалён")
        }
    }
}
