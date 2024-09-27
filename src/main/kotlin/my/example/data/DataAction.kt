package my.example.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow


enum class Action { Lock, Unlock, Update, Insert, Delete }

data class DataAction <out T> (val action: Action, val data: T)

class SharedActions <T> (private val convert: (Long)->T) {
    private val sharedFlow = MutableSharedFlow<DataAction<T>>()
    val flow = sharedFlow.asSharedFlow()

    private suspend fun lock(data: T) = sharedFlow.emit(DataAction(Action.Lock, data))
    private suspend fun unlock(data: T) = sharedFlow.emit(DataAction(Action.Unlock, data))
    suspend fun update(data: T) = sharedFlow.emit(DataAction(Action.Update, data))
    suspend fun insert(data: T) = sharedFlow.emit(DataAction(Action.Insert, data))
    suspend fun delete(data: T) = sharedFlow.emit(DataAction(Action.Delete, data))

    private val sessionLocks = mutableMapOf<Long, String>()
    val locks get() = sessionLocks.keys.map(convert).map { DataAction(Action.Lock, it) }

    suspend fun lock(data: T, session: String, convert: (T) -> Long) {
        val id = convert(data)
        if (id !in sessionLocks) {
            sessionLocks[id] = session
            lock(data)
        }
    }
    suspend fun unlock(data: T, session: String, convert: (T) -> Long) {
        val id = convert(data)
        if (sessionLocks[id] == session) {
            sessionLocks -= id
            unlock(data)
        }
    }
    suspend fun unlockAll(id: String) = sessionLocks.filterValues { it == id }.keys.also {
        sessionLocks -= it
    }.onEach {
        unlock(convert(it))
    }
}



