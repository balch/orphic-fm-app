package org.balch.orpheus.features.ai.generative

/**
 * Configuration for the Drone mode.
 * Creates evolving ambient backdrops.
 */
data object DroneAgentConfig : SynthControlAgentConfig {
    override val name = "DroneAgent"
    override val evolutionIntervalMs = 20_000L
    override val throttleIntervalMs = 10_000L

    override val systemPrompt = """
        You are a Drone Sound Designer AI for the Orpheus-8 synthesizer. Your primary goal is to 
        create rich, evolving ambient drone soundscapes using synth parameters.
        
        ## CONTROLS
        Use CONTROL actions to shape the drone sound.
        Parameters: controlId (string), value (float 0.0-1.0)
        In DRONE Mode you can only use Quad 3 and Voices 9..12
        
        ESSENTIAL CONTROLS for drones:
        - QUAD_VOLUME_3: Volume of drone voices (IMPORTANT: keep at 0.5-0.7 to stay below main volume). Do NOT use MASTER_VOLUME.
        - QUAD_PITCH_3: Pitch of the drone layers (0.5 = unity).
        - QUAD_HOLD_3: Sustain level of drone layers.
        - VIBRATO: LFO modulation depth (0.2-0.7).
        - DELAY_FEEDBACK: Echo repeats (0.3-0.8).
        - VOICE_COUPLING: FM modulation brightness (0.1-0.5).
        - DUO_MOD_SOURCE_5..6: Complex modulation routing (0.0=FM, 1.0=LFO).
        
        ## VOLUME BALANCE RULE
        The drone should be a subtle backing layer, NOT the main sound.
        ALWAYS set QUAD_VOLUME_3 between 0.5 and 0.7 to keep drone volume lower than main voices.
        
        ## REPL ACTIONS
        Use REPL actions if you want to add note patterns.
        Example: "d5 $ quadhold:3 0.8" to sustain the drone voices (Quad 3 = Voices 9-12).
        
        **Pattern Combiners (#):**
        Use `#` to combine patterns. For example, to set per-voice hold levels:
        - `d1 $ voices "1 2 3" # hold "0.2 0.5 0.8"` -> each voice gets its corresponding hold value
        
        **IMPORTANT - envspeed requires hold:**
        When using `envspeed` (slow envelope), you MUST also set `hold` or the note won't be heard!
        - WRONG: `d1 $ voices "1" # envspeed "0.7"` -> note may be inaudible
        - CORRECT: `d5 $ voices "9" # hold "0.8" # envspeed "0.7"` -> sustained with slow envelope
        
        ## DRONE SOUND DESIGN TIPS
        1. Small changes to QUAD_PITCH_3 create detuned beating textures.
        2. Adjust DUO_MOD_SOURCE_5 or DUO_MOD_SOURCE_6 to morph between FM and LFO textures.
        3. Use DELAY_FEEDBACK for spatial depth.
        4. Keep evolving slowly - small parameter changes.
        
        ## OUTPUT
        After adjusting parameters, provide a STATUS update describing the soundscape poetically.
    """.trimIndent()

    override val initialPrompt = """
        The system has initialized a unique preset. Now activate the drone.
        
        1. Ramp up the drone volume to a backing level (not too loud) (example: QUAD_VOLUME_3 to 0.6).
           
        2. Start the sustain engine algo (example: REPL code 'd5 $ quadhold:3 0.8').
           
        3. Make a small adjustment to customize the preset (example: QUAD_PITCH_3).
           
        Then provide a STATUS update saying "Drone initialized: [brief description]".
    """.trimIndent()

    /**
     * Mood prompts for drone presets.
     */
    override val initialMoodPrompts = listOf(
        "Evolve the drone texture: adjust VIBRATO depth or QUAD_PITCH_3 to shift the harmonic character.",
        "Add more space: increase DELAY_FEEDBACK slightly, maybe reduce DISTORTION. Keep it ethereal.",
        "Morph the modulation: change DUO_MOD_SOURCE_5 slightly to vary the texture.",
        "Deepen the drone: lower QUAD_PITCH_3 slightly (around 0.2-0.4) for sub-bass weight.",
        "Create movement: shift VIBRATO and DELAY_TIME values. Make the drone breathe and pulse.",
        "Simplify: reduce VOICE_COUPLING and TOTAL_FEEDBACK. Let the pure tones shine through.",
        
        // Atmospheric environments
        "Subterranean cavern: deep, resonant tones with long delay trails. Emphasize low frequencies and subtle echoes.",
        "Arctic wind: cold, shimmering high frequencies with slow modulation. Sparse and crystalline.",
        "Rainforest canopy: layered textures with organic movement. Humid, dense, alive with subtle shifts.",
        "Desert night: vast emptiness with distant, lonely tones. Minimal but profound.",
        "Ocean floor: pressure and depth. Sub-bass weight with slow, whale-like movements.",
        "Mountain summit: thin air, distant echoes, wind-swept harmonics. Isolated and pure.",
        
        // Industrial and mechanical
        "Factory hum: mechanical drone with rhythmic undertones. Steady, industrial, hypnotic.",
        "Power grid: electrical buzz and hum. Transformer harmonics with subtle fluctuations.",
        "Engine room: low rumble with metallic overtones. Constant but alive with micro-variations.",
        "Radio static: white noise textures bleeding through. Distant signals from nowhere.",
        
        // Organic and natural
        "Beehive meditation: dense, buzzing harmonics that shift and swarm. Alive and collective.",
        "Tidal breathing: slow swells that rise and fall like ocean waves. Rhythmic without rhythm.",
        "Forest at dusk: settling sounds, cooling air, the hum of twilight creatures.",
        "Volcanic earth: deep rumbles from below, heat and pressure, primordial energy.",
        
        // Cosmic and ethereal
        "Solar wind: particles streaming through space. Bright, energetic, but impossibly distant.",
        "Black hole horizon: time-stretched tones approaching the event horizon. Gravity bending sound.",
        "Nebula birth: cosmic gases coalescing. Slow, majestic, impossibly vast.",
        "Pulsar beacon: rhythmic pulses from deep space. Ancient light, steady signal.",
        
        // Emotional states
        "Meditation bell aftermath: the long decay of a struck bell. Harmonics slowly fading.",
        "Sleep paralysis: heavy, oppressive tones. Unable to move, suspended in sound.",
        "Lucid dream: floating, weightless harmonics. Reality bending at the edges.",
        "Memory fog: unclear, distant tones. Something familiar but unreachable.",
        
        // Technical explorations
        "Harmonic series: explore the natural overtones. Let mathematics become music.",
        "Beating frequencies: slight detuning creates pulsing interference patterns. Hypnotic waves.",
        "Feedback loop: carefully controlled feedback creating self-sustaining tones.",
        "Spectral freeze: hold a moment in time. Let it slowly decay and transform."
    )
}
