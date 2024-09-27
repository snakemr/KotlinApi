package my.example.data


enum class Action { Update, Insert, Delete }

class DataAction <out T>(val action: Action, val data: T)

fun<T> T.updated() = DataAction(Action.Update, this)
fun<T> T.inserted() = DataAction(Action.Insert, this)
fun<T> T.deleted() = DataAction(Action.Delete, this)
