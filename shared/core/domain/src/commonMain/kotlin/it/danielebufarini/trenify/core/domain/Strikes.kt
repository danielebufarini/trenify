package it.danielebufarini.trenify.core.domain

import it.danielebufarini.trenify.core.model.DataFreshness
import it.danielebufarini.trenify.core.model.Strike
import it.danielebufarini.trenify.core.model.StrikeId
import it.danielebufarini.trenify.core.model.StrikeRelevance
import it.danielebufarini.trenify.core.model.StrikeStatus
import it.danielebufarini.trenify.core.model.TrainRun
import it.danielebufarini.trenify.core.model.TrainRunId
import kotlinx.coroutines.flow.Flow
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

data class StrikePolicy(
    val cacheTtl: Duration = 45.minutes,
    val historyWindow: Duration = 1.days,
    val futureWindow: Duration = 90.days,
    val foregroundRefreshInterval: Duration = 45.minutes,
    val backgroundRefreshInterval: Duration = 60.minutes,
) {
    /**
     * One logical current strike window derived from a single captured
     * [now]: callers must use this instead of reading the clock twice so a
     * ticking clock can never stretch the span past the policy duration.
     */
    fun currentWindow(now: Instant): CurrentStrikeWindow =
        CurrentStrikeWindow(now, now - historyWindow, now + futureWindow)

    /**
     * Single-read variant of [currentWindow]: exactly one [Clock.now] call
     * per logical refresh operation.
     */
    fun currentWindow(clock: Clock): CurrentStrikeWindow = currentWindow(clock.now())

    /**
     * Documented logical refresh unit: the freshness quantum in which [now]
     * falls. Concurrent current-window refreshes captured inside the same
     * [cacheTtl] quantum represent the same logical current refresh and may
     * share one provider fetch; refreshes from different quanta, and any
     * explicit custom range, never share on timing grounds.
     */
    fun refreshBucket(now: Instant): Instant {
        val quantum = cacheTtl.inWholeMilliseconds
        require(quantum > 0)
        return Instant.fromEpochMilliseconds((now.toEpochMilliseconds() / quantum) * quantum)
    }
}

/**
 * One logical current strike refresh window: the captured [now] it was
 * derived from plus the resulting [from]/[to] bounds. The captured instant
 * identifies the logical refresh; the bounds drive the fetch and observation.
 */
data class CurrentStrikeWindow(
    val now: Instant,
    val from: Instant,
    val to: Instant,
) {
    init {
        require(to > from)
    }
}

enum class StrikeImpact {
    NONE,
    POTENTIAL,
    LIKELY,
    CONFIRMED_BY_OPERATOR,
}

/**
 * Verified official operator evidence applicable to one train/strike pair
 * (T7.12 impact semantics).
 *
 * HTTPS alone is never evidence: [verified] must be set only from actual
 * verified official evidence (for example a checked operator confirmation
 * source applicable to that train and strike), and [officialSourceUrl] must
 * pass the dedicated strike/operator-information policy. No confirmation
 * source exists in the repository today, so production never constructs a
 * verified instance; the flag exists so a future evidenced source can do so
 * without reshaping the contract, and so tests can pin both sides.
 */
data class OperatorStrikeConfirmation(
    val strikeId: StrikeId,
    val trainRunId: TrainRunId,
    val officialSourceUrl: String,
    val verified: Boolean = false,
)

class EvaluateStrikeImpact(
    private val serviceImpact: EvaluateServiceStrikeImpact = EvaluateServiceStrikeImpact(),
) {
    operator fun invoke(
        strike: Strike,
        train: TrainRun,
        confirmation: OperatorStrikeConfirmation? = null,
        freshness: DataFreshness = DataFreshness.Unknown,
    ): StrikeImpact = serviceImpact(train.strikeContext(), strike, freshness, confirmation).impact
}

enum class StrikeChangeKind {
    SCHEDULED,
    MODIFIED,
    REVOKED,
}

data class StrikeChangeEvent(
    val strike: Strike,
    val kind: StrikeChangeKind,
) {
    val fingerprint: String = "${strike.id.value}:$kind:${strike.contentFingerprint}"
}

