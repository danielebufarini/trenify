package it.danielebufarini.trenify.core.model

import kotlin.jvm.JvmInline

@JvmInline value class FavoriteTrainId(val value: String)

/**
 * A recurring train reference saved by the user.
 *
 * This is deliberately NOT a [TrainRunId]: a dated run identifies one specific
 * service on one service date for one provider, while a recurring favorite is a
 * reusable, provider-neutral reference used to start a fresh lookup. It carries
 * no service date, no provider identity and no volatile realtime data
 * (delay, platforms, status, position).
 *
 * Stable identity and display data are distinct concepts:
 * - [originId] is the provider-neutral internal [StationId] resolved when the
 *   favorite was saved. It is the primary origin discriminator and the only
 *   origin signal trusted for automatic candidate selection.
 * - [originName] and [destinationName] are presentation-only labels. They are
 *   never the sole stable identity when [originId] is available; [originName]
 *   additionally serves as a weak legacy hint for rows saved before the
 *   stable identity existed (see [TrainLookupIntent.compatibility]), but it
 *   can never prove a match on its own.
 */
data class FavoriteTrain(
    val id: FavoriteTrainId,
    val number: TrainNumber,
    val originId: StationId?,
    val operator: Operator?,
    val originName: String?,
    val destinationName: String?,
) {
    init {
        require(id == idFor(number, originId, originName, operator))
    }

    /** Stable criteria for a fresh lookup. Never a cached [TrainRunId]. */
    val lookupIntent: TrainLookupIntent get() = TrainLookupIntent(
        number = number,
        originId = originId,
        originName = originName,
        operator = operator,
    )

    companion object {
        fun create(
            number: TrainNumber,
            originId: StationId?,
            operator: Operator?,
            originName: String? = null,
            destinationName: String? = null,
        ): FavoriteTrain {
            val stableOriginId = originId?.takeIf { it.value.isNotBlank() }
            val stableOperator = operator?.takeIf { it.name.isNotBlank() }
            val displayOrigin = originName?.takeIf { it.isNotBlank() }
            val displayDestination = destinationName?.takeIf { it.isNotBlank() }
            return FavoriteTrain(
                idFor(number, stableOriginId, displayOrigin, stableOperator),
                number,
                stableOriginId,
                stableOperator,
                displayOrigin,
                displayDestination,
            )
        }

        /**
         * Ordered identity over the train number plus all known provider-neutral
         * discrimination. The origin key prefers the stable internal [StationId];
         * the normalized display name is only a fallback for rows saved before
         * the stable identity existed, so those rows keep distinct, verifiable
         * identities without fabricating a [StationId] from name coincidence.
         * Unknown discrimination collapses to a shared sentinel so duplicate
         * saves of the same unqualified reference stay idempotent; safety
         * against same-number ambiguity comes from the launch-time
         * compatibility evaluation, which never auto-selects an incompatible
         * or unverifiable candidate. Length prefixes keep the key unambiguous
         * for opaque labels.
         */
        fun idFor(
            number: TrainNumber,
            originId: StationId?,
            originName: String?,
            operator: Operator?,
        ): FavoriteTrainId {
            val origin = originId?.value?.takeIf { it.isNotBlank() }
                ?: originName?.takeIf { it.isNotBlank() }?.let(::normalizeStationQuery)
                ?: "\u0000unknown-origin"
            val operatorKey = operator?.name?.takeIf { it.isNotBlank() }?.trim()?.lowercase() ?: "\u0000unknown-operator"
            return FavoriteTrainId(
                listOf(number.value, origin, operatorKey).joinToString("") { "${it.length}:$it" },
            )
        }
    }
}

/**
 * Prefilled criteria for a fresh train lookup started from a recurring favorite.
 *
 * Minimal application-level routing/search contract: the train number to look
 * up, plus the stable discrimination needed to recognize compatible candidates.
 * [originName] is a legacy weak hint only (see [compatibility]) and is never
 * trusted as identity when [originId] is available. Carries no [TrainRunId],
 * no service date and no realtime state.
 */
