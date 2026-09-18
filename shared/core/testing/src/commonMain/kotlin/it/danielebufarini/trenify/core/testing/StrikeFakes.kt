package it.danielebufarini.trenify.core.testing

import it.danielebufarini.trenify.core.domain.CurrentStrikeWindow
import it.danielebufarini.trenify.core.domain.DataResult
import it.danielebufarini.trenify.core.domain.StrikeChangeEvent
import it.danielebufarini.trenify.core.domain.StrikeNotificationRepository
import it.danielebufarini.trenify.core.domain.StrikeRefresh
import it.danielebufarini.trenify.core.domain.StrikeRepository
import it.danielebufarini.trenify.core.model.DataFreshness
import it.danielebufarini.trenify.core.model.Operator
import it.danielebufarini.trenify.core.model.ProviderId
import it.danielebufarini.trenify.core.model.Strike
import it.danielebufarini.trenify.core.model.StrikeGeography
import it.danielebufarini.trenify.core.model.StrikeId
import it.danielebufarini.trenify.core.model.StrikeRelevance
import it.danielebufarini.trenify.core.model.StrikeSource
import it.danielebufarini.trenify.core.model.StrikeStatus
import it.danielebufarini.trenify.core.model.strikeIntervalsOverlap
import it.danielebufarini.trenify.core.provider.api.ProviderMetadata
import it.danielebufarini.trenify.core.provider.api.ProviderResult
import it.danielebufarini.trenify.core.provider.api.ProviderStrike
import it.danielebufarini.trenify.core.provider.api.StrikeProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.time.Clock
import kotlin.time.Instant

val testStrike = Strike(
    id = StrikeId("mit-strikes:8479"),
    externalId = "8479",
    start = Instant.parse("2026-09-07T19:18:00Z"),
    end = Instant.parse("2026-09-08T19:00:00Z"),
    sector = "Ferroviario",
    unions = listOf("ORSA Ferrovie"),
    workforce = "Personale Trenitalia",
    operators = listOf(Operator("Trenitalia")),
    geography = StrikeGeography(StrikeRelevance.REGIONAL, regions = listOf("Piemonte")),
    mode = "24 ore: dalle 21.18 alle 21.00",
    status = StrikeStatus.SCHEDULED,
    notes = null,
    source = StrikeSource(ProviderId("mit-strikes"), "MIT", "https://scioperi.mit.gov.it/8479"),
    sourceUpdatedAt = null,
    contentFingerprint = "fixture-fingerprint",
)

val testProviderStrike = ProviderStrike(
    externalId = testStrike.externalId,
    start = testStrike.start,
    end = testStrike.end,
    sector = testStrike.sector,
    unions = testStrike.unions,
    workforce = testStrike.workforce,
    operators = testStrike.operators,
    relevance = testStrike.geography.relevance,
    regions = testStrike.geography.regions,
    provinces = testStrike.geography.provinces,
    mode = testStrike.mode,
    status = testStrike.status,
    notes = testStrike.notes,
    sourceUrl = testStrike.source.url,
)

class FakeStrikeProvider(private val clock: Clock = MutableClock()) : StrikeProvider {
    override val id = ProviderId("mit-strikes")
    override var suppliesCompleteSnapshots = true
    var values = listOf(testProviderStrike)
    var result: ProviderResult<List<ProviderStrike>>? = null
    /** Per-response snapshot completeness (T7.12-A): false simulates a partially parsed RSS snapshot. */
    var complete = true
    var calls = 0
    val requestedIntervals = mutableListOf<Pair<Instant, Instant>>()

    override suspend fun getStrikes(from: Instant, to: Instant, includeRevoked: Boolean): ProviderResult<List<ProviderStrike>> {
        calls++
        requestedIntervals += from to to
        return result ?: ProviderResult.Success(
            values.filter {
                strikeIntervalsOverlap(it.start, it.end, from, to) &&
                    (includeRevoked || it.status != StrikeStatus.REVOKED)
            },
            ProviderMetadata(id, clock.now()),
            complete,
        )
    }
}

class FakeStrikeRepository : StrikeRepository, StrikeNotificationRepository {
    val state = MutableStateFlow<DataResult<List<Strike>>>(DataResult.Data(listOf(testStrike), DataFreshness.Unknown))
    val notificationsEnabled = MutableStateFlow(false)
    var refreshResult: DataResult<StrikeRefresh> = DataResult.Data(StrikeRefresh(listOf(testStrike)), DataFreshness.Unknown)
    var refreshes = 0
    val refreshWindows = mutableListOf<Pair<Instant, Instant>>()
    val pending = mutableListOf<StrikeChangeEvent>()
    val claimed = mutableListOf<StrikeChangeEvent>()

    override fun observeStrikes(from: Instant, to: Instant, includeRevoked: Boolean): Flow<DataResult<List<Strike>>> = state

    override suspend fun refresh(from: Instant, to: Instant, force: Boolean): DataResult<StrikeRefresh> {
        refreshes++
        refreshWindows += from to to
        return refreshResult
    }

    override fun observeStrikes(window: CurrentStrikeWindow, includeRevoked: Boolean): Flow<DataResult<List<Strike>>> =
        observeStrikes(window.from, window.to, includeRevoked)

    override suspend fun refresh(window: CurrentStrikeWindow, force: Boolean): DataResult<StrikeRefresh> =
        refresh(window.from, window.to, force)

    override fun observeNotificationsEnabled(): Flow<Boolean> = notificationsEnabled

    override suspend fun setNotificationsEnabled(enabled: Boolean) {
        notificationsEnabled.value = enabled
        if (!enabled) pending.clear()
    }

    override suspend fun pendingNotifications(): List<StrikeChangeEvent> = pending.toList()

    override suspend fun claimNotification(event: StrikeChangeEvent, emittedAt: Instant): Boolean =
        if (event in pending && event !in claimed) {
            claimed += event
            true
        } else {
            false
        }
}
