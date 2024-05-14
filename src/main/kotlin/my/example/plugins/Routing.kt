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
                    |POST login: Авторизация пользователя (email, pass)
                    |POST logout: Выход пользователя (token)
                    |POST forgot: Отправка кода сброса пароля (email) - см. вывод в консоли  
                    |POST otp: Код подтверждения (email, otp) после forgot
                    |POST password: Установка пароля (email, pass) после otp
                    |POST balance: Запрос баланса (token)
                    |POST delivery: Доставка json: { track, weight, worth, origin: {...}, destinations: [{...}] }
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
            call.respondText("Пользователь зарегистрирован")
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

        // При обращении к /user/№ выдаётся объект "пользователь" виде JSON
//        get("user/{id}") {
//            val id = call.parameters["id"]?.toLongOrNull() ?: return@get
//            val user = database.userQueries.user(id).executeAsOneOrNull()
//            if (user != null) call.respond(user)
//        }

        // При обращении к /name?id=№ выдаётся имя пользователя
//        get("name") {
//            val id = call.request.queryParameters["id"]?.toLongOrNull() ?: return@get
//            val user = database.userQueries.user(id).executeAsOneOrNull()
//            if (user != null) call.respondText(user.name)
//        }

        // При отправке поля "name" на адрес /add пользователь добавляется в таблицу
//        post("add") {
//            val name = call.receiveParameters()["name"] ?: return@post
//            database.userQueries.insert(name)
//            call.respondText("Пользователь добавлен")
//        }

        // При отправке json-объекта "User" на адрес /new пользователь добавляется в таблицу
//        post("new") {
//            val user = call.receive<User>()
//            database.userQueries.add(user)
//            call.respondText("Пользователь добавлен")
//        }

        // При запросе удаления по адресу /user/№ пользователь удаляется из таблицы
//        delete("user/{id}") {
//            val id = call.parameters["id"]?.toLongOrNull() ?: return@delete
//            database.userQueries.delete(id)
//            call.respondText("Пользователь удалён")
//        }
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