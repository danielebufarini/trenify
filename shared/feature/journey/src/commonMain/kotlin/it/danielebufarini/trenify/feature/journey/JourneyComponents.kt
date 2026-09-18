package it.danielebufarini.trenify.feature.journey

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import it.danielebufarini.trenify.core.domain.*
import it.danielebufarini.trenify.core.network.RequestInstrumentation
import it.danielebufarini.trenify.core.network.span
import it.danielebufarini.trenify.core.model.*
import it.danielebufarini.trenify.core.ui.*
import kotlinx.coroutines.*
import kotlinx.datetime.*
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.Instant

data class JourneySearchState(
    val originText: String = "", val destinationText: String = "",
    val origin: Station? = null, val destination: Station? = null,
    val suggestions: List<Station> = emptyList(), val editingOrigin: Boolean = true,
    /**
     * Typed Rome-local search truth (T7.14 corrective): the requested
     * service date and hour/minute. Presentation formats these per the
     * platform locale; a localized display string is never domain truth.
     */
    val date: LocalDate,
    val timeHour: Int,
    val timeMinute: Int,
    /** Free-text editor buffers, locale-formatted from the typed truth. */
    val dateText: String,
    val timeText: String,
    val mode: JourneySearchMode = JourneySearchMode.DEPART_AFTER,
    val loading: Boolean = false, val failure: DomainFailure? = null, val invalid: Boolean = false,
) {
    val failed: Boolean get() = failure != null
    /** The semantic Rome date/time currently represented, whatever the locale. */
    val localDateTime: LocalDateTime get() = LocalDateTime(date, LocalTime(timeHour, timeMinute))
}

/**
 * Durable journey-search input (T7.13-B): the text, resolved endpoints,
 * requested Rome date/time and mode required to continue the flow after
 * save/destroy/recreate. Suggestions, loading flags and transient errors
 * are never persisted; they are re-derived.
 */
private const val KEY_SAVED_INPUT = "journey-search-input"