class EvaluateStrikeNotification {
    operator fun invoke(previous: Strike?, current: Strike, now: Instant): StrikeChangeEvent? {
        if (!current.isRailwayRelevant() || current.end <= now || current.status == StrikeStatus.COMPLETED) return null
        if (previous == null) {
            return if (current.status == StrikeStatus.REVOKED) null
            else StrikeChangeEvent(current, StrikeChangeKind.SCHEDULED)
        }
        if (previous.status == current.status && previous.contentFingerprint == current.contentFingerprint) return null
        return StrikeChangeEvent(
            current,
            if (current.status == StrikeStatus.REVOKED) StrikeChangeKind.REVOKED else StrikeChangeKind.MODIFIED,
        )
    }
}

fun Strike.isRailwayRelevant(): Boolean {
    val text = buildString {
        append(sector)
        append(' ')
        append(mode)
        append(' ')
        append(workforce.orEmpty())
        append(' ')
        append(operators.joinToString(" ") { it.name })
    }.normalized()
    return RAILWAY_MARKERS.any(text::contains)
}

/**
 * Interval successfully covered by a complete strike refresh (T7.12-A).
 *
 * Only a successfully complete applicable snapshot establishes coverage:
 * freshness for [from]..[to] is freshness for that requested interval, never
 * evidence about a disjoint interval. Partial snapshots, failures and
 * uncovered intervals carry null coverage here and surface stale/unknown
 * freshness instead.
 */
data class StrikeIntervalCoverage(
    val from: Instant,
    val to: Instant,
    val fetchedAt: Instant,
) {
    init {
        require(to > from)
    }
}

data class StrikeRefresh(
    val strikes: List<Strike>,
    val changes: List<StrikeChangeEvent> = emptyList(),
    val coverage: StrikeIntervalCoverage? = null,
)

interface StrikeRepository {
    fun observeStrikes(from: Instant, to: Instant, includeRevoked: Boolean = true): Flow<DataResult<List<Strike>>>
    suspend fun refresh(from: Instant, to: Instant, force: Boolean = false): DataResult<StrikeRefresh>

    /**
     * Current-window variants: [window] carries the single captured instant
     * the refresh was derived from, so concurrent logical current refreshes
     * share one flight while explicit custom ranges keep exact keys.
     */
    fun observeStrikes(window: CurrentStrikeWindow, includeRevoked: Boolean = true): Flow<DataResult<List<Strike>>>
    suspend fun refresh(window: CurrentStrikeWindow, force: Boolean = false): DataResult<StrikeRefresh>
}

interface StrikeNotificationRepository {
    fun observeNotificationsEnabled(): Flow<Boolean>
    suspend fun setNotificationsEnabled(enabled: Boolean)
    suspend fun pendingNotifications(): List<StrikeChangeEvent>
    suspend fun claimNotification(event: StrikeChangeEvent, emittedAt: Instant): Boolean
}

class LoadStrikes(private val repository: StrikeRepository) {
    fun observe(from: Instant, to: Instant, includeRevoked: Boolean = true) =
        repository.observeStrikes(from, to, includeRevoked)

    suspend operator fun invoke(from: Instant, to: Instant, force: Boolean = false) =
        repository.refresh(from, to, force)

    fun observe(window: CurrentStrikeWindow, includeRevoked: Boolean = true) =
        repository.observeStrikes(window, includeRevoked)

    suspend operator fun invoke(window: CurrentStrikeWindow, force: Boolean = false) =
        repository.refresh(window, force)
}

class ObserveStrikeNotifications(private val repository: StrikeNotificationRepository) {
    operator fun invoke() = repository.observeNotificationsEnabled()
}

class SetStrikeNotifications(private val repository: StrikeNotificationRepository) {
    suspend operator fun invoke(enabled: Boolean) = repository.setNotificationsEnabled(enabled)
}

private fun String.normalized(): String = trim().lowercase().replace(Regex("\\s+"), " ")

private val RAILWAY_MARKERS = listOf(
    "ferrovi",
    "treno",
    "trenitalia",
    "trenord",
    "italo",
    "rfi",
    "gruppo fs",
    "captrain",
    "db cargo",
)
