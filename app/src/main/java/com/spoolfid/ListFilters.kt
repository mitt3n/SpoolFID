package com.spoolfid

import com.spoolfid.spoolman.Spool

/** A spool counts as running low below this share of its full weight. */
const val LOW_FRACTION = 0.2

/**
 * Filters for the Write list that combine with the tag filter: only the spools that will print wrong, only the ones
 * running low, and a material or location.
 */
data class ExtraFilters(
    val attention: Boolean = false,
    val low: Boolean = false,
    val material: String? = null,
    val location: String? = null,
) {
    val active: Boolean get() = attention || low || material != null || location != null

    /** [problems] are what [MaterialCatalog.problems] found for the spool. */
    fun matches(spool: Spool, problems: List<String>): Boolean =
        (!attention || problems.isNotEmpty()) &&
            (!low || spool.isLow) &&
            (material == null || spool.material.equals(material, ignoreCase = true)) &&
            (location == null || spool.location.orEmpty().trim().equals(location, ignoreCase = true))
}

val Spool.isLow: Boolean get() = fractionLeft?.let { it < LOW_FRACTION } == true

/** One thing the tag audit found wrong with a spool's tags. */
data class AuditItem(val spool: Spool, val issue: String)

object TagAudit {
    /**
     * What's off with the tags, per spool: missing tags, tags from version 1.0 that aren't linked, tags still on a
     * spool that is archived, and tags on spool #1 (the printer ignores that ID, so the tags can never work).
     */
    fun check(spools: List<Spool>, required: Int): List<AuditItem> = spools.sortedBy { it.id }.flatMap { s ->
        buildList {
            val n = s.tagCount
            when {
                s.archived -> if (n > 0) add(AuditItem(s, "Archived but still holds $n tag${plural(n)}"))
                s.unlinkedCount > 0 -> add(AuditItem(s, "$n tag${plural(n)} from version 1.0 not linked in Spoolman"))
                n < required -> add(AuditItem(s, "Has $n of $required tags"))
            }
            if (!s.archived && s.id <= 1 && n > 0) add(AuditItem(s, "Tagged as spool #${s.id}, which the printer ignores"))
        }
    }

    private fun plural(n: Int) = if (n == 1) "" else "s"
}
