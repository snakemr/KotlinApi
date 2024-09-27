package my.example.data


enum class Action { Lock, Unlock, Update, Insert, Delete }

class DataAction <out T>(val action: Action, val data: T)

fun<T> T.locked() = DataAction(Action.Lock, this)
fun<T> T.unlocked() = DataAction(Action.Unlock, this)
fun<T> T.updated() = DataAction(Action.Update, this)
fun<T> T.inserted() = DataAction(Action.Insert, this)
fun<T> T.deleted() = DataAction(Action.Delete, this)
