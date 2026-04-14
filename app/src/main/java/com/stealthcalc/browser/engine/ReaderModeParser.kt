package com.stealthcalc.browser.engine

/**
 * Simple reader mode parser that extracts article content from HTML.
 * Strips navigation, ads, sidebars, and returns clean content.
 */
object ReaderModeParser {

    data class Article(
        val title: String,
        val content: String,
        val textContent: String,
    )

    /**
     * Parses raw HTML and extracts the main article content.
     * Uses simple heuristics — not as sophisticated as Readability.js
     * but good enough for personal use.
     */
    fun parse(html: String, url: String): Article {
        val title = extractTitle(html)
        val content = extractMainContent(html)
        val textContent = stripHtmlTags(content)

        return Article(
            title = title,
            content = content,
            textContent = textContent,
        )
    }

    private fun extractTitle(html: String): String {
        // Try og:title first
        val ogTitle = Regex("""<meta[^>]*property="og:title"[^>]*content="([^"]*)"[^>]*>""")
            .find(html)?.groupValues?.get(1)
        if (!ogTitle.isNullOrBlank()) return decodeHtmlEntities(ogTitle)

        // Fallback to <title> tag
        val titleTag = Regex("""<title[^>]*>(.*?)</title>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)
        if (!titleTag.isNullOrBlank()) return decodeHtmlEntities(titleTag.trim())

        // Try h1
        val h1 = Regex("""<h1[^>]*>(.*?)</h1>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)
        if (!h1.isNullOrBlank()) return stripHtmlTags(decodeHtmlEntities(h1.trim()))

        return "Untitled"
    }

    private fun extractMainContent(html: String): String {
        // Remove scripts, styles, nav, header, footer, aside
        var cleaned = html
        val removePatterns = listOf(
            """<script[\s\S]*?</script>""",
            """<style[\s\S]*?</style>""",
            """<nav[\s\S]*?</nav>""",
            """<header[\s\S]*?</header>""",
            """<footer[\s\S]*?</footer>""",
            """<aside[\s\S]*?</aside>""",
            """<iframe[\s\S]*?</iframe>""",
            """<noscript[\s\S]*?</noscript>""",
            """<!--[\s\S]*?-->""",
        )
        for (pattern in removePatterns) {
            cleaned = Regex(pattern, RegexOption.IGNORE_CASE).replace(cleaned, "")
        }

        // Try to find <article> tag
        val article = Regex("""<article[^>]*>([\s\S]*?)</article>""", RegexOption.IGNORE_CASE)
            .find(cleaned)?.groupValues?.get(1)
        if (!article.isNullOrBlank() && article.length > 200) {
            return wrapInReaderHtml(article)
        }

        // Try to find main content div by common class names
        val contentPatterns = listOf(
            """<div[^>]*class="[^"]*(?:article|post|entry|content|story)[^"]*"[^>]*>([\s\S]*?)</div>""",
            """<main[^>]*>([\s\S]*?)</main>""",
        )
        for (pattern in contentPatterns) {
            val match = Regex(pattern, RegexOption.IGNORE_CASE).find(cleaned)
            if (match != null && match.groupValues[1].length > 200) {
                return wrapInReaderHtml(match.groupValues[1])
            }
        }

        // Fallback: extract all <p> tags
        val paragraphs = Regex("""<p[^>]*>([\s\S]*?)</p>""", RegexOption.IGNORE_CASE)
            .findAll(cleaned)
            .map { it.groupValues[1] }
            .filter { it.length > 50 }
            .joinToString("\n\n") { "<p>$it</p>" }

        if (paragraphs.isNotBlank()) {
            return wrapInReaderHtml(paragraphs)
        }

        return wrapInReaderHtml("<p>Could not extract article content.</p>")
    }

    private fun wrapInReaderHtml(content: String): String {
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>
                    body {
                        font-family: -apple-system, system-ui, sans-serif;
                        background: #1a1a1a;
                        color: #e0e0e0;
                        max-width: 680px;
                        margin: 0 auto;
                        padding: 20px;
                        line-height: 1.7;
                        font-size: 18px;
                    }
                    h1, h2, h3 { color: #fff; line-height: 1.3; }
                    h1 { font-size: 28px; margin-bottom: 16px; }
                    p { margin-bottom: 16px; }
                    a { color: #6b9eff; }
                    img { max-width: 100%; height: auto; border-radius: 8px; }
                    blockquote {
                        border-left: 3px solid #444;
                        margin-left: 0;
                        padding-left: 16px;
                        color: #aaa;
                    }
                    code {
                        background: #2a2a2a;
                        padding: 2px 6px;
                        border-radius: 4px;
                        font-size: 15px;
                    }
                    pre {
                        background: #2a2a2a;
                        padding: 16px;
                        border-radius: 8px;
                        overflow-x: auto;
                    }
                </style>
            </head>
            <body>$content</body>
            </html>
        """.trimIndent()
    }

    private fun stripHtmlTags(html: String): String {
        return html.replace(Regex("<[^>]*>"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun decodeHtmlEntities(text: String): String {
        return text
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
    }
}
