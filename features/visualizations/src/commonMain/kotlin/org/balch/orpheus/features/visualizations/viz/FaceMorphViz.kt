package org.balch.orpheus.features.visualizations.viz

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.decodeToImageBitmap
import com.diamondedge.logging.logging
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.core.playback.MetadataProducer
import org.balch.orpheus.features.visualizations.viz.face.FaceKeyframes
import org.balch.orpheus.features.visualizations.viz.face.FaceMorphInputs
import org.balch.orpheus.features.visualizations.viz.face.MorphDirector
import org.balch.orpheus.features.visualizations.viz.face.NewsBroadcastScene
import org.balch.orpheus.features.visualizations.viz.face.TurnOrigin
import org.balch.orpheus.features.visualizations.viz.face.buildFaceBiasMap
import org.balch.orpheus.features.visualizations.viz.face.drumLevel
import org.balch.orpheus.features.visualizations.viz.face.signalScaled
import org.balch.orpheus.features.visualizations.viz.face.stageBlend
import org.balch.orpheus.ui.infrastructure.CenterPanelStyle
import org.balch.orpheus.ui.infrastructure.VisualizationLiquidEffects
import org.balch.orpheus.ui.infrastructure.VisualizationLiquidScope
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.viz.Visualization
import org.jetbrains.compose.resources.ExperimentalResourceApi
import orpheus.features.visualizations.generated.resources.Res
import orpheus.features.visualizations.generated.resources.allDrawableResources

/**
 * A face that turns over the course of the song, staged as the story graphic on an old set
 * showing a news broadcast. Song position sets how far it has gone for good; loud passages push
 * it further and let it fall back; kicks flash what is coming and snap the picture to grey.
 */
