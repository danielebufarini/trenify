package it.danielebufarini.trenify.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class FavoriteTrainTest {
    private val operator = Operator("Trenitalia")
    private val roma = Station(StationId("station-roma"), "Roma Termini")
    private val napoli = Station(StationId("station-napoli"), "Napoli Centrale")

    @Test
    fun recurringIdentityIsDistinctFromDatedRunIdentity() {
        val favorite = FavoriteTrain.create(
            TrainNumber("123"),
            originId = roma.id,
            operator = operator,
            originName = roma.name,
            destinationName = "Milano Centrale",
        )

        // No service date, provider, delay, platform or status is part of the favorite.
        assertEquals(TrainNumber("123"), favorite.number)
        assertEquals(roma.id, favorite.originId)
        assertEquals(operator, favorite.operator)
        assertEquals("Roma Termini", favorite.originName)
        assertEquals("Milano Centrale", favorite.destinationName)
    }

    @Test
    fun favoriteIdentityUsesStableOriginIdentityWhenAvailable() {
        val fromRun = FavoriteTrain.create(
            TrainNumber("123"),
            originId = roma.id,
            operator = operator,
            originName = roma.name,
        )

        assertEquals(roma.id, fromRun.originId)
        // The stable identity, not the display label, drives the local ID.
        assertEquals(
            FavoriteTrain.idFor(TrainNumber("123"), roma.id, "A different label", operator),
            fromRun.id,
        )
    }

    @Test
    fun equalStableReferencesDeduplicate() {
        val first = FavoriteTrain.create(
            TrainNumber("123"),
            originId = roma.id,
            operator = operator,
            originName = "Roma Termini",
            destinationName = "Milano Centrale",
        )
        val second = FavoriteTrain.create(
            TrainNumber("123"),
            originId = roma.id,
            operator = Operator("trenitalia"),
            originName = "  roma TERMINI ",
            destinationName = "Other display",
        )

        // Display-only destination and label casing do not affect identity.
        assertEquals(first.id, second.id)
        assertEquals(listOf(first), (listOf(first) + second).distinctBy(FavoriteTrain::id))
    }

    @Test
    fun sameNumberWithDifferentStableOriginProducesDistinctIdentity() {
        val base = FavoriteTrain.create(TrainNumber("123"), originId = roma.id, operator = operator)
        val otherOrigin = FavoriteTrain.create(TrainNumber("123"), originId = napoli.id, operator = operator)

        assertNotEquals(base.id, otherOrigin.id)
    }

    @Test
    fun sameDisplayNameWithDifferentStationIdsDoesNotCollapse() {
        val first = FavoriteTrain.create(
            TrainNumber("123"),
            originId = StationId("station-roma-a"),
            operator = operator,
            originName = "Roma Termini",
        )
        val second = FavoriteTrain.create(
            TrainNumber("123"),
            originId = StationId("station-roma-b"),
            operator = operator,
            originName = "Roma Termini",
        )

        assertNotEquals(first.id, second.id)
    }

    @Test
    fun sameNumberWithDifferentOperatorOrUnknownDiscriminationDoesNotCollapse() {
        val base = FavoriteTrain.create(TrainNumber("123"), originId = roma.id, operator = operator)
        val otherOperator = FavoriteTrain.create(
            TrainNumber("123"),
            originId = roma.id,
            operator = Operator("Italo"),
        )
        val unknownOrigin = FavoriteTrain.create(TrainNumber("123"), originId = null, operator = operator)
        val unknownOperator = FavoriteTrain.create(TrainNumber("123"), originId = roma.id, operator = null)

        assertNotEquals(base.id, otherOperator.id)
        assertNotEquals(base.id, unknownOrigin.id)
        assertNotEquals(base.id, unknownOperator.id)
        assertNotEquals(unknownOrigin.id, unknownOperator.id)
    }

    @Test
    fun displayOnlyChangesDoNotRedefineRecurringIdentity() {
        val base = FavoriteTrain.create(
            TrainNumber("123"),
            originId = roma.id,
            operator = operator,
            originName = "Roma Termini",
            destinationName = "Milano Centrale",
        )
        val renamed = FavoriteTrain.create(
            TrainNumber("123"),
            originId = roma.id,
            operator = operator,
            originName = "Roma Termini Platform 1",
            destinationName = "Milano Rogoredo",
        )

        assertEquals(base.id, renamed.id)
        assertEquals(base, renamed.copy(originName = base.originName, destinationName = base.destinationName))
    }

    @Test
    fun blankDiscriminationIsStoredAsUnknownWithoutFabrication() {
        val favorite = FavoriteTrain.create(
            TrainNumber("123"),
            originId = StationId("  "),
            operator = Operator("  "),
            originName = "  ",
        )

        assertNull(favorite.originId)
        assertNull(favorite.originName)
        assertNull(favorite.operator)
        assertEquals(
            FavoriteTrain.create(TrainNumber("123"), originId = null, operator = null).id,
            favorite.id,
        )
    }

    @Test
    fun datedAndVolatileRunInformationIsNotPersistedAsFavoriteIdentity() {
        val summary = testSummary()
        val favorite = summary.toFavoriteTrain()

        assertEquals(summary.id.number, favorite.number)
        assertEquals(summary.origin.id, favorite.originId)
        assertEquals(summary.operator, favorite.operator)
        assertEquals(summary.origin.name, favorite.originName)
        assertEquals(summary.destinationName, favorite.destinationName)
        // Volatile run state (delay/platforms/status) and dated identity
        // (service date/provider/origin ref) have no representation in FavoriteTrain.
        assertEquals(
            FavoriteTrain.create(
                favorite.number,
                originId = favorite.originId,
                operator = favorite.operator,
                originName = favorite.originName,
                destinationName = favorite.destinationName,
            ),
            favorite,
        )
    }

    @Test
    fun lookupIntentPreservesStableDiscriminationWithoutVolatileState() {
        val favorite = FavoriteTrain.create(
            TrainNumber("123"),
            originId = roma.id,
            operator = operator,
            originName = "Roma Termini",
            destinationName = "Milano Centrale",
        )

        assertEquals(
            TrainLookupIntent(
                number = TrainNumber("123"),
                originId = roma.id,
                originName = "Roma Termini",
                operator = operator,
            ),
            favorite.lookupIntent,
        )
    }

    @Test
    fun compatibleCandidateSharesKnownOriginAndOperator() {
        val intent = favorite(number = "123", origin = roma, operator = operator).lookupIntent

        assertEquals(
            TrainCandidateCompatibility.COMPATIBLE,
            intent.compatibility(candidate(number = "123", origin = roma, operator = operator)),
        )
    }

    @Test
    fun sameNumberWithDifferentStableOriginIsIncompatibleEvenWhenAlone() {
        val intent = favorite(number = "123", origin = roma, operator = operator).lookupIntent

        // The blocking counterexample: a single Napoli run must never be
        // mistaken for the saved Roma recurring train.
        assertEquals(
            TrainCandidateCompatibility.INCOMPATIBLE,
            intent.compatibility(candidate(number = "123", origin = napoli, operator = operator)),
        )
    }

    @Test
    fun conflictingKnownOperatorIsIncompatible() {
        val intent = favorite(number = "123", origin = roma, operator = operator).lookupIntent

        assertEquals(
            TrainCandidateCompatibility.INCOMPATIBLE,
            intent.compatibility(candidate(number = "123", origin = roma, operator = Operator("Italo"))),
        )
    }

    @Test
    fun unknownValuesNeverInventAMatch() {
        val withOperator = favorite(number = "123", origin = roma, operator = operator).lookupIntent

        // A known expectation against an unknown candidate operator cannot be
        // verified: explicit selection is required instead of silent navigation.
        assertEquals(
            TrainCandidateCompatibility.AMBIGUOUS,
            withOperator.compatibility(candidate(number = "123", origin = roma, operator = null)),
        )
        // Unknown favorite discrimination imposes no constraint.
        val numberOnly = FavoriteTrain.create(TrainNumber("123"), originId = null, operator = null).lookupIntent
        assertEquals(
            TrainCandidateCompatibility.COMPATIBLE,
            numberOnly.compatibility(candidate(number = "123", origin = napoli, operator = Operator("Italo"))),
        )
    }

    @Test
    fun idOnlyDiscriminatorJudgesStableIdentityWithoutDisplayNames() {
        val idOnly = TrainLookupIntent(
            TrainNumber("123"),
            originId = roma.id,
            originName = null,
            operator = operator,
        )

        // The same stable origin ID is compatible even though the candidate
        // carries a display name the intent never knew.
        assertEquals(
            TrainCandidateCompatibility.COMPATIBLE,
            idOnly.compatibility(candidate(number = "123", origin = roma, operator = operator)),
        )
        // A different stable origin ID stays incompatible; matching never
        // falls back to display-name comparison in this branch.
        assertEquals(
            TrainCandidateCompatibility.INCOMPATIBLE,
            idOnly.compatibility(candidate(number = "123", origin = napoli, operator = operator)),
        )
    }

    @Test
    fun legacyDisplayNameMismatchBlocksButResemblanceNeverProvesIdentity() {
        val legacy = FavoriteTrain.create(
            TrainNumber("123"),
            originId = null,
            operator = operator,
            originName = "Roma Termini",
        ).lookupIntent

        assertEquals(
            TrainCandidateCompatibility.INCOMPATIBLE,
            legacy.compatibility(candidate(number = "123", origin = napoli, operator = operator)),
        )
        // Same labels can still coincide across distinct stations, so a match
        // stays ambiguous and requires explicit selection.
        assertEquals(
            TrainCandidateCompatibility.AMBIGUOUS,
            legacy.compatibility(
                candidate(
                    number = "123",
                    origin = roma.copy(id = StationId("another-roma")),
                    operator = operator,
                ),
            ),
        )
    }

    @Test
    fun differentTrainNumberNeverMatches() {
        val intent = favorite(number = "123", origin = roma, operator = operator).lookupIntent

        assertEquals(
            TrainCandidateCompatibility.INCOMPATIBLE,
            intent.compatibility(candidate(number = "124", origin = roma, operator = operator)),
        )
    }

    private fun favorite(number: String, origin: Station?, operator: Operator?): FavoriteTrain =
        FavoriteTrain.create(
            TrainNumber(number),
            originId = origin?.id,
            operator = operator,
            originName = origin?.name,
        )

    private fun candidate(number: String, origin: Station, operator: Operator?): TrainRunSummary =
        TrainRunSummary(
            id = TrainRunId(
                provider = ProviderId("test"),
                number = TrainNumber(number),
                origin = ExternalStationRef("opaque-origin"),
                serviceDate = kotlinx.datetime.LocalDate.parse("2026-09-06"),
            ),
            origin = origin,
            destinationName = "Milano Centrale",
            operator = operator,
        )

    private fun testSummary(): TrainRunSummary {
        val id = TrainRunId(
            provider = ProviderId("test"),
            number = TrainNumber("123"),
            origin = ExternalStationRef("opaque-origin"),
            serviceDate = kotlinx.datetime.LocalDate.parse("2026-09-05"),
        )
        return TrainRunSummary(
            id = id,
            origin = Station(StationId("internal-station"), "Roma Termini"),
            destinationName = "Milano Centrale",
            status = TrainStatus.RUNNING,
            delayMinutes = 12,
            scheduledPlatform = "1",
            actualPlatform = "2",
            operator = operator,
        )
    }
}
