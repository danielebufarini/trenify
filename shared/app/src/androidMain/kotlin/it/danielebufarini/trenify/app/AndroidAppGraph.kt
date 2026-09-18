package it.danielebufarini.trenify.app

import android.content.Context
import androidx.activity.ComponentActivity
import com.arkivanov.decompose.defaultComponentContext
import it.danielebufarini.trenify.core.database.AndroidDatabaseDriverFactory
import it.danielebufarini.trenify.core.network.createPlatformHttpClientEngine
import it.danielebufarini.trenify.core.network.RequestInstrumentation
import it.danielebufarini.trenify.core.platform.ApplicationState
import it.danielebufarini.trenify.core.platform.createPlatformServices

fun createAndroidAppGraph(
    context: Context,
    requestNotificationPermission: suspend () -> Boolean = { false },
    notificationChannelName: String = "Trenify updates",
    instrumentation: RequestInstrumentation = RequestInstrumentation.None,
): AppGraph = AppGraph.create(
    sqlDriver = AndroidDatabaseDriverFactory(context).create(),
    httpClientEngine = createPlatformHttpClientEngine(),
    platformServices = createPlatformServices(
        context,
        MonitoringJobService::class.java,
        requestNotificationPermission,
        notificationChannelName,
    ),
    instrumentation = instrumentation,
)

fun AppGraph.createRootComponent(
    activity: ComponentActivity,
    pendingDestination: it.danielebufarini.trenify.core.platform.NotificationDestination? = null,
): RootComponent =
    createRootComponent(activity.defaultComponentContext(), pendingDestination)

fun AppGraph.onApplicationForegrounded() {
    onApplicationStateChanged(ApplicationState.Foreground)
}

fun AppGraph.onApplicationBackgrounded() {
    onApplicationStateChanged(ApplicationState.Background)
}
