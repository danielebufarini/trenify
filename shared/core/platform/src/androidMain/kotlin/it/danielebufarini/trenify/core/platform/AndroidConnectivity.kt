package it.danielebufarini.trenify.core.platform

import android.content.Context
import android.app.job.JobService
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

fun createPlatformServices(context: Context): PlatformServices =
    PlatformServices.defaults().copy(
        connectivity = AndroidConnectivity(context.applicationContext),
        externalUrlLauncher = AndroidExternalUrlLauncher(context.applicationContext),
    )

fun createPlatformServices(
    context: Context,
    backgroundService: Class<out JobService>,
    requestNotificationPermission: suspend () -> Boolean,
    notificationChannelName: String = "Trenify updates",
): PlatformServices {
    val applicationContext = context.applicationContext
    return PlatformServices.defaults().copy(
        connectivity = AndroidConnectivity(applicationContext),
        notifications = AndroidNotificationPresenter(applicationContext, notificationChannelName),
        notificationPermission = AndroidNotificationPermission(applicationContext, requestNotificationPermission),
        backgroundScheduler = AndroidBackgroundScheduler(applicationContext, backgroundService),
        externalUrlLauncher = AndroidExternalUrlLauncher(applicationContext),
    )
}

private class AndroidConnectivity(context: Context) : Connectivity {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val state = MutableStateFlow(current())
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            state.value = if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                ConnectivityStatus.Available else ConnectivityStatus.Unavailable
        }
        override fun onLost(network: Network) { state.value = current() }
        override fun onUnavailable() { state.value = ConnectivityStatus.Unavailable }
    }
    init { manager.registerDefaultNetworkCallback(callback) }
    private fun current(): ConnectivityStatus =
        if (manager.getNetworkCapabilities(manager.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
            ConnectivityStatus.Available else ConnectivityStatus.Unavailable
    override fun status(): StateFlow<ConnectivityStatus> = state
    override fun close() { manager.unregisterNetworkCallback(callback) }
}
