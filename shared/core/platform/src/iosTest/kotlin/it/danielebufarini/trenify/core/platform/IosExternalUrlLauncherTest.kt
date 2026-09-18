package it.danielebufarini.trenify.core.platform

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class IosExternalUrlLauncherTest {
    // These paths return before any UIKit interaction, so they are deterministic
    // on the simulator test host. Actually opening an HTTPS URL requires a real
    // device/simulator foreground app and is verified manually, not here.
    @Test fun rejectsMalformedUrlWithoutOpening() = runTest {
        assertFalse(IosExternalUrlLauncher().open("not a url"))
    }

    @Test fun rejectsNonHttpsWithoutOpening() = runTest {
        assertFalse(IosExternalUrlLauncher().open("http://www.trenitalia.com"))
    }
}
