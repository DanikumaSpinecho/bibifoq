package app.bibifoq.core.net

/**
 * Sites hand out very different markup depending on the user agent. The desktop string gets
 * us the full page (with OpenGraph and JSON-LD blocks); some hosts only emit those for a
 * browser-looking client.
 */
object UserAgents {
    const val DESKTOP: String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36"

    const val MOBILE: String =
        "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36"
}
