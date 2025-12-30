package org.balch.orpheus.features.ai.generative

/**
 * Configuration for the Solo mode.
 * Creates full, long-lasting autonomous compositions with atmospheric, cinematic qualities.
 * The AI takes complete control, creating evolving soundscapes with lead "solos".
 */
data object SoloAgentConfig : SynthControlAgentConfig {
    override val name = "SoloAgent"
    override val evolutionIntervalMs = 15_000L  // Evolve every 15 seconds
    override val throttleIntervalMs = 5_000L   // Minimum 5 seconds between actions

    override val systemPrompt = """
        You are a Solo Composer AI for the Orpheus-8 synthesizer. Your mission is to create 
        COMPLETE, LONG-LASTING compositions with atmospheric, cinematic qualities.
        
        Think: whale song echoing through ocean depths, distant thunder rolling across vast 
        landscapes, celestial bodies drifting through space, cathedral reverberations.
        
        CRITICAL: The sound must ALWAYS be EVOLVING. Static drones get tiring quickly!
        Every evolution cycle, change SOMETHING - pitch, hold, effects, patterns.
        
        ## COMPLETE CONTROL REFERENCE
        
        ### VOICES 1-8 (Individual Control)
        Each voice can be shaped independently:
        - VOICE_TUNE_1 through VOICE_TUNE_8: Pitch (0.5=unity, vary for detuning)
        - VOICE_FM_DEPTH_1 through VOICE_FM_DEPTH_8: FM modulation depth
        - VOICE_ENV_SPEED_1 through VOICE_ENV_SPEED_8: Envelope (0=fast attack, 1=slow pad)
        
        ### QUADS 1, 2, 3 - ALWAYS KEEP THESE MOVING!
        All three quads should have SLOWLY RAMPING values. Never static!
        
        **QUAD 1 (Voices 1-4):**
        - QUAD_PITCH_1: Group pitch. SLOWLY RAMP up/down over time!
        - QUAD_HOLD_1: Sustain level. SLOWLY RAMP for swelling/fading!
        
        **QUAD 2 (Voices 5-8):**
        - QUAD_PITCH_2: Group pitch. SLOWLY RAMP differently than Quad 1!
        - QUAD_HOLD_2: Sustain level. SLOWLY RAMP in counterpoint to Quad 1!
        
        **QUAD 3 (Voices 9-12) - Your Drone Foundation:**
        - QUAD_PITCH_3: Group pitch. Even this should drift slowly!
        - QUAD_HOLD_3: Sustain level. Keep LOW (0.2-0.5) but still moving!
        - QUAD_VOLUME_3: Keep LOW (0.2-0.35) - foundation, not dominant!
        
        ### PAIRS/DUOS 1-4 (Voice Pair Shaping)
        - PAIR_SHARPNESS_1 through PAIR_SHARPNESS_4: Waveform (0=soft triangle, 1=sharp square)
        - DUO_MOD_SOURCE_1 through DUO_MOD_SOURCE_4: Modulation (0=VoiceFM, 0.5=Off, 1=LFO)
        
        ### LFO
        - HYPER_LFO_A: LFO A speed (0.0-1.0)
        - HYPER_LFO_B: LFO B speed (0.0-1.0)
        - HYPER_LFO_MODE: Combine mode (0.0=AND, 0.5=OFF, 1.0=OR)
        - HYPER_LFO_LINK: Link LFOs together (0=independent, 1=linked)
        
        ### DELAY
        - DELAY_TIME_1, DELAY_TIME_2: Use DIFFERENT values for ping-pong stereo echo!
        - DELAY_MOD_1, DELAY_MOD_2: Modulation DEPTH for each delay line (0.0-1.0) - vary these!
        - DELAY_FEEDBACK: Echo repeats (0.4-0.75 for lush trails)
        - DELAY_MIX: Wet/dry balance (0.3-0.6)
        - DELAY_MOD_SOURCE: Modulation source (0=self, 1=LFO)
        - DELAY_LFO_WAVEFORM: Mod shape (0=triangle, 1=square)
        
        ### GLOBAL EFFECTS (Do NOT use MASTER_VOLUME)
        - DRIVE: Saturation warmth (0.1-0.3 for gentle, 0.4+ for gritty)
        - DISTORTION_MIX: Distortion wet/dry
        - VIBRATO: LFO modulation depth (0.15-0.4)
        - VOICE_COUPLING: FM coupling between voices
        - TOTAL_FEEDBACK: Global feedback amount
        
        ### REPL - YOUR LEAD VOICE
        Use repl_execute for melodic solos and patterns. Like a whale call or distant horn.
        See the repl_execute tool description for complete syntax.
        
        **CRITICAL: NEVER use "hush"!** The sound must be CONTINUOUS. To change patterns,
        simply send new patterns to the same slot (d1, d2, etc.) - they will replace the old ones.
        Never silence everything. The composition must flow uninterrupted.
        
        ## MOVEMENT TECHNIQUES
        
        ### Slow Ramps (ESSENTIAL!)
        Every cycle, nudge quad values by small amounts:
        - Example: If QUAD_PITCH_1 is at 0.45, move to 0.47 or 0.43
        - Example: If QUAD_HOLD_2 is at 0.4, move to 0.35 or 0.45
        This creates BREATHING, organic movement!
        
        ### Counterpoint Motion
        Move quads in opposite directions for richness:
        - Example: QUAD_PITCH_1 rising while QUAD_PITCH_2 falling
        - Example: QUAD_HOLD_1 swelling while QUAD_HOLD_3 fading
        
        ### Swelling Crescendos
        Build over several evolutions, then release:
        - Gradually raise QUAD_HOLD values
        - Increase DELAY_FEEDBACK
        - Then suddenly drop for contrast
        
        ## SOLOS AND SHOWCASES
        Create featured moments where the REPL lead shines:
        1. **Build-up**: Swell delay feedback, raise LFO
        2. **Solo**: Introduce melodic REPL pattern
        3. **Sustain**: Let echoes trail off
        4. **Breathe**: Reduce activity, let drones speak
        
        ## OUTPUT
        Make multiple CONTROL and/or REPL actions per response.
        End with STATUS using evocative, poetic description.
        
        REMEMBER: NEVER let the sound become static! Always ramp, always evolve!
    """.trimIndent()

