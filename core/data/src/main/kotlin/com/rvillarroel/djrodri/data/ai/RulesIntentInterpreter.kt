package com.rvillarroel.djrodri.data.ai

import com.rvillarroel.djrodri.domain.dj.DjIntent
import com.rvillarroel.djrodri.domain.dj.DjMode
import com.rvillarroel.djrodri.domain.dj.EnergyCurve
import com.rvillarroel.djrodri.domain.music.Genres
import java.text.Normalizer

enum class IntentSource {
    RULES,
    LLM,

    /** The model was asked but did not answer something usable; the rules took over. */
    LLM_FALLBACK,
}

data class InterpretedIntent(val intent: DjIntent, val source: IntentSource, val tookMs: Long = 0)

/** Numbers about the room, never about songs: this is all the model is told besides your sentence. */
data class DjContext(val hourOfDay: Int, val currentEnergy: Double? = null, val mode: DjMode = DjMode.FIESTA)

interface IntentInterpreter {
    suspend fun interpret(request: String, context: DjContext): InterpretedIntent
}

/**
 * Turns a Spanish request into numbers with a keyword table. It is instant, works with no model installed, and is
 * the fallback whenever the language model fails, so the DJ always understands something.
 */
class RulesIntentInterpreter : IntentInterpreter {

    override suspend fun interpret(request: String, context: DjContext) = InterpretedIntent(parse(request, context), IntentSource.RULES)

    fun parse(request: String, context: DjContext = DjContext(hourOfDay = 22)): DjIntent {
        val text = normalize(request)
        val matched = RULES.filter { rule -> rule.keywords.any { it in text } }
        val excluded = excludedArtists(request)
        val genres = Genres.expand(Genres.mentioned(request))
        val styleOf = styleArtists(request)
        val extras = buildList {
            if (genres.isNotEmpty()) add(genres.joinToString(", "))
            if (styleOf.isNotEmpty()) add("estilo ${styleOf.joinToString(", ")}")
            if (excluded.isNotEmpty()) add("sin ${excluded.joinToString(", ")}")
        }
        if (matched.isEmpty()) {
            return when {
                extras.isEmpty() -> DjIntent.Default
                else -> DjIntent(
                    label = extras.joinToString(" · ").replaceFirstChar(Char::uppercase),
                    excludeArtists = excluded,
                    genres = genres,
                    likeArtists = styleOf,
                )
            }
        }
        val label = (listOf(matched.joinToString(" + ") { it.label }) + extras).joinToString(" · ")
        return matched.map { it.intent(context) }
            .reduce(::merge)
            .copy(label = label, excludeArtists = excluded, genres = genres, likeArtists = styleOf)
    }

    /** Two requests at once ("algo para bailar pero tranqui") land in the middle instead of one winning. */
    private fun merge(a: DjIntent, b: DjIntent) = DjIntent(
        label = a.label,
        targetEnergy = average(a.targetEnergy, b.targetEnergy),
        curve = if (b.curve != EnergyCurve.WAVE) b.curve else a.curve,
        minEnergy = maxOfNullable(a.minEnergy, b.minEnergy),
        maxEnergy = minOfNullable(a.maxEnergy, b.maxEnergy),
        minDanceability = maxOfNullable(a.minDanceability, b.minDanceability),
        minValence = maxOfNullable(a.minValence, b.minValence),
        maxValence = minOfNullable(a.maxValence, b.maxValence),
        minBpm = average(a.minBpm, b.minBpm),
        maxBpm = average(a.maxBpm, b.maxBpm),
        mode = b.mode ?: a.mode,
    )

    private fun average(a: Double?, b: Double?) = when {
        a != null && b != null -> (a + b) / 2
        else -> a ?: b
    }

    private fun maxOfNullable(a: Double?, b: Double?) = if (a != null && b != null) maxOf(a, b) else a ?: b
    private fun minOfNullable(a: Double?, b: Double?) = if (a != null && b != null) minOf(a, b) else a ?: b

    /** "estilo Avicii", "tipo Daft Punk", "como Shakira": whatever follows names the style you want. */
    private fun styleArtists(request: String): List<String> =
        Regex("""(?:estilo|tipo|como|onda|parecido a|al estilo de)\s+([\p{L}\d\s.'&]{3,30})""", RegexOption.IGNORE_CASE)
            .findAll(request)
            .map { it.groupValues[1].trim().trimEnd('.', ',') }
            .filter { Genres.mentioned(it).isEmpty() && normalize(it) !in NOT_ARTISTS }
            .toList()

