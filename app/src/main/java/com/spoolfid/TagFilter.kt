package com.spoolfid

/** Which spools the Write list shows, by how many tags Spoolman has linked to them. */
enum class TagFilter(val label: String) {
    /** Spools that still need tags: the default, so the list shows what's left to do. */
    TO_DO("To do"),
    ALL("All"),
    UNTAGGED("Untagged"),

    /** Some tags, but fewer than needed (only meaningful when two tags per spool are required). */
    PARTIAL("Partly tagged"),

    /** All the tags needed. */
    TAGGED("Fully tagged");

    /** Whether a spool with [tagCount] tags passes, when [required] tags make a spool fully tagged. */
    fun matches(tagCount: Int, required: Int): Boolean = when (this) {
        ALL -> true
        TO_DO -> tagCount < required
        UNTAGGED -> tagCount == 0
        PARTIAL -> tagCount in 1 until required
        TAGGED -> tagCount >= required
    }
}
