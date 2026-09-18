package it.danielebufarini.trenify.core.platform

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlin.math.max
import kotlin.time.Clock

internal class AndroidNotificationPresenter(
    context: Context,
    channelName: String,
) : NotificationPresenter {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    override suspend fun show(message: NotificationMessage) {
        val icon = appContext.applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_dialog_info
        // Real content intent (T7.13-F): carries only the minimal validated
        // destination payload from the shared tap contract. Immutable and
        // update-safe flags; the stable per-destination request code plus the
        // distinct data URI keep different notifications from overwriting
        // each other's destinations. The Activity interprets no business
        // policy: it decodes the transport form and forwards it to the
        // shared root.
        val tap = android.content.Intent(NotificationTap.ACTION).apply {
            setPackage(appContext.packageName)
            data = android.net.Uri.parse(NotificationTap.dataUri(message.destination))
            message.destination?.let { destination ->
                NotificationTap.extras(destination).forEach { (key, value) -> putExtra(key, value) }
            }
        }
        val content = android.app.PendingIntent.getActivity(
            appContext,
            NotificationTap.requestCode(message.destination, message.id),
            tap,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(message.title)
            .setContentText(message.body)
            .setStyle(Notification.BigTextStyle().bigText(message.body))
            .setContentIntent(content)
            .setAutoCancel(true)
            .build()
        manager.notify(message.id.hashCode(), notification)
    }

    companion object {
        internal const val CHANNEL_ID = "train-monitoring"
    }
}

internal class AndroidNotificationPermission(
    private val context: Context,
    private val requestPermission: suspend () -> Boolean,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : NotificationPermission {
    private val manager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    private fun runtimeGranted(): Boolean = sdkInt < 33 ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /**
     * Effective delivery state (T7.7): the runtime permission (API 33+ only;
     * it does not exist on API 31/32) plus the system-level application and
     * channel notification state. A disabled app or channel denies delivery
     * on every API level.
     */
    override suspend fun effective(): EffectiveNotificationPermission {
        val channelEnabled = manager.getNotificationChannel(AndroidNotificationPresenter.CHANNEL_ID)?.importance !=
            NotificationManager.IMPORTANCE_NONE
        return androidEffectiveNotificationPermission(
            sdkInt = sdkInt,
            runtimeGranted = runtimeGranted(),
            appNotificationsEnabled = manager.areNotificationsEnabled(),
            channelNotificationsEnabled = channelEnabled,
        )
    }

    override suspend fun isGranted(): Boolean = effective() == EffectiveNotificationPermission.GRANTED

    override suspend fun request(): Boolean = if (isGranted()) true else requestPermission()
}

internal class AndroidBackgroundScheduler(
    context: Context,
    serviceClass: Class<out JobService>,
) : BackgroundScheduler {
    private val scheduler = context.getSystemService(JobScheduler::class.java)
    private val component = ComponentName(context, serviceClass)

    override suspend fun schedule(task: BackgroundTask) {
        val delay = max(0L, task.earliestStartEpochMillis - Clock.System.now().toEpochMilliseconds())
        val info = JobInfo.Builder(task.id.hashCode(), component)
            .setMinimumLatency(delay)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .build()
        scheduler.schedule(info)
    }

    override suspend fun cancel(taskId: String) {
        scheduler.cancel(taskId.hashCode())
    }
}
