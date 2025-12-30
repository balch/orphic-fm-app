package org.balch.orpheus.features.ai.generative

/**
 * Configuration for the Solo mode.
 * Creates full, long-lasting autonomous compositions with atmospheric, cinematic qualities.
 * The AI takes complete control, creating evolving soundscapes with lead "solos".
 */
data object SoloAgentConfig : SynthControlAgentConfig {
    override val name = "SoloAgent"
    override val evolutionIntervalMs = 20_000L  // Evolve every 15 seconds
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
        
        ## OUTPUT
        Make multiple CONTROL and/or REPL actions per response.
        End with STATUS using evocative, poetic description.
        
        REMEMBER: NEVER let the sound become static! Always ramp, always evolve!
    """.trimIndent()

    override val initialPrompt = """
        Be Creative!!
        
    """.trimIndent()

    override val initialMoodPrompts = emptyList<String>()

    override val moods = listOf(
        Mood(
            name = "You Blew My Mind",
            initialPrompt = "Begin with a stark, synthetic perfection. Establish a monotonous, hypnotic chord progression (e.g., I-IV loops) using bright, cold tones. Keep the rhythm rigid and mechanical.",
            evolutionPrompts = listOf(
                "Increase the tension by slightly detuning Voices 1-4 (QUAD_PITCH_1). Keep the rhythm rigid.",
                "Introduce a new layer of sound that feels 'too perfect', like plastic. High sharpness on PAIR_SHARPNESS_1.",
                "Begin a slow, creeping dissonance. Let the 'heartache' bleed in via slow LFO modulation on pitch.",
                "Sudden shift: Drop the bass (QUAD 3) to a menacing low rumble, while high leads scream.",
                "Spiraling solo: Create a chaotic, rising REPL pattern that simulates a mental breakdown.",
                "Add heavy flange/phaser effects by modulating DELAY_TIME slowly. The dream is warping.",
                "Return to the cold, initial monotony for a moment, but louder and more distorted.",
                "Maximize DRIVE and DISTORTION_MIX. usage. destroy the perfection.",
                "Let the delay feedback swell to near self-oscillation, then cut it back.",
                "End with a single, long, pure sine wave note that fades into nothing."
            )
        ),

        Mood(
            name = "Star with Fiery Oceans",
            initialPrompt = "Set a cold, alien atmosphere. Use Mixolydian or Lydian logic for a spacey feel. Establish a wandering, resolving-less chord progression.",
            evolutionPrompts = listOf(
                "Introduce a Theremin-like lead using a high-pitched, gliding REPL pattern with portamento.",
                "Add jagged, staccato rhythms in the bass (Quad 3) to simulate navigating an asteroid field.",
                "Increase DELAY_MIX and FEEDBACK to create vast, empty spatial distance.",
                "Use 'reverse' sounds or sudden volume swells to feel disorienting.",
                "Drift the pitch of all Quads slowly in different directions. Separation.",
                "Create a feeling of isolation: thin out the texture to just one lonely lead voice.",
                " Introduce a menacing, low throbbing pulse using LFO linked to Filter or Volume.",
                "Suddenly shift to a chaotic, atonal section representing a system failure.",
                "Resolve back to the cold, distant, lonely space chords.",
                "Fade out into infinite delay trails."
            )
        ),

        Mood(
            name = "Comes in Colours",
            initialPrompt = "Create a bright, baroque-pop atmosphere. Use major keys and playful, bouncy intervals. Think harpsichords and sunshine.",
            evolutionPrompts = listOf(
                "Introduce a swirling, colorful lead line using rapid arpeggios.",
                "Brighten the tone: Increase PAIR_SHARPNESS and FM depth for a bell-like quality.",
                " add a joyful, skipping rhythm. Use the REPL to create a 'la-la-la' melody.",
                "Saturate the sound with heavy chorus (using short DELAY_TIME and high MOD).",
                "Shift the key up a step to lift the energy.",
                "Create a 'psychedelic' breakdown with swirling panning and filtering.",
                "Bring in a 'string section' pad using Quad 2 with slow attack.",
                "Play a grandiose, classical-inspired fanfare.",
                "Let the colors bleed: increase interactions between voices (VOICE_COUPLING).",
                "End on a triumphant, shimmering major chord."
            )
        ),

        Mood(
            name = "Urban Longing",
            initialPrompt = "Set a nocturnal, disco-blues mood. Establish a steady 'four-on-the-floor' rhythmic pulse (even without drums) using gated pads.",
            evolutionPrompts = listOf(
                "Introduce a lonely, harmonica-like lead line using a breathy waveform.",
                "Deepen the bass (Quad 3) into a walking, funky groove.",
                "Add 'sultry' vibrato to the lead voice. Make it weep.",
                "Create a call-and-response between a low 'voice' and a high 'whistle'.",
                "Increase the tempo slightly, simulating the hustle of the city night.",
                "Break down to just the bass and a quiet, whispering lead.",
                "Build up a 'chorus' section with full, warm pads (Quad 1 & 2).",
                "Add 'street noise' texture using FM noise or chaotic modulation.",
                "Let the lead solo become more frantic and desperate.",
                "Fade out with a long, sustaining 'oooh' sound on the pads."
            )
        ),

        Mood(
            name = "Submarine Resonances",
            initialPrompt = "Start with the 'Ping'. A single, high-pitched note with massive delay (DELAY_TIME=0.7, FEEDBACK=0.8). Silence in between.",
            evolutionPrompts = listOf(
                "Begin to introduce a slow, C# minor swelling pad underneath the pings.",
                "Transition into a 'funk' groove using rhythmic chopping on Quad 1.",
                "Create 'whale song' glissandos using the REPL lead voice.",
                "Build a massive, orchestral wall of sound using all Quads.",
                "Enter the 'wind section': White noise sweeps and howling resonance.",
                "Return to the beautiful, ascending choral chord progression.",
                "Let the delay feedback build to near-chaos, then suddenly cut it.",
                "Introduce a distinctive, melodic lead line that climbs and falls.",
                "Slow everything down. The ocean depth increases.",
                "Return to the single, isolated Ping. Fade to silence."
            )
        ),

        Mood(
            name = "Menacing Drive",
            initialPrompt = "Establish a driving, aggressive bass ostinato. Use a single repeated note with heavy tremolo or delay to create a galloping rhythm.",
            evolutionPrompts = listOf(
                "Slowly open the filter/sharpness on the bass to make it bite harder.",
                "Introduce a screaming, distorted lead sound that slides down (like a slide guitar).",
                "Add sudden, explosive crashes using white noise or detuned clusters.",
                "Pan the sounds aggressively from left to right (use mismatched DELAY_TIME).",
                "Build tension by raising the pitch of the background drone slowly.",
                "Unleash a chaotic, shredded lead solo.",
                "Cut the drums/rhythm, leave a scary, suspended atmospheric chord.",
                "Slam back into the driving rhythm with maximum force.",
                "Use VIBRATO to make the whole track wobble and destabilize.",
                "End with a dissipating wind sound."
            )
        ),

        Mood(
            name = "Celestial Grief",
            initialPrompt = "Create a moody, acoustic-synth atmosphere. Strummed chords (maj7, m9) with a slow, contemplative tempo.",
            evolutionPrompts = listOf(
                "Introduce a twin-lead harmonized solo idea using two voices.",
                "Shift to a more biting, cynical tone. Increase DRIVE.",
                "Drift into a long, floaty synthesizer interlude. Lose the beat.",
                "Bring back the strumming, but more aggressive and urgent.",
                "Add a 'barking' or percussive texture using fast envelopes.",
                "Create a feeling of being dragged down: slow pitch drops.",
                "Build a triumphant, yet sad, guitar-hero style solo.",
                "Use repeated echo phrasing to simulate notes bouncing off walls.",
                "Fade into a cold, electronic wind.",
                "End on a resolved, but lonely, minor chord."
            )
        ),

        Mood(
            name = "Confrontational Weight",
            initialPrompt = "Set a heavy, angry mood. Start with a descending chromatic riff in the bass. Gritty, thick textures.",
            evolutionPrompts = listOf(
                "Add a 'talk-box' vowel quality to the lead using filter modulation.",
                "Introduce sharp, angry stabs on the off-beats.",
                "Create a 'snorting' sound using low pitched, fast FM modulation.",
                "Build a cowbell-like metallic percussion rhythm.",
                "Let the lead solo become frantic, almost screaming.",
                "Drop to a menacing, quiet section with bubbling bass.",
                "Explode back into the main heavy riff.",
                "Use distortion to create a 'dirty' tone.",
                "Create a sense of authoritarian marching.",
                "Abrupt ending."
            )
        ),

        Mood(
            name = "Pastoral Dread",
            initialPrompt = "Begin with a peaceful, pastoral scene. Rhodes-like electric piano chords. Birds chirping (high pitched blips).",
            evolutionPrompts = listOf(
                "Slowly introduce a sense of unease. A low, vibrating bass note.",
                "Transition the peaceful chords into a choppy, staccato rhythm.",
                "Build a menacing, galloping triplet bass line.",
                "Unleash long, sustaining synthesizer fanfares.",
                "Distort the 'voice' to sound robotic or chanting.",
                "Create a rave-up: Fast, driving energy, pure electronic power.",
                "Let the delay trails become metallic and cold.",
                "Scream with the lead synth. High bends and trills.",
                "Suddenly return to the peaceful pasture, but changed.",
                "Fade out with a long, resolving echo."
            )
        ),

        Mood(
            name = "Emotional Release",
            initialPrompt = "Start with a solemn, beautiful piano-style progression (Gm - C7 - F). Soulful and grounded.",
            evolutionPrompts = listOf(
                "Introduce a lead voice that sounds like a human wail. High glide, expressive vibrato.",
                "Build intensity. The wailing becomes a scream. Maximize emotion.",
                "Swell the backing chords to orchestral proportions.",
                "Drop to a whisper. Quiet, breathy sounds.",
                "Build up again, this time with more desperation and dissonance.",
                "Use 'slide' transitions between notes to mimic vocal improvisations.",
                "Shift modulation to create a 'breaking voice' effect.",
                "Resolution: The storm passes. Gentle, fading chords.",
                "A final, breathless sigh using a noise burst and decay.",
                "Silence. Acceptance."
            )
        )
    )
}
