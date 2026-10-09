package it.danielebufarini.trenify.core.model

import kotlin.random.Random
import kotlin.test.*

/** Synthetic policy fixtures; real catalog/HTTP regressions live in provider:journey. */
class StationSuggestionsTest {
    private fun station(name: String, category: StationSearchCategory = StationSearchCategory.UNKNOWN, id: String = name) =
        Station(StationId(id), name, category)

    @Test fun exactCompletePrefixPartialPrefixTokenAndSubstringHaveExplicitPrecedence() {
        val candidates = listOf("CASIGNANA VILLA ROMANA", "Aroma", "Cesano Di Roma", "Roma Tiburtina", "Romagnano Sesia", "Roma").map(::station)
        assertEquals(listOf("Roma", "Roma Tiburtina", "Romagnano Sesia", "Cesano Di Roma", "CASIGNANA VILLA ROMANA", "Aroma"),
            rankStationSuggestions("roma", candidates).map { it.name })
    }

    @Test fun tokenPrefixesAndMultiwordQueriesFindStationsWithoutTheirCityPrefix() {
        val candidates = listOf("Roma Tiburtina", "Roma Termini", "CASIGNANA VILLA ROMANA", "Milano Centrale").map(::station)
        assertEquals("Roma Tiburtina", rankStationSuggestions("tiburtina", candidates).first().name)
        assertEquals("Roma Tiburtina", rankStationSuggestions("roma tib", candidates).first().name)
        assertEquals("CASIGNANA VILLA ROMANA", rankStationSuggestions("villa romana", candidates).first().name)
        assertEquals("Milano Centrale", rankStationSuggestions("centrale", candidates).first().name)
    }

    @Test fun caseDiacriticsPunctuationAndWhitespaceShareSearchSpellingWithoutChangingIdentity() {
        assertEquals("terni", stationSearchText(" TE\u0300RNI "))
        assertEquals("terni", stationSearchText(" TÈRNI "))
        assertEquals(stationSearchText("Firenze S.M.Novella"), stationSearchText(" FIRENZE  S. M. Novella "))
        assertEquals(stationSearchText("Narni-Amelia"), stationSearchText("narni amelia"))
        assertEquals(stationSearchText("L’Aquila"), stationSearchText("l'aquila"))
        val value = station("Narni-Amelia", id = "opaque:original")
        assertEquals(value, rankStationSuggestions("narni amelia", listOf(value)).single())
        assertEquals("narni-amelia", value.normalizedName)
    }

    @Test fun verifiedRailwayMetadataBreaksTextTiesButCannotOverrideAnExactOrStrongerMatch() {
        val major = station("Roma Termini", StationSearchCategory.RAILWAY)
        val minor = station("Roma Aurelia")
        val exact = station("Roma")
        val unrelated = station("Cesano Di Roma", StationSearchCategory.RAILWAY)
        assertEquals(listOf(exact, major, minor, unrelated), rankStationSuggestions("roma", listOf(unrelated, minor, major, exact)))
        assertEquals(minor, rankStationSuggestions("Roma Aurelia", listOf(major, minor)).first())
    }

    @Test fun unknownMetadataDoesNotInventImportanceOrPenalizeMultiwordNames() {
        val candidates = listOf("Roma Tiburtina", "Roma Monte Mario", "Roma Aurelia").map(::station)
        assertEquals(listOf("Roma Aurelia", "Roma Monte Mario", "Roma Tiburtina"), rankStationSuggestions("roma", candidates).map { it.name })
        assertEquals("Roma Monte Mario", rankStationSuggestions("roma monte", candidates).first().name)
    }

    @Test fun deterministicIdentityDeduplicationPreservesDistinctLocationsAndStrongestEvidence() {
        val a = station("Station", id = "a")
        val b = station("Station", id = "b")
        val confirmed = a.copy(searchCategory = StationSearchCategory.RAILWAY)
        val candidates = listOf(a, b, confirmed, station("Station North", id = "c"))
        val expected = listOf(confirmed, b, candidates.last())
        repeat(20) { assertEquals(expected, rankStationSuggestions("station", candidates.shuffled(Random(it)))) }
    }

    @Test fun rankingPrecedesDisplayLimitAndPreservesMinorStationsAndProviderAliases() {
        val minor = station("Terni")
        val candidates = (1..40).map { station("Cisternino $it") } + minor + station("Alias from provider")
        assertEquals(minor, rankStationSuggestions("terni", candidates).take(5).first())
        assertEquals(candidates.toSet(), rankStationSuggestions("terni", candidates).toSet())
        assertTrue(rankStationSuggestions("", candidates).isEmpty())
    }

    @Test fun qualifiedLocationKeysStayDistinctAndLegacyJourneyCacheKeysRemainCompatible() {
        val origin = station("Milano Centrale", id = "station:milano centrale")
        val destination = station("Terni", id = "station:terni")
        val at = kotlin.time.Instant.parse("2026-10-08T09:00:00Z")
        val request = JourneySearchRequest(origin, destination, at)
        val legacyKey = listOf(origin.normalizedName, destination.normalizedName, at.toString(), request.mode.name)
            .joinToString("") { "${it.length}:$it" }
        assertEquals(legacyKey, request.key)
        assertEquals(request.key, request.copy(destination = destination.copy(searchCategory = StationSearchCategory.RAILWAY)).key)
        val first = station("Same Name", id = "location:catalog:a")
        val second = station("Same Name", id = "location:catalog:b")
        assertNotEquals(request.copy(destination = first).key, request.copy(destination = second).key)
    }
}