@Serializable
private data class SavedJourneySearchInput(
    val originText: String = "",
    val destinationText: String = "",
    val origin: SerializableStationRef? = null,
    val destination: SerializableStationRef? = null,
    val editingOrigin: Boolean = true,
    val date: String = "",
    val time: String = "",
    val mode: String = "DEPART_AFTER",
)
class JourneySearchComponent(
    componentContext: ComponentContext,
    private val repository: JourneyRepository?,
    private val onSearch: (JourneySearchRequest) -> Unit,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    clock: Clock = Clock.System,
    favoritesRepository: FavoritesRepository? = null,
    initialIntent: JourneySearchIntent? = null,
    private val historyRepository: HistoryRepository? = null,
    initialRequest: JourneySearchRequest? = null,
    private val onHistory: () -> Unit = {},
    localeTag: String = platformLocaleTag(),
    private val instrumentation: RequestInstrumentation = RequestInstrumentation.None,
) : ComponentContext by componentContext {
    private val scope = componentScope(lifecycle, dispatcher)
    private var locale: String = localeTag
    // A history repeat restores the originally requested Rome date/time as-is,
    // even when it lies in the past, so it stays visible and editable instead
    // of being silently advanced to the current time.
    private val initialLocal = (initialRequest?.at ?: clock.now()).toLocalDateTime(RailwayTime.zone)
    private val mutable = MutableValue(JourneySearchState(
        originText = initialRequest?.origin?.name ?: initialIntent?.origin?.name.orEmpty(),
        destinationText = initialRequest?.destination?.name ?: initialIntent?.destination?.name.orEmpty(),
        origin = initialRequest?.origin ?: initialIntent?.origin,
        destination = initialRequest?.destination ?: initialIntent?.destination,
        date = initialLocal.date,
        timeHour = initialLocal.hour,
        timeMinute = initialLocal.minute,
        dateText = formatSearchDate(initialLocal.date, locale),
        timeText = formatSearchTime(initialLocal.hour, initialLocal.minute, locale),
        mode = initialRequest?.mode ?: JourneySearchMode.DEPART_AFTER,
    ))
    private val routeFavorite = favoritesRepository?.let { favorites ->
        FavoriteRouteController(componentContext, favorites, ::selectedRoute, dispatcher)
    }
    val state: Value<JourneySearchState> = mutable
    val favoriteRouteState: Value<RouteFavoriteState>? = routeFavorite?.state
    private var lookup: Job? = null

    init {
        stateKeeper.consume(KEY_SAVED_INPUT, SavedJourneySearchInput.serializer())?.let { saved ->
            val current = mutable.value
            // Durable truth is the semantic Rome date/time in ISO form (never
            // a rendered locale string); buffers reformat for the current locale.
            val restoredDate = saved.date.ifBlank { null }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                ?: current.date
            val restoredTime = saved.time.ifBlank { null }?.let { parseSearchTime(it, "en") }
                ?: (current.timeHour to current.timeMinute)
            mutable.value = current.copy(
                originText = saved.originText,
                destinationText = saved.destinationText,
                origin = saved.origin?.station(),
                destination = saved.destination?.station(),
                editingOrigin = saved.editingOrigin,
                date = restoredDate,
                timeHour = restoredTime.first,
                timeMinute = restoredTime.second,
                dateText = formatSearchDate(restoredDate, locale),
                timeText = formatSearchTime(restoredTime.first, restoredTime.second, locale),
                mode = runCatching { JourneySearchMode.valueOf(saved.mode) }.getOrNull() ?: current.mode,
            )
            routeFavorite?.routeChanged()
        }
        stateKeeper.register(KEY_SAVED_INPUT, SavedJourneySearchInput.serializer()) {
            val current = mutable.value
            SavedJourneySearchInput(
                originText = current.originText,
                destinationText = current.destinationText,
                origin = current.origin?.serialized(),
                destination = current.destination?.serialized(),
                editingOrigin = current.editingOrigin,
                date = current.date.toString(),
                time = "${current.timeHour.toString().padStart(2, '0')}:${current.timeMinute.toString().padStart(2, '0')}",
                mode = current.mode.name,
            )
        }
    }

    fun stationText(origin: Boolean, value: String) {
        lookup?.cancel()
        mutable.value = (if (origin) mutable.value.copy(originText = value, origin = null)
            else mutable.value.copy(destinationText = value, destination = null))
            .copy(editingOrigin = origin, suggestions = emptyList(), failure = null, invalid = false, loading = false)
        routeFavorite?.routeChanged()
        if (value.trim().length < 2) return
        lookup = scope.launch {
            delay(275)
            mutable.value = mutable.value.copy(loading = true)
            val result = repository?.searchStations(value) ?: DataResult.Failure(DomainFailure.UNSUPPORTED)
            currentCoroutineContext().ensureActive()
            mutable.value = when (result) {
                is DataResult.Data -> mutable.value.copy(suggestions = result.value, loading = false, failure = result.warning)
                is DataResult.Failure -> mutable.value.copy(loading = false, failure = result.error)
            }
        }
    }
    fun select(station: Station) {
        if (station !in mutable.value.suggestions) return
        lookup?.cancel()
        mutable.value = (if (mutable.value.editingOrigin) mutable.value.copy(origin = station, originText = station.name)
            else mutable.value.copy(destination = station, destinationText = station.name))
            .copy(suggestions = emptyList(), loading = false, invalid = false)
        routeFavorite?.routeChanged()
    }
    /**
     * Free-text date edit: the buffer always updates; the typed truth
     * advances only when the text parses in the current locale (ISO
     * accepted everywhere). An unparseable buffer fails at search, never
     * silently.
     */
    fun date(value: String) {
        val current = mutable.value
        val parsed = parseSearchDate(value, locale)
        mutable.value = if (parsed != null) {
            current.copy(date = parsed, dateText = value, invalid = false)
        } else {
            current.copy(dateText = value, invalid = false)
        }
    }

    /** Free-text time edit, same buffer/truth contract as [date]. */
    fun time(value: String) {
        val current = mutable.value
        val parsed = parseSearchTime(value, locale)
        mutable.value = if (parsed != null) {
            current.copy(timeHour = parsed.first, timeMinute = parsed.second, timeText = value, invalid = false)
        } else {
            current.copy(timeText = value, invalid = false)
        }
    }

    /**
     * Device-locale change: re-presents the same semantic Rome date/time in
     * the new locale. The represented instant never moves.
     */
    fun relocale(tag: String) {
        if (tag == locale) return
        locale = tag
        val current = mutable.value
        mutable.value = current.copy(
            dateText = formatSearchDate(current.date, tag),
            timeText = formatSearchTime(current.timeHour, current.timeMinute, tag),
        )
    }

    fun mode(value: JourneySearchMode) { mutable.value = mutable.value.copy(mode = value) }

    /**
     * Atomic origin/destination swap for the T8.5 trip composer. Exchanges the
     * resolved endpoints and their display texts as one composed trip input,
     * preserving typed Rome date/time, mode and history recording rules.
     * It never triggers a search, never records history and never calls a
     * provider: suggestion lookup is cancelled and stale suggestions/loading
     * state is cleared, like an endpoint edit. Favorite-route state refreshes
     * through the existing controller.
     */
    fun swap() {
        lookup?.cancel()
        val current = mutable.value
        mutable.value = current.copy(
            origin = current.destination,
            destination = current.origin,
            originText = current.destinationText,
            destinationText = current.originText,
            suggestions = emptyList(),
            loading = false,
            failure = null,
            invalid = false,
        )
        routeFavorite?.routeChanged()
    }
    fun toggleFavoriteRoute() { routeFavorite?.toggle() }
    fun history() { onHistory() }
    fun search() {
        val state = mutable.value
        val request = runCatching {
            // Buffers re-parse here so a stale typed value can never mask an
            // unparseable field: the search instant always comes from the
            // visible text. The Rome round-trip rejects the spring-forward
            // gap; the fall-back fold resolves deterministically through
            // kotlinx-datetime like every other railway instant.
            val date = parseSearchDate(state.dateText, locale) ?: return@runCatching null
            val (hour, minute) = parseSearchTime(state.timeText, locale) ?: return@runCatching null
            val local = LocalDateTime(date, LocalTime(hour, minute))
            val at = local.toInstant(RailwayTime.zone)
            require(at.toLocalDateTime(RailwayTime.zone) == local)
            JourneySearchRequest(requireNotNull(state.origin), requireNotNull(state.destination), at, state.mode)
        }.getOrNull()
        if (request == null || repository == null || !SearchJourneys(repository).valid(request)) {
            mutable.value = state.copy(invalid = true)
        } else {
            // Exactly one durable entry per validated submission, whatever the
            // later search outcome is. Refreshes, observations, retries and
            // keystrokes never reach this path, so they are never recorded.
            historyRepository?.let { history ->
                scope.launch {
                    try {
                        history.recordSearch(request)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        // History is best-effort personal data: a persistence
                        // failure must never block the validated journey search.
                    }
                }
            }
            instrumentation.event("journey.submit")
            onSearch(request)
        }
    }

    private fun selectedRoute(): FavoriteRoute? {
        return favoriteRoute(mutable.value.origin, mutable.value.destination)
    }
}

