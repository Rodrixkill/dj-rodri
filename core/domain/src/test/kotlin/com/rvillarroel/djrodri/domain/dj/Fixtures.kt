package com.rvillarroel.djrodri.domain.dj

import com.rvillarroel.djrodri.domain.lyrics.ChorusAnalysis
import com.rvillarroel.djrodri.domain.lyrics.TimeRange
import com.rvillarroel.djrodri.domain.music.CamelotKey
import kotlin.random.Random

/** A typical 3:50 party song: choruses at 0:40, 1:40 and 2:40, 20 s each, then a 50 s outro. */
fun song(
    id: String,
    artist: String = "Artista $id",
    energy: Double? = 0.6,
    bpm: Double? = 95.0,
    key: CamelotKey? = CamelotKey(8, isMinor = true),
    chorusConfidence: Double? = 0.9,
    durationMs: Long = 230_000,
    userDropMs: Long? = null,
) = TrackProfile(
    id = id,
    artist = artist,
    durationMs = durationMs,
    bpm = bpm,
    key = key,
    energy = energy,
    chorus = chorusConfidence?.let {
        ChorusAnalysis(
            occurrences = listOf(TimeRange(40_000, 60_000), TimeRange(100_000, 120_000), TimeRange(160_000, 180_000)),
            linesPerBlock = 4,
            confidence = it,
        )
    },
    userDropMs = userDropMs,
)

/** A messy real-world playlist: repeated artists, missing data, short songs, weak lyrics. */
fun randomPlaylist(random: Random, size: Int = 60): List<TrackProfile> = List(size) { i ->
    song(
        id = "t$i",
        artist = "Artista ${random.nextInt(12)}",
        energy = if (random.nextInt(5) == 0) null else random.nextDouble(),
        bpm = if (random.nextInt(4) == 0) null else random.nextDouble(70.0, 180.0),
        key = if (random.nextInt(4) == 0) null else CamelotKey(random.nextInt(1, 13), random.nextBoolean()),
        chorusConfidence = if (random.nextInt(4) == 0) null else random.nextDouble(0.4, 0.95),
        durationMs = random.nextLong(120_000, 300_000),
        userDropMs = if (random.nextInt(6) == 0) random.nextLong(20_000, 80_000) else null,
    )
}

/** Plays a whole session the way the adapter would, obeying every plan. Returns the commands issued. */
fun DjSession.playThrough(): List<PlayCommand> {
    val commands = mutableListOf<PlayCommand>()
    start()?.let(commands::add)
    while (current != null) {
        val command = if (plan?.exit is Exit.CutAt) onCutPointReached() else onTrackEnded()
        command?.let(commands::add)
    }
    return commands
}
