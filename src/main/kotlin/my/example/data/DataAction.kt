package my.example.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow


enum class Action { Update, Insert, Delete }

class DataAction <out T>(val action: Action, val data: T)

class SharedActions <T> {
    private val sharedFlow = MutableSharedFlow<DataAction<T>>()
    val flow = sharedFlow.asSharedFlow()

    suspend fun update(data: T) = sharedFlow.emit(DataAction(Action.Update, data))
    suspend fun insert(data: T) = sharedFlow.emit(DataAction(Action.Insert, data))
    suspend fun delete(data: T) = sharedFlow.emit(DataAction(Action.Delete, data))
}