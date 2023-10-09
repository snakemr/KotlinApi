package my.example.plugins

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.http.content.staticFiles
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import my.example.Database
import java.io.File

fun Application.configureRouting() {
    val driver = JdbcSqliteDriver("jdbc:sqlite:database.s3db")
    val database = Database(driver)

    routing {

        // При первом обращении к корню / сервиса создаётся база данных
        get("/") {
            try {
                database.modelQueries.all().executeAsList()
                call.respondText("""API готов к работе.
                    |GET models: Вывод всех моделей (json)
                    |GET model/№: Вывод модели № (json)
                    |POST add: Добавить пользователя (поле name)
                    |POST new: Добавить пользователя (json)
                    |DELETE user/№: Удалить пользователя №
                """.trimMargin())
            } catch (_: Exception) {
                Database.Schema.create(driver)
                call.respondText("База данных успешно создана")
            }
        }

        // При обращении к /models выдаётся полный список в виде JSON ᖬᖬᖬ
        get("models") {
            val models = database.modelQueries.all().executeAsList()
            call.respond(models)
        }

        // При обращении к /model/№ выдаётся объект "модель" виде JSON
        get("model/{id}") {
            val id = call.parameters["id"]?.toLongOrNull() ?: return@get
            val model = database.modelQueries.model(id).executeAsOneOrNull()
            if (model != null) call.respond(model)
        }

        // При обращении к /fav/№ выдаётся список id моделей в виде JSON
        get("faves/{name}") {
            val name = call.parameters["name"] ?: return@get
            val faves = database.favQueries.faves(name).executeAsList()
            call.respond(faves)
        }

        post("fav/{id}") {
            val id = call.parameters["id"]?.toLongOrNull() ?: return@post
            val form = call.receiveParameters()
            val name = form["name"] ?: return@post
            val pas1 = form["pass"] ?: return@post
            val pas2 = database.userQueries.pass(name).executeAsOneOrNull()
            if (pas1 != pas2) return@post
            val fav = database.favQueries.fav(name, id).executeAsOneOrNull()
            if (fav == null)
                database.favQueries.fave(name, id)
            else
                database.favQueries.unfave(name, id)
            call.respondText("ok")
        }

        // При обращении к /cart/№ выдаётся список id моделей в виде JSON
        get("cart/{name}") {
            val name = call.parameters["name"] ?: return@get
            val cart = database.cartQueries.models(name).executeAsList()
            call.respond(cart)
        }

        // При обращении к /cart/№/№ выдаётся количество заказанных экземпляров
        get("cart/{name}/{id}") {
            val name = call.parameters["name"] ?: return@get
            val id = call.parameters["id"]?.toLongOrNull() ?: return@get
            val amount = database.cartQueries.amount(name, id).executeAsOneOrNull()
            call.respond(amount ?: 0L)
        }

        post("cart/{id}") {
            val id = call.parameters["id"]?.toLongOrNull() ?: return@post
            val form = call.receiveParameters()
            val amount = form["amount"]?.toLongOrNull() ?: 1
            val name = form["name"] ?: return@post
            val pas1 = form["pass"] ?: return@post
            val pas2 = database.userQueries.pass(name).executeAsOneOrNull()
            if (pas1 != pas2) return@post
            database.cartQueries.remove(name, id)
            if (amount > 0) database.cartQueries.add(name, id, amount)
            call.respondText("ok")
        }

        // При обращении к /cost/№ выдаётся сумма заказа
        get("cost/{name}") {
            val name = call.parameters["name"] ?: return@get
            val cost = database.cartQueries.cart(name).executeAsOneOrNull()?.cost?.toLong() ?: 0
            call.respond(cost)
        }

        // При обращении к /money/№ выдаётся сумма на счету пользователя
        get("money/{name}") {
            val name = call.parameters["name"] ?: return@get
            val money = database.userQueries.money(name).executeAsOneOrNull()
            if (money != null) call.respond(money)
        }

        post("buy") {
            val form = call.receiveParameters()
            val name = form["name"] ?: return@post
            val pas1 = form["pass"] ?: return@post
            val pas2 = database.userQueries.pass(name).executeAsOneOrNull()
            if (pas1 != pas2) return@post
            val money = database.userQueries.money(name).executeAsOneOrNull() ?: 0
            val cost = database.cartQueries.cart(name).executeAsOneOrNull()?.cost?.toLong() ?: 0
            if (cost > 0 && cost < money) {
                database.userQueries.minus(cost, name)
                database.cartQueries.clear(name)
                call.respondText("ok")
            }
        }

        post("login") {
            val form = call.receiveParameters()
            val name = form["name"] ?: return@post
            val pas1 = form["pass"] ?: return@post
            val pas2 = database.userQueries.pass(name).executeAsOneOrNull()
            if (pas1 == pas2)
                call.respondText("ok")
            else
                call.respond(HttpStatusCode.Forbidden)
        }


//        // При отправке поля "name" на адрес /add пользователь добавляется в таблицу
//        post("add") {
//            val name = call.receiveParameters()["name"] ?: return@post
//            database.modelQueries.insert(name)
//            call.respondText("Пользователь добавлен")
//        }
//
//        // При отправке json-объекта "User" на адрес /new пользователь добавляется в таблицу
//        post("new") {
//            val user = call.receive<Model>()
//            database.modelQueries.add(user)
//            call.respondText("Пользователь добавлен")
//        }
//
//        // При запросе удаления по адресу /user/№ пользователь удаляется из таблицы
//        delete("user/{id}") {
//            val id = call.parameters["id"]?.toLongOrNull() ?: return@delete
//            database.modelQueries.delete(id)
//            call.respondText("Пользователь удалён")
//        }

        staticFiles("/images", File("images"))
    }
}
