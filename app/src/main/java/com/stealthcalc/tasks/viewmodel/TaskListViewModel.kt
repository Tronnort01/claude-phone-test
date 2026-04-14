package com.stealthcalc.tasks.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stealthcalc.tasks.data.TaskRepository
import com.stealthcalc.tasks.model.Priority
import com.stealthcalc.tasks.model.Task
import com.stealthcalc.tasks.model.TaskList
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class TaskFilter { ALL, TODAY, UPCOMING, COMPLETED }

data class TaskListScreenState(
    val tasks: List<Task> = emptyList(),
    val lists: List<TaskList> = emptyList(),
    val selectedListId: String? = null,
    val filter: TaskFilter = TaskFilter.ALL,
    val pendingCount: Int = 0,
)

@HiltViewModel
class TaskListViewModel @Inject constructor(
    private val repository: TaskRepository
) : ViewModel() {

    private val _selectedListId = MutableStateFlow<String?>(null)
    private val _filter = MutableStateFlow(TaskFilter.ALL)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val tasks = combine(_selectedListId, _filter) { listId, filter ->
        Pair(listId, filter)
    }.flatMapLatest { (listId, filter) ->
        when (filter) {
            TaskFilter.TODAY -> repository.getTodayTasks()
            TaskFilter.UPCOMING -> repository.getUpcomingTasks()
            TaskFilter.COMPLETED -> repository.getCompletedTasks()
            TaskFilter.ALL -> {
                if (listId != null) repository.getTasksByList(listId)
                else repository.getAllTasks()
            }
        }
    }

    val state: StateFlow<TaskListScreenState> = combine(
        tasks,
        repository.getAllLists(),
        _selectedListId,
        _filter,
        repository.getPendingCount(),
    ) { tasks, lists, selectedId, filter, pending ->
        TaskListScreenState(
            tasks = tasks,
            lists = lists,
            selectedListId = selectedId,
            filter = filter,
            pendingCount = pending,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = TaskListScreenState()
    )

    fun setFilter(filter: TaskFilter) { _filter.value = filter }
    fun selectList(listId: String?) { _selectedListId.value = listId; _filter.value = TaskFilter.ALL }

    fun toggleTaskCompleted(taskId: String) {
        viewModelScope.launch { repository.toggleTaskCompleted(taskId) }
    }

    fun quickAddTask(title: String) {
        if (title.isBlank()) return
        viewModelScope.launch {
            val listId = _selectedListId.value ?: run {
                // Create a default list if none exists
                val lists = state.value.lists
                if (lists.isEmpty()) {
                    repository.createList("Personal").id
                } else {
                    lists.first().id
                }
            }
            repository.saveTask(Task(listId = listId, title = title))
        }
    }

    fun createList(name: String) {
        viewModelScope.launch { repository.createList(name) }
    }

    fun deleteTask(task: Task) {
        viewModelScope.launch { repository.deleteTask(task) }
    }
}
