package it.danielebufarini.trenify.core.model

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class SearchHistoryTest {
    @Test
    fun journeyEntryKeepsSubmissionIdentityCriteriaAndSeparateSubmissionTime() {
        val origin = Station(StationId("internal-roma"), "Roma Termini")
        val destination = Station(StationId("internal-milano"), "Milano Centrale")
        val at = Instant.parse("2026-01-15T09:00:00Z")
        val submittedAt = Instant.parse("2026-09-06T08:00:00Z")
        val entry = JourneySearchHistoryEntry(
            SearchHistoryEntryId("entry-1"),
            origin,
            destination,
            at,
            JourneySearchMode.ARRIVE_BY,
            submittedAt,
        )

        assertEquals(JourneySearchRequest(origin, destination, at, JourneySearchMode.ARRIVE_BY), entry.request())
        // The requested travel instant and the submission timestamp are
        // distinct concepts: an old requested date stays untouched.
        assertEquals(at, entry.request().at)
        assertEquals(submittedAt, entry.submittedAt)
    }

    @Test
    fun numberOnlyTrainEntryStoresNoRouteAndYieldsUndiscriminatedIntent() {
        val submittedAt = Instant.parse("2026-09-06T08:00:00Z")
        val entry = TrainSearchHistoryEntry(
            SearchHistoryEntryId("train-1"),
            TrainNumber("123"),
            serviceDate = LocalDate.parse("2026-09-06"),
            originId = null,
            originName = null,
            operator = null,
            destinationName = null,
            submittedAt = submittedAt,
        )

        // No fictional origin/destination is ever stored for a number-only search.
        assertNull(entry.originId)
        assertNull(entry.originName)
        assertNull(entry.operator)
        assertNull(entry.destinationName)
        assertEquals(submittedAt, entry.submittedAt)
        assertEquals(
            TrainLookupIntent(TrainNumber("123")),
            entry.lookupIntent(),
        )
    }

    @Test
    fun discriminatedTrainEntryRoundTripsOriginAndOperatorThroughLookupIntent() {
        val origin = Station(StationId("internal-roma"), "Roma Termini")
        val entry = TrainSearchHistoryEntry(
            SearchHistoryEntryId("train-2"),
            TrainNumber("123"),
            serviceDate = LocalDate.parse("2026-09-06"),
            originId = origin.id,
            originName = origin.name,
            operator = Operator("Trenitalia"),
            destinationName = null,
            submittedAt = Instant.parse("2026-09-06T08:00:00Z"),
        )

        assertEquals(
            TrainLookupIntent(
                TrainNumber("123"),
                originId = origin.id,
                originName = origin.name,
                operator = Operator("Trenitalia"),
            ),
            entry.lookupIntent(),
        )
    }

    @Test
    fun idOnlyDiscriminatorRoundTripsThroughLookupIntentExactly() {
        val entry = TrainSearchHistoryEntry(
            SearchHistoryEntryId("train-id-only"),
            TrainNumber("123"),
            serviceDate = LocalDate.parse("2026-09-06"),
            originId = StationId("some-stable-id"),
            originName = null,
            operator = Operator("Trenitalia"),
            destinationName = null,
            submittedAt = Instant.parse("2026-09-06T08:00:00Z"),
        )

        // A stable ID without a display name is preserved exactly: it is
        // neither dropped nor weakened into name-only discrimination.
        assertEquals(
            TrainLookupIntent(
                TrainNumber("123"),
                originId = StationId("some-stable-id"),
                originName = null,
                operator = Operator("Trenitalia"),
            ),
            entry.lookupIntent(),
        )
    }

    @Test
    fun nameOnlyLegacyDiscriminatorRoundTripsThroughLookupIntent() {
        val entry = TrainSearchHistoryEntry(
            SearchHistoryEntryId("train-3"),
            TrainNumber("123"),
            serviceDate = LocalDate.parse("2026-09-06"),
            originId = null,
            originName = "Roma Termini",
            operator = Operator("Trenitalia"),
            destinationName = null,
            submittedAt = Instant.parse("2026-09-06T08:00:00Z"),
        )

        // A legacy name-only origin survives without a stable station ID.
        assertEquals(
            TrainLookupIntent(
                TrainNumber("123"),
                originId = null,
                originName = "Roma Termini",
                operator = Operator("Trenitalia"),
            ),
            entry.lookupIntent(),
        )
    }
}
