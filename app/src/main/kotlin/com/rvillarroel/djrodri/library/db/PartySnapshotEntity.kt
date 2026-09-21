package com.rvillarroel.djrodri.library.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import com.rvillarroel.djrodri.data.party.PartySettings
import com.rvillarroel.djrodri.data.party.PartySnapshot
import com.rvillarroel.djrodri.data.party.PartySnapshotStore
import com.rvillarroel.djrodri.domain.dj.DjIntent
import com.rvillarroel.djrodri.domain.dj.DjMode
import com.rvillarroel.djrodri.domain.dj.EnergyCurve
import com.rvillarroel.djrodri.domain.dj.Entry
import com.rvillarroel.djrodri.domain.dj.Exit
import com.rvillarroel.djrodri.domain.dj.SessionStats
import com.rvillarroel.djrodri.domain.dj.Transition
import org.json.JSONArray
import org.json.JSONObject

/** One row: the party going on right now. Only one party happens at a time. */
@Entity(tableName = "party_snapshot")
data class PartySnapshotEntity(
    @PrimaryKey val id: Int = 1,
    val sessionId: Long,
    val playlistId: String,
    val mode: String,
    val dropWithPause: Boolean,
    val cutLeadMs: Long,
    val endLeadMs: Long,
    val currentTrackId: String,
    val positionMs: Long,
    val upcomingIds: String,
    val playedIds: String,
    val transitions: String,
    val fullPlays: Int,
    val cuts: Int,
    val dropEntries: Int,
    val manualSkips: Int,
    val dislikes: Int,
    val intent: String,
    val savedAtMs: Long,
)

@Dao
interface PartySnapshotDao {
    @Upsert
    suspend fun save(snapshot: PartySnapshotEntity)

    @Query("SELECT * FROM party_snapshot WHERE id = 1")
    suspend fun load(): PartySnapshotEntity?

    @Query("DELETE FROM party_snapshot")
    suspend fun clear()
}

class RoomPartySnapshotStore(private val dao: PartySnapshotDao) : PartySnapshotStore {

    override suspend fun save(snapshot: PartySnapshot) = dao.save(
        PartySnapshotEntity(
            sessionId = snapshot.sessionId,
            playlistId = snapshot.playlistId,
            mode = snapshot.settings.mode.name,
            dropWithPause = snapshot.settings.dropWithPause,
            cutLeadMs = snapshot.settings.cutLeadMs,
            endLeadMs = snapshot.settings.endLeadMs,
            currentTrackId = snapshot.currentTrackId,
            positionMs = snapshot.positionMs,
            upcomingIds = snapshot.upcomingIds.joinToString(","),
            playedIds = snapshot.playedIds.joinToString(","),
            transitions = snapshot.transitions.joinToString(",", transform = ::encodeTransition),
            fullPlays = snapshot.stats.fullPlays,
            cuts = snapshot.stats.cuts,
            dropEntries = snapshot.stats.dropEntries,
            manualSkips = snapshot.stats.manualSkips,
            dislikes = snapshot.stats.dislikes,
            intent = encodeIntent(snapshot.intent),
            // The engine's clock counts since boot; on disk only the wall clock says how old a party is.
            savedAtMs = System.currentTimeMillis(),
        ),
    )

    override suspend fun load(): PartySnapshot? = dao.load()?.let { row ->
        PartySnapshot(
            sessionId = row.sessionId,
            playlistId = row.playlistId,
            settings = PartySettings(
                mode = runCatching { DjMode.valueOf(row.mode) }.getOrDefault(DjMode.FIESTA),
                dropWithPause = row.dropWithPause,
                cutLeadMs = row.cutLeadMs,
                endLeadMs = row.endLeadMs,
            ),
            currentTrackId = row.currentTrackId,
            positionMs = row.positionMs,
            upcomingIds = row.upcomingIds.split(",").filter(String::isNotBlank),
            playedIds = row.playedIds.split(",").filter(String::isNotBlank),
            transitions = row.transitions.split(",").filter(String::isNotBlank).mapNotNull(::decodeTransition),
            stats = SessionStats(row.fullPlays, row.cuts, row.dropEntries, row.manualSkips, row.dislikes),
            intent = decodeIntent(row.intent),
            savedAtMs = row.savedAtMs,
        )
    }

