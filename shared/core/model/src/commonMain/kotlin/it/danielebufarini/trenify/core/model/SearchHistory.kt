package it.danielebufarini.trenify.core.model

import kotlinx.datetime.LocalDate
import kotlin.jvm.JvmInline
import kotlin.time.Instant

@JvmInline value class SearchHistoryEntryId(val value: String)

/**
 * Durable record of one explicit validated search submission.
 *
 * History is personal data independent of result caches and station recency:
 * each submission carries its own identity (never derived from a cache key),
 * the requested criteria, and a submission timestamp separate from the
 * requested travel instant. Repeats reuse [JourneySearchHistoryEntry.request]
 * to populate search input for explicit execution; they never reopen a
 * cached result and never shift an old travel date.
 */
sealed interface SearchHistoryEntry {
    val id: SearchHistoryEntryId
    val submittedAt: Instant
}

/** One validated journey search submission with its requested travel criteria. */
data class JourneySearchHistoryEntry(
    override val id: SearchHistoryEntryId,
    val origin: Station,
    val destination: Station,
    val at: Instant,
    val mode: JourneySearchMode = JourneySearchMode.DEPART_AFTER,
    override val submittedAt: Instant,
) : SearchHistoryEntry {
    /** Reusable criteria for an explicit repeat; never a cached result. */
    fun request(): JourneySearchRequest = JourneySearchRequest(origin, destination, at, mode)
}

/**
 * One validated train-number search submission.
 *
 * Only the train number is ever required. The requested service date and the
 * origin/operator discrimination are kept when the submission actually knew
 * them (for example a repeat carrying the stored discrimination); a plain
 * number-only search stores no invented route, so [originId]/[originName],
 * [operator] and [destinationName] stay null. [serviceDate] is null only for
 * rows that predate it; new submissions always carry the explicit requested
 * date. Repeats prefill the existing train-search flow through [lookupIntent]
 * and the stored date for explicit execution; they never reopen a dated run.
 *
 * [originId] and [originName] are independent discriminators mirroring
 * [TrainLookupIntent]: a stable station ID is preferred when known, while a
 * display name alone remains a valid weaker legacy discriminator. They are
 * deliberately not fused into a single [Station], which would drop valid
 * name-only states.
 *
 * Invariant: [originId] is an opaque identity discriminator, not a reference
 * requiring a canonical station row. An ID-only entry (stable ID without a
 * display name, as produced by favorites saved without one) persists and
 * round-trips as-is: persistence must never fabricate a display name or a
 * canonical station row for it, drop the ID, or weaken it into name-only
 * discrimination. The stored ID needs no matching station row.
 */
data class TrainSearchHistoryEntry(
    override val id: SearchHistoryEntryId,
    val number: TrainNumber,
    val serviceDate: LocalDate?,
    val originId: StationId?,
    val originName: String?,
    val operator: Operator?,
    val destinationName: String?,
    override val submittedAt: Instant,
) : SearchHistoryEntry {
    /** Stable criteria for a fresh lookup; never a cached [TrainRunId]. */
    fun lookupIntent(): TrainLookupIntent = TrainLookupIntent(
        number = number,
        originId = originId,
        originName = originName,
        operator = operator,
    )
}
