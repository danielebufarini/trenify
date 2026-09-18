package it.danielebufarini.trenify.core.domain

import it.danielebufarini.trenify.core.model.ExternalStationRef
import it.danielebufarini.trenify.core.model.ProviderId
import it.danielebufarini.trenify.core.model.TrainNumber
import it.danielebufarini.trenify.core.model.TrainRunId
import it.danielebufarini.trenify.core.model.TrainStatus
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * T7.7 delivery precedence: global switch x per-train switch x event flag x
 * effective OS permission, for every supported event kind.
 */
class NotificationDeliveryTest {
    private val id = TrainRunId(
        ProviderId("test"),
        TrainNumber("42"),
        ExternalStationRef("origin"),
        LocalDate.parse("2026-09-05"),
    )
    private val settings = NotificationSettings()
    private val monitor = TrainMonitor(
        MonitorId(id.key),
        id,
        true,
        MonitorThresholds(delayMinutes = 15),
        Instant.parse("2026-09-05T07:00:00Z"),
        null,
    )

    private fun event(kind: MonitorEventKind): TrainMonitorEvent = when (kind) {
        MonitorEventKind.DELAY -> TrainMonitorEvent.DelayThresholdCrossed(id, 5, 20, 15)
        MonitorEventKind.CANCELLATION -> TrainMonitorEvent.Cancelled(id)
        MonitorEventKind.PARTIAL_CANCELLATION -> TrainMonitorEvent.PartiallyCancelled(id, listOf("Milano"))
        MonitorEventKind.PLATFORM -> TrainMonitorEvent.PlatformChanged(id, "Roma", "1", "2")
        MonitorEventKind.SCHEDULE -> TrainMonitorEvent.ScheduleChanged(id, "Roma")
        MonitorEventKind.STATUS -> TrainMonitorEvent.StatusChanged(id, TrainStatus.RUNNING, TrainStatus.DIVERTED)
        MonitorEventKind.DEPARTURE -> TrainMonitorEvent.Departed(id)
        MonitorEventKind.ARRIVAL -> TrainMonitorEvent.Arrived(id)
        MonitorEventKind.ROUTE_CHANGED -> TrainMonitorEvent.RouteChanged(id, listOf("b"), emptyList())
    }

    @Test fun allKindsDeliverWhenEveryGateAllows() {
        MonitorEventKind.entries.forEach { kind ->
            assertTrue(
                NotificationDelivery.shouldDeliverTrainEvent(settings, monitor, event(kind), true),
                "expected delivery for $kind",
            )
        }
    }

    @Test fun globalSwitchOffBlocksEveryKind() {
        val off = settings.copy(notificationsEnabled = false)
        MonitorEventKind.entries.forEach { kind ->
            assertFalse(
                NotificationDelivery.shouldDeliverTrainEvent(off, monitor, event(kind), true),
                "expected no delivery for $kind",
            )
        }
    }

    @Test fun mutedTrainBlocksEveryKind() {
        val muted = monitor.copy(notificationsEnabled = false)
        MonitorEventKind.entries.forEach { kind ->
            assertFalse(
                NotificationDelivery.shouldDeliverTrainEvent(settings, muted, event(kind), true),
                "expected no delivery for $kind",
            )
        }
    }

    @Test fun deniedPermissionBlocksEveryKind() {
        MonitorEventKind.entries.forEach { kind ->
            assertFalse(
                NotificationDelivery.shouldDeliverTrainEvent(settings, monitor, event(kind), false),
                "expected no delivery for $kind",
            )
        }
    }

