package com.stealthcalc.tasks.model

data class Recurrence(
    val type: RecurrenceType,
    val interval: Int = 1,
    val daysOfWeek: Set<Int>? = null
)

enum class RecurrenceType { DAILY, WEEKLY, MONTHLY, CUSTOM }
