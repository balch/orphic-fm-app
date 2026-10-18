package org.balch.orpheus.core.presets

import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import org.balch.orpheus.core.plugin.PortValue

/** Json config for preset files on disk, shared by the JVM preset repository and take storage. */
val PresetJson: Json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    serializersModule = SerializersModule {
        polymorphic(PortValue::class) {
            subclass(PortValue.FloatValue::class)
            subclass(PortValue.IntValue::class)
            subclass(PortValue.BoolValue::class)
        }
    }
}