private const val KEY_SAVED_SORT = "journey-results-sort"

/** Durable results sort (T7.13-B); results themselves re-observe the repository. */
@Serializable
private data class SavedJourneySort(val sort: String = "DEPARTURE") {
    fun sortValue(): JourneySort? = runCatching { JourneySort.valueOf(sort) }.getOrNull()
}

data class JourneyResultsState(
    val results: RealtimeState<JourneySearchResult> = RealtimeState(),
    val sort: JourneySort = JourneySort.DEPARTURE,
    /**
     * Strike warnings per journey from scheduled leg intervals (T7.12-B):
     * no realtime correlation is required. Only journeys with at least one
     * overlapping strike appear here.
     */
    val strikeWarnings: Map<Journey, List<ServiceStrikeWarning>> = emptyMap(),
    /** Backing strike data is stale authoritative coverage. */
    val strikesStale: Boolean = false,
    /**
     * No authoritative coverage exists for the requested interval (T7.12
     * corrective pass): never rendered as merely outdated.
     */
    val strikesUnknown: Boolean = false,
    /** The targeted strike refresh failed; cached warnings are retained. */
    val strikesFailed: Boolean = false,
) {
    val journeys: List<Journey> get() = results.data?.journeys.orEmpty().sortedBy(sort)
}
class JourneyResultsComponent(
    componentContext: ComponentContext,
    val request: JourneySearchRequest,
    private val search: SearchJourneys?,
    private val onJourney: (Journey) -> Unit,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val strikes: LoadStrikes? = null,
    private val policy: JourneyPolicy = JourneyPolicy(),
    private val clock: Clock = Clock.System,
    private val strikePolicy: StrikePolicy = StrikePolicy(),
    private val instrumentation: RequestInstrumentation = RequestInstrumentation.None,
) : ComponentContext by componentContext {
    private val scope = componentScope(lifecycle, dispatcher)
    private val freshnessWatcher = FreshnessWatcher(lifecycle, scope, clock)
    private val mutable = MutableValue(JourneyResultsState(
        sort = stateKeeper.consume(KEY_SAVED_SORT, SavedJourneySort.serializer())?.sortValue() ?: JourneySort.DEPARTURE,
    ))
    val state: Value<JourneyResultsState> = mutable
    private var refreshJob: Job? = null
    private var presented = false

    init {
        stateKeeper.register(KEY_SAVED_SORT, SavedJourneySort.serializer()) {
            SavedJourneySort(sort = mutable.value.sort.name)
        }
        // Elapsed-time aging (T7.14-B, union deadline): held results become
        // Stale at the journey TTL and strike coverage becomes Stale at the
        // strike cache TTL, both with zero provider calls; resume recomputes.
        // The strike repository's local expiry ticker tracks strike end
        // times, never coverage freshness, so this watcher owns the strike
        // staleness boundary too.
        freshnessWatcher.observe(
            nextDeadline = { resultsStrikeDeadline() },
            onTick = { ageResultsAndStrikes() },
        )
    }
    /**
     * Strike window for these results (T7.12-B): departures lie inside the
     * requested window while arrivals may extend one horizon beyond it. One
     * component-level observation plus one targeted stale refresh — never a
     * lookup per journey/result/card.
     */
    private val strikeFrom = request.departureFrom
    private val strikeTo = request.departureUntil + JourneySearchRequest.horizon
    private val strikeEvaluator = EvaluateServiceStrikeImpact()
    private var latestStrikes: List<Strike> = emptyList()
    private var latestStrikeFreshness: DataFreshness = DataFreshness.Unknown
    init {
        if (search != null) scope.launch {
            search.observe(request).collect { result ->
                mutable.value = mutable.value.copy(results = when (result) {
                    is DataResult.Data -> RealtimeState(result.value, mutable.value.results.loading, result.warning,
                        result.freshness !is DataFreshness.Fresh, result.freshness)
                    is DataResult.Failure -> mutable.value.results.copy(failure = result.error.takeUnless { mutable.value.results.loading })
                })
                if (result is DataResult.Data && !presented) {
                    presented = true
                    instrumentation.event("journey.submit_to_first_presentable_result", mapOf(
                        "partial" to result.value.partial.toString(),
                    ))
                }
                freshnessWatcher.poke()
                refreshStrikeWarnings()
            }
        }
        if (strikes != null) scope.launch {
            strikes.observe(strikeFrom, strikeTo).collect { result ->
                when (result) {
                    is DataResult.Data -> {
                        latestStrikes = result.value
                        latestStrikeFreshness = result.freshness
                        mutable.value = mutable.value.copy(
                            strikeWarnings = journeyWarnings(mutable.value.journeys, result.value, result.freshness),
                            strikesStale = result.freshness is DataFreshness.Stale,
                            strikesUnknown = result.freshness is DataFreshness.Unknown,
                            strikesFailed = result.warning != null,
                        )
                    }
                    is DataResult.Failure -> {
                        mutable.value = mutable.value.copy(
                            strikesStale = false,
                            strikesUnknown = true,
                            strikesFailed = true,
                        )
                    }
                }
                // New strike truth restarts the union boundary tracking.
                freshnessWatcher.poke()
            }
        }
        refresh(false)
    }
    fun sort(value: JourneySort) { mutable.value = mutable.value.copy(sort = value) }
    fun refresh(force: Boolean = true) {
        if (refreshJob?.isActive == true) return
        mutable.value = mutable.value.copy(results = mutable.value.results.copy(loading = true, failure = null))
        refreshJob = scope.launch {
            val result = search?.invoke(request, force) ?: DataResult.Failure(DomainFailure.UNSUPPORTED)
            currentCoroutineContext().ensureActive()
            mutable.value = mutable.value.copy(results = mutable.value.results.copy(loading = false,
                failure = (result as? DataResult.Failure)?.error ?: (result as? DataResult.Data)?.warning))
            if (result is DataResult.Data) {
                instrumentation.event("journey.submit_to_complete_result", mapOf(
                    "partial" to result.value.partial.toString(),
                ))
            }
            freshnessWatcher.poke()
        }
        refreshStrikes()
    }

    /**
     * One targeted stale refresh for the results window: uncovered intervals
     * fetch, covered-fresh intervals reuse the repository single flight, and
     * failures keep cached warnings with a truthful stale flag.
     */
    fun refreshStrikes() {
        val load = strikes ?: return
        scope.launch {
            load(strikeFrom, strikeTo)
            currentCoroutineContext().ensureActive()
            refreshStrikeWarnings()
        }
    }

    private fun refreshStrikeWarnings() {
        if (strikes == null) return
        mutable.value = mutable.value.copy(
            strikeWarnings = journeyWarnings(mutable.value.journeys, latestStrikes, latestStrikeFreshness),
        )
    }

    /**
     * Earliest freshness boundary across journey results and strike
     * coverage (T7.14 corrective): a single component-level deadline, never
     * one timer per journey/card and never a provider poll.
     */
    private fun resultsStrikeDeadline(): Instant? = listOfNotNull(
        mutable.value.results.freshness?.staleTransitionAt(policy.ttl),
        latestStrikeFreshness.staleTransitionAt(strikePolicy.cacheTtl),
    ).minOrNull()

    /**
     * Local tick (T7.14 corrective): re-derives results and strike-coverage
     * freshness from the injected clock with zero provider calls, then
     * re-evaluates warning presentation against the aged strike freshness
     * (a stale backing can only downgrade operator confirmation, never
     * change strike identity/interval/status). Repository truth is
     * untouched: the next emission overwrites this display copy.
     */
    private fun ageResultsAndStrikes() {
        val now = clock.now()
        val aged = mutable.value.results.agedDisplay(now, policy.ttl)
        if (aged !== mutable.value.results) mutable.value = mutable.value.copy(results = aged)
        val agedStrikes = latestStrikeFreshness.aged(now, strikePolicy.cacheTtl)
        if (agedStrikes != latestStrikeFreshness) {
            latestStrikeFreshness = agedStrikes
            mutable.value = mutable.value.copy(
                strikeWarnings = journeyWarnings(mutable.value.journeys, latestStrikes, agedStrikes),
                strikesStale = agedStrikes is DataFreshness.Stale,
                strikesUnknown = agedStrikes is DataFreshness.Unknown,
            )
        }
    }

    private fun journeyWarnings(
        journeys: List<Journey>,
        strikes: List<Strike>,
        freshness: DataFreshness,
    ): Map<Journey, List<ServiceStrikeWarning>> {
        if (strikes.isEmpty()) return emptyMap()
        return journeys.associateWith { journey ->
            journey.legs.flatMap { leg ->
                strikeEvaluator.warnings(leg.strikeContext(), strikes, freshness)
            }.distinctBy { it.strike.id }
        }.filterValues { it.isNotEmpty() }
    }
    fun select(journey: Journey) {
        if (journey in mutable.value.journeys) {
            instrumentation.event("journey.detail.selection")
            onJourney(journey)
        }
    }
}

