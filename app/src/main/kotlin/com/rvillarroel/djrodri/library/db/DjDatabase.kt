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
        DropMarkEntity::class, PartySessionEntity::class, PlayEventEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class DjDatabase : RoomDatabase() {
    abstract fun library(): LibraryDao
    abstract fun analysis(): AnalysisDao
    abstract fun party(): PartyDao

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

        fun create(context: Context): DjDatabase =
            Room.databaseBuilder(context, DjDatabase::class.java, "djrodri.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