@Inject
@ContributesIntoSet(FeatureScope::class, binding = binding<Visualization>())
class FaceMorphViz(
    private val engine: SynthEngine,
    private val metadataProducer: MetadataProducer,
    private val dispatcherProvider: DispatcherProvider,
) : Visualization {

    override val id = "stay_asleep"
    override val name = "Stay Asleep"
    override val exclusiveToSong = EXCLUSIVE_SONG
    // The set is the point of this one, so the panels get out of its way once the user stops.
    override val hidesPanelsWhenIdle = true
    override val color = OrpheusColors.neonMagenta
    override val knob1Label = "DECAY"
    override val knob2Label = "SIGNAL"
    override val liquidEffects = Default

    private var decayKnob = 0.5f
    private var signalKnob = 0.5f
    override fun setKnob1(value: Float) { decayKnob = value.coerceIn(0f, 1f) }
    override fun setKnob2(value: Float) { signalKnob = value.coerceIn(0f, 1f) }

    private val director = MorphDirector()
    private val turnOrigin = TurnOrigin()
    private val bias by lazy { buildFaceBiasMap() }
    private val log = logging("FaceMorphViz")

    @OptIn(ExperimentalResourceApi::class)
    private val keyframes = FaceKeyframes(
        exists = { Res.allDrawableResources.containsKey("face_$it") },
    ) { index ->
        try {
            Res.readBytes("drawable/face_$index.webp").decodeToImageBitmap()
        } catch (e: Exception) {
            // Existence was already confirmed, so this is a corrupt keyframe, not end-of-list.
            log.warn(e) { "Face keyframe $index failed to decode" }
            null
        }
    }

    private var active = false
    // The shader's noise pattern keeps running even while inactive/reset, so re-selecting the
    // viz or starting a new song never pops the picture; only the turn itself restarts.
    private var shaderTime = 0f
    private var failed = false

    override fun onActivate() {
        active = true
        failed = false
        director.reset()
        turnOrigin.onActivate(metadataProducer.progressFlow.value)
    }

    override fun onDeactivate() {
        active = false
        keyframes.clear()
    }

    @Composable
    override fun Content(modifier: Modifier) {
        var frameCount by remember { mutableIntStateOf(0) }
        var stage by remember { mutableIntStateOf(0) }
        var scene by remember { mutableStateOf<BroadcastFrame?>(null, neverEqualPolicy()) }

        LaunchedEffect(Unit) {
            // count() only checks resource existence now, no decode, so no dispatch needed.
            frameCount = keyframes.count()
        }
        // Decode off the frame loop; the loop holds the last good pair until the new one is in.
        LaunchedEffect(stage, frameCount) {
            if (frameCount >= 2) withContext(dispatcherProvider.default) { keyframes.prepare(stage) }
        }

        LaunchedEffect(frameCount) {
            if (frameCount < 2) return@LaunchedEffect
            var last = 0L
            // songFlow, not titleFlow: Orpheus overlays "Orpheus" onto titleFlow during AI/Evo
            // modes, and that toggle must not look like a song change mid-turn.
            var lastTitle = metadataProducer.songFlow.value
            while (true) {
                withFrameNanos { now ->
                    val dt = if (last == 0L) 0.016f else ((now - last) / 1_000_000_000f).coerceIn(0.001f, 0.1f)
                    last = now
                    if (!active || failed) return@withFrameNanos
                    try {
                        shaderTime += dt

                        val title = metadataProducer.songFlow.value
                        if (title != lastTitle) {
                            lastTitle = title
                            director.reset()
                            turnOrigin.onSongChange(metadataProducer.progressFlow.value)
                        }

                        val progress = turnOrigin.fraction(metadataProducer.progressFlow.value, dt)

                        val levels = engine.pulsarVizFlow.value.trackLevels
                        val master = engine.masterLevelFlow.value
                        val frame = director.update(
                            dt = dt,
                            progress = progress,
                            level = master,
                            kick = levels.getOrElse(0) { 0f },
                            drums = drumLevel(levels),
                            floorOffset = (decayKnob - 0.5f) * 0.6f,
                        )
                        val blend = stageBlend(frame.morph, frameCount)
                        stage = blend.stage
                        // Until the wanted pair is decoded, keep drawing the previous one at its end.
                        val pair = keyframes.pair(blend.stage)
                        if (pair != null) {
                            // The shader's noise hash loses precision as its argument grows;
                            // wrapping trades one pop an hour for stable noise at any run length.
                            val clock = shaderTime % TIME_WRAP_S
                            scene = BroadcastFrame(
                                face = FaceMorphInputs(
                                    faceA = pair.first, faceB = pair.second, bias = bias,
                                    t = blend.t,
                                    flicker = frame.flicker,
                                    // The tear and the fringe run over the whole tube now, so the
                                    // story box must not glitch a second time inside them.
                                    glitch = 0f,
                                    // Scanlines belong to the whole picture now, not the story box.
                                    crt = 0f,
                                    mono = frame.mono,
                                    time = clock,
                                ),
                                level = if (master.isFinite()) master.coerceIn(0f, 1f) else 0f,
                                crt = signalScaled(frame.crt, signalKnob),
                                glitch = signalScaled(frame.glitch, signalKnob),
                                time = clock,
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        // A throw here would propagate through withFrameNanos into the Recomposer
                        // and take down the whole Compose UI; freeze on the last good frame instead.
                        log.error(e) { "Face Morph frame update failed; freezing on last good frame" }
                        failed = true
                    }
                }
            }
        }

        scene?.let { NewsBroadcastScene(modifier, it.face, it.level, it.crt, it.time, it.glitch) }
    }

    /** One frame of the whole broadcast: the story graphic plus what the set itself needs. */
    private class BroadcastFrame(
        val face: FaceMorphInputs,
        val level: Float,
        val crt: Float,
        val glitch: Float,
        val time: Float,
    )

    // Public so the vibe carrying this name can be guard-tested against it from another module;
    // everything else in here stays private.
    companion object {
        const val EXCLUSIVE_SONG = "Stay Asleep"

        private const val TIME_WRAP_S = 3600f

        // The broadcast is small ticker text over a CRT picture, so no refraction bends it
        // (refraction/curve 0 disables the liquid effect). The glass is this thin because the
        // panels fade away when idle. The DJ app's dock never fades them, so it raises these
        // values itself (see dockLiquidEffects).
        private val FLAT_SCOPE = VisualizationLiquidScope(saturation = 1f, contrast = 1f)

        private val Default = VisualizationLiquidEffects(
            frostSmall = 4f,
            frostMedium = 6f,
            frostLarge = 8f,
            tintAlpha = 0f,
            top = FLAT_SCOPE,
            bottom = FLAT_SCOPE,
            title = CenterPanelStyle(
                scope = FLAT_SCOPE,
                titleColor = OrpheusColors.sterlingSilver,
                borderColor = Color.White.copy(alpha = 0.15f),
            ),
        )
    }
}
