package com.stealthcalc.tasks.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stealthcalc.tasks.data.TaskRepository
import com.stealthcalc.tasks.model.Priority
import com.stealthcalc.tasks.model.Recurrence
import com.stealthcalc.tasks.model.Task
import com.stealthcalc.tasks.model.TaskList
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TaskDetailState(
    val taskId: String = "",
    val title: String = "",
    val description: String = "",
    val listId: String = "",
    val priority: Priority = Priority.MEDIUM,
    val dueDate: Long? = null,
    val reminderTime: Long? = null,
    val recurrence: Recurrence? = null,
    val isCompleted: Boolean = false,
    val isNew: Boolean = true,
    val subtasks: List<Task> = emptyList(),
)

@HiltViewModel
class TaskDetailViewModel @Inject constructor(
    private val repository: TaskRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val taskId: String? = savedStateHandle["taskId"]

    private val _state = MutableStateFlow(TaskDetailState())
    val state: StateFlow<TaskDetailState> = _state.asStateFlow()

    val allLists: StateFlow<List<TaskList>> = repository.getAllLists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        if (taskId != null && taskId != "new") {
            loadTask(taskId)
        } else {
            _state.value = TaskDetailState(
                taskId = java.util.UUID.randomUUID().toString(),
                isNew = true
            )
        }
    }

    private fun loadTask(id: String) {
        viewModelScope.launch {
            val task = repository.getTaskById(id) ?: return@launch
            _state.value = TaskDetailState(
                taskId = task.id,
                title = task.title,
                description = task.description ?: "",
                listId = task.listId,
                priority = task.priority,
                dueDate = task.dueDate,
                reminderTime = task.reminderTime,
                recurrence = task.recurrence,
                isCompleted = task.isCompleted,
                isNew = false,
            )
        }
    }

    fun onTitleChanged(title: String) { _state.update { it.copy(title = title) } }
    fun onDescriptionChanged(desc: String) { _state.update { it.copy(description = desc) } }
    fun onPriorityChanged(p: Priority) { _state.update { it.copy(priority = p) } }
    fun onDueDateChanged(date: Long?) { _state.update { it.copy(dueDate = date) } }
    fun onListChanged(listId: String) { _state.update { it.copy(listId = listId) } }
    fun onReminderChanged(time: Long?) { _state.update { it.copy(reminderTime = time) } }

    fun save() {
        val s = _state.value
        if (s.title.isBlank()) return
        viewModelScope.launch {
            val task = Task(
                id = s.taskId,
                listId = s.listId,
                title = s.title,
                description = s.description.ifBlank { null },
                priority = s.priority,
                dueDate = s.dueDate,
                reminderTime = s.reminderTime,
                recurrence = s.recurrence,
                isCompleted = s.isCompleted,
            )
            repository.saveTask(task)
        }
    }

    fun delete() {
        viewModelScope.launch {
            val task = repository.getTaskById(_state.value.taskId) ?: return@launch
            repository.deleteTask(task)
        }
    }

    fun addSubtask(title: String) {
        if (title.isBlank()) return
        viewModelScope.launch {
            val subtask = Task(
                listId = _state.value.listId,
                title = title,
                parentTaskId = _state.value.taskId
            )
            repository.saveTask(subtask)
        }
    }

    fun toggleSubtask(subtaskId: String) {
        viewModelScope.launch { repository.toggleTaskCompleted(subtaskId) }
    }
}