data class TrainLookupIntent(
    val number: TrainNumber,
    val originId: StationId? = null,
    val originName: String? = null,
    val operator: Operator? = null,
)

/**
 * How a fresh-lookup candidate relates to the discrimination stored in a
 * recurring favorite.
 */
enum class TrainCandidateCompatibility {
    /** Every known discrimination is verified: safe for single-result navigation. */
    COMPATIBLE,

    /**
     * Nothing conflicts, but a known discrimination cannot be verified from the
     * candidate (unknown candidate operator, or only a legacy display-name
     * resemblance for the origin): requires explicit user selection.
     */
    AMBIGUOUS,

    /** A known discrimination conflicts: must never be automatically opened. */
    INCOMPATIBLE,
}

/**
 * Conservatively evaluates a fresh-lookup [candidate] against the stable
 * discrimination carried by this intent. Train number is a lookup criterion,
 * not sufficient identity: a same-number candidate with a conflicting known
 * origin or operator is [TrainCandidateCompatibility.INCOMPATIBLE], even when
 * it is the only result. Unknown favorite fields impose no constraint, but
 * missing candidate data never invents a match: a known expected operator
 * against an unknown candidate operator, and a legacy display-name resemblance
 * without a stable origin identity, are [TrainCandidateCompatibility.AMBIGUOUS].
 */
fun TrainLookupIntent.compatibility(candidate: TrainRunSummary): TrainCandidateCompatibility {
    if (candidate.id.number != number) return TrainCandidateCompatibility.INCOMPATIBLE
    val expectedOriginId = originId?.takeIf { it.value.isNotBlank() }
    if (expectedOriginId != null) {
        if (candidate.origin.id != expectedOriginId) return TrainCandidateCompatibility.INCOMPATIBLE
    } else {
        val expectedName = originName?.takeIf { it.isNotBlank() }?.let(::normalizeStationQuery)
        if (expectedName != null) {
            // Legacy rows predate the stable origin identity: a clear display
            // mismatch still blocks automatic selection, but a resemblance can
            // never prove station identity, so it stays ambiguous.
            if (normalizeStationQuery(candidate.origin.name) != expectedName) {
                return TrainCandidateCompatibility.INCOMPATIBLE
            }
            return ambiguousUnlessCompatibleOperator(candidate)
        }
    }
    return operatorCompatibility(candidate)
}

/**
 * Operator evaluation shared by both origin branches: a conflicting known
 * operator is incompatible, an unknown candidate operator against a known
 * expectation cannot prove a match, and unknown expectations impose nothing.
 */
private fun TrainLookupIntent.operatorCompatibility(candidate: TrainRunSummary): TrainCandidateCompatibility {
    val expected = operator?.takeIf { it.name.isNotBlank() }?.name?.trim()?.lowercase()
        ?: return TrainCandidateCompatibility.COMPATIBLE
    val actual = candidate.operator?.takeIf { it.name.isNotBlank() }?.name?.trim()?.lowercase()
        ?: return TrainCandidateCompatibility.AMBIGUOUS
    return if (actual == expected) TrainCandidateCompatibility.COMPATIBLE
    else TrainCandidateCompatibility.INCOMPATIBLE
}

private fun TrainLookupIntent.ambiguousUnlessCompatibleOperator(
    candidate: TrainRunSummary,
): TrainCandidateCompatibility = when (operatorCompatibility(candidate)) {
    TrainCandidateCompatibility.COMPATIBLE -> TrainCandidateCompatibility.AMBIGUOUS
    else -> operatorCompatibility(candidate)
}

/** Builds the recurring favorite for a train run without keeping any dated or volatile run state. */
fun TrainRunSummary.toFavoriteTrain(): FavoriteTrain = FavoriteTrain.create(
    number = id.number,
    originId = origin.id,
    operator = operator,
    originName = origin.name,
    destinationName = destinationName,
)
