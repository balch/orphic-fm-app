package org.balch.orpheus.tools.vibecodegen

import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asClassName
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.features.pulsar.models.Vibe
import org.balch.orpheus.features.pulsar.models.VibeProvider

private const val VIBES_PACKAGE = "org.balch.orpheus.features.pulsar.vibes"

/**
 * Builds the Kotlin source for a `<className>Vibe.kt` provider from a decoded [Vibe] — the
 * generated-file analog of a hand-authored `*Vibe.kt` (see RustedCoastVibe.kt for the shape this
 * mirrors), except every field is emitted explicitly and there's no musical prose (see the v1
 * scope boundaries in the design spec).
 */
fun generateVibeFile(vibe: Vibe, className: String): FileSpec {
    val providerType = TypeSpec.classBuilder(className)
        .addKdoc(
            "%L — generated from an AI-archived vibe JSON by tools:vibe-codegen (NOT hand-authored).\n" +
                "A compiling, faithful starting point; polish (idiom collapsing, musical notes, tuning)\n" +
                "via the vibe-creator skill once this vibe earns a permanent keep.\n",
            vibe.name,
        )
        .addAnnotation(Inject::class)
        .addAnnotation(
            com.squareup.kotlinpoet.AnnotationSpec.builder(ContributesIntoSet::class)
                .addMember(
                    "%T::class, binding = binding<%T>()",
                    FeatureScope::class.asClassName(),
                    VibeProvider::class.asClassName(),
                )
                .build(),
        )
        .addSuperinterface(VibeProvider::class.asClassName())
        .addProperty(
            PropertySpec.builder("name", String::class.asClassName())
                .addModifiers(KModifier.OVERRIDE)
                .initializer("%S", vibe.name)
                .build(),
        )
        .addProperty(
            PropertySpec.builder("vibe", Vibe::class.asClassName())
                .addModifiers(KModifier.OVERRIDE)
                .delegate("lazy {\n%L\n}", valueToCodeBlock(vibe))
                .build(),
        )
        .build()

    return FileSpec.builder(VIBES_PACKAGE, className)
        .addImport("dev.zacsweers.metro", "binding")
        .addType(providerType)
        .build()
}
