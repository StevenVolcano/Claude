package io.terminus.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import io.terminus.app.R
import io.terminus.app.di.ActiveSessionHolder
import io.terminus.core.game.GameCommand
import io.terminus.core.game.GamePhase
import io.terminus.core.geo.LatLng
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * GPS-mode foreground service (ARCHITECTURE.md §1.2 `service`): requests GPS
 * updates every 2 s / 5 m from [LocationManager], forwards each fix into the active
 * [io.terminus.app.di.GameSession] as [GameCommand.GpsFix], and keeps a persistent
 * notification showing the current phase and game clock so the game survives
 * backgrounding. Started/stopped by the game screen when `playMode == GPS`.
 */
class GameForegroundService : Service(), LocationListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var requestingUpdates = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        val notification = buildNotification(
            getString(R.string.notification_starting),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        startLocationUpdates()
        observeSession()
        return START_STICKY
    }

    override fun onDestroy() {
        if (requestingUpdates) {
            locationManager().removeUpdates(this)
            requestingUpdates = false
        }
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- location

    private fun startLocationUpdates() {
        if (requestingUpdates) return
        val fineGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted) return
        try {
            locationManager().requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                UPDATE_INTERVAL_MILLIS,
                UPDATE_MIN_DISTANCE_METERS,
                this,
            )
            requestingUpdates = true
        } catch (_: SecurityException) {
            // Permission revoked between check and request; the game continues without fixes.
        } catch (_: IllegalArgumentException) {
            // No GPS provider on this device.
        }
    }

    override fun onLocationChanged(location: Location) {
        val session = ActiveSessionHolder.session ?: return
        session.send(
            GameCommand.GpsFix(
                playerId = ActiveSessionHolder.humanPlayerId,
                position = LatLng(location.latitude, location.longitude),
                accuracyMeters = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
                gameTimeMillis = session.state.value.gameTimeMillis,
            ),
        )
    }

    private fun locationManager(): LocationManager =
        getSystemService(Context.LOCATION_SERVICE) as LocationManager

    // ---------------------------------------------------------------- notification

    private fun observeSession() {
        val session = ActiveSessionHolder.session ?: return
        scope.launch {
            session.state.collect { state ->
                val phaseText = when (state.phase) {
                    GamePhase.SETUP -> getString(R.string.phase_setup)
                    GamePhase.HIDING -> getString(R.string.phase_hiding)
                    GamePhase.SEEKING -> getString(R.string.phase_seeking)
                    GamePhase.FINAL_APPROACH -> getString(R.string.phase_final_approach)
                    GamePhase.ROUND_END -> getString(R.string.phase_round_end)
                }
                val clock = formatClock(state.gameTimeMillis)
                notificationManager().notify(
                    NOTIFICATION_ID,
                    buildNotification(getString(R.string.notification_text, phaseText, clock)),
                )
                if (state.phase == GamePhase.ROUND_END) {
                    stopSelf()
                }
            }
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.description = getString(R.string.notification_channel_description)
        notificationManager().createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_terminus)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun notificationManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun formatClock(gameTimeMillis: Long): String {
        val totalSeconds = gameTimeMillis / 1000
        return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }

    companion object {
        private const val CHANNEL_ID = "terminus_game"
        private const val NOTIFICATION_ID = 1
        private const val UPDATE_INTERVAL_MILLIS = 2_000L
        private const val UPDATE_MIN_DISTANCE_METERS = 5f

        /** Starts the service (call only in GPS mode with an active session). */
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, GameForegroundService::class.java),
            )
        }

        /** Stops the service. */
        fun stop(context: Context) {
            context.stopService(Intent(context, GameForegroundService::class.java))
        }
    }
}
