package com.rvillarroel.djrodri.library.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        PlaylistEntity::class, TrackEntity::class, PlaylistTrackEntity::class, AnalysisEntity::class,
        DropMarkEntity::class, PartySessionEntity::class, PlayEventEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class DjDatabase : RoomDatabase() {
    abstract fun library(): LibraryDao
    abstract fun analysis(): AnalysisDao
    abstract fun party(): PartyDao

    companion object {
        // Versioned from day one: your drop marks and party history must survive every update.
        fun create(context: Context): DjDatabase =
            Room.databaseBuilder(context, DjDatabase::class.java, "djrodri.db").build()
    }
}
