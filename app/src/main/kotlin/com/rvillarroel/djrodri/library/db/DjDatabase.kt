package com.rvillarroel.djrodri.library.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

@Database(
    entities = [
        PlaylistEntity::class, TrackEntity::class, PlaylistTrackEntity::class, AnalysisEntity::class,
        DropMarkEntity::class, PartySessionEntity::class, PlayEventEntity::class, PartySnapshotEntity::class,
        LocalPlaylistEntity::class, LocalPlaylistItemEntity::class, GenreOverrideEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
abstract class DjDatabase : RoomDatabase() {
    abstract fun library(): LibraryDao
    abstract fun analysis(): AnalysisDao
    abstract fun party(): PartyDao
    abstract fun partySnapshot(): PartySnapshotDao
    abstract fun localPlaylists(): LocalPlaylistDao

    companion object {
        /**
         * Genres arrived in version 2. The columns are added empty and the analyzer refills them, because your
         * drop marks and party history must survive every update.
         */
        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE analysis ADD COLUMN genres TEXT NOT NULL DEFAULT ''")
                connection.execSQL("ALTER TABLE analysis ADD COLUMN artistMbid TEXT")
            }
        }

        /** Version 3 keeps the party going after HyperOS kills the app: the table starts empty. */
        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS party_snapshot (
                        id INTEGER NOT NULL PRIMARY KEY,
                        sessionId INTEGER NOT NULL, playlistId TEXT NOT NULL, mode TEXT NOT NULL,
                        dropWithPause INTEGER NOT NULL, cutLeadMs INTEGER NOT NULL, endLeadMs INTEGER NOT NULL,
                        currentTrackId TEXT NOT NULL, positionMs INTEGER NOT NULL,
                        upcomingIds TEXT NOT NULL, playedIds TEXT NOT NULL, transitions TEXT NOT NULL,
                        fullPlays INTEGER NOT NULL, cuts INTEGER NOT NULL, dropEntries INTEGER NOT NULL,
                        manualSkips INTEGER NOT NULL, dislikes INTEGER NOT NULL,
                        intent TEXT NOT NULL, savedAtMs INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        /** Version 4 shows album covers: the column starts empty and fills the next time a playlist is read. */
        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE track ADD COLUMN imageUrl TEXT")
            }
        }

        /** Version 5 keeps playlists of your own files for the DJ decks: two new tables, nothing else touched. */
        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS local_playlist (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, createdAtMs INTEGER NOT NULL)",
                )
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS local_playlist_item (playlistId INTEGER NOT NULL, position INTEGER NOT NULL, songId INTEGER NOT NULL, PRIMARY KEY(playlistId, position))",
                )
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_local_playlist_item_playlistId ON local_playlist_item (playlistId)")
            }
        }

        /** Version 6 keeps the styles you set by hand: one new table, nothing else touched. */
        internal val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS genre_override (trackId TEXT NOT NULL PRIMARY KEY, family TEXT NOT NULL, markedAtMs INTEGER NOT NULL)",
                )
            }
        }

        fun create(context: Context): DjDatabase =
            Room.databaseBuilder(context, DjDatabase::class.java, "djrodri.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .build()
    }
}
