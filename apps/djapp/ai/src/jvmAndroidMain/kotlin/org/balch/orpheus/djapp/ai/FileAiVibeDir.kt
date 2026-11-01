package org.balch.orpheus.djapp.ai

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import java.io.File

/** [AiVibeDir] in [OrphicFiles.root], the folder the desktop and Android archives have always used. */
@ContributesBinding(AppScope::class)
class FileAiVibeDir internal constructor(private val dir: File) : AiVibeDir {

    @Inject
    constructor(files: OrphicFiles) : this(File(files.root, AiVibeFolder.ARCHIVE_DIR))

    override fun jsonFiles(): List<String> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.map { it.name } ?: emptyList()

    override fun read(name: String): String? = runCatching { File(dir, name).readText() }.getOrNull()

    override fun write(name: String, text: String) {
        dir.mkdirs()
        File(dir, name).writeText(text)
    }

    override fun moveToDeleted(name: String): Boolean {
        val trash = File(dir, AiVibeFolder.DELETED_DIR).apply { mkdirs() }
        return File(dir, name).renameTo(File(trash, name))
    }

    override fun modifiedAt(name: String): Long = File(dir, name).lastModified()
}