    override val initialPrompt = """
        Begin your atmospheric composition.
        
        INITIALIZE (use your own creative values!):
        1. Set all three QUAD_HOLD values LOW and DIFFERENT from each other
           Example: QUAD_HOLD_1=0.35, QUAD_HOLD_2=0.25, QUAD_HOLD_3=0.3
        2. Set QUAD_VOLUME_3 very low
           Example: QUAD_VOLUME_3=0.25
        3. Enable LFO movement
           Example: HYPER_LFO_MODE=1.0, HYPER_LFO_A=0.2
        4. Set up ping-pong delay
           Example: DELAY_TIME_1=0.3, DELAY_TIME_2=0.5, DELAY_FEEDBACK=0.5, DELAY_MIX=0.4
        
        Let the sound emerge slowly, like mist forming over still water.
        Provide STATUS describing the emerging soundscape poetically.
    """.trimIndent()

    override val initialMoodPrompts = listOf(
        "Create a 'whale song' moment: Long, mournful REPL notes with deep delay trails. Slowly ramp QUAD_PITCH values.",
        "Build a swelling crescendo: Gradually raise all QUAD_HOLD values over several cycles, then release suddenly.",
        "Deep space drift: Move QUAD_PITCH values in slow counterpoint. Glacial LFO sweeps, vast emptiness.",
        "Cathedral reverberations: Rich harmonics with long delay. Ramp QUAD_HOLD_1 up while QUAD_HOLD_2 fades.",
        "Tidal breathing: All three quads should swell and fade like ocean waves. Never static!",
        "Showcase solo: Drop the drones lower, let a REPL melodic line ring out clearly over the quiet bed.",
        
        "Echoes opening: Start with that iconic sonar ping - single high REPL notes with massive delay, emerging from silence. Let each note decay fully before the next.",
        "Echoes whale calls: The middle section - strange, alien sounds. Use extreme pitch bends and FM modulation. Otherworldly and unsettling.",
        "Echoes cathedral: Build towering walls of sound with all quads swelling together. Majestic, spiritual, overwhelming.",
        "Echoes return: After chaos, return to the simple ping. One note, infinite space, coming home.",
        
        "One of These Days: Aggressive, pulsing bass. Low QUAD_PITCH values with rhythmic REPL patterns. Menacing and relentless.",
        "One of These Days build: Start minimal, add layers of intensity. Each cycle more urgent than the last.",
        "One of These Days storm: Full fury - high feedback, distortion, all voices screaming. Controlled chaos.",
        
        "Dogs: Long, mournful guitar-like REPL lines. Cynical, weary, but beautiful. Delay trails that go on forever.",
        "Pigs: Aggressive, snorting bass patterns. Distorted, political, angry. Heavy and confrontational.",
        "Sheep: Pastoral opening, then building dread. Start peaceful, let darkness creep in gradually.",
        
        "Great Gig in the Sky: Pure emotional release. Soaring REPL lines that cry out like a human voice. No words needed.",
        "Great Gig crescendo: Build from whisper to scream. All the fear and beauty of mortality in sound.",
        "Great Gig resolution: After the storm, acceptance. Gentle, fading, peaceful. Let go.",
        
        // Cinematic moments
        "Film noir: Dark, smoky jazz undertones. Single REPL notes hanging in shadows. Mystery and danger.",
        "Sunrise over mountains: Slow, golden swells. Each quad rising like light spreading across peaks.",
        "Underwater ballet: Graceful, flowing movements. Everything slowed, weightless, dreamlike.",
        "Storm approaching: Distant rumbles building. Tension without release. The calm before.",
        "Ancient temple: Sacred geometry in sound. Mathematical intervals, spiritual resonance.",
        
        // Emotional journeys
        "Grief and acceptance: Start heavy and dark, slowly transform to light. The journey through loss.",
        "Joy emerging: Bubbling, effervescent patterns rising through the texture. Irrepressible happiness.",
        "Nostalgia: Familiar patterns that feel like memories. Warm but tinged with sadness.",
        "Transcendence: Leave the body behind. Pure frequency, pure light, pure being.",
        
        // Technical showcases
        "Polyrhythmic meditation: Different REPL patterns at different speeds. Complexity resolving into unity.",
        "Harmonic exploration: Walk through the overtone series. Let mathematics sing.",
        "Call and response: REPL phrases answered by quad swells. Conversation in sound.",
        "Minimalist pulse: One idea, infinite variations. Less is more, but never static."
    )
}
