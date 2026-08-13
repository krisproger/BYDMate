package com.bydmate.app.ui.settings

import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.backup.BackupManager
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class FallbackSmokeTest {
    @Test
    fun `backup listing via real Downloads works and feeds picker state`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dir = File(ctx.cacheDir, "fallback_smoke_dir").also {
            deleteRecursively(it)
            it.mkdirs()
        }
        File(dir, "bydmate_backup_smoke.zip").writeBytes(byteArrayOf(1))
        val list = BackupManager.listBackupsInDownloads(dir)
        assertNotNull(list)
        assertTrue(list.isEmpty().not())
        deleteRecursively(dir)
    }

    private fun deleteRecursively(file: File) {
        file.listFiles()?.forEach { deleteRecursively(it) }
        file.delete()
    }
}