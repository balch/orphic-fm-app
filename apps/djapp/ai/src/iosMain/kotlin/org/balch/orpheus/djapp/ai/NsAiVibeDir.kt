package org.balch.orpheus.djapp.ai

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile

/** [AiVibeDir] under Application Support: app data the user never manages, kept in device backups. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
@ContributesBinding(AppScope::class)
class NsAiVibeDir internal constructor(private val dir: String) : AiVibeDir {

    @Inject
    constructor() : this(applicationSupport() + "/" + AiVibeFolder.ARCHIVE_DIR)

    private val fm get() = NSFileManager.defaultManager

    override fun jsonFiles(): List<String> =
        fm.contentsOfDirectoryAtPath(dir, null).orEmpty().filterIsInstance<String>().filter { it.endsWith(".json") }

    override fun read(name: String): String? =
        NSString.stringWithContentsOfFile("$dir/$name", NSUTF8StringEncoding, null)

    override fun write(name: String, text: String) {
        fm.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
        val ok = NSString.create(string = text).writeToFile("$dir/$name", atomically = true, encoding = NSUTF8StringEncoding, error = null)
        check(ok) { "could not write $dir/$name" }
    }

    override fun moveToDeleted(name: String): Boolean {
        val trash = "$dir/${AiVibeFolder.DELETED_DIR}"
        fm.createDirectoryAtPath(trash, withIntermediateDirectories = true, attributes = null, error = null)
        return fm.moveItemAtPath("$dir/$name", toPath = "$trash/$name", error = null)
    }

    override fun modifiedAt(name: String): Long =
        ((fm.attributesOfItemAtPath("$dir/$name", null)?.get(NSFileModificationDate) as? NSDate)
            ?.timeIntervalSince1970 ?: 0.0).times(1000).toLong()

    private companion object {
        fun applicationSupport(): String =
            NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).first() as String
    }
}
