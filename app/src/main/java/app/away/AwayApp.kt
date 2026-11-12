package app.away

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class AwayApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_INDICATOR, "Time away", NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_URGENT, "Urgent alerts", NotificationManager.IMPORTANCE_HIGH)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_RESTORED, "Held notifications", NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        const val CH_INDICATOR = "indicator"
        const val CH_URGENT = "urgent"
        const val CH_RESTORED = "restored"
    }
}
