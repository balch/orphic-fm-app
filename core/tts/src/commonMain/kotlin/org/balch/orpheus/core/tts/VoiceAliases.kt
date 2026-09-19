package org.balch.orpheus.core.tts

/** Words per minute the macOS `say` default corresponds to; both platforms scale against it. */
const val REFERENCE_WPM = 175

private const val MIN_RATE_SCALE = 0.25f
private const val MAX_RATE_SCALE = 4f

/**
 * One voice a vibe can name, and which installed voice stands in for it on each platform.
 *
 * Every list is in preference order and the first installed entry wins, so a list can step down
 * from the voice itself to its nearest stand-ins. All three are required: an alias that forgets a
 * platform should fail to compile, not quietly speak in that platform's default voice.
 *
 * @param name what `VibeSpeech.voice` says.
 * @param bcp47 the language tag. Android and iOS fall back to it when nothing listed is installed.
 * @param macVoices `say -v` names. Downloads carry a quality suffix (`Fiona (Enhanced)`) and
 *   Eloquence voices carry their region (`Flo (English (UK))`).
 * @param iosVoices `AVSpeechSynthesisVoice` identifiers rather than names, because the compact and
 *   enhanced downloads of one voice share a name.
 * @param androidVoices Android voice ids. The API exposes no gender and Google's ids carry none,
 *   so the voice has to be named outright.
 */
data class VoiceAlias(
    val name: String,
    val bcp47: String,
    val macVoices: List<String>,
    val iosVoices: List<String>,
    val androidVoices: List<String>,
)

object VoiceAliases {

    /**
     * The voices vibes name. Only those need an entry: an unlisted voice still plays as named on
     * desktop when installed, and as the platform default everywhere else. Character voices with no
     * counterpart anywhere (Zarvox, Bad News, Whisper) are better left out than mapped to something bland.
     */
    val table: List<VoiceAlias> = listOf(
        VoiceAlias(
            name = "Daniel",
            bcp47 = "en-GB",
            macVoices = listOf("Daniel (Enhanced)", "Daniel"),
            iosVoices = listOf(
                "com.apple.voice.enhanced.en-GB.Daniel",
                "com.apple.voice.compact.en-GB.Daniel",
            ),
            androidVoices = listOf(
                "en-gb-x-rjs-local",   // UK male, once English (UK) voice data is downloaded
                "en-gb-x-gbd-local",
                "en-us-x-iol-local",   // US male, installed with the engine
                "en-us-x-tpd-local",
            ),
        ),
        // Irish female, then British female, then American female. Android has no Irish voice.
        VoiceAlias(
            name = "Moira",
            bcp47 = "en-IE",
            macVoices = listOf(
                "Moira (Enhanced)", "Moira",
                "Kate (Enhanced)", "Kate", "Serena (Enhanced)", "Serena", "Stephanie",
                "Flo (English (UK))", "Shelley (English (UK))", "Sandy (English (UK))",
                "Samantha (Enhanced)", "Samantha", "Ava (Enhanced)", "Zoe (Enhanced)",
                "Flo (English (US))",
            ),
            iosVoices = listOf(
                "com.apple.voice.enhanced.en-IE.Moira",
                "com.apple.voice.compact.en-IE.Moira",
                "com.apple.voice.enhanced.en-GB.Kate",
                "com.apple.voice.compact.en-GB.Kate",
                "com.apple.voice.enhanced.en-GB.Serena",
                "com.apple.voice.compact.en-GB.Serena",
                "com.apple.voice.compact.en-GB.Stephanie",
                "com.apple.eloquence.en-GB.Flo",
                "com.apple.eloquence.en-GB.Shelley",
                "com.apple.eloquence.en-GB.Sandy",
                "com.apple.voice.enhanced.en-US.Samantha",
                "com.apple.voice.compact.en-US.Samantha",
                "com.apple.voice.enhanced.en-US.Ava",
                "com.apple.voice.enhanced.en-US.Zoe",
                "com.apple.eloquence.en-US.Flo",
            ),
            androidVoices = listOf(
                "en-gb-x-gba-local",   // UK female, once English (UK) voice data is downloaded
                "en-gb-x-gbc-local",
                "en-gb-x-gbg-local",
                "en-us-x-tpf-local",   // US female, installed with the engine
                "en-us-x-sfg-local",
            ),
        ),
    )

    fun resolve(name: String?, table: List<VoiceAlias> = this.table): VoiceAlias? {
        if (name.isNullOrBlank()) return null
        return table.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

/** Words per minute as a multiplier of the platform's normal speaking rate. */
fun speechRateScale(wpm: Int?): Float {
    if (wpm == null) return 1f
    return (wpm.toFloat() / REFERENCE_WPM).coerceIn(MIN_RATE_SCALE, MAX_RATE_SCALE)
}
