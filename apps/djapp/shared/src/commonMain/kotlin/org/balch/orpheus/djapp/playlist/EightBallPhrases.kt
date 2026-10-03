package org.balch.orpheus.djapp.playlist

import kotlin.random.Random

// Each shows on one line over the stage at 22sp, wide enough to read, and flies to the sheet's header:
// keep every phrase at most 42 characters so the line fits a phone's width.
internal val EightBallPhrases: List<String> = listOf(
    "Life's too short for just making money",
    "Play that funky music",
    "Cold pizza for breakfast",
    "You got nottin' to lose",
    "The Bell Tolls for thee",
    "Three rights make a left",
    "All you need is Love",
    "Love is all you need",
    "Get off the couch",
    "Peace, Love and Understanding",
    "Any given Sunday",
    "Can't make this up",
    "Life will find a way",
    "Catnip is the best nip",
    "Get off my cloud",
    "Throw the damn ball",
    "42",
)

/** A phrase at random, never [last] twice running while there is another to pick. */
internal fun nextPhrase(last: String?, phrases: List<String> = EightBallPhrases, random: Random = Random): String {
    val pool = phrases.filter { it != last }.ifEmpty { phrases }
    return pool[random.nextInt(pool.size)]
}
