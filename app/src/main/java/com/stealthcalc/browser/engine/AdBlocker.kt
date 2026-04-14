package com.stealthcalc.browser.engine

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/**
 * Basic URL-based ad/tracker blocker for the WebView.
 * Blocks requests matching known ad/tracking domains.
 */
object AdBlocker {

    private val blockedDomains = setOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "google-analytics.com",
        "googletagmanager.com",
        "facebook.com/tr",
        "facebook.net/signals",
        "connect.facebook.net",
        "analytics.twitter.com",
        "ads.twitter.com",
        "amazon-adsystem.com",
        "adnxs.com",
        "adsrvr.org",
        "adform.net",
        "criteo.com",
        "criteo.net",
        "outbrain.com",
        "taboola.com",
        "moatads.com",
        "scorecardresearch.com",
        "quantserve.com",
        "bluekai.com",
        "exelator.com",
        "rubiconproject.com",
        "pubmatic.com",
        "openx.net",
        "casalemedia.com",
        "sharethrough.com",
        "indexexchange.com",
        "advertising.com",
        "admob.com",
        "chartbeat.com",
        "hotjar.com",
        "mixpanel.com",
        "segment.io",
        "segment.com",
        "amplitude.com",
        "optimizely.com",
        // Fingerprinting & tracking
        "fingerprintjs.com",
        "cdn.mxpnl.com",
        "bat.bing.com",
        "pixel.facebook.com",
        "tr.snapchat.com",
        "analytics.tiktok.com",
        "sc-static.net",
        "ads.linkedin.com",
        "px.ads.linkedin.com",
        "sentry.io",
        "bugsnag.com",
        "newrelic.com",
        "nr-data.net",
        "demdex.net",
        "omtrdc.net",
        "2mdn.net",
        "serving-sys.com",
        "eyeota.net",
        "krxd.net",
        "company-target.com",
        "rlcdn.com",
        "mathtag.com",
        "everesttech.net",
        "turn.com",
    )

    private val blockedPathPatterns = listOf(
        "/ads/",
        "/ad/",
        "/advert",
        "/tracker",
        "/pixel",
        "/beacon",
        "pagead",
        "adsense",
    )

    fun shouldBlock(request: WebResourceRequest): Boolean {
        val url = request.url.toString().lowercase()
        val host = request.url.host?.lowercase() ?: return false

        // Check blocked domains
        for (domain in blockedDomains) {
            if (host == domain || host.endsWith(".$domain")) {
                return true
            }
        }

        // Check path patterns
        for (pattern in blockedPathPatterns) {
            if (url.contains(pattern)) {
                return true
            }
        }

        return false
    }

    fun createEmptyResponse(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "utf-8",
            ByteArrayInputStream("".toByteArray())
        )
    }
}
