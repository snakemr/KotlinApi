package my.example.plugins

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import my.example.Database
import my.example.User
import my.example.data.*

fun Application.configureRouting() {
    val driver = JdbcSqliteDriver("jdbc:sqlite:database.s3db")
    val database = Database(driver)

    val userSharedFlow = MutableSharedFlow<DataAction<User>>()
    val userFlow = userSharedFlow.asSharedFlow()
    val userLocks = mutableMapOf<Long, String>()

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
            userSharedFlow.emit(User(id, name).inserted())
        }

        // При отправке json-объекта "User" на адрес /new пользователь добавляется в таблицу
        post("new") {
            val user = call.receive<User>()
            database.userQueries.add(user)
            call.respondText("Пользователь №${user.id} добавлен")
            userSharedFlow.emit(user.inserted())
        }

        // При отправке json-объекта "User" на адрес /user данные пользователя обновляются
        post("user") {
            val user = call.receive<User>()
            database.userQueries.user(user.id).executeAsOneOrNull()?.takeIf { it != user }?. let {
                database.userQueries.update(user.name, user.id)
                call.respondText("Пользователь №${user.id} обновлён")
                userSharedFlow.emit(user.updated())
            }
        }

        // При запросе удаления по адресу /user/№ пользователь удаляется из таблицы
        delete("user/{id}") {
            val id = call.parameters["id"]?.toLongOrNull() ?: return@delete
            database.userQueries.user(id).executeAsOneOrNull()?.let { user ->
                database.userQueries.delete(id)
                call.respondText("Пользователь №$id удалён")
                userSharedFlow.emit(user.deleted())
            }
        }

        // Подписаться на обновления таблицы user
        webSocket("/user") {
            val id = toString().substringAfter('@')
            launch {
                userLocks.forEach {
                    sendSerialized(User(it.key, "").locked())
                }
                userFlow.collect { action ->
                    sendSerialized(action)
                }
            }
            while (coroutineContext.isActive) try {
                receiveDeserialized<DataAction<User>>().let {
                    if (it.action == Action.Lock && it.data.id !in userLocks) {
                        userLocks[it.data.id] = id
                        userSharedFlow.emit(it)
                    } else if (it.action == Action.Unlock && userLocks[it.data.id] == id) {
                        userLocks.remove(it.data.id)
                        userSharedFlow.emit(it)
                    }
                }
            } catch (ex: ClosedReceiveChannelException) {
                userLocks -= userLocks.filterValues { it == id }.keys.onEach {
                    userSharedFlow.emit(User(it, "").unlocked())
                }
                break
            } catch (_: Exception) {}
        }
    }
}
