package my.example.plugins

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import my.example.Database
import my.example.User
import my.example.data.Action
import my.example.data.DataAction
import my.example.data.SharedActions

fun Application.configureRouting() {
    val driver = JdbcSqliteDriver("jdbc:sqlite:database.s3db")
    val database = Database(driver)

    val userActions = SharedActions { User(it, "") }

    routing {

        // При первом обращении к корню / сервиса создаётся база данных
        get("/") {
            try {
                database.userQueries.all().executeAsList()
                call.respondText("""API готов к работе.
                    |GET users: Вывод всех пользователей (json)
                    |GET user/№: Вывод пользователя № (json)
                    |GET name?id=№: Вывод имени пользователя №
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
            val id = database.userQueries.insert(name).executeAsOne()
            call.respondText("Пользователь №$id добавлен")
            userActions.insert(User(id, name))
        }

        // При отправке json-объекта "User" на адрес /new пользователь добавляется в таблицу
        post("new") {
            val user = call.receive<User>()
            if (database.userQueries.user(user.id).executeAsOneOrNull() != null)
                return@post call.respond(HttpStatusCode.Conflict)
            database.userQueries.add(user)
            call.respondText("Пользователь №${user.id} добавлен")
            userActions.insert(user)
        }

        // При отправке json-объекта "User" на адрес /user данные пользователя обновляются
        post("user") {
            val user = call.receive<User>()
            val old = database.userQueries.user(user.id).executeAsOneOrNull()
            if (old == null)
                return@post call.respond(HttpStatusCode.Gone)
            else if (old == user)
                return@post call.respond(HttpStatusCode.NoContent)
            database.userQueries.update(user.name, user.id)
            call.respondText("Пользователь №${user.id} обновлён")
            userActions.update(user)
        }

        // При запросе удаления по адресу /user/№ пользователь удаляется из таблицы
        delete("user/{id}") {
            val id = call.parameters["id"]?.toLongOrNull() ?: return@delete
            database.userQueries.user(id).executeAsOneOrNull()?.let { user ->
                database.userQueries.delete(id)
                call.respondText("Пользователь №$id удалён")
                userActions.delete(user)
            }
        }

        // Подписаться на обновления таблицы user
        webSocket("/user") {
            val session = toString().substringAfter('@')
            launch {
                userActions.locks.forEach {
                    sendSerialized(it)
                }
                userActions.flow.collect {
                    sendSerialized(it)
                }
            }
            while (coroutineContext.isActive) try {
                val (action, data) = receiveDeserialized<DataAction<User>>()
                when (action) {
                    Action.Lock -> userActions.lock(data, session) { it.id }
                    Action.Unlock -> userActions.unlock(data, session) { it.id }
                    else -> {}
                }
            } catch (ex: ClosedReceiveChannelException) {
                userActions.unlockAll(session)
                break
            } catch (_: Exception) {}
        }
    }
}
