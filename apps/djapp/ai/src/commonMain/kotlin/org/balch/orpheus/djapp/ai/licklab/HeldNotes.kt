package org.balch.orpheus.djapp.ai.licklab

/** The stack of held keys for a monophonic voice: overlap is legato, the newest key sounds. */
class HeldNotes {
    sealed interface Change {
        data class Strike(val midi: Int) : Change
        data class Legato(val midi: Int) : Change
        data object Release : Change
        data object None : Change
    }

    private val stack = ArrayList<Pair<Any, Int>>()

    fun press(id: Any, midi: Int): Change {
        if (stack.any { it.first == id }) return Change.None
        val first = stack.isEmpty()
        stack += id to midi
        return if (first) Change.Strike(midi) else Change.Legato(midi)
    }

    fun release(id: Any): Change {
        val i = stack.indexOfFirst { it.first == id }
        if (i < 0) return Change.None
        val wasTop = i == stack.lastIndex
        stack.removeAt(i)
        return when {
            stack.isEmpty() -> Change.Release
            wasTop -> Change.Legato(stack.last().second)
            else -> Change.None
        }
    }

    /** A held key slides to [midi] in place: legato when it is the sounding one. */
    fun move(id: Any, midi: Int): Change {
        val i = stack.indexOfFirst { it.first == id }
        if (i < 0 || stack[i].second == midi) return Change.None
        stack[i] = id to midi
        return if (i == stack.lastIndex) Change.Legato(midi) else Change.None
    }

    fun clear() = stack.clear()
}
