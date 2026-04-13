package com.stealthcalc.core.data

import androidx.room.TypeConverter
import com.stealthcalc.recorder.model.CameraFacing
import com.stealthcalc.recorder.model.RecordingType
import com.stealthcalc.tasks.model.Priority
import com.stealthcalc.tasks.model.Recurrence
import com.stealthcalc.tasks.model.RecurrenceType

class Converters {

    // Priority
    @TypeConverter
    fun fromPriority(priority: Priority): String = priority.name

    @TypeConverter
    fun toPriority(value: String): Priority = Priority.valueOf(value)

    // Recurrence (stored as simple string: "DAILY:1" or "WEEKLY:1:1,3,5")
    @TypeConverter
    fun fromRecurrence(recurrence: Recurrence?): String? {
        if (recurrence == null) return null
        val base = "${recurrence.type.name}:${recurrence.interval}"
        return if (recurrence.daysOfWeek != null) {
            "$base:${recurrence.daysOfWeek.joinToString(",")}"
        } else {
            base
        }
    }

    @TypeConverter
    fun toRecurrence(value: String?): Recurrence? {
        if (value == null) return null
        val parts = value.split(":")
        val type = RecurrenceType.valueOf(parts[0])
        val interval = parts[1].toInt()
        val days = if (parts.size > 2) {
            parts[2].split(",").map { it.toInt() }.toSet()
        } else null
        return Recurrence(type, interval, days)
    }

    // RecordingType
    @TypeConverter
    fun fromRecordingType(type: RecordingType): String = type.name

    @TypeConverter
    fun toRecordingType(value: String): RecordingType = RecordingType.valueOf(value)

    // CameraFacing
    @TypeConverter
    fun fromCameraFacing(facing: CameraFacing?): String? = facing?.name

    @TypeConverter
    fun toCameraFacing(value: String?): CameraFacing? = value?.let { CameraFacing.valueOf(it) }

    // Set<String> for tags
    @TypeConverter
    fun fromStringSet(set: Set<String>?): String? = set?.joinToString(",")

    @TypeConverter
    fun toStringSet(value: String?): Set<String>? {
        if (value.isNullOrBlank()) return null
        return value.split(",").toSet()
    }
}