    override suspend fun clear() = dao.clear()
}

// "F|S" is a whole song into a start; "C120000|D40000" a cut at 2:00 into a drop at 0:40; "X…" a drop swap, "B…" a blend.
private fun encodeTransition(transition: Transition): String {
    val exit = when (val e = transition.exit) {
        Exit.Full -> "F"
        is Exit.CutAt -> (if (e.swap) "X" else if (e.blend) "B" else "C") + e.positionMs
    }
    val entry = when (val e = transition.entry) {
        Entry.FromStart -> "S"
        is Entry.AtDrop -> "D${e.positionMs}"
    }
    return "$exit|$entry"
}

private fun decodeTransition(text: String): Transition? {
    val (exitText, entryText) = text.split("|").takeIf { it.size == 2 } ?: return null
    val exit = when {
        exitText == "F" -> Exit.Full
        exitText.startsWith("C") -> exitText.drop(1).toLongOrNull()?.let { Exit.CutAt(it) } ?: return null
        exitText.startsWith("X") -> exitText.drop(1).toLongOrNull()?.let { Exit.CutAt(it, swap = true) } ?: return null
        exitText.startsWith("B") -> exitText.drop(1).toLongOrNull()?.let { Exit.CutAt(it, blend = true) } ?: return null
        else -> return null
    }
    val entry = when {
        entryText == "S" -> Entry.FromStart
        entryText.startsWith("D") -> entryText.drop(1).toLongOrNull()?.let(Entry::AtDrop) ?: return null
        else -> return null
    }
    return Transition(exit, entry)
}

private fun encodeIntent(intent: DjIntent): String = JSONObject()
    .put("label", intent.label)
    .put("targetEnergy", intent.targetEnergy ?: JSONObject.NULL)
    .put("curve", intent.curve.name)
    .put("minEnergy", intent.minEnergy ?: JSONObject.NULL)
    .put("maxEnergy", intent.maxEnergy ?: JSONObject.NULL)
    .put("minDanceability", intent.minDanceability ?: JSONObject.NULL)
    .put("minValence", intent.minValence ?: JSONObject.NULL)
    .put("maxValence", intent.maxValence ?: JSONObject.NULL)
    .put("minBpm", intent.minBpm ?: JSONObject.NULL)
    .put("maxBpm", intent.maxBpm ?: JSONObject.NULL)
    .put("excludeArtists", JSONArray(intent.excludeArtists))
    .put("genres", JSONArray(intent.genres))
    .put("excludeGenres", JSONArray(intent.excludeGenres))
    .put("likeArtists", JSONArray(intent.likeArtists))
    .put("mode", intent.mode?.name ?: JSONObject.NULL)
    .toString()

private fun decodeIntent(text: String): DjIntent = runCatching {
    val json = JSONObject(text)
    DjIntent(
        label = json.optString("label").ifEmpty { DjIntent.Default.label },
        targetEnergy = json.optDoubleOrNull("targetEnergy"),
        curve = runCatching { EnergyCurve.valueOf(json.optString("curve")) }.getOrDefault(EnergyCurve.WAVE),
        minEnergy = json.optDoubleOrNull("minEnergy"),
        maxEnergy = json.optDoubleOrNull("maxEnergy"),
        minDanceability = json.optDoubleOrNull("minDanceability"),
        minValence = json.optDoubleOrNull("minValence"),
        maxValence = json.optDoubleOrNull("maxValence"),
        minBpm = json.optDoubleOrNull("minBpm"),
        maxBpm = json.optDoubleOrNull("maxBpm"),
        excludeArtists = json.stringList("excludeArtists"),
        genres = json.stringList("genres").toSet(),
        excludeGenres = json.stringList("excludeGenres").toSet(),
        likeArtists = json.stringList("likeArtists"),
        mode = json.optString("mode").takeIf { it.isNotEmpty() }?.let { name -> runCatching { DjMode.valueOf(name) }.getOrNull() },
    )
}.getOrDefault(DjIntent.Default)

private fun JSONObject.optDoubleOrNull(key: String): Double? = if (isNull(key)) null else optDouble(key).takeIf { !it.isNaN() }

private fun JSONObject.stringList(key: String): List<String> {
    val array = optJSONArray(key) ?: return emptyList()
    return (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
}
