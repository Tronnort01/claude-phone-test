package com.stealthcalc.server.routes

import com.stealthcalc.server.db.Events
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.io.File

private val logger = LoggerFactory.getLogger("Cleanup")

fun startRetentionCleanup(retentionDays: Int = 30) {
    val scope = CoroutineScope(Dispatchers.IO)
    scope.launch {
        while (isActive) {
            delay(6 * 3600 * 1000L)
            runCatching {
                val cutoff = System.currentTimeMillis() - retentionDays * 24L * 3600 * 1000L

                val deleted = transaction {
                    Events.deleteWhere { capturedAt less cutoff }
                }
                if (deleted > 0) {
                    logger.info("Retention cleanup: deleted $deleted events older than $retentionDays days")
                }

                cleanupOldFiles(cutoff)
            }.onFailure { e ->
                logger.error("Retention cleanup error: ${e.message}")
            }
        }
    }
}

private fun cleanupOldFiles(cutoffMs: Long) {
    val filesDir = File(System.getenv("FILES_DIR") ?: "uploaded_files")
    if (!filesDir.exists()) return

    var cleaned = 0
    filesDir.listFiles()?.forEach { deviceDir ->
        if (!deviceDir.isDirectory) return@forEach
        deviceDir.listFiles()?.forEach { file ->
            if (file.name.startsWith(".")) return@forEach
            if (file.lastModified() < cutoffMs) {
                file.delete()
                cleaned++
            }
        }
    }
    if (cleaned > 0) {
        logger.info("File cleanup: deleted $cleaned old files")
    }
}
