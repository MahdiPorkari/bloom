package com.rrajath.grove.testing

import com.rrajath.grove.data.GroveDatabase
import com.rrajath.grove.data.RoomNoteIndex
import com.rrajath.grove.org.OrgKeywords
import com.rrajath.grove.vault.FileStore

/** Helpers for writing fixtures and building the same Room index used by sync. */
object TestVaultSeeder {
    suspend fun seed(store: FileStore, files: Map<String, String> = OrgFixtures.all) {
        files.forEach { (name, content) -> store.write(name, content) }
    }

    suspend fun index(db: GroveDatabase, store: FileStore, keywords: OrgKeywords = OrgKeywords.DEFAULT) {
        val roomIndex = RoomNoteIndex(db, { keywords })
        store.list().filter { it.name.endsWith(".org", ignoreCase = true) || it.name.endsWith(".md", ignoreCase = true) }
            .forEach { entry ->
                roomIndex.indexNotebook(
                    fileName = entry.name,
                    revision = "${entry.lastModified}:${entry.size}",
                    text = store.read(entry.name),
                    lastModified = entry.lastModified,
                    conflictFileName = null,
                )
            }
    }
}
