package com.amar.vault

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Going back to an older app never wipes the vault. An older build finds a database written by a
 * newer one (version 16 by the photo-search build; here a version past this build's own) and,
 * with no step down and no "start over" fallback configured, stops with an error and leaves every
 * row where it was; installing the newer build again opens it as before.
 *
 * Built the way [VaultDatabase.get] builds it, with the same migrations and nothing else.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class DowngradeKeepsTheVaultTest {

    @Test
    fun anOlderAppStopsRatherThanWipingANewerVault() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = context.getDatabasePath("downgrade-probe.db").apply { parentFile?.mkdirs(); delete() }
        // Any version past this build's own: Room's @Database is not kept at run time to read it.
        val newer = 1000
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE kept (id INTEGER PRIMARY KEY NOT NULL, text TEXT NOT NULL)")
            db.execSQL("INSERT INTO kept VALUES (1, 'a row written by the newer app')")
            db.version = newer
        }

        val older = Room.databaseBuilder(context, VaultDatabase::class.java, file.path)
            .addMigrations(*VaultDatabase.migrations(context))
            .build()
        val error = runCatching { older.openHelper.writableDatabase }.exceptionOrNull()
        older.close()

        assertTrue("the older app must stop, got $error", error is IllegalStateException)
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals("the version is left as the newer app wrote it", newer, db.version)
            db.rawQuery("SELECT text FROM kept", null).use { c ->
                assertTrue("the row is still there", c.moveToFirst())
                assertEquals("a row written by the newer app", c.getString(0))
            }
        }
    }
}
