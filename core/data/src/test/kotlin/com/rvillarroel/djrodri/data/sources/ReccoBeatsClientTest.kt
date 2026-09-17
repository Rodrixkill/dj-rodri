package com.rvillarroel.djrodri.data.sources

import com.rvillarroel.djrodri.data.http.PoliteHttp
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class ReccoBeatsClientTest {
    private val server = MockWebServer().apply { start() }
    private val client = ReccoBeatsClient(PoliteHttp(OkHttpClient(), sleep = {}), server.url("/"))

    @AfterEach
    fun stop() = server.shutdown()

    @Test
    fun `features are keyed by the Spotify id taken from the link, since the response has no Spotify id`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"content":[{"id":"66dee58f","href":"https://open.spotify.com/track/6habFhsOp2NvshLv26DqMb",
                "isrc":"USUM71607007","danceability":0.655,"energy":0.797,"key":2,"mode":1,"tempo":177.928,"valence":0.839}]}""",
            ),
        )

        val features = client.features(listOf("6habFhsOp2NvshLv26DqMb", "unknownId"))

        assertEquals(AudioFeatures(177.928, 2, 1, 0.797, 0.655, 0.839), features["6habFhsOp2NvshLv26DqMb"])
        assertFalse("unknownId" in features)
        assertEquals("6habFhsOp2NvshLv26DqMb,unknownId", server.takeRequest().requestUrl!!.queryParameter("ids"))
    }

    @Test
    fun `a big playlist is asked for in chunks of forty`() = runTest {
        repeat(3) { server.enqueue(MockResponse().setBody("""{"content":[]}""")) }

        client.features((1..95).map { "id$it" })

        assertEquals(3, server.requestCount)
    }
}
