package com.rvillarroel.djrodri.data.ai

import com.rvillarroel.djrodri.data.sources.GenreSource
import com.rvillarroel.djrodri.domain.dj.DjIntent
import com.rvillarroel.djrodri.domain.dj.TrackProfile
import com.rvillarroel.djrodri.domain.music.Tempo
import kotlinx.coroutines.CancellationException

/**
 * "Algo estilo Avicii" turned into numbers.
 *
 * If that artist is in your own library, their songs describe the style better than any database: their average
 * energy, danceability, mood and tempo become the target. Otherwise MusicBrainz says what genres they play.
 */
class ArtistStyleResolver(private val genreSource: GenreSource) {

    suspend fun resolve(intent: DjIntent, library: List<TrackProfile>): DjIntent {
        if (intent.likeArtists.isEmpty()) return intent
        val theirs = library.filter { track -> intent.likeArtists.any { track.artist.contains(it, ignoreCase = true) } }
        return if (theirs.isNotEmpty()) fromYourLibrary(intent, theirs) else fromMusicBrainz(intent)
    }

    private fun fromYourLibrary(intent: DjIntent, theirs: List<TrackProfile>): DjIntent {
        val energy = theirs.mapNotNull { it.energy }.averageOrNull()
        val dance = theirs.mapNotNull { it.danceability }.averageOrNull()
        val valence = theirs.mapNotNull { it.valence }.averageOrNull()
        val tempos = theirs.mapNotNull { it.bpm }.map { Tempo.fold(it) }
        val genres = theirs.flatMap { it.genres }.toSet()
        return intent.copy(
            // What you asked for explicitly always wins over what the style suggests.
            targetEnergy = intent.targetEnergy ?: energy,
            minDanceability = intent.minDanceability ?: dance?.let { (it - STYLE_MARGIN).coerceAtLeast(0.0) },
            minValence = intent.minValence ?: valence?.let { (it - STYLE_MARGIN * 2).coerceAtLeast(0.0) },
            minBpm = intent.minBpm ?: tempos.minOrNull()?.minus(TEMPO_MARGIN),
            maxBpm = intent.maxBpm ?: tempos.maxOrNull()?.plus(TEMPO_MARGIN),
            genres = intent.genres + genres,
        )
    }

    private suspend fun fromMusicBrainz(intent: DjIntent): DjIntent {
        val genres = intent.likeArtists.flatMap { name ->
            try {
                genreSource.genresByArtistName(name)?.genres.orEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptySet()
            }
        }.toSet()
        return if (genres.isEmpty()) intent else intent.copy(genres = intent.genres + genres)
    }

    private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()

    private companion object {
        const val STYLE_MARGIN = 0.15
        const val TEMPO_MARGIN = 8.0
    }
}
