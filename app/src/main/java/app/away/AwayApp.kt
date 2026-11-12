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
    }

    companion object {
        const val CH_INDICATOR = "indicator"
        const val CH_URGENT = "urgent"
    }
}
