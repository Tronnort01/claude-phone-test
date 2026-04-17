package com.stealthcalc.server.routes

import com.stealthcalc.server.ErrorResponse
import com.stealthcalc.server.auth.TokenAuth
import com.stealthcalc.server.db.Devices
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.io.File
import java.util.UUID

@Serializable
data class FileMetadata(
    val fileId: String,
    val deviceId: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val category: String,
    val uploadedAt: Long,
    val capturedAt: Long? = null,
)

@Serializable
data class FileListResponse(
    val files: List<FileMetadata>,
    val total: Int,
)

private val filesDir: File by lazy {
    val dir = File(System.getenv("FILES_DIR") ?: "uploaded_files")
    dir.mkdirs()
    dir
}

private val fileIndex = mutableListOf<FileMetadata>()
private val indexFile: File by lazy { File(filesDir, ".index.json") }
private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

fun loadFileIndex() {
    if (indexFile.exists()) {
        runCatching {
            val list = json.decodeFromString<List<FileMetadata>>(indexFile.readText())
            fileIndex.clear()
            fileIndex.addAll(list)
        }
    }
}

private fun saveIndex() {
    runCatching { indexFile.writeText(json.encodeToString(fileIndex)) }
}

fun Route.fileRoutes() {
    post("/files/upload") {
        val token = call.request.header("Authorization")?.removePrefix("Bearer ")
        if (token == null) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Missing token"))
            return@post
        }
        val deviceId = TokenAuth.authenticateDevice(token)
        if (deviceId == null) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
            return@post
        }

        val multipart = call.receiveMultipart()
        var fileName = "unknown"
        var mimeType = "application/octet-stream"
        var category = "other"
        var capturedAt: Long? = null
        var savedFile: File? = null
        val fileId = UUID.randomUUID().toString().take(16)

        multipart.forEachPart { part ->
            when (part) {
                is PartData.FormItem -> {
                    when (part.name) {
                        "fileName" -> fileName = part.value
                        "mimeType" -> mimeType = part.value
                        "category" -> category = part.value
                        "capturedAt" -> capturedAt = part.value.toLongOrNull()
                    }
                }
                is PartData.FileItem -> {
                    val deviceDir = File(filesDir, deviceId).apply { mkdirs() }
                    val ext = fileName.substringAfterLast('.', "bin")
                    val dest = File(deviceDir, "$fileId.$ext")
                    part.streamProvider().use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                    savedFile = dest
                }
                else -> {}
            }
            part.dispose()
        }

        val file = savedFile
        if (file == null || !file.exists()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("No file received"))
            return@post
        }

        val meta = FileMetadata(
            fileId = fileId,
            deviceId = deviceId,
            fileName = fileName,
            mimeType = mimeType,
            sizeBytes = file.length(),
            category = category,
            uploadedAt = System.currentTimeMillis(),
            capturedAt = capturedAt,
        )
        synchronized(fileIndex) {
            fileIndex.add(meta)
            saveIndex()
        }

        transaction {
            Devices.update({ Devices.id eq deviceId }) {
                it[lastSeen] = System.currentTimeMillis()
            }
        }

        call.respond(meta)
    }

    get("/files/{deviceId}") {
        val token = call.request.header("Authorization")?.removePrefix("Bearer ")
        if (token == null || TokenAuth.authenticateDevice(token) == null) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
            return@get
        }

        val deviceId = call.parameters["deviceId"] ?: return@get
        val category = call.request.queryParameters["category"]
        val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L

        val filtered = synchronized(fileIndex) {
            fileIndex.filter { meta ->
                meta.deviceId == deviceId &&
                meta.uploadedAt > since &&
                (category == null || meta.category == category)
            }.sortedByDescending { it.uploadedAt }
        }

        call.respond(FileListResponse(files = filtered.take(100), total = filtered.size))
    }

    get("/files/download/{fileId}") {
        val token = call.request.header("Authorization")?.removePrefix("Bearer ")
        if (token == null || TokenAuth.authenticateDevice(token) == null) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
            return@get
        }

        val fileId = call.parameters["fileId"] ?: return@get
        val meta = synchronized(fileIndex) { fileIndex.find { it.fileId == fileId } }
        if (meta == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("File not found"))
            return@get
        }

        val ext = meta.fileName.substringAfterLast('.', "bin")
        val file = File(File(filesDir, meta.deviceId), "$fileId.$ext")
        if (!file.exists()) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("File missing from disk"))
            return@get
        }

        call.response.header(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, meta.fileName).toString()
        )
        call.respondFile(file)
    }
}
