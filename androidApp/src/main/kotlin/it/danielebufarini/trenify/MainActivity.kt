package it.danielebufarini.trenify

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import it.danielebufarini.trenify.app.AppGraph
import it.danielebufarini.trenify.navigation.TrenifyAndroidShell
import it.danielebufarini.trenify.app.createAndroidAppGraph
import it.danielebufarini.trenify.app.createRootComponent
import it.danielebufarini.trenify.app.onApplicationBackgrounded
import it.danielebufarini.trenify.app.onApplicationForegrounded
import it.danielebufarini.trenify.app.notificationDestination
import kotlinx.coroutines.CompletableDeferred

class MainActivity : ComponentActivity() {
    private lateinit var appGraph: AppGraph
    private var pendingNotificationPermission: CompletableDeferred<Boolean>? = null
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pendingNotificationPermission?.complete(granted)
        pendingNotificationPermission = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        appGraph = createAndroidAppGraph(
            applicationContext,
            ::requestNotificationPermission,
            // Platform-locale channel name from Android resources (T7.14-C);
            // train/strike wording stays in the shared catalog.
            getString(R.string.notification_channel_updates),
            instrumentation = debugLatencyInstrumentation(applicationContext),
        )
        // Cold launch (T7.13-F/H): a tap destination in the launching intent
        // is offered to the graph handoff and consumed by the new shared
        // root on top of restored navigation. Malformed/missing extras
        // decode to null and are safely ignored. The Activity never creates
        // a second navigation tree and interprets no business policy.
        val rootComponent = appGraph.createRootComponent(this, intent.notificationDestination())
        setContent {
            TrenifyAndroidShell(rootComponent)
        }
    }

    /**
     * Warm launch (T7.13-F): with singleTop, tap intents for an already
     * running Activity arrive here and are forwarded into the existing
     * shared root. Duplicate OS deliveries are idempotent in the shared
     * routing entry point.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (::appGraph.isInitialized) {
            intent.notificationDestination()?.let(appGraph::deliverNotificationDestination)
        }
    }

    override fun onStart() {
        super.onStart()
        appGraph.onApplicationForegrounded()
    }

    override fun onStop() {
        appGraph.onApplicationBackgrounded()
        super.onStop()
    }

    override fun onDestroy() {
        appGraph.close()
        super.onDestroy()
    }

    private suspend fun requestNotificationPermission(): Boolean {
        pendingNotificationPermission?.let { return it.await() }
        val result = CompletableDeferred<Boolean>()
        pendingNotificationPermission = result
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        return result.await()
    }
}
