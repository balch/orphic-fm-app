package org.balch.orpheus.djapp.ai

import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.balch.orpheus.features.ai.AiVibeArchive
import org.balch.orpheus.features.ai.NoOpAiVibeArchive
import org.balch.orpheus.features.ai.aiVibeFileName
import java.io.File

/**
 * JVM desktop [AiVibeArchive]: writes each AI-applied vibe as its own JSON file under
 * `~/.config/orpheus-dj/ai-vibes/` (next to the DJ app's settings.json), retrievable later.
 */
@ContributesBinding(AppScope::class, replaces = [NoOpAiVibeArchive::class])
@Inject
class JvmAiVibeArchive : AiVibeArchive {
    private val log = logging("JvmAiVibeArchive")

    override suspend fun archive(vibeName: String, vibeJson: String) {
        withContext(Dispatchers.IO) {
            try {
                val dir = File(System.getProperty("user.home"), "$CONFIG_DIR/$ARCHIVE_DIR").apply { mkdirs() }
                val file = File(dir, aiVibeFileName(vibeName, System.currentTimeMillis()))
                file.writeText(vibeJson)
                log.debug { "Archived AI vibe '$vibeName' -> ${file.absolutePath}" }
            } catch (e: Exception) {
                log.error { "Failed to archive AI vibe '$vibeName': ${e.message}" }
            }
        }
    }

    private companion object {
        const val CONFIG_DIR = ".config/orpheus-dj"
        const val ARCHIVE_DIR = "ai-vibes"
    }
}
