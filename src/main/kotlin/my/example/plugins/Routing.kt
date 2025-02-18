package my.example.plugins

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import io.ktor.util.pipeline.*
import my.example.Database
import my.example.data.UserSession
import java.awt.Font
import java.util.*
import javax.swing.BorderFactory
import javax.swing.JFrame
import javax.swing.JLabel
import kotlin.random.Random

fun Application.configureRouting(driver: JdbcSqliteDriver, database: Database) = routing {

    // При первом обращении к корню / сервиса создаётся база данных
    get("/") {
        try {
            database.userQueries.all().executeAsList()
            call.respondText(
                """API готов к работе.
                    |GET hello: Вывод профиля пользователя (требуется авторизация)
                    |GET analysis: Вывод списка анализов (требуется авторизация)
                    |GET history: Вывод истории (требуется авторизация)
                    |POST history: Добавление записи в историю (поле формы new, требуется авторизация)
                    |POST otp: Отправка кода OTP (поле формы mail)
                    |POST check: Авторизация пользователя по коду OTP (поля формы mail и otp)
                    |POST login: Авторизация пользователя по паролю (поля формы mail и pass)
                    |POST logout: Выход из системы
                """.trimMargin()
            )
        } catch (_: Exception) {
            Database.Schema.create(driver)
            call.respondText("База данных успешно создана")
        }
    }

    post("otp") {
        val params = call.receiveParameters()
        val mail = params["mail"] ?: return@post call.respond(HttpStatusCode.BadRequest)
        database.userQueries.user(mail).executeAsOneOrNull()
            ?: return@post call.respondText("Invalid mail", status = HttpStatusCode.BadRequest)
        val otp = Random.nextLong(1000, 10000)
        database.userQueries.otp(otp, mail)
        call.respondText("OTP code was sent")
        JFrame("OTP code verification").apply {
            JLabel("OPT code for $mail: $otp").apply {
                font = Font("Serif", Font.BOLD, 20)
                border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
            }.let(::add)
            pack()
            isLocationByPlatform = true
            isAlwaysOnTop = true
            isVisible = true
        }
    }

    suspend fun PipelineContext<Unit, ApplicationCall>.login() {
        val userName = call.principal<UserIdPrincipal>()?.name.toString()
        val uuid = UUID.randomUUID().toString()
        database.userQueries.session(uuid, userName)
        call.sessions.set(UserSession(userName, uuid))
        val user = database.userQueries.user(userName).executeAsOneOrNull() ?: return
        call.respond(user)
    }

    authenticate("auth-form") {
        post("/login") {
            login()
        }
    }

    authenticate("otp-form") {
        post("/check") {
            login()
        }
    }

    authenticate("auth-session") {

        get("/hello") {
            val session = call.principal<UserSession>() ?: return@get
            val user = database.userQueries.user(session.name).executeAsOneOrNull() ?: return@get
            call.respond(user)
        }

        get("/analysis") {
            val session = call.principal<UserSession>() ?: return@get
            val user = database.userQueries.user(session.name).executeAsOneOrNull() ?: return@get
            val history = database.analysisQueries.all(user.id).executeAsList()
            call.respond(history)
        }

        get("/history") {
            val session = call.principal<UserSession>() ?: return@get
            val user = database.userQueries.user(session.name).executeAsOneOrNull() ?: return@get
            val history = database.historyQueries.all(user.id).executeAsList()
            call.respond(history)
        }

        post("/history") {
            val new = call.receiveParameters()["new"] ?: return@post
            val session = call.principal<UserSession>() ?: return@post
            val user = database.userQueries.user(session.name).executeAsOneOrNull() ?: return@post
            database.historyQueries.insert(user.id, new).executeAsOne()
            val history = database.historyQueries.all(user.id).executeAsList()
            call.respond(history)
        }

        post("/logout") {
            val session = call.principal<UserSession>()
            if (session != null) database.userQueries.logout(session.name)
            call.respondText("Goodbye, ${session?.name}!")
        }

    }
}