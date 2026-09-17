package com.rvillarroel.djrodri.data.ai

import com.rvillarroel.djrodri.domain.dj.DjMode
import com.rvillarroel.djrodri.domain.dj.EnergyCurve
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LlmIntentInterpreterTest {

    private class FakeEngine(
        var answer: String? = null,
        var failure: Exception? = null,
        status: LlmStatus = LlmStatus.Ready("gemma", "GPU", 1_000),
    ) : LlmEngine {
        override val status = MutableStateFlow(status)
        var lastSystem: String? = null
        var lastUser: String? = null

        override suspend fun json(systemInstruction: String, userMessage: String, jsonSchema: String, timeoutMs: Long): String? {
            lastSystem = systemInstruction
            lastUser = userMessage
            failure?.let { throw it }
            return answer
        }

        override fun unload() = Unit
    }

    private val context = DjContext(hourOfDay = 23, currentEnergy = 0.55, mode = DjMode.FIESTA)

    @Test
    fun `a good answer becomes the intent the DJ follows`() = runTest {
        val engine = FakeEngine(
            """{"label":"Perreo intenso","targetEnergy":0.85,"curve":"BUILD","minDanceability":0.7,
               "minBpm":88,"maxBpm":102,"excludeArtists":["Maluma"],"mode":"HORA_LOCA"}""",
        )

        val result = LlmIntentInterpreter(engine).interpret("algo bien perreo pero sin Maluma", context)

        assertEquals(IntentSource.LLM, result.source)
        assertEquals("Perreo intenso", result.intent.label)
        assertEquals(EnergyCurve.BUILD, result.intent.curve)
        assertEquals(listOf("Maluma"), result.intent.excludeArtists)
        assertEquals(DjMode.HORA_LOCA, result.intent.mode)
    }

    @Test
    fun `the model is only ever told the request, the hour and the energy, never a song`() = runTest {
        val engine = FakeEngine("""{"label":"x","targetEnergy":0.5}""")

        LlmIntentInterpreter(engine).interpret("algo para bailar", context)

        assertEquals("Pedido: \"algo para bailar\"\nHora: 23\nEnergía actual: 0.55\nModo actual: FIESTA", engine.lastUser)
        assertTrue(engine.lastSystem!!.contains("NO conoces ninguna canción"))
    }

    @Test
    fun `numbers outside their range are clamped instead of trusted`() = runTest {
        val engine = FakeEngine("""{"label":"raro","targetEnergy":7.5,"minValence":-3,"minBpm":10000}""")

        val intent = LlmIntentInterpreter(engine).interpret("lo que sea", context).intent

        assertEquals(1.0, intent.targetEnergy)
        assertEquals(0.0, intent.minValence)
        assertEquals(220.0, intent.minBpm)
    }

    @Test
    fun `text around the JSON does not break it`() = runTest {
        val engine = FakeEngine("""Claro, aquí tienes: {"label":"Cumbia","targetEnergy":0.7} ¡listo!""")

        assertEquals("Cumbia", LlmIntentInterpreter(engine).interpret("cumbia", context).intent.label)
    }

    @Test
    fun `junk, an empty answer or a crash all fall back to the rules`() = runTest {
        val cases = listOf(
            FakeEngine("no tengo idea de qué me pides"),
            FakeEngine("""{"label":"vacío"}"""),
            FakeEngine(null),
            FakeEngine(failure = IllegalStateException("el modelo explotó")),
        )

        cases.forEach { engine ->
            val result = LlmIntentInterpreter(engine).interpret("pon algo para perrear", context)

            assertEquals(IntentSource.LLM_FALLBACK, result.source, engine.answer.orEmpty())
            assertEquals("Perreo", result.intent.label)
        }
    }

    @Test
    fun `with no model installed the rules answer without pretending the model failed`() = runTest {
        val engine = FakeEngine(null, status = LlmStatus.NotInstalled)

        val result = LlmIntentInterpreter(engine).interpret("algo tranqui", context)

        assertEquals(IntentSource.RULES, result.source)
        assertEquals(EnergyCurve.WIND_DOWN, result.intent.curve)
    }

    @Test
    fun `how long the model took is measured, because it happens mid-party`() = runTest {
        var now = 0L
        val engine = FakeEngine("""{"label":"x","targetEnergy":0.5}""")

        val result = LlmIntentInterpreter(engine, clock = { now.also { now += 1_500 } }).interpret("algo", context)

        assertEquals(1_500, result.tookMs)
        assertFalse(result.intent.label.isEmpty())
    }
}
