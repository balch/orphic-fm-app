package org.balch.orpheus.features.visualizations.viz.face

/** On-screen wording. Placeholders: the user writes the real copy. */
internal object NewsCopy {
    const val bannerColour = "BREAKING NEWS"
    const val bannerMono = "STAY ASLEEP"

    const val profileColour = "POLITICS"
    const val profileMono = "OBEY"

    /** One line each, top to bottom; the last is set smaller. */
    val standBy = listOf("TECHNICAL", "DIFFICULTIES", "PLEASE STAND BY...")

    val headlines = listOf(
        "MARKETS HITS RECORD HIGH",
        "SCHOOL BOARD APPROVES BUDGET",
        "RECORD LOTTERY JACKPOT",
        "FREEDOM",
        "LOCAL BAKERY TURNS FIFTY",
        "CONGRESS PASSES BUDGET",
        "GO VOTE",
        "MILD WEEKEND AHEAD",
    )

    val commands = listOf(
        "LOWER YOUR VOICE",
        "TRUST THE BROADCAST",
        "ACCEPT YOUR FATE",
        "SHOP",
        "KEEP YOUR EYES DOWN",
        "ASK NOTHING",
        "WATCH TV",
        "SCROLL ENDLESSLY",
    )
}
