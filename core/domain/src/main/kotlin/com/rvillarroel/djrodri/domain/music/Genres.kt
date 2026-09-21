package com.rvillarroel.djrodri.domain.music

import java.text.Normalizer

/**
 * Genre tags come from MusicBrainz as free text ("progressive house", "latin pop", "hip hop"), so they are
 * normalized to a small vocabulary the DJ can reason with, plus the families each one belongs to:
 * asking for "electrónica" should also bring house and techno.
 */
object Genres {
    val ELECTRONIC = "electronic"
    val HOUSE = "house"
    val TECHNO = "techno"
    val EDM = "edm"
    val POP = "pop"
    val ROCK = "rock"
    val METAL = "metal"
    val PUNK = "punk"
    val INDIE = "indie"
    val HIP_HOP = "hip hop"
    val TRAP = "trap"
    val RNB = "r&b"
    val REGGAETON = "reggaeton"
    val LATIN = "latin"
    val CUMBIA = "cumbia"
    val SALSA = "salsa"
    val BACHATA = "bachata"
    val MERENGUE = "merengue"
    val REGGAE = "reggae"
    val DISCO = "disco"
    val FUNK = "funk"
    val SOUL = "soul"
    val JAZZ = "jazz"
    val BLUES = "blues"
    val FOLK = "folk"
    val COUNTRY = "country"
    val CLASSICAL = "classical"
    val BALLAD = "balada"
    val TRANCE = "trance"
    val DRUM_AND_BASS = "drum and bass"
    val DUBSTEP = "dubstep"
    val DANCEHALL = "dancehall"
    val AFROBEATS = "afrobeats"
    val AMAPIANO = "amapiano"

    /** A genre implies its families, so "house" also counts as electronic and as dance music. */
    private val families = mapOf(
        HOUSE to setOf(ELECTRONIC, EDM),
        TECHNO to setOf(ELECTRONIC, EDM),
        EDM to setOf(ELECTRONIC),
        TRANCE to setOf(ELECTRONIC, EDM),
        DRUM_AND_BASS to setOf(ELECTRONIC),
        DUBSTEP to setOf(ELECTRONIC, EDM),
        DANCEHALL to setOf(REGGAE),
        AMAPIANO to setOf(AFROBEATS),
        TRAP to setOf(HIP_HOP),
        REGGAETON to setOf(LATIN),
        CUMBIA to setOf(LATIN),
        SALSA to setOf(LATIN),
        BACHATA to setOf(LATIN),
        MERENGUE to setOf(LATIN),
        METAL to setOf(ROCK),
        PUNK to setOf(ROCK),
        INDIE to setOf(ROCK),
        DISCO to setOf(FUNK),
        SOUL to setOf(RNB),
    )

    /** Written forms seen in MusicBrainz tags and in how people ask, mapped to the vocabulary. */
    private val aliases = listOf(
        setOf("house", "deep house", "progressive house", "tech house", "future house", "electro house") to HOUSE,
        setOf("techno", "minimal techno", "melodic techno", "hard techno", "hardstyle") to TECHNO,
        setOf("trance", "psytrance", "progressive trance", "uplifting trance") to TRANCE,
        setOf("drum and bass", "drum & bass", "dnb", "jungle", "liquid funk") to DRUM_AND_BASS,
        setOf("dubstep", "brostep", "riddim") to DUBSTEP,
        setOf("edm", "electro", "dance", "dance pop", "big room", "electronic dance music") to EDM,
        setOf("electronic", "electronica", "electronico", "electronic music", "ambient", "downtempo", "breakbeat") to ELECTRONIC,
        setOf("afrobeats", "afrobeat", "afropop", "afro pop", "afro house") to AFROBEATS,
        setOf("amapiano") to AMAPIANO,
        setOf("dancehall", "ragga") to DANCEHALL,
        setOf("pop", "synthpop", "electropop", "k-pop", "latin pop", "pop latino", "teen pop") to POP,
        setOf("rock", "classic rock", "hard rock", "rock en espanol", "rock nacional", "alternative rock", "grunge") to ROCK,
        setOf("metal", "heavy metal", "death metal", "nu metal") to METAL,
        setOf("punk", "pop punk", "punk rock") to PUNK,
        setOf("indie", "indie rock", "indie pop", "alternative") to INDIE,
        setOf("hip hop", "hip-hop", "rap", "rap latino", "boom bap") to HIP_HOP,
        setOf("trap", "trap latino", "latin trap") to TRAP,
        setOf("r&b", "rnb", "contemporary r&b", "rhythm and blues") to RNB,
        setOf("reggaeton", "reggaeton", "dembow", "perreo", "urbano latino", "latin urban") to REGGAETON,
        setOf("latin", "musica latina", "latino", "latin american") to LATIN,
        setOf("cumbia", "chicha", "tecnocumbia", "cumbia villera") to CUMBIA,
        setOf("salsa", "son cubano", "timba") to SALSA,
        setOf("bachata") to BACHATA,
        setOf("merengue") to MERENGUE,
        setOf("reggae", "ska") to REGGAE,
        setOf("disco", "nu-disco", "italo disco") to DISCO,
        setOf("funk", "funk carioca", "g-funk") to FUNK,
        setOf("soul", "motown", "neo soul") to SOUL,
        setOf("jazz", "bossa nova", "swing") to JAZZ,
        setOf("blues") to BLUES,
        setOf("folk", "folklore", "singer-songwriter", "cantautor") to FOLK,
        setOf("country") to COUNTRY,
        setOf("classical", "clasica", "opera", "soundtrack") to CLASSICAL,
        setOf("ballad", "balada", "baladas", "bolero", "romantica", "romantic") to BALLAD,
    )

    private val byAlias: Map<String, String> = buildMap {
        aliases.forEach { (written, canonical) -> written.forEach { put(normalize(it), canonical) } }
    }

    /** A tag or a word you said turned into the vocabulary, or null when it is not a genre we know. */
    fun canonical(tag: String): String? {
        val normalized = normalize(tag)
        byAlias[normalized]?.let { return it }
        // "latin pop fusion" and the like: the longest known alias contained in the tag wins.
        return byAlias.keys
            .filter { alias -> alias.length > 3 && normalized.contains(alias) }
            .maxByOrNull { it.length }
            ?.let { byAlias.getValue(it) }
    }

    /** Every canonical genre a set of tags stands for, families included. */
    fun expand(tags: Iterable<String>): Set<String> = buildSet {
        tags.mapNotNull(::canonical).forEach { genre ->
            add(genre)
            addAll(families[genre].orEmpty())
        }
    }

    /** Words from a request that name a genre ("pon algo de house y techno"). */
    fun mentioned(text: String): Set<String> {
        val normalized = normalize(text)
        return byAlias.entries
            .filter { (alias, _) -> alias.length > 2 && normalized.contains(alias) }
            .map { it.value }
            .toSet()
    }

    private fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("""\p{Mn}+"""), "")
            .replace(Regex("""[^\p{L}\p{N}& ]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
}
