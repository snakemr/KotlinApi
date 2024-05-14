package my.example.plugins

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import io.ktor.util.pipeline.*
import my.example.Database
import my.example.Session
import java.util.*
import kotlin.random.Random

fun Application.configureRouting() {
    val driver = JdbcSqliteDriver("jdbc:sqlite:database.s3db")
    val database = Database(driver)

    routing {

        // При первом обращении к корню / сервиса создаётся база данных
        get("/") {
            try {
                database.userQueries.all().executeAsList()
                call.respondText("""API готов к работе.
                    |POST register: Добавить пользователя (name, email, phone, pass)
                    |POST login: Авторизация пользователя (email, pass) → cookie Session token
                    |POST forgot: Отправка кода сброса пароля (email) - см. вывод в консоли  
                    |POST otp: Код подтверждения (email, otp) после forgot
                    |POST password: Установка пароля (email, pass) после otp
                    |далее требуется авторизация (cookie Session token=...)
                    |POST logout: Выход пользователя
                    |POST balance: Запрос баланса
                    |POST delivery: Доставка json: { track, weight, worth, origin: {...}, destinations: [{...}] }
                    |GET chat: Все последние сообщения в чатах
                    |GET chat/№: Все сообщения в чате с пользователем id с указанием непросмотренных
                    |POST chat/№: Отправить сообщение пользователю id (message)
                    |POST chat/seen/№: Отметить сообщение id просмотренным
                """.trimMargin())
            } catch (_: Exception) {
                Database.Schema.create(driver)
                call.respondText("База данных успешно создана")
            }
        }

        post("register") {
            val params = call.receiveParameters()
            val name = params["name"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val email = params["email"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val phone = params["phone"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val pass = params["pass"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            if (name.isEmpty() || email.isEmpty() || phone.isEmpty() || pass.isEmpty()) {
                return@post call.respond(HttpStatusCode.BadRequest, "Required parameter is empty")
            }
            database.userQueries.login(email).executeAsOneOrNull()?.let {
                return@post call.respond(HttpStatusCode.Conflict, "Email already registered")
            }
            database.userQueries.insert(name, email, phone, pass)
            call.respondText("User registered")
        }

        post("login") {
            val params = call.receiveParameters()
            val email = params["email"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val pass = params["pass"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            if (email.isEmpty() || pass.isEmpty())
                return@post call.respond(HttpStatusCode.BadRequest, "Required parameter is empty")
            val user = database.userQueries.login(email).executeAsOneOrNull()?.takeIf { it.pass == pass }
                ?: return@post call.respond(HttpStatusCode.Unauthorized, "Email or password is incorrect")
            val token = UUID.randomUUID().toString()
            database.logonQueries.login(email, user.id, token)
            database.forgotQueries.delete(email)
            call.sessions.set(Session(token))
            call.respondText("You are logged in")
            //call.respond(token)
        }

        post("logout") {
//            val token = call.receiveParameters()["token"]?.takeIf { it.isNotEmpty() }
//                ?: return@post call.respond(HttpStatusCode.BadRequest, "Token is empty")
            val token = getAuth(database) ?: return@post
            database.logonQueries.logout(token)
            call.sessions.clear<Session>()
            call.respondText("You are logged out")
        }

        post("forgot") {
            val email = call.receiveParameters()["email"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            if (email.isEmpty())
                return@post call.respond(HttpStatusCode.BadRequest, "Required parameter is empty")
            database.userQueries.login(email).executeAsOneOrNull() ?:
                return@post call.respond(HttpStatusCode.NotFound, "Email not registered")
            val code = Random.nextLong(1_000_000)
            database.forgotQueries.delete(email)
            database.forgotQueries.add(email, code)
            println("FORGOT PASSWORD: OTP CODE FOR $email = $code")
            call.respondText("Your code is sent to your email")
        }

        post("otp") {
            val params = call.receiveParameters()
            val email = params["email"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val otp = params["otp"]?.toLongOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest)
            if (email.isEmpty())
                return@post call.respond(HttpStatusCode.BadRequest, "Required parameter is empty")
            database.forgotQueries.awaiting(email, otp).executeAsOneOrNull()
                ?: return@post call.respond(HttpStatusCode.Unauthorized, "Wrong OTP code")
            database.forgotQueries.getready(email)
            call.respondText("Awaiting a new password")
        }

        post("password") {
            val params = call.receiveParameters()
            val pass = params["pass"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val email = params["email"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            if (email.isEmpty() || pass.isEmpty())
                return@post call.respond(HttpStatusCode.BadRequest, "Required parameter is empty")
            database.forgotQueries.ready(email).executeAsOneOrNull()
                ?: return@post call.respond(HttpStatusCode.Unauthorized, "OTP Verification required")
            database.forgotQueries.delete(email)
            database.userQueries.setpassword(pass, email)
            call.respondText("New password is set")
        }

        get("balance") {
//            val token = call.receiveParameters()["token"]?.takeIf { it.isNotEmpty() }
//                ?: return@post call.respond(HttpStatusCode.BadRequest, "Token is empty")
            val token = getAuth(database) ?: return@get
            val balance = database.logonQueries.balance(token).executeAsOneOrNull()?.balance
                ?: return@get call.respond(HttpStatusCode.NotFound, "User not found")
            call.respond(balance)
        }

        post("delivery") {
            getAuth(database) ?: return@post
            val delivery = call.receive<Delivery>()
            val track = delivery.track.takeIf { it.isNotEmpty() } ?: "R-${UUID.randomUUID()}"
            with(delivery) {
                database.packageQueries.insert(track, weight, worth).executeAsOne()
            }
            with(delivery.origin) {
                database.addressQueries.insert(address, state, phone, others, track).executeAsOne()
            }
            delivery.destinations.forEach {
                database.addressQueries.insert(it.address, it.state, it.phone, it.others, track).executeAsOne()
            }
            call.respond(track)
        }

        get("delivery/{id}") {
            getAuth(database) ?: return@get
            val track = call.parameters["id"] ?: return@get
            val pack = database.packageQueries.get(track).executeAsOneOrNull() ?: return@get
            val address = database.addressQueries.get(track).executeAsList().takeIf { it.isNotEmpty() } ?: return@get
            val delivery = Delivery(pack.track, pack.weight, pack.worth, address.first(), address.drop(1))
            call.respond(delivery)
        }

        post("chat/seen/{id}") {
            val token = getAuth(database) ?: return@post
            val recipient = database.logonQueries.user(token).executeAsOneOrNull() ?: return@post
            val id = call.parameters["id"]?.toLongOrNull() ?: return@post
            database.chatQueries.seen(id, recipient)
            call.respond(HttpStatusCode.OK)
        }

        post("chat/{id}") {
            val token = getAuth(database) ?: return@post
            val sender = database.logonQueries.user(token).executeAsOneOrNull() ?: return@post
            val recipient = call.parameters["id"]?.toLongOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest)
            val message = call.receiveParameters()["message"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            database.chatQueries.send(sender, recipient, message)
            call.respond(HttpStatusCode.OK)
        }

        get("chat/{id}") {
            val token = getAuth(database) ?: return@get
            val user = call.parameters["id"]?.toLongOrNull() ?: return@get
            val messages = database.chatQueries.messages(token, user).executeAsList()
            call.respond(messages)
        }

        get("chat") {
            val token = getAuth(database) ?: return@get
            val user = database.logonQueries.user(token).executeAsOneOrNull() ?: return@get
            val last = database.chatQueries.last(token).executeAsList()
            val messages = database.chatQueries.all(user, last.mapNotNull { it.id }).executeAsList().map { chat ->
                chat.copy(unseen = last.find { it.id==chat.id }?.unseen?.toLong() ?: 0)
            }
            call.respond(messages)
        }
    }
}

private suspend fun PipelineContext<Unit, ApplicationCall>.getAuth(database: Database): String? {
    val token = call.sessions.get<Session>()?.token?.takeIf { it.isNotEmpty() } ?: run {
        call.respond(HttpStatusCode.Unauthorized, "Session token is empty")
        return null
    }
    database.logonQueries.user(token).executeAsOneOrNull() ?: run {
        call.respond(HttpStatusCode.Unauthorized, "Bad authorization token")
        return null
    }
    return token
}