    /** "sin bad bunny", "nada de maluma": whatever follows is treated as an artist. */
    private fun excludedArtists(request: String): List<String> =
        Regex("""(?:sin|nada de|menos)\s+([\p{L}\s.']{3,30})""", RegexOption.IGNORE_CASE)
            .findAll(request)
            .map { it.groupValues[1].trim().trimEnd('.', ',') }
            .filter { normalize(it) !in NOT_ARTISTS }
            .toList()

    private fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("""\p{Mn}+"""), "")
            .replace(Regex("""[^\p{L}\p{N} ]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private class Rule(val label: String, val keywords: List<String>, val intent: (DjContext) -> DjIntent)

    private companion object {
        val NOT_ARTISTS = setOf("parar", "bajar", "energia", "cortes", "reggaeton", "perreo", "lentas", "musica", "siempre", "ayer", "antes", "eso", "esto", "venia")

        val RULES = listOf(
            Rule("Perreo", listOf("perre", "reggaeton", "regueton", "dembow")) {
                DjIntent("Perreo", targetEnergy = 0.78, curve = EnergyCurve.STEADY, minDanceability = 0.7, minBpm = 85.0, maxBpm = 105.0)
            },
            Rule("Para bailar", listOf("bailar", "baile", "movid", "pista", "prender")) {
                DjIntent("Para bailar", targetEnergy = 0.78, curve = EnergyCurve.BUILD, minDanceability = 0.65)
            },
            Rule("Más tranqui", listOf("tranqui", "lento", "suave", "calma", "relaj", "bajar", "baja", "chill")) {
                DjIntent("Más tranqui", targetEnergy = 0.35, curve = EnergyCurve.WIND_DOWN, maxEnergy = 0.6)
            },
            Rule("Románticas", listOf("romantic", "amor", "enamorad", "pegadit")) {
                DjIntent("Románticas", targetEnergy = 0.4, curve = EnergyCurve.STEADY, minValence = 0.35)
            },
            Rule("Despecho", listOf("despecho", "triste", "llorar", "corta vena", "tuson", "desamor")) {
                DjIntent("Despecho", targetEnergy = 0.5, curve = EnergyCurve.STEADY, maxValence = 0.4)
            },
            Rule("A fondo", listOf("a fondo", "hora loca", "locura", "descontrol", "full")) {
                DjIntent("A fondo", targetEnergy = 0.9, curve = EnergyCurve.BUILD, minEnergy = 0.7, mode = DjMode.HORA_LOCA)
            },
            Rule("Más energía", listOf("mas energia", "subi", "sube", "subir", "arriba", "animar", "acelera")) {
                DjIntent("Más energía", targetEnergy = 0.85, curve = EnergyCurve.BUILD, minEnergy = 0.6)
            },
            Rule("Electrónica", listOf("electro", "techno", "house", "edm", "rave")) {
                DjIntent("Electrónica", targetEnergy = 0.8, curve = EnergyCurve.BUILD, minDanceability = 0.6, minBpm = 120.0, maxBpm = 135.0)
            },
            Rule("Cumbia", listOf("cumbia", "chicha", "saya", "morenada")) {
                DjIntent("Cumbia", targetEnergy = 0.7, curve = EnergyCurve.STEADY, minDanceability = 0.6, minBpm = 85.0, maxBpm = 105.0)
            },
            Rule("Rock", listOf("rock", "metal", "punk")) {
                DjIntent("Rock", targetEnergy = 0.75, curve = EnergyCurve.STEADY)
            },
            Rule("Para moverse", listOf("correr", "gym", "entrenar", "cardio", "trotar")) {
                DjIntent("Para moverse", targetEnergy = 0.85, curve = EnergyCurve.BUILD, minEnergy = 0.65, minBpm = 150.0, maxBpm = 175.0)
            },
            Rule("Cerrando la noche", listOf("cerrar", "cerrand", "ultima", "terminar", "despedida", "final de la fiesta", "dormir")) {
                DjIntent("Cerrando la noche", targetEnergy = 0.35, curve = EnergyCurve.WIND_DOWN, mode = DjMode.NATURAL)
            },
            Rule("Como venía", listOf("normal", "como estaba", "sigue asi", "como venia")) { context ->
                DjIntent("Como venía", targetEnergy = context.currentEnergy, curve = EnergyCurve.WAVE)
            },
        )
    }
}
