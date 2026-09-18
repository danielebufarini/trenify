package it.danielebufarini.trenify.core.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotificationDestinationTest {
    private val train = NotificationDestination.Train(
        provider = "test",
        number = "123",
        origin = "opaque-origin",
        serviceDate = "2026-09-05",
    )
    private val strike = NotificationDestination.Strike("mit-strikes:8479")

    @Test
    fun trainRoundTripsThroughTransportCodec() {
        val decoded = NotificationDestinationCodec.decode(NotificationDestinationCodec.encode(train))
        assertEquals(train, decoded)
    }

    @Test
    fun strikeRoundTripsThroughTransportCodec() {
        val decoded = NotificationDestinationCodec.decode(NotificationDestinationCodec.encode(strike))
        assertEquals(strike, decoded)
    }

    @Test
    fun decodeRejectsMalformedTrainPayloads() {
        val base = NotificationDestinationCodec.encode(train)
        assertNull(NotificationDestinationCodec.decode(base + (NotificationDestinationCodec.KEY_NUMBER to "12A")))
        assertNull(NotificationDestinationCodec.decode(base + (NotificationDestinationCodec.KEY_NUMBER to "")))
        assertNull(NotificationDestinationCodec.decode(base + (NotificationDestinationCodec.KEY_SERVICE_DATE to "05/09/2026")))
        assertNull(NotificationDestinationCodec.decode(base + (NotificationDestinationCodec.KEY_SERVICE_DATE to "2026-13-40")))
        assertNull(NotificationDestinationCodec.decode(base - NotificationDestinationCodec.KEY_PROVIDER))
        assertNull(NotificationDestinationCodec.decode(base - NotificationDestinationCodec.KEY_ORIGIN))
        assertNull(NotificationDestinationCodec.decode(base - NotificationDestinationCodec.KEY_KIND))
        assertNull(NotificationDestinationCodec.decode(emptyMap()))
        assertNull(NotificationDestinationCodec.decode(mapOf(NotificationDestinationCodec.KEY_KIND to "spaceship")))
    }

    @Test
    fun decodeRejectsMalformedStrikePayloads() {
        assertNull(NotificationDestinationCodec.decode(
            mapOf(NotificationDestinationCodec.KEY_KIND to NotificationDestinationCodec.KIND_STRIKE)))
        assertNull(NotificationDestinationCodec.decode(
            mapOf(NotificationDestinationCodec.KEY_KIND to NotificationDestinationCodec.KIND_STRIKE,
                NotificationDestinationCodec.KEY_STRIKE_ID to "")))
    }

    @Test
    fun displayTextNeverDecodesToDestination() {
        // Title/body parsing must never determine navigation: only the
        // namespaced payload keys decode.
        assertNull(NotificationDestinationCodec.decode(mapOf("title" to "Train 123", "body" to "Delay changed")))
    }

    @Test
    fun destinationDoesNotChangeFingerprintInputs() {
        // Routing metadata travels alongside the id; ids are built exactly
        // as before (covered by coordinator tests asserting exact ids).
        val without = NotificationMessage(id = "key:fp", title = "t", body = "b")
        val with = without.copy(destination = train)
        assertEquals(without.id, with.id)
        assertEquals(without.title, with.title)
        assertEquals(without.body, with.body)
        assertEquals(train, with.destination)
    }
}

class NotificationTapContractTest {
    private val first = NotificationDestination.Train("test", "123", "opaque-a", "2026-09-05")
    private val second = NotificationDestination.Train("test", "456", "opaque-b", "2026-09-05")
    private val strike = NotificationDestination.Strike("mit-strikes:8479")

    @Test
    fun distinctDestinationsDoNotCollide() {
        assertNotEquals(NotificationTap.dataUri(first), NotificationTap.dataUri(second))
        assertNotEquals(NotificationTap.dataUri(first), NotificationTap.dataUri(strike))
        assertNotEquals(
            NotificationTap.requestCode(first, "a"),
            NotificationTap.requestCode(second, "b"),
        )
    }

    @Test
    fun tapEncodingIsStable() {
        assertEquals(NotificationTap.dataUri(first), NotificationTap.dataUri(first))
        assertEquals(
            NotificationTap.requestCode(first, "a"),
            NotificationTap.requestCode(first, "a"),
        )
        assertTrue(NotificationTap.dataUri(first).startsWith("trenify://notification/"))
    }

    @Test
    fun extrasRoundTripThroughDecode() {
        assertEquals(first, NotificationTap.decode(NotificationTap.extras(first)))
        assertEquals(strike, NotificationTap.decode(NotificationTap.extras(strike)))
    }

    @Test
    fun decodeSafelyIgnoresMalformedOrMissingPayloads() {
        assertNull(NotificationTap.decode(null))
        assertNull(NotificationTap.decode(emptyMap()))
        assertNull(NotificationTap.decode(mapOf("other" to "value")))
        assertNull(NotificationTap.decode(mapOf(NotificationDestinationCodec.KEY_KIND to "train")))
        assertNull(
            NotificationTap.decode(
                mapOf(
                    NotificationDestinationCodec.KEY_KIND to "train",
                    NotificationDestinationCodec.KEY_PROVIDER to "test",
                    NotificationDestinationCodec.KEY_NUMBER to "12X",
                    NotificationDestinationCodec.KEY_ORIGIN to "o",
                    NotificationDestinationCodec.KEY_SERVICE_DATE to "2026-09-05",
                ),
            ),
        )
    }

    @Test
    fun decodeIgnoresNonStringAndForeignEntries() {
        val extras: Map<String, String?> = NotificationTap.extras(strike) + ("foreign" to null)
        assertEquals(strike, NotificationTap.decode(extras))
    }
}

class NotificationEntryHandoffTest {
    private val first = NotificationDestination.Train("test", "123", "opaque-a", "2026-09-05")
    private val second = NotificationDestination.Strike("mit-strikes:8479")

    @Test
    fun consumeDeliversExactlyOnce() {
        val handoff = NotificationEntryHandoff()
        handoff.offer(first)
        assertEquals(first, handoff.consume())
        // Consumed entries are never redelivered: duplicate native delivery
        // after consumption must not navigate again by itself.
        assertNull(handoff.consume())
    }

    @Test
    fun duplicateOfferWhilePendingIsRetainedOnce() {
        val handoff = NotificationEntryHandoff()
        handoff.offer(first)
        handoff.offer(first)
        assertEquals(first, handoff.consume())
        assertNull(handoff.consume())
    }

    @Test
    fun newerOfferReplacesUnconsumedPending() {
        val handoff = NotificationEntryHandoff()
        handoff.offer(first)
        handoff.offer(second)
        // The latest tap wins; at most one pending entry is retained.
        assertEquals(second, handoff.consume())
        assertNull(handoff.consume())
    }

    @Test
    fun emptyHandoffConsumesToNull() {
        assertNull(NotificationEntryHandoff().consume())
        assertNull(NotificationEntryHandoff().peek())
    }

    @Test
    fun peekDoesNotConsume() {
        val handoff = NotificationEntryHandoff()
        handoff.offer(first)
        assertEquals(first, handoff.peek())
        assertEquals(first, handoff.consume())
    }
}
