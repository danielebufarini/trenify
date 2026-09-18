package it.danielebufarini.trenify.feature.strikes

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import it.danielebufarini.trenify.core.domain.EvaluateServiceStrikeImpact
import it.danielebufarini.trenify.core.domain.LoadStrikes
import it.danielebufarini.trenify.core.domain.OpenStrikeReference
import it.danielebufarini.trenify.core.domain.ServiceStrikeContext
import it.danielebufarini.trenify.core.domain.StrikeImpact
import it.danielebufarini.trenify.core.domain.StrikeInformationPolicy
import it.danielebufarini.trenify.core.model.DataFreshness
import it.danielebufarini.trenify.core.testing.FakeStrikeRepository
import it.danielebufarini.trenify.core.testing.MutableClock
import it.danielebufarini.trenify.core.testing.testStrike
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T7.12-D official strike/source reference actions: only actually available
 * references validated by the dedicated policy launch through the injected
 * URL abstraction; absence is honest; a URL never promotes impact.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StrikeReferenceActionTest {
    private fun kotlinx.coroutines.test.TestScope.component(
        openUrl: (suspend (String) -> Boolean)?,
        policy: StrikeInformationPolicy = StrikeInformationPolicy(),
    ) = DefaultAlertsTabComponent(
        DefaultComponentContext(LifecycleRegistry()),
        LoadStrikes(FakeStrikeRepository()),
        dispatcher = StandardTestDispatcher(testScheduler),
        openStrikeReference = openUrl?.let { OpenStrikeReference(policy, it) },
        informationPolicy = policy,
    )

    @Test
    fun officialReferenceIsLaunchableWhileUntrustedIsNot() = runTest {
        val component = component(openUrl = { true })
        assertTrue(component.canOpenReference("https://scioperi.mit.gov.it/8479"))
        assertFalse(component.canOpenReference("https://www.trenitalia.com"))
        assertFalse(component.canOpenReference("https://www.italotreno.com"))
        assertFalse(component.canOpenReference("http://scioperi.mit.gov.it/8479"))
        assertFalse(component.canOpenReference("not a url"))
        assertFalse(component.canOpenReference(null))
        assertFalse(component.canOpenReference(""))
    }

    @Test
    fun successfulOpenLaunchesTheActualTarget() = runTest {
        val lifecycle = LifecycleRegistry()
        val opened = mutableListOf<String>()
        val component = DefaultAlertsTabComponent(
            DefaultComponentContext(lifecycle),
            LoadStrikes(FakeStrikeRepository()),
            dispatcher = StandardTestDispatcher(testScheduler),
            openStrikeReference = OpenStrikeReference { url -> opened += url; true },
        )
        try {
            runCurrent()
            component.openReference("https://scioperi.mit.gov.it/8479")
            runCurrent()
            assertEquals(listOf("https://scioperi.mit.gov.it/8479"), opened)
            assertFalse(component.state.value.referenceFailed)
            assertFalse(component.state.value.referenceInProgress)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test
    fun rejectedTargetNeverReachesTheLauncherAndFailsTruthfully() = runTest {
        val lifecycle = LifecycleRegistry()
        var calls = 0
        val component = DefaultAlertsTabComponent(
            DefaultComponentContext(lifecycle),
            LoadStrikes(FakeStrikeRepository()),
            dispatcher = StandardTestDispatcher(testScheduler),
            openStrikeReference = OpenStrikeReference { calls++; true },
        )
        try {
            runCurrent()
            component.openReference("https://www.trenitalia.com")
            runCurrent()
            assertEquals(0, calls)
            assertTrue(component.state.value.referenceFailed)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test
    fun failedLaunchIsReportedWithoutFabrication() = runTest {
        val lifecycle = LifecycleRegistry()
        val component = DefaultAlertsTabComponent(
            DefaultComponentContext(lifecycle),
            LoadStrikes(FakeStrikeRepository()),
            dispatcher = StandardTestDispatcher(testScheduler),
            openStrikeReference = OpenStrikeReference { false },
        )
        try {
            runCurrent()
            component.openReference("https://scioperi.mit.gov.it/8479")
            runCurrent()
            assertTrue(component.state.value.referenceFailed)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test
    fun missingOpenerIsANoOp() = runTest {
        val lifecycle = LifecycleRegistry()
        val component = DefaultAlertsTabComponent(
            DefaultComponentContext(lifecycle),
            LoadStrikes(FakeStrikeRepository()),
            dispatcher = StandardTestDispatcher(testScheduler),
            openStrikeReference = null,
        )
        try {
            runCurrent()
            component.openReference("https://scioperi.mit.gov.it/8479")
            runCurrent()
            assertFalse(component.state.value.referenceFailed)
            assertFalse(component.state.value.referenceInProgress)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test
    fun referenceAvailabilityNeverPromotesImpact() {
        val policy = StrikeInformationPolicy()
        assertTrue(policy.officialReferenceUsable(testStrike.source.url))
        val assessment = EvaluateServiceStrikeImpact().invoke(
            ServiceStrikeContext(
                testStrike.start,
                testStrike.end,
                testStrike.operators.singleOrNull(),
            ),
            testStrike,
            DataFreshness.Fresh(MutableClock().now(), null),
        )
        // A usable official URL with no verified confirmation stays below
        // confirmation: temporal overlap alone is never certainty.
        assertTrue(assessment.impact == StrikeImpact.LIKELY || assessment.impact == StrikeImpact.POTENTIAL)
    }
}
