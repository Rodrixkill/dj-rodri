package com.rvillarroel.djrodri.data.sources

import com.rvillarroel.djrodri.data.http.PoliteHttp
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class LrclibClientTest {
    private val server = MockWebServer().apply { start() }
    private val sleeps = mutableListOf<Long>()
    private val client = LrclibClient(PoliteHttp(OkHttpClient(), sleep = { sleeps += it }), server.url("/"))
    private val query = LyricsQuery("Despacito", "Luis Fonsi", "Vida", durationMs = 229_000)

    @AfterEach
    fun stop() = server.shutdown()

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setBody(body)

    @Test
    fun `an exact match with synced lyrics is used directly`() = runTest {
        server.enqueue(json("""{"id":1,"duration":229.0,"instrumental":false,"plainLyrics":"x","syncedLyrics":"[00:01.00] Ay"}"""))

        assertEquals(Lyrics.Synced("[00:01.00] Ay"), client.lyrics(query))
        val request = server.takeRequest()
        assertEquals("/api/get", request.requestUrl!!.encodedPath)
        assertEquals("229", request.requestUrl!!.queryParameter("duration"))
        assertTrue(request.getHeader("User-Agent")!!.startsWith("DJRodri"))
    }

    @Test
    fun `when the album does not match, search finds the version with the same duration`() = runTest {
        server.enqueue(json("""{"code":404,"name":"TrackNotFound"}""", code = 404))
        server.enqueue(
            json(
                """[
                  {"duration":312.0,"instrumental":false,"syncedLyrics":"[00:02.00] remix"},
                  {"duration":230.0,"instrumental":false,"syncedLyrics":"[00:01.00] original"}
                ]""",
            ),
        )

        assertEquals(Lyrics.Synced("[00:01.00] original"), client.lyrics(query))
        server.takeRequest()
        assertEquals("/api/search", server.takeRequest().requestUrl!!.encodedPath)
    }

    @Test
    fun `plain lyrics without timestamps are reported as such`() = runTest {
        server.enqueue(json("""{"duration":229.0,"instrumental":false,"plainLyrics":"sin tiempos","syncedLyrics":null}"""))
        server.enqueue(json("""[{"duration":229.0,"instrumental":false,"plainLyrics":"sin tiempos"}]"""))

        assertEquals(Lyrics.PlainOnly, client.lyrics(query))
    }

    @Test
    fun `an instrumental track is known to have no lyrics`() = runTest {
        server.enqueue(json("""{"duration":229.0,"instrumental":true,"plainLyrics":null,"syncedLyrics":null}"""))

        assertEquals(Lyrics.Instrumental, client.lyrics(query))
    }

    @Test
    fun `nothing anywhere is not found`() = runTest {
        server.enqueue(json("{}", code = 404))
        server.enqueue(json("[]"))

        assertEquals(Lyrics.NotFound, client.lyrics(query))
    }

    @Test
    fun `a rate limit is waited out using Retry-After`() = runTest {
        server.enqueue(json("", code = 429).setHeader("Retry-After", "2"))
        server.enqueue(json("""{"duration":229.0,"syncedLyrics":"[00:01.00] ok"}"""))

        assertEquals(Lyrics.Synced("[00:01.00] ok"), client.lyrics(query))
        assertEquals(listOf(2_000L), sleeps)
    }

    @Test
    fun `a service that keeps failing is an error, not a silent not-found`() = runTest {
        repeat(3) { server.enqueue(json("", code = 503)) }

        assertThrows<SourceException> { client.lyrics(query) }
        assertEquals(2, sleeps.size)
    }
}
