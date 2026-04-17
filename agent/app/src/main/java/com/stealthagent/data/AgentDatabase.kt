package com.stealthagent.data

import androidx.room.Database
import androidx.room.RoomDatabase
import com.stealthagent.model.MonitoringEvent

@Database(
    entities = [MonitoringEvent::class],
    version = 1,
    exportSchema = false,
)
abstract class AgentDatabase : RoomDatabase() {
    abstract fun agentDao(): AgentDao
}
