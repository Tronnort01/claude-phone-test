package com.stealthcalc.tasks.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stealthcalc.tasks.viewmodel.TaskFilter
import com.stealthcalc.tasks.viewmodel.TaskListViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskListScreen(
    onBack: () -> Unit,
    onTaskClick: (taskId: String) -> Unit,
    onNewTask: () -> Unit,
    onNavigateToHabits: () -> Unit,
    onNavigateToGoals: () -> Unit,
    viewModel: TaskListViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showQuickAdd by remember { mutableStateOf(false) }
    var quickAddText by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Tasks")
                        if (state.pendingCount > 0) {
                            Text(
                                "${state.pendingCount} pending",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToHabits) {
                        Icon(Icons.Default.Loop, contentDescription = "Habits")
                    }
                    IconButton(onClick = onNavigateToGoals) {
                        Icon(Icons.Default.EmojiEvents, contentDescription = "Goals")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showQuickAdd = true },
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Task")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Filter chips
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(vertical = 8.dp)
            ) {
                item {
                    FilterChip(
                        selected = state.filter == TaskFilter.ALL && state.selectedListId == null,
                        onClick = { viewModel.setFilter(TaskFilter.ALL); viewModel.selectList(null) },
                        label = { Text("All") }
                    )
                }
                item {
                    FilterChip(
                        selected = state.filter == TaskFilter.TODAY,
                        onClick = { viewModel.setFilter(TaskFilter.TODAY) },
                        label = { Text("Today") }
                    )
                }
                item {
                    FilterChip(
                        selected = state.filter == TaskFilter.UPCOMING,
                        onClick = { viewModel.setFilter(TaskFilter.UPCOMING) },
                        label = { Text("Upcoming") }
                    )
                }
                item {
                    FilterChip(
                        selected = state.filter == TaskFilter.COMPLETED,
                        onClick = { viewModel.setFilter(TaskFilter.COMPLETED) },
                        label = { Text("Done") }
                    )
                }

                // List filters
                items(state.lists) { list ->
                    FilterChip(
                        selected = state.selectedListId == list.id,
                        onClick = { viewModel.selectList(list.id) },
                        label = { Text(list.name) }
                    )
                }
            }

            // Task list
            if (state.tasks.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = when (state.filter) {
                            TaskFilter.TODAY -> "Nothing due today"
                            TaskFilter.UPCOMING -> "No upcoming tasks"
                            TaskFilter.COMPLETED -> "No completed tasks"
                            TaskFilter.ALL -> "No tasks yet.\nTap + to add one."
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.tasks, key = { it.id }) { task ->
                        TaskCard(
                            task = task,
                            onToggleCompleted = { viewModel.toggleTaskCompleted(task.id) },
                            onClick = { onTaskClick(task.id) }
                        )
                    }
                }
            }
        }
    }

    // Quick add dialog
    if (showQuickAdd) {
        AlertDialog(
            onDismissRequest = { showQuickAdd = false; quickAddText = "" },
            title = { Text("Quick Add Task") },
            text = {
                OutlinedTextField(
                    value = quickAddText,
                    onValueChange = { quickAddText = it },
                    label = { Text("Task title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.quickAddTask(quickAddText.trim())
                    showQuickAdd = false
                    quickAddText = ""
                }) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { showQuickAdd = false; quickAddText = "" }) { Text("Cancel") }
            }
        )
    }
}
