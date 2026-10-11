package com.spoolfid

/**
 * Where "back" goes: the places visited before the current one, most recent last. Moving somewhere new pushes the
 * place being left; back pops it. Going to where you already are adds nothing, and the oldest places are dropped
 * once the list is long.
 */
class NavHistory<T>(private val limit: Int = 20) {
    private val stack = ArrayDeque<T>()

    val size: Int get() = stack.size

    /** Remember [place] as somewhere back can return to. */
    fun push(place: T) {
        if (stack.lastOrNull() == place) return
        stack.addLast(place)
        while (stack.size > limit) stack.removeFirst()
    }

    /** The place to go back to, or null when there is nowhere left. */
    fun pop(): T? = stack.removeLastOrNull()
}
