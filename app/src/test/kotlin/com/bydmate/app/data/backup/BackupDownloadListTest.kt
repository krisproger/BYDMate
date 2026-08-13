package com.bydmate.app.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BackupDownloadListTest {

    private fun tempDir(prefix: String): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "${prefix}_" + System.nanoTime())
        dir.mkdirs()
        return dir
    }

    @Test
    fun `empty dir yields empty list`() {
        val dir = tempDir("btest_empty")
        assertTrue(BackupManager.listBackupsInDownloads(dir).isEmpty())
    }

    @Test
    fun `non-existing dir yields empty list`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "does_not_exist_" + System.nanoTime())
        assertTrue(BackupManager.listBackupsInDownloads(dir).isEmpty())
    }

    @Test
    fun `filters to bydmate_backup zips and sorts newest first`() {
        val dir = tempDir("btest_mix")
        fun m(name: String, ts: Long) = File(dir, name).apply {
            writeBytes(byteArrayOf(1))
            setLastModified(ts)
        }
        val old = m("bydmate_backup_old.zip", 1_000_000L)
        val newer = m("bydmate_backup_new.zip", 2_000_000L)
        m("bydmate_backup_note.txt", 3_000_000L)
        m("other.zip", 4_000_000L)

        val result = BackupManager.listBackupsInDownloads(dir)

        assertEquals(listOf("bydmate_backup_new.zip", "bydmate_backup_old.zip"), result.map { it.name })
        old.setLastModified(0); newer.setLastModified(0)
    }

    @Test
    fun `stable order when lastModified equal`() {
        val dir = tempDir("btest_eq")
        fun m(name: String) = File(dir, name).apply {
            writeBytes(byteArrayOf(1))
            setLastModified(5_000_000L)
        }
        m("bydmate_backup_a.zip")
        m("bydmate_backup_b.zip")

        val result = BackupManager.listBackupsInDownloads(dir)

        assertEquals(listOf("bydmate_backup_a.zip", "bydmate_backup_b.zip"), result.map { it.name })
    }
}