package com.alpha.assistant.automations

import android.app.*
import android.content.*
import android.os.IBinder
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.alpha.assistant.MainActivity
import com.alpha.assistant.R

/** Alarm and system restore broadcasts reach only this explicit, non-exported component. */
class AutomationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        if(intent.action == AutomationRuntime.ALARM) AutomationRuntime.alarmReceived(context,intent,pending::finish)
        else AutomationRuntime.launch(context,pending::finish) { store -> AutomationRuntime.reconcile(context,store,true) }
    }
}
class AutomationGeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if(intent.action != "com.alpha.assistant.GEOFENCE") return
        val pending = goAsync()
        AutomationRuntime.geofence(context,intent,pending::finish)
    }
}
/** Android grants this listener separately. Only selected package IDs and event tokens leave it. */
class AutomationNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() { AutomationRuntime.refresh(this) }
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if(sbn == null || sbn.packageName == packageName || sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        AutomationRuntime.notification(this,sbn.packageName,sbn.key,sbn.postTime)
    }
    override fun onListenerDisconnected() {
        AutomationRuntime.launch(this) { store ->
            store.rules().filter { it.enabled && it.definition.type == "notification" }.forEach { store.attention(it.id,it.revision,"Слушатель уведомлений отключён Android. После восстановления доступа включи сценарий снова. Пропущенные уведомления не воспроизводятся.") }
        }
    }
}
/** User starts this monitor in resumed Alpha. It does not restart silently after force-stop or reboot. */
class ChargingMonitor : Service() {
    companion object { @Volatile var active = false; private set }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when(intent.action) {
                Intent.ACTION_POWER_CONNECTED -> AutomationRuntime.powerChanged(context,true)
                Intent.ACTION_POWER_DISCONNECTED -> AutomationRuntime.powerChanged(context,false)
            }
        }
    }
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("alpha-automation-monitor","Наблюдение питания",NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this,71,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        startForeground(7101,Notification.Builder(this,"alpha-automation-monitor").setSmallIcon(R.drawable.ic_alpha)
            .setContentTitle("Alpha: наблюдение питания").setContentText("Сценарии видят подключение и отключение зарядки. Остановить можно в Alpha.")
            .setOngoing(true).setContentIntent(open).build())
        active = true
        AutomationRuntime.launch(this) { store ->
            AutomationRuntime.charging(this)?.let { store.setState("power",if(it) "connected" else "disconnected") }
        }
        val filter = IntentFilter(Intent.ACTION_POWER_CONNECTED).apply { addAction(Intent.ACTION_POWER_DISCONNECTED) }
        if(android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(receiver,filter,Context.RECEIVER_NOT_EXPORTED)
        else { @Suppress("DEPRECATION") registerReceiver(receiver,filter) }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY
    override fun onDestroy() {
        active = false
        unregisterReceiver(receiver)
        AutomationRuntime.refresh(this)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