data class JourneyDetailState(
    /**
     * Repository-observed journey presented as live truth. Set immediately
     * on the live path; populated by re-resolution on the restore path and
     * never from a deserialized snapshot.
     */
    val resolved: Journey? = null,
    /**
     * Provenance of the resolved journey (T7.14-B): the search-result
     * freshness on the restore path, Unknown on the live path where no fetch
     * timestamp is genuinely known. Aged by elapsed local time.
     */
    val journeyFreshness: DataFreshness = DataFreshness.Unknown,
    /** True while a restored detail is re-resolving through the repository. */
    val resolving: Boolean = false,
    /**
     * Controlled recovery: the restored target is expired, deleted,
     * malformed or no longer resolvable (including ambiguous
     * same-looking services). The UI shows not-found with Back; nothing
     * similar-looking is ever substituted.
     */
    val notFound: Boolean = false,
    val trainRuns: Map<Int, TrainRunId> = emptyMap(),
    val correlating: Boolean = false,
    val bookingAvailable: Boolean = false,
    val bookingInProgress: Boolean = false,
    val bookingFailed: Boolean = false,
    /**
     * Strike warnings per leg index from scheduled leg intervals (T7.12-B):
     * no realtime correlation is required before a warning can display.
     */
    val strikeWarnings: Map<Int, List<ServiceStrikeWarning>> = emptyMap(),
    val strikesStale: Boolean = false,
    /**
     * No authoritative coverage exists for the requested interval (T7.12
     * corrective pass): never rendered as merely outdated.
     */
    val strikesUnknown: Boolean = false,
    val strikesFailed: Boolean = false,
    /**
     * Available realtime enrichment per leg index (T7.12-D): already
     * correlated/cached snapshots observed read-only. Never a realtime
     * lookup per leg: observation only, zero refresh calls.
     */
    val correlatedRuns: Map<Int, DataResult<TrainRun>> = emptyMap(),
)
class JourneyDetailComponent(
    componentContext: ComponentContext,
    journey: Journey?,
    correlate: CorrelateJourneyLeg?,
    private val onTrain: (TrainRunId) -> Unit,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val canBook: ((Journey) -> Boolean)? = null,
    private val openBooking: (suspend (Journey) -> BookingHandoffResult)? = null,
    private val favoritesRepository: FavoritesRepository? = null,
    private val strikes: LoadStrikes? = null,
    private val trains: TrainRepository? = null,
    private val restoreKey: JourneyLookupKey? = null,
    private val restoreSearch: SearchJourneys? = null,
    private val policy: JourneyPolicy = JourneyPolicy(),
    private val clock: Clock = Clock.System,
    private val strikePolicy: StrikePolicy = StrikePolicy(),
    private val realtimePolicy: RealtimePolicy = RealtimePolicy(),
    private val instrumentation: RequestInstrumentation = RequestInstrumentation.None,
) : ComponentContext by componentContext {
    private val scope = componentScope(lifecycle, dispatcher)
    private val freshnessWatcher = FreshnessWatcher(lifecycle, scope, clock)
    private val correlateUseCase = correlate
    private val mutable = MutableValue(JourneyDetailState(
        resolved = journey,
        // A null journey with a lookup key is a restoration resolving
        // through the repository; without a key there is nothing to
        // resolve and the component reports not-found.
        resolving = journey == null && restoreKey != null,
        notFound = journey == null && restoreKey == null,
        correlating = journey != null && correlate != null,
        // Availability and execution are bound to one handoff instance by the caller:
        // both lambdas must be present and `canBook` must approve this journey.
        bookingAvailable = journey != null && openBooking != null && canBook?.invoke(journey) == true,
    ))
    private var routeFavorite: FavoriteRouteController? = favoritesRepository?.let { favorites ->
        journey?.let { live ->
            FavoriteRouteController(
                componentContext,
                favorites,
                { favoriteRoute(live.origin, live.destination) },
                dispatcher,
            )
        }
    }
    val state: Value<JourneyDetailState> = mutable
    val favoriteRouteState: Value<RouteFavoriteState>? get() = routeFavorite?.state

    /**
     * Repository-observed journey presented as live truth, or null while a
     * restoration is resolving / when the target is unresolvable.
     */
    val journey: Journey? get() = mutable.value.resolved

    private val strikeEvaluator = EvaluateServiceStrikeImpact()
    private var latestStrikes: List<Strike> = emptyList()
    private var latestStrikeFreshness: DataFreshness = DataFreshness.Unknown
    private var firstUsefulContentPublished = false
    private val observedRealtimeLegs = mutableSetOf<Int>()

    init {
        // Elapsed-time aging (T7.14-B, union deadline): the restored
        // provenance, every correlated realtime leg and the strike coverage
        // each age at their own TTL with zero provider calls; resume
        // recomputes from the clock. The strike repository's local expiry
        // ticker tracks strike end times, never coverage freshness, so this
        // watcher owns the strike staleness boundary too.
        freshnessWatcher.observe(
            nextDeadline = { detailDeadline() },
            onTick = { ageDetail() },
        )
        if (journey != null) bindJourney(journey)
        else if (restoreKey != null) resolveRestored(restoreKey)
    }

    /**
     * Restored-detail entry point (T7.13-C): re-resolves the persisted
     * lookup key through the repository instead of deserializing a stale
     * snapshot. Only used by navigation restoration, never by live flows.
     */
    companion object {
        fun restored(
            componentContext: ComponentContext,
            key: JourneyLookupKey,
            search: SearchJourneys?,
            correlate: CorrelateJourneyLeg?,
            onTrain: (TrainRunId) -> Unit = {},
            dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
            canBook: ((Journey) -> Boolean)? = null,
            openBooking: (suspend (Journey) -> BookingHandoffResult)? = null,
            favoritesRepository: FavoritesRepository? = null,
            strikes: LoadStrikes? = null,
            trains: TrainRepository? = null,
            policy: JourneyPolicy = JourneyPolicy(),
            clock: Clock = Clock.System,
            strikePolicy: StrikePolicy = StrikePolicy(),
            realtimePolicy: RealtimePolicy = RealtimePolicy(),
            instrumentation: RequestInstrumentation = RequestInstrumentation.None,
        ): JourneyDetailComponent = JourneyDetailComponent(
            componentContext = componentContext,
            journey = null,
            correlate = correlate,
            onTrain = onTrain,
            dispatcher = dispatcher,
            canBook = canBook,
            openBooking = openBooking,
            favoritesRepository = favoritesRepository,
            strikes = strikes,
            trains = trains,
            restoreKey = key,
            restoreSearch = search,
            policy = policy,
            clock = clock,
            strikePolicy = strikePolicy,
            realtimePolicy = realtimePolicy,
            instrumentation = instrumentation,
        )
    }

    /**
     * Restored-detail resolution (T7.13-C): the cached observation first
     * (fresh data, or useful stale data where the repository contract
     * allows it), then one allowed repository lookup when the cache no
     * longer holds the target. The serialized key is matching criteria
     * only — it is never presented as journey data — and an ambiguous
     * match resolves to nothing rather than the wrong service.
     */
    private fun resolveRestored(key: JourneyLookupKey) {
        scope.launch {
            val request = key.searchRequest()
            val search = restoreSearch
            var resolved: Pair<Journey, DataFreshness>? = null
            if (request != null && search != null) {
                resolved = lookup(key, search, request, force = false)
                    ?: lookup(key, search, request, force = true)
            }
            currentCoroutineContext().ensureActive()
            if (resolved != null) bindJourney(resolved.first, resolved.second)
            else mutable.value = mutable.value.copy(resolving = false, notFound = true)
        }
    }

    private suspend fun lookup(
        key: JourneyLookupKey,
        search: SearchJourneys,
        request: JourneySearchRequest,
        force: Boolean,
    ): Pair<Journey, DataFreshness>? {
        val result = search(request, force)
        val data = (result as? DataResult.Data) ?: return null
        val journey = key.resolveIn(data.value.journeys) ?: return null
        return journey to data.freshness
    }

    private fun bindJourney(resolved: Journey, freshness: DataFreshness = DataFreshness.Unknown) {
        if (!firstUsefulContentPublished) {
            firstUsefulContentPublished = true
            instrumentation.event("journey.detail.first_useful_content", mapOf(
                "partial" to (freshness !is DataFreshness.Fresh).toString(),
            ))
        }
        mutable.value = mutable.value.copy(
            resolved = resolved,
            journeyFreshness = freshness,
            resolving = false,
            notFound = false,
            correlating = correlateUseCase != null,
            bookingAvailable = openBooking != null && canBook?.invoke(resolved) == true,
        )
        // New repository truth restarts freshness boundary tracking.
        freshnessWatcher.poke()
        val favorites = favoritesRepository
        if (favorites != null && routeFavorite == null) {
            // The component itself is the ComponentContext (by delegation).
            routeFavorite = FavoriteRouteController(
                this@JourneyDetailComponent,
                favorites,
                { favoriteRoute(resolved.origin, resolved.destination) },
                dispatcher,
            )
        }
        if (correlateUseCase != null) scope.launch {
            for ((index, leg) in resolved.legs.withIndex()) {
                instrumentation.span("journey.detail.train_correlation", mapOf("leg" to index.toString())) {
                    correlateUseCase(leg)
                }?.let { id ->
                    currentCoroutineContext().ensureActive()
                    mutable.value = mutable.value.copy(trainRuns = mutable.value.trainRuns + (index to id))
                    watchCorrelatedRun(index, id)
                }
            }
            mutable.value = mutable.value.copy(correlating = false)
        }
        if (strikes != null) scope.launch {
            // The exact scheduled journey interval: warnings need no realtime.
            strikes.observe(resolved.departure, resolved.arrival).collect { result ->
                val legs = mutable.value.resolved?.legs ?: resolved.legs
                when (result) {
                    is DataResult.Data -> {
                        latestStrikes = result.value
                        latestStrikeFreshness = result.freshness
                        mutable.value = mutable.value.copy(
                            strikeWarnings = legWarnings(legs, result.value, result.freshness),
                            strikesStale = result.freshness is DataFreshness.Stale,
                            strikesUnknown = result.freshness is DataFreshness.Unknown,
                            strikesFailed = result.warning != null,
                        )
                    }
                    is DataResult.Failure -> {
                        mutable.value = mutable.value.copy(
                            strikesStale = false,
                            strikesUnknown = true,
                            strikesFailed = true,
                        )
                    }
                }
                // New strike truth restarts the union boundary tracking.
                freshnessWatcher.poke()
            }
        }
        refreshStrikes()
    }

    /**
     * One targeted stale refresh for the exact journey interval (T7.12-B).
     * Never a realtime lookup and never one lookup per leg.
     */
    fun refreshStrikes() {
        val load = strikes ?: return
        val resolved = mutable.value.resolved ?: return
        scope.launch {
            load(resolved.departure, resolved.arrival)
        }
    }

    private fun legWarnings(
        legs: List<JourneyLeg>,
        strikes: List<Strike>,
        freshness: DataFreshness,
    ): Map<Int, List<ServiceStrikeWarning>> {
        if (strikes.isEmpty()) return emptyMap()
        return legs.mapIndexed { index, leg ->
            index to strikeEvaluator.warnings(leg.strikeContext(), strikes, freshness)
        }.filter { it.second.isNotEmpty() }.toMap()
    }

    /**
     * Available realtime enrichment for one correlated leg (T7.12-D):
     * observes the already correlated/cached snapshot read-only — scheduled
     * data stays primary and no refresh is ever issued here, so opening a
     * detail can never fan out into per-leg provider lookups.
     */
    private fun watchCorrelatedRun(index: Int, id: TrainRunId) {
        val repository = trains ?: return
        scope.launch {
            repository.observeTrain(id).collect { result ->
                mutable.value = mutable.value.copy(correlatedRuns = mutable.value.correlatedRuns + (index to result))
                if (result is DataResult.Data && observedRealtimeLegs.add(index)) {
                    instrumentation.event("journey.detail.train_realtime_content", mapOf("leg" to index.toString()))
                }
                // New correlated truth restarts the union boundary tracking.
                freshnessWatcher.poke()
            }
        }
    }

    /**
     * Earliest freshness boundary across the restored-journey provenance,
     * every correlated realtime leg and the strike coverage (T7.14
     * corrective): one component-level deadline, never one timer per leg
     * and never a provider poll.
     */
    private fun detailDeadline(): Instant? {
        val candidates = mutableListOf(
            mutable.value.journeyFreshness.staleTransitionAt(policy.ttl),
            latestStrikeFreshness.staleTransitionAt(strikePolicy.cacheTtl),
        )
        mutable.value.correlatedRuns.values.forEach { result ->
            (result as? DataResult.Data)?.let { data ->
                candidates += data.freshness.staleTransitionAt(
                    realtimePolicy.ttlForTrain(data.value.summary.status),
                )
            }
        }
        return candidates.filterNotNull().minOrNull()
    }

    /**
     * Local tick (T7.14 corrective): re-derives the restored provenance,
     * every correlated leg and the strike coverage from the injected clock
     * with zero provider calls, then re-evaluates leg warnings against the
     * aged strike freshness. Repository truth is untouched: the next
     * emission overwrites this display copy.
     */
    private fun ageDetail() {
        val now = clock.now()
        val current = mutable.value
        val agedJourney = current.journeyFreshness.aged(now, policy.ttl)
        val agedRuns = current.correlatedRuns.mapValues { (_, result) ->
            val data = result as? DataResult.Data ?: return@mapValues result
            val aged = data.freshness.aged(now, realtimePolicy.ttlForTrain(data.value.summary.status))
            if (aged == data.freshness) result else data.copy(freshness = aged)
        }
        val agedStrikes = latestStrikeFreshness.aged(now, strikePolicy.cacheTtl)
        var next = current
        if (agedJourney != current.journeyFreshness) next = next.copy(journeyFreshness = agedJourney)
        if (agedRuns != current.correlatedRuns) next = next.copy(correlatedRuns = agedRuns)
        if (agedStrikes != latestStrikeFreshness) {
            latestStrikeFreshness = agedStrikes
            val legs = next.resolved?.legs ?: current.resolved?.legs.orEmpty()
            next = next.copy(
                strikeWarnings = legWarnings(legs, latestStrikes, agedStrikes),
                strikesStale = agedStrikes is DataFreshness.Stale,
                strikesUnknown = agedStrikes is DataFreshness.Unknown,
            )
        }
        if (next != current) mutable.value = next
    }
    fun realtime(index: Int) {
        mutable.value.trainRuns[index]?.let {
            instrumentation.event("train.selection", mapOf("source" to "journey_detail", "leg" to index.toString()))
            onTrain(it)
        }
    }
    fun toggleFavoriteRoute() { routeFavorite?.toggle() }
    fun buy() {
        val opener = openBooking ?: return
        val resolved = mutable.value.resolved ?: return
        if (!mutable.value.bookingAvailable || mutable.value.bookingInProgress) return
        mutable.value = mutable.value.copy(bookingInProgress = true, bookingFailed = false)
        scope.launch {
            val result = opener(resolved)
            currentCoroutineContext().ensureActive()
            mutable.value = mutable.value.copy(
                bookingInProgress = false,
                bookingFailed = result !is BookingHandoffResult.Opened,
            )
        }
    }
}
