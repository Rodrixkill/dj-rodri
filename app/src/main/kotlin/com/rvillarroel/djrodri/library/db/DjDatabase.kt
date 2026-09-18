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
    ],
    version = 3,
    exportSchema = true,
)
abstract class DjDatabase : RoomDatabase() {
    abstract fun library(): LibraryDao
    abstract fun analysis(): AnalysisDao
    abstract fun party(): PartyDao
    abstract fun partySnapshot(): PartySnapshotDao

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

        fun create(context: Context): DjDatabase =
            Room.databaseBuilder(context, DjDatabase::class.java, "djrodri.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}
