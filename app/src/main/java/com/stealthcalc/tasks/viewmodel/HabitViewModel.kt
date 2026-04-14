package com.stealthcalc.tasks.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stealthcalc.tasks.data.TaskRepository
import com.stealthcalc.tasks.model.Habit
import com.stealthcalc.tasks.model.HabitEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

data class HabitWithStreak(
    val habit: Habit,
    val streak: Int = 0,
    val completedToday: Boolean = false,
    val entries: List<HabitEntry> = emptyList()
)

data class HabitScreenState(
    val habits: List<HabitWithStreak> = emptyList(),
    val selectedDate: Long = todayMidnight(),
)

fun todayMidnight(): Long {
    val cal = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    return cal.timeInMillis
}

@HiltViewModel
class HabitViewModel @Inject constructor(
    private val repository: TaskRepository
) : ViewModel() {

    val habits: StateFlow<List<Habit>> = repository.getAllHabits()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _habitsWithStreaks = MutableStateFlow<List<HabitWithStreak>>(emptyList())
    val habitsWithStreaks: StateFlow<List<HabitWithStreak>> = _habitsWithStreaks.asStateFlow()

    init {
        viewModelScope.launch {
            repository.getAllHabits().collect { habitList ->
                val withStreaks = habitList.map { habit ->
                    val streak = repository.getHabitStreak(habit.id)
                    HabitWithStreak(
                        habit = habit,
                        streak = streak,
                        completedToday = streak > 0
                    )
                }
                _habitsWithStreaks.value = withStreaks
            }
        }
    }

    fun toggleHabitToday(habitId: String) {
        viewModelScope.launch {
            repository.toggleHabitForDay(habitId, todayMidnight())
            // Refresh
            val updated = _habitsWithStreaks.value.map { hws ->
                if (hws.habit.id == habitId) {
                    val newStreak = repository.getHabitStreak(habitId)
                    hws.copy(streak = newStreak, completedToday = newStreak > 0)
                } else hws
            }
            _habitsWithStreaks.value = updated
        }
    }

    fun createHabit(name: String, targetDays: Int = 7) {
        viewModelScope.launch { repository.createHabit(name, targetDays = targetDays) }
    }

    fun deleteHabit(habit: Habit) {
        viewModelScope.launch { repository.deleteHabit(habit) }
    }
}
