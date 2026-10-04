package com.spoolfid.spoolman

/** How a tag held to the phone relates to what Spoolman has linked. */
sealed interface TagLinkStatus {
    /** Spoolman links this tag to the same spool the tag itself names. */
    data class Linked(val spool: Spool) : TagLinkStatus

    /** Spoolman links this tag to a different spool than the tag names (or the tag names none). */
    data class Mismatch(val tagSpoolId: Int?, val linked: Spool) : TagLinkStatus

    /** The tag names a spool but Spoolman hasn't linked the tag. [spool] is null if that spool doesn't exist. */
    data class NotLinked(val tagSpoolId: Int, val spool: Spool?) : TagLinkStatus

    /** The tag names no spool and Spoolman doesn't know it. */
    data object Unknown : TagLinkStatus
}

object TagLink {
    /**
     * @param tagSpoolId the spool ID written on the tag, or null if it has none (0 and 1 mean "none" to the printer)
     * @param linked the spool Spoolman has this tag's UID linked to, if any
     * @param namedSpool the spool with the ID written on the tag, if it exists
     */
    fun classify(tagSpoolId: Int?, linked: Spool?, namedSpool: Spool?): TagLinkStatus = when {
        linked != null && linked.id == tagSpoolId -> TagLinkStatus.Linked(linked)
        linked != null -> TagLinkStatus.Mismatch(tagSpoolId, linked)
        tagSpoolId != null -> TagLinkStatus.NotLinked(tagSpoolId, namedSpool)
        else -> TagLinkStatus.Unknown
    }
}
