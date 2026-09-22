package com.rvillarroel.djrodri.domain.music

/** One source saying a song is [tag], with how much that source's word is worth. */
data class GenreVote(val tag: String, val weight: Double)

/**
 * Decides a song's genres from what several sources say, the way you would ask around: the label's own genre counts
 * most, the song's tags next, the artist's tags least, and a tag few people gave is ignored. Only the genres that
 * really lead survive, so one listener tagging Dua Lipa "house" no longer puts her in an electronic mix.
 */
object GenreVotes {
    /** A genre survives if it has at least this share of the leading genre's votes. */
    const val KEEP_SHARE = 0.5

    /** Within one source, tags below this share of its most-voted tag are noise. */
    const val TAG_SHARE = 0.3

    /** How much each source counts. */
    const val LABEL_MAIN = 1.5
    const val LABEL_OTHER = 0.5
    const val SONG_TAGS = 1.0
    const val ARTIST_TAGS = 0.6

    /**
     * Community tags with their vote counts, turned into votes worth up to [weight]: the most-voted tag gets all of
     * it, the others their share, and those under [TAG_SHARE] nothing.
     */
    fun fromCounts(counts: Map<String, Int>, weight: Double): List<GenreVote> {
        val top = counts.values.maxOrNull()?.takeIf { it > 0 } ?: return emptyList()
        return counts.mapNotNull { (tag, count) ->
            val share = count.toDouble() / top
            if (share < TAG_SHARE) null else GenreVote(tag, weight * share)
        }
    }

    /**
     * The genres that lead, with their families. A family also collects its members' votes ("latin" hears every
     * "reggaeton"), but the bar is set by the best genre on its own votes: so the broad genre can't raise the bar
     * above its own members, and the DJ can still tell reggaeton from the rest of latin music.
     */
    fun decide(votes: List<GenreVote>): Set<String> {
        val own = mutableMapOf<String, Double>()
        votes.forEach { vote -> Genres.canonical(vote.tag)?.let { own.merge(it, vote.weight, Double::plus) } }
        if (own.isEmpty()) return emptySet()

        val rolled = mutableMapOf<String, Double>()
        own.forEach { (genre, score) -> Genres.withFamilies(genre).forEach { rolled.merge(it, score, Double::plus) } }
        val top = own.values.max()
        return rolled.filterValues { it >= top * KEEP_SHARE }.keys
            .flatMap(Genres::withFamilies)
            .toSet()
    }
}
