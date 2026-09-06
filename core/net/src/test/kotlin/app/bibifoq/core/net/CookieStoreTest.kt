package app.bibifoq.core.net

import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class NetscapeCookiesTest {

    private val sample = """
        # Netscape HTTP Cookie File
        # This is a comment

        .example.com	TRUE	/	FALSE	1893456000	session	abc123
        example.com	FALSE	/members	TRUE	1893456000	member	yes
        #HttpOnly_.example.com	TRUE	/	TRUE	0	token	xyz
    """.trimIndent()

    @Test
    fun `reads the fields of a cookies file`() {
        val cookies = NetscapeCookies.parse(sample)
        assertEquals(3, cookies.size)

        val session = cookies.first()
        assertEquals(".example.com", session.domain)
        assertTrue(session.includeSubdomains)
        assertEquals("/", session.path)
        assertFalse(session.secure)
        assertEquals(1893456000L, session.expiresAtSeconds)
        assertEquals("session", session.name)
        assertEquals("abc123", session.value)
        assertFalse(session.httpOnly)
    }

    @Test
    fun `understands the http-only marker that is written as a comment`() {
        // curl and yt-dlp both encode httpOnly as a "#HttpOnly_" prefix rather than a field, so
        // a parser that skips every "#" line silently drops the session token.
        val token = NetscapeCookies.parse(sample).single { it.name == "token" }
        assertTrue(token.httpOnly)
        assertEquals(".example.com", token.domain)
        assertEquals(0L, token.expiresAtSeconds)
    }

    @Test
    fun `ignores comments and blank lines but not data`() {
        assertEquals(0, NetscapeCookies.parse("# just a comment\n\n   \n").size)
        assertNull(NetscapeCookies.parseLine("not\tenough\tfields"))
        assertNull(NetscapeCookies.parseLine(""))
    }

    @Test
    fun `keeps a value that contains its own separators`() {
        val cookie = assertNotNull(
            NetscapeCookies.parseLine(".e.com\tTRUE\t/\tFALSE\t0\tdata\ta=1; b=2"),
        )
        assertEquals("a=1; b=2", cookie.value)
    }

    @Test
    fun `survives a round trip`() {
        val original = NetscapeCookies.parse(sample)
        assertEquals(original, NetscapeCookies.parse(NetscapeCookies.serialize(original)))
    }

    @Test
    fun `converts a browser cookie header into storable cookies`() {
        // This is all a WebView gives back after a sign-in: names and values, no metadata.
        val cookies = NetscapeCookies.fromBrowserCookieHeader(
            host = "www.example.com",
            header = "session=abc123; pref=dark; token=a=b",
            expiresAtSeconds = 2_000_000_000,
        )

        assertEquals(3, cookies.size)
        // Scoped to the registrable host so it also works on the CDN subdomain serving media.
        assertTrue(cookies.all { it.domain == ".example.com" && it.includeSubdomains })
        assertTrue(cookies.all { it.secure })
        assertEquals("abc123", cookies.single { it.name == "session" }.value)
        // A value may legitimately contain "="; splitting on every one loses half of it.
        assertEquals("a=b", cookies.single { it.name == "token" }.value)
    }

    @Test
    fun `ignores junk in a browser cookie header instead of storing it`() {
        assertEquals(
            emptyList(),
            NetscapeCookies.fromBrowserCookieHeader("e.com", " ; ; noequals ; =novalue", 0),
        )
    }

    @Test
    fun `writes a header the engine recognises`() {
        assertTrue(NetscapeCookies.serialize(emptyList()).startsWith(NetscapeCookies.HEADER))
    }

    @Test
    fun `matches hosts without handing a session to a lookalike domain`() {
        val cookie = StoredCookie(".example.com", true, "/", false, 0, "s", "v")

        assertTrue(cookie.matchesHost("example.com"))
        assertTrue(cookie.matchesHost("www.example.com"))
        assertTrue(cookie.matchesHost("cdn.media.example.com"))
        // The one that matters: a naive endsWith would send the session here.
        assertFalse(cookie.matchesHost("evilexample.com"))
        assertFalse(cookie.matchesHost("example.com.attacker.net"))
    }

    @Test
    fun `keeps a host-only cookie on its own host`() {
        val cookie = StoredCookie("example.com", false, "/", false, 0, "s", "v")
        assertTrue(cookie.matchesHost("example.com"))
        assertFalse(cookie.matchesHost("www.example.com"))
    }

    @Test
    fun `matches paths by segment, not by prefix`() {
        val cookie = StoredCookie(".e.com", true, "/members", false, 0, "s", "v")
        assertTrue(cookie.matchesPath("/members"))
        assertTrue(cookie.matchesPath("/members/area"))
        // "/membersonly" is a different path, however similar it looks.
        assertFalse(cookie.matchesPath("/membersonly"))
    }

    @Test
    fun `treats zero as a session cookie rather than as long expired`() {
        val session = StoredCookie(".e.com", true, "/", false, 0, "s", "v")
        assertFalse(session.isExpired(nowSeconds = 1_900_000_000))

        val expired = StoredCookie(".e.com", true, "/", false, 1_000, "s", "v")
        assertTrue(expired.isExpired(nowSeconds = 2_000))
    }
}

