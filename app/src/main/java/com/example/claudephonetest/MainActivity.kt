package com.example.claudephonetest

import android.app.Activity
import android.os.Bundle
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

class MainActivity : Activity() {

    private lateinit var geckoSession: GeckoSession
    private var canGoBack = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val geckoView = GeckoView(this)
        setContentView(geckoView)

        val runtime = GeckoRuntime.getDefault(this)
        geckoSession = GeckoSession()

        geckoSession.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                this@MainActivity.canGoBack = canGoBack
            }
        }

        geckoSession.open(runtime)
        geckoView.setSession(geckoSession)
        geckoSession.loadUri("https://www.mozilla.org")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (canGoBack) {
            geckoSession.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        if (::geckoSession.isInitialized) {
            geckoSession.close()
        }
        super.onDestroy()
    }
}
