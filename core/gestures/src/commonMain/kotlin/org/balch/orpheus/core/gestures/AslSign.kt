package org.balch.orpheus.core.gestures

/** ASL signs recognized by the gesture model, mapped to synth control functions. */
enum class AslSign(
    val label: String,
    val category: AslCategory,
) {
    // Numbers — voice selection
    NUM_1("1", AslCategory.NUMBER),
    NUM_2("2", AslCategory.NUMBER),
    NUM_3("3", AslCategory.NUMBER),
    NUM_4("4", AslCategory.NUMBER),
    NUM_5("5", AslCategory.NUMBER),
    NUM_6("6", AslCategory.NUMBER),
    NUM_7("7", AslCategory.NUMBER),
    NUM_8("8", AslCategory.NUMBER),

    // Letters — mode/parameter selection
    LETTER_A("A", AslCategory.COMMAND),    // Neutral / deselect
    THUMBS_UP("+", AslCategory.COMMAND),   // Hold voice on
    THUMBS_DOWN("-", AslCategory.COMMAND), // Hold voice off
    ILY("ILY", AslCategory.COMMAND),  // I Love You — toggle Maestro Mode
    LETTER_B("B", AslCategory.PARAMETER),  // Pitch bend mode
    LETTER_H("H", AslCategory.PARAMETER),  // Parameter: Hold level (quad)
    LETTER_C("C", AslCategory.SYSTEM),     // System: Coupling
    LETTER_D("D", AslCategory.MODE),       // Duo mode prefix
    LETTER_L("L", AslCategory.PARAMETER),  // Reserved
    LETTER_M("M", AslCategory.PARAMETER),  // Parameter: Morph
    LETTER_Q("Q", AslCategory.MODE),       // Quad mode prefix
    LETTER_R("R", AslCategory.COMMAND),    // Remote adjust (no-gate pinch)
    LETTER_S("S", AslCategory.PARAMETER),  // Parameter: Sharpness
    LETTER_V("V", AslCategory.SYSTEM),     // System: Vibrato
    LETTER_W("W", AslCategory.PARAMETER),  // Parameter: Volume (quad)
    LETTER_Y("Y", AslCategory.SYSTEM),     // System: Chaos
    ;

    /** Voice index (0-based) for number signs, null for non-numbers. */
    fun voiceIndex(): Int? = when (this) {
        NUM_1 -> 0; NUM_2 -> 1; NUM_3 -> 2; NUM_4 -> 3
        NUM_5 -> 4; NUM_6 -> 5; NUM_7 -> 6; NUM_8 -> 7
        else -> null
    }

    companion object {
        private val byLabel = entries.associateBy { it.label }

        /** Map from MediaPipe native gesture recognizer names to AslSign. */
        private val byNativeGesture = mapOf(
            "Thumb_Up" to THUMBS_UP,
            "Thumb_Down" to THUMBS_DOWN,
            "Closed_Fist" to LETTER_A,
            "Open_Palm" to NUM_5,
            "Victory" to LETTER_V,
            "Pointing_Up" to NUM_1,
        )

        /** Resolve an AslSign from either our label or a MediaPipe native gesture name. */
        fun fromLabel(label: String): AslSign? = byLabel[label] ?: byNativeGesture[label]
    }
}

/** Human-readable label when this sign is used as a target selection. */
val AslSign.targetDisplayLabel: String
    get() = when (this) {
        AslSign.NUM_1 -> "V1"; AslSign.NUM_2 -> "V2"; AslSign.NUM_3 -> "V3"; AslSign.NUM_4 -> "V4"
        AslSign.NUM_5 -> "V5"; AslSign.NUM_6 -> "V6"; AslSign.NUM_7 -> "V7"; AslSign.NUM_8 -> "V8"
        AslSign.LETTER_V -> "Vibrato"
        AslSign.LETTER_C -> "Coupling"
        AslSign.LETTER_Y -> "Chaos"
        else -> label
    }

/** Human-readable label when this sign is used as a parameter selection, or null if not a parameter. */
val AslSign.paramDisplayLabel: String?
    get() = when (this) {
        AslSign.LETTER_M -> "Morph"
        AslSign.LETTER_S -> "Sharp"
        AslSign.LETTER_B -> "Bend"
        AslSign.LETTER_L -> "ModLvl"
        AslSign.LETTER_H -> "Hold"
        AslSign.LETTER_W -> "Volume"
        else -> null
    }

/** Display label for duo pair selection. */
fun AslSign.Companion.duoDisplayLabel(duoIndex: Int): String = "D${duoIndex + 1}"

/** Display label for quad group selection. */
fun AslSign.Companion.quadDisplayLabel(quadIndex: Int): String = "Q${quadIndex + 1}"

enum class AslCategory {
    NUMBER,     // Voice selection (1-8)
    MODE,       // Layer prefix (D=duo, Q=quad)
    PARAMETER,  // Parameter selection (M=morph, S=sharpness, etc.)
    SYSTEM,     // System params (V=vibrato, C=coupling, Y=chaos)
    COMMAND,    // Commands (A=deselect)
}
