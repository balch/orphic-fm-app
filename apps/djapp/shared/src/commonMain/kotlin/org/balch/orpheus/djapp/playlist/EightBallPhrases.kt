package org.balch.orpheus.djapp.playlist

import kotlin.random.Random

// Each shows in capitals on the die, about three lines of eight characters at 160dp,
// so keep every phrase at most 24 characters.
internal val EightBallPhrases: List<String> = listOf(
    "Signs point to funk",
    "Ask again after the drop",
    "Cold Pizza for breakfast",
    "You got nothing to lose",
    "The Bell Tolls for thee",
    "Three rights make a left",
    "All you need is Love",
    "42"
)

/** A phrase at random, never [last] twice running while there is another to pick. */
internal fun nextPhrase(last: String?, phrases: List<String> = EightBallPhrases, random: Random = Random): String {
    val pool = phrases.filter { it != last }.ifEmpty { phrases }
    return pool[random.nextInt(pool.size)]
}
