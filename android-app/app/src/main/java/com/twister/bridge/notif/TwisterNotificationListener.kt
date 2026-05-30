package com.twister.bridge.notif

import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.twister.bridge.protocol.TwisterNotifSource
import com.twister.bridge.service.TwisterForegroundService

class TwisterNotificationListener : NotificationListenerService() {

    private val allowedPackages = setOf(
        "com.whatsapp",
        "org.telegram.messenger",
        "com.google.android.dialer"
    )

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val source = mapSource(sbn.packageName) ?: run {
            return
        }

        val extras = sbn.notification.extras
        val title = extras.getString("android.title") ?: "(sin titulo)"
        val text = extras.getCharSequence("android.text")?.toString() ?: ""

        Log.i("TwisterNotif", "Notif ${sbn.packageName}: $title / $text")

        val intent = Intent(TwisterForegroundService.ACTION_FORWARD_NOTIF)
            .setPackage(packageName)
            .putExtra(TwisterForegroundService.EXTRA_NOTIF_SOURCE, source.raw.toInt())
            .putExtra(TwisterForegroundService.EXTRA_NOTIF_TITLE, title.take(32))
            .putExtra(TwisterForegroundService.EXTRA_NOTIF_BODY, text.take(64))
        sendBroadcast(intent)
    }

    private fun mapSource(packageName: String): TwisterNotifSource? {
        if (packageName !in allowedPackages) {
            return null
        }

        return when (packageName) {
            "com.whatsapp" -> TwisterNotifSource.WHATSAPP
            "org.telegram.messenger" -> TwisterNotifSource.TELEGRAM
            "com.google.android.dialer" -> TwisterNotifSource.CALL
            else -> TwisterNotifSource.CALL
        }
    }
}
