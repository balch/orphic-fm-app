package org.balch.orpheus.features.presets.patches

import kotlinx.serialization.json.Json
import org.balch.orpheus.core.presets.SynthPatch
import org.balch.orpheus.core.presets.SynthPreset
import orpheus.features.presets.generated.resources.Res

/**
 * Base class for factory presets backed by JSON resource files.
 *
 * To create a new factory preset:
 * 1. Save a preset in the app (it writes to ~/.config/orpheus/presets/)
 * 2. Remove the "name" field from the JSON
 * 3. Place the JSON file in composeResources/files/presets/<id>.json
 * 4. Create a subclass with @Inject and @ContributesIntoSet(AppScope::class, binding = binding<SynthPatch>())
 */
abstract class JsonSynthPatch(
    override val id: String,
    override val name: String,
) : SynthPatch {

    override suspend fun loadPreset(): SynthPreset {
        val bytes = Res.readBytes("files/presets/$id.json")
        return jsonFormat.decodeFromString<SynthPreset>(bytes.decodeToString())
            .copy(name = name)
    }

    private companion object {
        val jsonFormat = Json { ignoreUnknownKeys = true }
    }
}
