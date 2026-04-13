package com.stealthcalc.tasks.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stealthcalc.tasks.data.TaskRepository
import com.stealthcalc.tasks.model.Goal
import com.stealthcalc.tasks.model.Milestone
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GoalViewModel @Inject constructor(
    private val repository: TaskRepository
) : ViewModel() {

    val goals: StateFlow<List<Goal>> = repository.getAllGoals()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun getMilestones(goalId: String): StateFlow<List<Milestone>> =
        repository.getMilestones(goalId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun createGoal(title: String, description: String? = null) {
        viewModelScope.launch { repository.createGoal(title, description) }
    }

    fun addMilestone(goalId: String, title: String) {
        viewModelScope.launch { repository.addMilestone(goalId, title) }
    }

    fun toggleMilestone(milestoneId: String, goalId: String) {
        viewModelScope.launch { repository.toggleMilestone(milestoneId, goalId) }
    }

    fun deleteGoal(goal: Goal) {
        viewModelScope.launch { repository.deleteGoal(goal) }
    }
}
