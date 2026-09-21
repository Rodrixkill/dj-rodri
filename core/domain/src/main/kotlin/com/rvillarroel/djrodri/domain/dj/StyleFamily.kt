package com.rvillarroel.djrodri.domain.dj

import com.rvillarroel.djrodri.domain.music.Genres

/**
 * How a DJ moves in and out of each kind of music, reduced to the moves Spotify allows: play whole, cut dry on a
 * phrase, enter on a drop, swap drops, or let Spotify's crossfade blend. See docs/filosofia-dj.md.
 *
 * @param swaps a drop swap is part of the style (EDM, reggaeton, hip hop, dancehall), not a gimmick.
 * @param longBlend a long blend is the natural transition (house, techno, disco). Everywhere else the crossfade is
 *   only for songs that end on their own, and cuts are dry, on the end of a sung phrase.
 * @param cutFactor how willing the DJ is to leave early: rock and tropical songs are songs, not loops.
 */
enum class StyleFamily(val label: String, val swaps: Boolean, val longBlend: Boolean, val cutFactor: Double) {
    ELECTRONIC("electrónica", swaps = true, longBlend = true, cutFactor = 1.2),
    BASS("EDM", swaps = true, longBlend = false, cutFactor = 1.2),
    REGGAETON("reggaetón", swaps = true, longBlend = false, cutFactor = 1.0),
    HIP_HOP("hip hop", swaps = true, longBlend = false, cutFactor = 1.0),
    AFRO_CARIBBEAN("afro y dancehall", swaps = true, longBlend = false, cutFactor = 1.0),
    POP("pop", swaps = false, longBlend = false, cutFactor = 0.9),
    GROOVE("funk y disco", swaps = false, longBlend = true, cutFactor = 0.9),
    TROPICAL("tropical", swaps = false, longBlend = false, cutFactor = 0.5),
    ROCK("rock", swaps = false, longBlend = false, cutFactor = 0.5),
    MELLOW("tranqui", swaps = false, longBlend = false, cutFactor = 0.4),
    UNKNOWN("sin género", swaps = true, longBlend = false, cutFactor = 1.0),
    ;

    companion object {
        /** The most specific family wins: "reggaeton" beats the "latin" it also carries, "house" beats "edm". */
        fun of(track: TrackProfile): StyleFamily {
            val g = track.genres
            return when {
                g.any { it in TROPICAL_GENRES } -> TROPICAL
                Genres.REGGAETON in g -> REGGAETON
                g.any { it in AFRO_GENRES } -> AFRO_CARIBBEAN
                Genres.HIP_HOP in g || Genres.TRAP in g -> HIP_HOP
                g.any { it in FOUR_ON_THE_FLOOR } -> ELECTRONIC
                g.any { it in BASS_GENRES } -> BASS
                Genres.ELECTRONIC in g -> ELECTRONIC
                g.any { it in GROOVE_GENRES } -> GROOVE
                g.any { it in ROCK_GENRES } -> ROCK
                Genres.POP in g || Genres.LATIN in g -> POP
                g.any { it in MELLOW_GENRES } -> MELLOW
                else -> UNKNOWN
            }
        }

        /**
         * How naturally one family follows another, 0..1. Open format is allowed everywhere: a low number does not
         * forbid the change, it makes it a creative one that needs a clean cut rather than a blend.
         */
        fun affinity(a: StyleFamily, b: StyleFamily): Double {
            if (a == b) return 1.0
            if (a == UNKNOWN || b == UNKNOWN) return NEUTRAL
            return AFFINITY[setOf(a, b)] ?: DISTANT
        }

        /** The genres a family stands for, to ask the DJ for "a house mix" in the numbers it understands. */
        fun genresOf(family: StyleFamily): Set<String> = when (family) {
            ELECTRONIC -> FOUR_ON_THE_FLOOR + Genres.ELECTRONIC
            BASS -> BASS_GENRES
            REGGAETON -> setOf(Genres.REGGAETON)
            HIP_HOP -> setOf(Genres.HIP_HOP, Genres.TRAP)
            AFRO_CARIBBEAN -> AFRO_GENRES
            POP -> setOf(Genres.POP)
            GROOVE -> GROOVE_GENRES
            TROPICAL -> TROPICAL_GENRES
            ROCK -> ROCK_GENRES
            MELLOW -> MELLOW_GENRES
            UNKNOWN -> emptySet()
        }

        private const val NEUTRAL = 0.5
        private const val DISTANT = 0.35

        private val TROPICAL_GENRES = setOf(Genres.SALSA, Genres.BACHATA, Genres.MERENGUE, Genres.CUMBIA)
        private val AFRO_GENRES = setOf(Genres.AFROBEATS, Genres.AMAPIANO, Genres.DANCEHALL, Genres.REGGAE)
        private val FOUR_ON_THE_FLOOR = setOf(Genres.HOUSE, Genres.TECHNO, Genres.TRANCE)
        private val BASS_GENRES = setOf(Genres.DUBSTEP, Genres.DRUM_AND_BASS, Genres.EDM)
        private val GROOVE_GENRES = setOf(Genres.DISCO, Genres.FUNK, Genres.SOUL, Genres.RNB)
        private val ROCK_GENRES = setOf(Genres.ROCK, Genres.METAL, Genres.PUNK, Genres.INDIE)
        private val MELLOW_GENRES = setOf(Genres.BALLAD, Genres.FOLK, Genres.JAZZ, Genres.BLUES, Genres.COUNTRY, Genres.CLASSICAL)

        private val AFFINITY: Map<Set<StyleFamily>, Double> = mapOf(
            setOf(ELECTRONIC, BASS) to 0.8,
            setOf(ELECTRONIC, GROOVE) to 0.8, // disco into house is a classic
            setOf(ELECTRONIC, POP) to 0.6,
            setOf(ELECTRONIC, AFRO_CARIBBEAN) to 0.6, // afro house
            setOf(ELECTRONIC, REGGAETON) to 0.5, // latin house
            setOf(BASS, POP) to 0.7,
            setOf(BASS, HIP_HOP) to 0.6,
            setOf(REGGAETON, HIP_HOP) to 0.8,
            setOf(REGGAETON, AFRO_CARIBBEAN) to 0.8, // dembow comes from dancehall
            setOf(REGGAETON, POP) to 0.7,
            setOf(REGGAETON, TROPICAL) to 0.6,
            setOf(HIP_HOP, AFRO_CARIBBEAN) to 0.7,
            setOf(HIP_HOP, GROOVE) to 0.7, // samples
            setOf(HIP_HOP, POP) to 0.7,
            setOf(POP, GROOVE) to 0.7,
            setOf(POP, ROCK) to 0.6,
            setOf(POP, MELLOW) to 0.6,
            setOf(POP, AFRO_CARIBBEAN) to 0.6,
            setOf(POP, TROPICAL) to 0.5,
            setOf(ROCK, GROOVE) to 0.5,
            setOf(ROCK, MELLOW) to 0.5,
            setOf(TROPICAL, AFRO_CARIBBEAN) to 0.5,
            setOf(ROCK, REGGAETON) to 0.2,
            setOf(ROCK, TROPICAL) to 0.2,
            setOf(MELLOW, BASS) to 0.1,
            setOf(MELLOW, ELECTRONIC) to 0.2,
        )
    }
}
