package com.rvillarroel.djrodri.library.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Your sets for the decks: order kept, no song twice, and a removal leaves no gap. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class LocalPlaylistDaoTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), DjDatabase::class.java).allowMainThreadQueries().build()
    private val dao = db.localPlaylists()

    @After
    fun close() = db.close()

    @Test
    fun `songs keep the order they were added in, and the same song is not added twice`() = runBlocking {
        val id = dao.insert(LocalPlaylistEntity(name = "Previa", createdAtMs = 1))
        dao.add(id, 30)
        dao.add(id, 10)
        dao.add(id, 30)
        dao.add(id, 20)

        assertEquals(listOf(30L, 10L, 20L), dao.songIds(id))
        assertEquals(3, dao.playlists().first().single().songs)
    }

    @Test
    fun `removing a song renumbers the rest, so the next one goes at the end`() = runBlocking {
        val id = dao.insert(LocalPlaylistEntity(name = "Previa", createdAtMs = 1))
        listOf(1L, 2L, 3L).forEach { dao.add(id, it) }

        dao.replace(id, dao.songIds(id).filter { it != 2L })
        dao.add(id, 4)

        assertEquals(listOf(1L, 3L, 4L), dao.songIds(id))
    }

    @Test
    fun `deleting a set takes its songs with it and leaves the others alone`() = runBlocking {
        val gone = dao.insert(LocalPlaylistEntity(name = "Vieja", createdAtMs = 1))
        val kept = dao.insert(LocalPlaylistEntity(name = "Nueva", createdAtMs = 2))
        dao.add(gone, 1)
        dao.add(kept, 2)

        dao.delete(gone)

        assertEquals(emptyList<Long>(), dao.songIds(gone))
        assertEquals(listOf("Nueva"), dao.playlists().first().map { it.name })
        assertEquals(listOf(2L), dao.songIds(kept))
    }
}