class FileCookieStoreTest {

    private lateinit var directory: File
    private lateinit var store: FileCookieStore

    @BeforeEach
    fun setUp() {
        directory = Files.createTempDirectory("cookies").toFile()
        store = FileCookieStore(File(directory, "cookies.txt"), clock = { 1_700_000_000 })
    }

    @AfterEach
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun `has nothing to offer the engine until a site is signed into`() {
        assertNull(store.fileOrNull())
        assertEquals(emptyList(), store.domains())
    }

    @Test
    fun `keeps cookies per domain and lists what is signed in`() {
        store.replaceForDomain(
            "example.com",
            listOf(StoredCookie(".example.com", true, "/", false, 0, "s", "1")),
        )
        store.replaceForDomain(
            "other.net",
            listOf(StoredCookie(".other.net", true, "/", false, 0, "s", "2")),
        )

        assertEquals(listOf("example.com", "other.net"), store.domains())
        assertNotNull(store.fileOrNull())
    }

    @Test
    fun `replacing a domain leaves the others alone`() {
        store.replaceForDomain("a.com", listOf(StoredCookie(".a.com", true, "/", false, 0, "s", "1")))
        store.replaceForDomain("b.com", listOf(StoredCookie(".b.com", true, "/", false, 0, "s", "2")))

        store.replaceForDomain("a.com", listOf(StoredCookie(".a.com", true, "/", false, 0, "s", "new")))

        assertEquals("new", store.all().single { it.domain == ".a.com" }.value)
        assertEquals("2", store.all().single { it.domain == ".b.com" }.value)
    }

    @Test
    fun `signing out of one site does not sign out of the rest`() {
        store.replaceForDomain("a.com", listOf(StoredCookie(".a.com", true, "/", false, 0, "s", "1")))
        store.replaceForDomain("b.com", listOf(StoredCookie(".b.com", true, "/", false, 0, "s", "2")))

        store.removeDomain("a.com")

        assertEquals(listOf("b.com"), store.domains())
    }

    @Test
    fun `sends a cookie only where it belongs`() {
        store.replaceForDomain(
            "example.com",
            listOf(
                StoredCookie(".example.com", true, "/", false, 0, "plain", "1"),
                StoredCookie(".example.com", true, "/", true, 0, "secureOnly", "2"),
                StoredCookie(".example.com", true, "/members", false, 0, "scoped", "3"),
            ),
        )

        val overHttps = store.loadForRequest("https://www.example.com/".toHttpUrl()).map { it.name }
        assertEquals(setOf("plain", "secureOnly"), overHttps.toSet())

        // A secure cookie must never travel in clear.
        val overHttp = store.loadForRequest("http://www.example.com/".toHttpUrl()).map { it.name }
        assertEquals(setOf("plain"), overHttp.toSet())

        val scoped = store.loadForRequest("https://example.com/members/x".toHttpUrl()).map { it.name }
        assertTrue("scoped" in scoped)

        assertEquals(emptyList(), store.loadForRequest("https://elsewhere.net/".toHttpUrl()))
    }

    @Test
    fun `does not send an expired cookie`() {
        store.replaceForDomain(
            "example.com",
            listOf(StoredCookie(".example.com", true, "/", false, 1_000, "old", "1")),
        )
        assertEquals(emptyList(), store.loadForRequest("https://example.com/".toHttpUrl()))
    }

    @Test
    fun `persists what a response asks to keep and not what it does not`() {
        val url = "https://example.com/".toHttpUrl()
        store.saveFromResponse(
            url,
            listOf(
                Cookie.Builder().name("keep").value("1").domain("example.com")
                    .expiresAt(2_000_000_000_000L).build(),
                Cookie.Builder().name("sessionOnly").value("2").domain("example.com").build(),
            ),
        )

        assertEquals(listOf("keep"), store.all().map { it.name })
    }

    @Test
    fun `survives being reloaded from disk`() {
        val file = File(directory, "cookies.txt")
        store.replaceForDomain(
            "example.com",
            listOf(StoredCookie(".example.com", true, "/", false, 0, "s", "value")),
        )

        val reopened = FileCookieStore(file, clock = { 1_700_000_000 })

        assertEquals("value", reopened.all().single().value)
    }

    @Test
    fun `treats an unreadable file as no cookies rather than failing`() {
        File(directory, "cookies.txt").writeText("this is not a cookie file at all")
        assertEquals(emptyList(), store.all())
    }
}
