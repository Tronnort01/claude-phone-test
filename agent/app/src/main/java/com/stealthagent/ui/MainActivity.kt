package com.stealthagent.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.stealthagent.data.AgentRepository
import dagger.hilt.android.AndroidEntryPoint
import java.security.MessageDigest
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var repository: AgentRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        setContent {
            var unlocked by remember { mutableStateOf(false) }

            if (unlocked) {
                SetupScreen(
                    repository = repository,
                    onComplete = { unlocked = false }
                )
            } else {
                CalculatorScreen(
                    onSecretCode = { code ->
                        val hash = sha256(code)
                        if (!repository.isSetupDone) {
                            repository.setSecretCode(hash)
                            unlocked = true
                        } else if (hash == repository.secretCodeHash) {
                            unlocked = true
                        }
                    }
                )
            }
        }
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
