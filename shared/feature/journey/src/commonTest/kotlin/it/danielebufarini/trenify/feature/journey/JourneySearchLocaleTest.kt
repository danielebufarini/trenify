package it.danielebufarini.trenify.feature.journey

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import com.arkivanov.essenty.statekeeper.StateKeeperDispatcher
import it.danielebufarini.trenify.core.domain.SearchJourneys
import it.danielebufarini.trenify.core.model.JourneySearchIntent
import it.danielebufarini.trenify.core.model.JourneySearchMode
import it.danielebufarini.trenify.core.model.JourneySearchRequest
import it.danielebufarini.trenify.core.testing.FakeJourneyRepository
import it.danielebufarini.trenify.core.testing.MutableClock
import it.danielebufarini.trenify.core.testing.journeyDestination
import it.danielebufarini.trenify.core.testing.testStation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Locale-aware journey-search date/time (T7.14 corrective): EN/IT
 * presentation of the same Rome instant, typed durable truth, history
 * repeat and save/recreate preserving the semantic date/time, Rome DST
 * gap rejection with deterministic fold behavior, and locale switches
 * that never mutate the search instant.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JourneySearchLocaleTest {
    private fun kotlinx.coroutines.test.TestScope.search(
        lifecycle: LifecycleRegistry,
        localeTag: String,
        initialRequest: JourneySearchRequest? = null,
        onSearch: (JourneySearchRequest) -> Unit = {},
    ) = JourneySearchComponent(
        DefaultComponentContext(lifecycle),
        FakeJourneyRepository(),
        onSearch,
        StandardTestDispatcher(testScheduler),
        MutableClock(),
        initialIntent = JourneySearchIntent(testStation, journeyDestination),
        initialRequest = initialRequest,
        localeTag = localeTag,
    )

    @Test fun englishAndItalianPresentTheSameRomeInstant() = runTest {
        // MutableClock default 2026-09-05T08:00Z is 10:00 in Rome.
        val expectedAt = Instant.parse("2026-09-05T08:00:00Z")
        var englishAt: JourneySearchRequest? = null
        var italianAt: JourneySearchRequest? = null
        val englishLifecycle = LifecycleRegistry()
        val english = search(englishLifecycle, "en-US", onSearch = { englishAt = it })
        val italianLifecycle = LifecycleRegistry()
        val italian = search(italianLifecycle, "it-IT", onSearch = { italianAt = it })
        try {
            assertEquals(LocalDate.parse("2026-09-05"), english.state.value.date)
            assertEquals("9/5/2026", english.state.value.dateText)
            assertEquals("10:00 AM", english.state.value.timeText)
            assertEquals(LocalDate.parse("2026-09-05"), italian.state.value.date)
            assertEquals("5/9/2026", italian.state.value.dateText)
            assertEquals("10:00", italian.state.value.timeText)
            english.search(); runCurrent()
            italian.search(); runCurrent()
            assertEquals(expectedAt, assertNotNull(englishAt).at)
            assertEquals(expectedAt, assertNotNull(italianAt).at)
        } finally {
            englishLifecycle.destroy()
            italianLifecycle.destroy()
        }
    }

    @Test fun localeTypedEntryParsesToTheSameInstant() = runTest {
        var englishAt: JourneySearchRequest? = null
        var italianAt: JourneySearchRequest? = null
        val englishLifecycle = LifecycleRegistry()
        val english = search(englishLifecycle, "en", onSearch = { englishAt = it })
        val italianLifecycle = LifecycleRegistry()
        val italian = search(italianLifecycle, "it", onSearch = { italianAt = it })
        try {
            english.date("09/05/2026"); english.time("10:00 AM")
            italian.date("5/9/2026"); italian.time("10:00")
            english.search(); italian.search(); runCurrent()
            val expected = Instant.parse("2026-09-05T08:00:00Z")
            assertEquals(expected, assertNotNull(englishAt).at)
            assertEquals(expected, assertNotNull(italianAt).at)
        } finally {
            englishLifecycle.destroy()
            italianLifecycle.destroy()
        }
    }

    @Test fun historyRepeatPreservesTheSameInstant() = runTest {
        val at = Instant.parse("2026-01-15T09:00:00Z")
        val request = JourneySearchRequest(testStation, journeyDestination, at, JourneySearchMode.ARRIVE_BY)
        var searched: JourneySearchRequest? = null
        val lifecycle = LifecycleRegistry()
        val component = search(lifecycle, "it-IT", initialRequest = request, onSearch = { searched = it })
        try {
            // The originally requested Rome date/time restores as typed truth...
            assertEquals(LocalDate.parse("2026-01-15"), component.state.value.date)
            assertEquals(10, component.state.value.timeHour)
            assertEquals(0, component.state.value.timeMinute)
            // ...presented in the current locale...
            assertEquals("15/1/2026", component.state.value.dateText)
            assertEquals("10:00", component.state.value.timeText)
            // ...and re-executes the identical instant.
            component.search(); runCurrent()
            assertEquals(request, assertNotNull(searched))
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun saveDestroyRecreatePreservesTheSemanticDateTime() = runTest {
        val keeper = StateKeeperDispatcher(null)
        val context = DefaultComponentContext(LifecycleRegistry(), keeper)
        var searched: JourneySearchRequest? = null
        val component = JourneySearchComponent(
            context,
            FakeJourneyRepository(),
            { searched = it },
            StandardTestDispatcher(testScheduler),
            MutableClock(),
            initialIntent = JourneySearchIntent(testStation, journeyDestination),
            localeTag = "it-IT",
        )
        component.date("15/1/2026"); component.time("18:30")
        val saved = keeper.save()

        val revivedLifecycle = LifecycleRegistry()
        val revived = JourneySearchComponent(
            DefaultComponentContext(revivedLifecycle, StateKeeperDispatcher(saved)),
            FakeJourneyRepository(),
            { searched = it },
            StandardTestDispatcher(testScheduler),
            MutableClock(),
            initialIntent = JourneySearchIntent(testStation, journeyDestination),
            // Recreate under the other locale: buffers reformat, truth stays.
            localeTag = "en-US",
        )
        try {
            assertEquals(LocalDate.parse("2026-01-15"), revived.state.value.date)
            assertEquals(18, revived.state.value.timeHour)
            assertEquals(30, revived.state.value.timeMinute)
            assertEquals("1/15/2026", revived.state.value.dateText)
            assertEquals("6:30 PM", revived.state.value.timeText)
            revived.search(); runCurrent()
            // 18:30 Rome in January is UTC+1.
            assertEquals(Instant.parse("2026-01-15T17:30:00Z"), assertNotNull(searched).at)
        } finally {
            revivedLifecycle.destroy()
        }
    }

    @Test fun romeSpringGapRemainsRejectedAndFallFoldIsDeterministic() = runTest {
        var searches = 0
        val lifecycle = LifecycleRegistry()
        val component = search(lifecycle, "en-US", onSearch = { searches++ })
        try {
            // 2026-03-29 02:30 does not exist in Europe/Rome.
            component.date("2026-03-29"); component.time("02:30"); component.search()
            assertTrue(component.state.value.invalid)
            // 2026-10-25 02:30 happens twice; kotlinx-datetime resolves the
            // fold deterministically and the round-trip holds.
            component.date("2026-10-25"); component.time("02:30"); component.search()
            assertEquals(1, searches)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun switchingLocaleCannotMutateTheSearchInstant() = runTest {
        var first: JourneySearchRequest? = null
        var second: JourneySearchRequest? = null
        val lifecycle = LifecycleRegistry()
        val component = search(lifecycle, "en-US", onSearch = { first = it })
        try {
            component.search(); runCurrent()
            val before = assertNotNull(first)
            val typedBefore = component.state.value.localDateTime
            component.relocale("it-IT")
            // Same typed truth, re-presented in Italian...
            assertEquals(typedBefore, component.state.value.localDateTime)
            assertEquals("5/9/2026", component.state.value.dateText)
            // ...and the re-presented form searches the identical instant.
            val secondLifecycle = LifecycleRegistry()
            val component2 = search(secondLifecycle, "it-IT", onSearch = { second = it })
            try {
                component2.date(component.state.value.dateText)
                component2.time(component.state.value.timeText)
                component2.search(); runCurrent()
                assertEquals(before.at, assertNotNull(second).at)
            } finally {
                secondLifecycle.destroy()
            }
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun searchPrimitivesAreDeterministicInBothLocales() {
        assertEquals("5/9/2026", formatSearchDate(LocalDate.parse("2026-09-05"), "it"))
        assertEquals("9/5/2026", formatSearchDate(LocalDate.parse("2026-09-05"), "en"))
        assertEquals("10:00", formatSearchTime(10, 0, "it"))
        assertEquals("10:00 AM", formatSearchTime(10, 0, "en"))
        assertEquals("6:30 PM", formatSearchTime(18, 30, "en-US"))
        assertEquals(LocalDate.parse("2026-09-05"), parseSearchDate("5/9/2026", "it"))
        assertEquals(LocalDate.parse("2026-09-05"), parseSearchDate("09/05/2026", "en"))
        assertEquals(LocalDate.parse("2026-09-05"), parseSearchDate("2026-09-05", "it"))
        assertEquals(LocalDate.parse("2026-09-05"), parseSearchDate("2026-09-05", "en"))
        assertEquals(10 to 0, parseSearchTime("10:00", "it"))
        assertEquals(10 to 0, parseSearchTime("10:00 AM", "en"))
        assertEquals(18 to 30, parseSearchTime("6:30 pm", "en"))
        assertEquals(null, parseSearchDate("bad-date", "en"))
        assertEquals(null, parseSearchDate("13/40/2026", "en"))
        assertEquals(null, parseSearchTime("25:00", "it"))
        assertEquals(null, parseSearchTime("13:00 PM", "en"))
        assertEquals(null, parseSearchTime("10", "en"))
    }
}