    @Test fun eachFlagGatesOnlyItsKind() {
        val flags = mapOf(
            MonitorEventKind.DELAY to MonitorThresholds(delayMinutes = 15, notifyDelay = false),
            MonitorEventKind.CANCELLATION to MonitorThresholds(delayMinutes = 15, notifyCancellation = false),
            MonitorEventKind.PARTIAL_CANCELLATION to MonitorThresholds(delayMinutes = 15, notifyCancellation = false),
            MonitorEventKind.PLATFORM to MonitorThresholds(delayMinutes = 15, notifyPlatform = false),
            MonitorEventKind.DEPARTURE to MonitorThresholds(delayMinutes = 15, notifyDeparture = false),
            MonitorEventKind.ARRIVAL to MonitorThresholds(delayMinutes = 15, notifyArrival = false),
        )
        flags.forEach { (kind, thresholds) ->
            val gated = monitor.copy(thresholds = thresholds)
            assertFalse(
                NotificationDelivery.shouldDeliverTrainEvent(settings, gated, event(kind), true),
                "expected $kind to be gated by its flag",
            )
            MonitorEventKind.entries.filterNot { it == kind }.forEach { other ->
                // Other kinds keep their own default-on flags, except kinds
                // sharing the cancellation flag.
                val shared = (kind == MonitorEventKind.CANCELLATION && other == MonitorEventKind.PARTIAL_CANCELLATION) ||
                    (kind == MonitorEventKind.PARTIAL_CANCELLATION && other == MonitorEventKind.CANCELLATION)
                if (!shared && other !in setOf(MonitorEventKind.SCHEDULE, MonitorEventKind.STATUS, MonitorEventKind.ROUTE_CHANGED)) {
                    assertTrue(
                        NotificationDelivery.shouldDeliverTrainEvent(settings, gated, event(other), true),
                        "expected $other to stay enabled when $kind is disabled",
                    )
                }
            }
        }
    }

    @Test fun scheduleAndStatusHaveNoFlagAndFollowTrainSwitch() {
        // No supported flag exists for these kinds: only the installation
        // switch, the per-train switch and OS permission gate them.
        assertTrue(NotificationDelivery.eventFlagEnabled(monitor.thresholds, event(MonitorEventKind.SCHEDULE)))
        assertTrue(NotificationDelivery.eventFlagEnabled(monitor.thresholds, event(MonitorEventKind.STATUS)))
        assertTrue(NotificationDelivery.eventFlagEnabled(monitor.thresholds, event(MonitorEventKind.ROUTE_CHANGED)))
        val allFlagsOff = monitor.copy(
            thresholds = MonitorThresholds(
                delayMinutes = 15,
                notifyDelay = false,
                notifyPlatform = false,
                notifyCancellation = false,
                notifyDeparture = false,
                notifyArrival = false,
            ),
        )
        assertTrue(
            NotificationDelivery.shouldDeliverTrainEvent(settings, allFlagsOff, event(MonitorEventKind.SCHEDULE), true),
        )
        assertTrue(
            NotificationDelivery.shouldDeliverTrainEvent(settings, allFlagsOff, event(MonitorEventKind.STATUS), true),
        )
        assertTrue(
            NotificationDelivery.shouldDeliverTrainEvent(settings, allFlagsOff, event(MonitorEventKind.ROUTE_CHANGED), true),
        )
        val muted = allFlagsOff.copy(notificationsEnabled = false)
        assertFalse(
            NotificationDelivery.shouldDeliverTrainEvent(settings, muted, event(MonitorEventKind.SCHEDULE), true),
        )
    }

    @Test fun nullDelayThresholdDisablesDelayEventProduction() {
        // A null threshold means EvaluateTrainChanges produces no delay
        // events; the policy mapping stays consistent with production.
        val noThreshold = monitor.copy(thresholds = MonitorThresholds(delayMinutes = null))
        assertTrue(NotificationDelivery.eventFlagEnabled(noThreshold.thresholds, event(MonitorEventKind.DELAY)))
    }

    @Test fun strikeRequiresGlobalOptInAndPermission() {
        assertTrue(NotificationDelivery.shouldDeliverStrikeEvent(settings, true, true))
        assertFalse(NotificationDelivery.shouldDeliverStrikeEvent(settings.copy(notificationsEnabled = false), true, true))
        assertFalse(NotificationDelivery.shouldDeliverStrikeEvent(settings, false, true))
        assertFalse(NotificationDelivery.shouldDeliverStrikeEvent(settings, true, false))
        assertFalse(NotificationDelivery.shouldDeliverStrikeEvent(settings.copy(notificationsEnabled = false), false, false))
    }
}
