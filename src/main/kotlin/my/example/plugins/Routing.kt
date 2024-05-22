package my.example.plugins

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import io.ktor.util.pipeline.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import my.example.Database
import my.example.Session
import my.example.client.Geocoding
import java.awt.Font
import java.io.File
import java.util.*
import javax.swing.BorderFactory
import javax.swing.JFrame
import javax.swing.JLabel
import kotlin.random.Random

fun Application.configureRouting() {
    val driver = JdbcSqliteDriver("jdbc:sqlite:database.s3db")
    val database = Database(driver)
    val geocoding = Geocoding()

    routing {

        // При первом обращении к корню / сервиса создаётся база данных
        get("/") {
            try {
                database.userQueries.all().executeAsList()
                call.respondText("""API готов к работе.
                    |POST register: Добавить пользователя (name, email, phone, pass)
                    |POST login: Авторизация пользователя (email, pass) → Session token
                    |POST forgot: Отправка кода сброса пароля (email) - во всплывающем окне
                    |POST otp: Код подтверждения (email, otp) после forgot
                    |POST password: Установка пароля (email, pass) после otp
                    |далее требуется авторизация (cookie Session token=...)
                    |POST logout: Выход пользователя
                    |POST profile: Запрос профиля
                    |POST balance: Запрос баланса
                    |POST delivery: Доставка json: { track, weight, worth, origin: {...}, destinations: [{...}] }
                    |GET delivery/{track-id}: Информация о пакете → json
                    |POST payment/{track-id}: Запрос оплаты доставки
                    |GET payment/{track-id}: Информация о статусе оплаты
                    |GET tracking/{track-id}: Статус доставки → json
                    |POST feedback/{track-id}: Отправка отзыва
                    |GET history: История транзакций → json
                    |GET chat: Все последние сообщения в чатах
                    |GET chat/№: Все сообщения в чате с пользователем id с указанием непросмотренных
                    |POST chat/№: Отправить сообщение пользователю id (message)
                    |POST chat/seen/№: Отметить сообщение id просмотренным
                    |images/*.png: Изображения 
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
            JFrame("OTP code verification").apply {
                JLabel("OPT code for $email: " + code.toString().padStart(6, '0')).apply {
                    font = Font("Serif", Font.BOLD, 20)
                    border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
                }.let(::add)
                pack()
                isLocationByPlatform = true
                isAlwaysOnTop = true
                isVisible = true
            }
            call.respondText("Your code is \"sent\" to your \"email\"")
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

        get("profile") {
            val token = getAuth(database) ?: return@get
            val profile = database.logonQueries.profile(token).executeAsOneOrNull()
                ?: return@get call.respond(HttpStatusCode.NotFound, "User not found")
            call.respond(profile)
        }

        get("balance") {
            val token = getAuth(database) ?: return@get
            val balance = database.logonQueries.profile(token).executeAsOneOrNull()?.balance
                ?: return@get call.respond(HttpStatusCode.NotFound, "User not found")
            call.respond(balance)
        }

        post("delivery") {
            getAuth(database) ?: return@post
            val delivery = call.receive<Delivery>()
            val track = delivery.track.takeIf { it.isNotEmpty() } ?: "R-${UUID.randomUUID()}"
            try {
                database.packageQueries.delete(track)
                with(delivery) {
                    database.packageQueries.insert(track, items, weight, worth)
                }
            } catch (_: Exception) {
                return@post call.respond(HttpStatusCode.BadRequest, "Package is already registered")
            }
            with(delivery.origin) {
                database.addressQueries.delete(track)
                val geo = geocoding.geocode("$state,$address")
                database.addressQueries.insert(address, state, phone, others, geo?.lat, geo?.lng, track)
            }
            delivery.destinations.forEach {
                val geo = geocoding.geocode("${it.state},${it.address}")
                database.addressQueries.insert(it.address, it.state, it.phone, it.others, geo?.lat, geo?.lng, track)
            }
            call.respond(track)
        }

        get("delivery/{id}") {
            getAuth(database) ?: return@get
            val track = call.parameters["id"] ?: return@get
            val pack = database.packageQueries.get(track).executeAsOneOrNull() ?: return@get
            val address = database.addressQueries.get(track).executeAsList().takeIf { it.isNotEmpty() } ?: return@get
            val delivery = Delivery(
                pack.track, pack.items, pack.weight, pack.worth, address.first(), address.drop(1), pack.status
            )
            call.respond(delivery)
        }

        post("payment/{id}") {
            val token = getAuth(database) ?: return@post
            val balance = database.logonQueries.profile(token).executeAsOneOrNull()?.balance
                ?: return@post call.respond(HttpStatusCode.NotFound, "User not found")
            val track = call.parameters["id"] ?: return@post
            val pack = database.packageQueries.get(track).executeAsOneOrNull()
                ?: return@post call.respond(HttpStatusCode.NotFound, "Package not found")
            val addresses = database.addressQueries.get(track).executeAsList().size - 1
            if (pack.status > Status.Created.ordinal || addresses <= 0)
                return@post call.respond(HttpStatusCode.Conflict, "Bad package status")
            val sum = (addresses * 2_500 + 300) * 1.05
            if (sum > balance)
                return@post call.respond(HttpStatusCode.PaymentRequired, "Insufficient funds")
            val user = database.logonQueries.user(token).executeAsOneOrNull()
                ?: return@post call.respond(HttpStatusCode.NotFound, "User not found")
            database.packageQueries.status(Status.Processing.ordinal.toLong(), track)
            call.respondText(Status.Processing.text)
            launch {
                delay(Random.nextLong(500, 3000))
                database.userQueries.charge(sum, user)
                database.historyQueries.add(user, -sum, pack.items)
                database.packageQueries.status(Status.Successful.ordinal.toLong(), track)
            }
        }

        get("payment/{id}") {
            getAuth(database) ?: return@get
            val track = call.parameters["id"] ?: return@get
            val pack = database.packageQueries.get(track).executeAsOneOrNull()
                ?: return@get call.respond(HttpStatusCode.NotFound, "Package not found")
            call.respondText(Status.entries.getOrNull(pack.status.toInt())?.text ?: "Unknown")
        }

        get("tracking/{id}") {
            getAuth(database) ?: return@get
            val track = call.parameters["id"] ?: return@get
            val pack = database.packageQueries.get(track).executeAsOneOrNull()
                ?: return@get call.respond(HttpStatusCode.NotFound, "Package not found")
            var status = pack.status.takeIf { it >= Status.Successful.ordinal }
                ?: return@get call.respond(HttpStatusCode.NotFound, "Package not sent yet")
            val addresses = database.addressQueries.get(track).executeAsList().takeIf { it.size > 1 }
                ?: return@get call.respond(HttpStatusCode.NotFound, "Destinations not found")
            val history = database.trackingQueries.status(track).executeAsList().map {
                Track(
                    Status.entries.getOrNull(it.status.toInt())?.text ?: "Unknown", it.date, it.lat, it.lng,
                    addresses.find { a -> a.id == it.path }?.address
                )
            }

            call.respond(history)

            if (status < Status.Delivered.ordinal && Random.nextInt(1) == 0) launch {

                val from = addresses.first().takeIf { it.lat != null && it.lng != null }
                    ?.run { Geocoding.Location(lat!!, lng!!) }

                status ++
                if (status < Status.Transit.ordinal) {
                    database.packageQueries.status(status, track)
                    database.trackingQueries.insert(track, status, null, null, null, null)

                    if (status < Status.Ready.ordinal && from != null) {
                        addresses.drop(1).filter { it.lat != null && it.lng != null }.forEach { last ->
                            val to = Geocoding.Location(last.lat!!, last.lng!!)
                            geocoding.directions(from, to).forEach {
                                database.pathQueries.insert(last.id, it.end_location.lat, it.end_location.lng)
                            }
                        }
                    }
                }
                else {
                    val last = database.trackingQueries.status(track).executeAsList().lastOrNull()
                    if (last?.status != Status.Transit.ordinal.toLong()) {
                        database.packageQueries.status(status, track)
                        database.trackingQueries.insert(track, status, addresses[1].id, -1, from?.lat, from?.lng)
                    }
                    else {
                        val path = last.path?.let { database.pathQueries.get(it) }?.executeAsList() ?: emptyList()
                        val next = path.indexOfFirst { it.id == last.point } + 1
                        if (next < path.size) path[next].let {
                            status--
                            database.trackingQueries.delete(track, status)
                            database.trackingQueries.insert(track, status, last.path, it.id, it.lat, it.lng)
                        } else {
                            val new = addresses.indexOfFirst { it.id == last.path } + 1
                            if (new < addresses.size) addresses[new].let {
                                status--
                                database.trackingQueries.delete(track, status)
                                database.trackingQueries.insert(track, status, it.id, -1, from?.lat, from?.lng)
                            } else {
                                database.packageQueries.status(status, track)
                                database.trackingQueries.insert(track, status, null, null, null, null)
                                addresses.drop(1).forEach {
                                    database.pathQueries.delete(it.id)
                                }
                            }
                        }
                    }
                }
            }
        }

        post("feedback/{id}") {
            getAuth(database) ?: return@post
            val track = call.parameters["id"] ?: return@post
            val params = call.receiveParameters()
            val message = params["message"]
            val rating = params["rating"]?.toLongOrNull()
            database.feedbackQueries.delete(track)
            database.feedbackQueries.insert(track, message, rating)
            call.respondText("Feedback is sent")
        }

        get("history") {
            val token = getAuth(database) ?: return@get
            val history = database.logonQueries.history(token).executeAsList()
            call.respond(history)
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

        staticFiles("/images", File("images"))
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