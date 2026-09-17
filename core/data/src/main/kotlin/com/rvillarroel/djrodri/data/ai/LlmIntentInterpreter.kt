package com.rvillarroel.djrodri.data.ai

import com.rvillarroel.djrodri.domain.dj.DjIntent
import com.rvillarroel.djrodri.domain.dj.DjMode
import com.rvillarroel.djrodri.domain.dj.EnergyCurve
import com.rvillarroel.djrodri.domain.music.Genres
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Asks the local model to turn your sentence into numbers, and checks its homework.
 *
 * Two rules hold this together:
 * 1. **The model never sees your music.** Its whole input is your sentence plus the hour and the current energy.
 * 2. **Its answer is validated and clamped**, and anything odd falls back to [RulesIntentInterpreter], so a bad
 *    answer can never leave the party without a plan.
 */
class LlmIntentInterpreter(
    private val engine: LlmEngine,
    private val rules: RulesIntentInterpreter = RulesIntentInterpreter(),
    private val timeoutMs: Long = 8_000,
    private val clock: () -> Long = System::currentTimeMillis,
) : IntentInterpreter {

    override suspend fun interpret(request: String, context: DjContext): InterpretedIntent {
        val startedAt = clock()
        val answer = try {
            engine.json(SYSTEM_INSTRUCTION, userMessage(request, context), SCHEMA, timeoutMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val parsed = answer?.let(::parse)
        val took = clock() - startedAt
        return when {
            parsed != null -> InterpretedIntent(parsed, IntentSource.LLM, took)
            answer == null && engine.status.value is LlmStatus.NotInstalled ->
                InterpretedIntent(rules.parse(request, context), IntentSource.RULES, took)
            else -> InterpretedIntent(rules.parse(request, context), IntentSource.LLM_FALLBACK, took)
        }
    }

    private fun userMessage(request: String, context: DjContext) = buildString {
        append("Pedido: \"").append(request.take(MAX_REQUEST_CHARS)).append("\"\n")
        append("Hora: ").append(context.hourOfDay).append("\n")
        context.currentEnergy?.let { append("Energía actual: ").append("%.2f".format(it)).append("\n") }
        append("Modo actual: ").append(context.mode.name)
    }

    internal fun parse(answer: String): DjIntent? {
        val json = answer.substringAfter('{', "").let { if (it.isEmpty()) return null else "{$it" }
            .substringBeforeLast('}') + "}"
        val dto = runCatching { lenientJson.decodeFromString<IntentDto>(json) }.getOrNull() ?: return null
        val label = dto.label?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_LABEL_CHARS } ?: "Pedido"
        val intent = DjIntent(
            label = label,
            targetEnergy = dto.targetEnergy?.unit(),
            curve = runCatching { EnergyCurve.valueOf(dto.curve.orEmpty().uppercase()) }.getOrDefault(EnergyCurve.WAVE),
            minEnergy = dto.minEnergy?.unit(),
            maxEnergy = dto.maxEnergy?.unit(),
            minDanceability = dto.minDanceability?.unit(),
            minValence = dto.minValence?.unit(),
            maxValence = dto.maxValence?.unit(),
            minBpm = dto.minBpm?.bpm(),
            maxBpm = dto.maxBpm?.bpm(),
            excludeArtists = dto.excludeArtists.orEmpty().mapNotNull { it.trim().takeIf(String::isNotEmpty) }.take(MAX_EXCLUDED),
            // Asking for house also means edm and electronic; excluding cumbia must not exclude all latin music.
            genres = Genres.expand(dto.genres.orEmpty()),
            excludeGenres = dto.excludeGenres.orEmpty().mapNotNull(Genres::canonical).toSet(),
            likeArtists = dto.likeArtists.orEmpty().mapNotNull { it.trim().takeIf(String::isNotEmpty) }.take(MAX_EXCLUDED),
            mode = dto.mode?.let { name -> runCatching { DjMode.valueOf(name.uppercase()) }.getOrNull() },
        )
        // A model that answers with nothing usable is worse than the rules: treat it as a failure.
        val saysSomething = listOfNotNull(
            intent.targetEnergy, intent.minEnergy, intent.maxEnergy, intent.minDanceability,
            intent.minValence, intent.maxValence, intent.minBpm, intent.maxBpm,
        ).isNotEmpty() || intent.excludeArtists.isNotEmpty() || intent.genres.isNotEmpty() ||
            intent.excludeGenres.isNotEmpty() || intent.likeArtists.isNotEmpty() || intent.curve != EnergyCurve.WAVE
        return intent.takeIf { saysSomething }
    }

    private fun Double.unit() = takeIf { it.isFinite() }?.coerceIn(0.0, 1.0)

    private fun Double.bpm() = takeIf { it.isFinite() && it > 0 }?.coerceIn(40.0, 220.0)

    @Serializable
    private data class IntentDto(
        val label: String? = null,
        val targetEnergy: Double? = null,
        val curve: String? = null,
        val minEnergy: Double? = null,
        val maxEnergy: Double? = null,
        val minDanceability: Double? = null,
        val minValence: Double? = null,
        val maxValence: Double? = null,
        val minBpm: Double? = null,
        val maxBpm: Double? = null,
        val excludeArtists: List<String>? = null,
        val genres: List<String>? = null,
        val excludeGenres: List<String>? = null,
        val likeArtists: List<String>? = null,
        val mode: String? = null,
    )

    private companion object {
        const val MAX_REQUEST_CHARS = 200
        const val MAX_LABEL_CHARS = 40
        const val MAX_EXCLUDED = 5
        val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

        val SYSTEM_INSTRUCTION = """
            Eres el cerebro de un DJ para fiestas. Traduces un pedido en español a números.
            NO conoces ninguna canción ni artista de la biblioteca: solo describes qué tipo de música se busca.
            Responde SOLO con un JSON con estos campos (omite los que no apliquen):
            label: frase corta en español que resuma el pedido.
            targetEnergy, minEnergy, maxEnergy, minDanceability, minValence, maxValence: números entre 0 y 1.
            minBpm, maxBpm: pulsaciones por minuto reales de ese género.
            curve: STEADY (mantener), BUILD (subir), WAVE (subidas y bajadas) o WIND_DOWN (bajar).
            excludeArtists: lista de artistas que el usuario pidió evitar.
            genres: géneros pedidos, en inglés y en minúsculas (house, techno, edm, pop, rock, metal, indie, hip hop,
            trap, r&b, reggaeton, cumbia, salsa, bachata, merengue, reggae, disco, funk, soul, jazz, folk, balada).
            excludeGenres: géneros que el usuario NO quiere.
            likeArtists: artistas que el usuario usó como referencia de estilo ("algo estilo Avicii").
            mode: NATURAL (pocos cortes), FIESTA o HORA_LOCA (muchos cortes), solo si el pedido lo sugiere.
            Guías: valence alto = alegre, bajo = triste o despecho. Reggaetón 85-105 BPM, cumbia 85-105,
            electrónica 120-135, rock 100-140, música para correr 150-175.
        """.trimIndent()

        val SCHEMA = """
            {
              "type": "object",
              "properties": {
                "label": {"type": "string"},
                "targetEnergy": {"type": "number"},
                "curve": {"type": "string", "enum": ["STEADY", "BUILD", "WAVE", "WIND_DOWN"]},
                "minEnergy": {"type": "number"},
                "maxEnergy": {"type": "number"},
                "minDanceability": {"type": "number"},
                "minValence": {"type": "number"},
                "maxValence": {"type": "number"},
                "minBpm": {"type": "number"},
                "maxBpm": {"type": "number"},
                "excludeArtists": {"type": "array", "items": {"type": "string"}},
                "genres": {"type": "array", "items": {"type": "string"}},
                "excludeGenres": {"type": "array", "items": {"type": "string"}},
                "likeArtists": {"type": "array", "items": {"type": "string"}},
                "mode": {"type": "string", "enum": ["NATURAL", "FIESTA", "HORA_LOCA"]}
              },
              "required": ["label"]
            }
        """.trimIndent()
    }
}
