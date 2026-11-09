package com.naoya0117.paperless.core.model

/**
 * How Paperless assigns a label to new documents automatically. A value class rather than an enum so that
 * algorithms added by newer servers survive a read-modify-write round trip.
 */
@JvmInline
value class MatchingAlgorithm(
    val id: Int,
) {
    val isKnown: Boolean get() = id in NONE.id..AUTO.id

    companion object {
        val NONE = MatchingAlgorithm(0)
        val ANY = MatchingAlgorithm(1)
        val ALL = MatchingAlgorithm(2)
        val LITERAL = MatchingAlgorithm(3)
        val REGEX = MatchingAlgorithm(4)
        val FUZZY = MatchingAlgorithm(5)

        /** Learned from previously assigned documents. */
        val AUTO = MatchingAlgorithm(6)
    }
}

/** @property pattern words or expression for [MatchingAlgorithm.ANY] .. [MatchingAlgorithm.FUZZY]. */
data class Matching(
    val algorithm: MatchingAlgorithm,
    val pattern: String = "",
    val isInsensitive: Boolean = true,
)
