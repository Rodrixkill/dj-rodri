package com.rvillarroel.djrodri.library.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Updating the app must never cost you your drop marks or your party history.
 * The migration runs against the real version 1 schema, with data already in it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class MigrationTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val helper by lazy {
        MigrationTestHelper(
            instrumentation = InstrumentationRegistry.getInstrumentation(),
            file = temp.newFile("migration-test.db"),
            driver = AndroidSQLiteDriver(),
            databaseClass = DjDatabase::class,
        )
    }

    @Test
    fun `adding genres keeps every analysis, drop mark and party already stored`() {
        helper.createDatabase(version = 1).use { db ->
            db.execSQL(
                """
                INSERT INTO analysis (trackId, lyricsStatus, syncedLrc, featuresFound, tempo, musicalKey, mode,
                    energy, danceability, valence, analyzerVersion, analyzedAtMs)
                VALUES ('t1', 'SYNCED', '[00:01.00] hola', 1, 96.0, 9, 0, 0.8, 0.7, 0.5, 1, 5)
                """.trimIndent(),
            )
            db.execSQL("INSERT INTO drop_mark (trackId, positionMs, markedAtMs) VALUES ('t1', 52300, 10)")
            db.execSQL(
                """
                INSERT INTO party_session (playlistId, mode, startedAtMs, endedAtMs, fullPlays, cuts, dropEntries, manualSkips, dislikes)
                VALUES ('p1', 'FIESTA', 100, 900, 4, 2, 1, 1, 0)
                """.trimIndent(),
            )
        }

        helper.runMigrationsAndValidate(version = 2, migrations = listOf(DjDatabase.MIGRATION_1_2)).use { db ->
            db.readRow("SELECT trackId, genres, artistMbid, analyzerVersion FROM analysis") { row ->
                assertEquals("t1", row.getText(0))
                // Genres arrive empty and the analyzer refills them, because its version bumped to 2.
                assertEquals("", row.getText(1))
                assertTrue(row.isNull(2))
                assertEquals(1, row.getInt(3))
            }
            db.readRow("SELECT positionMs FROM drop_mark") { assertEquals(52_300L, it.getLong(0)) }
            db.readRow("SELECT fullPlays FROM party_session") { assertEquals(4, it.getInt(0)) }
        }
    }

    @Test
    fun `the table that carries a party across a kill is added empty, without touching the rest`() {
        helper.createDatabase(version = 2).use { db ->
            db.execSQL("INSERT INTO drop_mark (trackId, positionMs, markedAtMs) VALUES ('t1', 52300, 10)")
        }

        helper.runMigrationsAndValidate(version = 3, migrations = listOf(DjDatabase.MIGRATION_2_3)).use { db ->
            db.readRow("SELECT COUNT(*) FROM party_snapshot") { assertEquals(0, it.getInt(0)) }
            db.readRow("SELECT positionMs FROM drop_mark") { assertEquals(52_300L, it.getLong(0)) }
        }
    }

    private fun SQLiteConnection.readRow(sql: String, assertions: (androidx.sqlite.SQLiteStatement) -> Unit) {
        prepare(sql).use { statement ->
            assertTrue("sin filas: $sql", statement.step())
            assertions(statement)
        }
    }
}
