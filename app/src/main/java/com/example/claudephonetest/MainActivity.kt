package com.example.claudephonetest

import android.app.Activity
import android.os.Bundle
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

class MainActivity : Activity() {

    private lateinit var geckoSession: GeckoSession

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val geckoView = GeckoView(this)
        setContentView(geckoView)

        val runtime = GeckoRuntime.getDefault(this)
        geckoSession = GeckoSession()
        geckoSession.open(runtime)
        geckoView.setSession(geckoSession)
        geckoSession.loadUri("https://www.mozilla.org")
    }

    override fun onDestroy() {
        if (::geckoSession.isInitialized) {
            geckoSession.close()
        }
        super.onDestroy()
    }
}
