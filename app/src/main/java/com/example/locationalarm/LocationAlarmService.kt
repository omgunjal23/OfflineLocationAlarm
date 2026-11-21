package com.example.locationalarm

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.*
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat

class LocationAlarmService : Service(), LocationListener {

    private lateinit var locationManager: LocationManager
    private var targetLat: Double = 0.0
    private var targetLng: Double = 0.0
    private var targetRadius: Float = 500f

    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var isAlarmRinging = false

    companion object {
        const val CHANNEL_ID = "LocationAlarmChannel"
        const val NOTIFICATION_ID = 101
        const val ACTION_STOP = "ACTION_STOP_ALARM"
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopAlarmAndService()
            return START_NOT_STICKY
        }

        // If the system restarts the service without data, stop instead of using (0,0)
        if (intent == null || !intent.hasExtra("LAT") || !intent.hasExtra("LNG")) {
            stopSelf()
            return START_NOT_STICKY
        }

        targetLat = intent.getDoubleExtra("LAT", 0.0)
        targetLng = intent.getDoubleExtra("LNG", 0.0)
        targetRadius = intent.getFloatExtra("RADIUS", 500f)

        val notification = buildNotification("Searching for GPS satellites...")
        startForeground(NOTIFICATION_ID, notification)

        startOfflineGpsTracking()
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startOfflineGpsTracking() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            stopSelf()
            return
        }

        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            3000L,
            10f,
            this
        )

        // Check immediately in case the user is already inside the radius
        locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let {
            onLocationChanged(it)
        }
    }

    override fun onLocationChanged(currentLocation: Location) {
        val targetLocation = Location("").apply {
            latitude = targetLat
            longitude = targetLng
        }

        val distanceInMeters = currentLocation.distanceTo(targetLocation)

        if (!isAlarmRinging) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(
                NOTIFICATION_ID,
                buildNotification("Distance remaining: ${distanceInMeters.toInt()} meters")
            )
        }

        if (distanceInMeters <= targetRadius && !isAlarmRinging) {
            triggerLoudAlarm()
        }
    }

    // Required on Android 7-10 where these have no default implementation
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    private fun triggerLoudAlarm() {
        isAlarmRinging = true

        val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

        ringtone = RingtoneManager.getRingtone(applicationContext, alarmUri)?.apply {
            audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                isLooping = true
            }
            play()
        }

        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        val pattern = longArrayOf(0, 1000, 500, 1000)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(pattern, 0)
        }

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(
            NOTIFICATION_ID,
            buildNotification("DESTINATION REACHED! Tap Stop to dismiss alarm.")
        )
    }

    private fun stopAlarmAndService() {
        isAlarmRinging = false
        ringtone?.stop()
        vibrator?.cancel()
        locationManager.removeUpdates(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(contentText: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingOpenIntent = PendingIntent.getActivity(
            this, 0, openAppIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, LocationAlarmService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStopIntent = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Location Alarm Active")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentIntent(pendingOpenIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Alarm", pendingStopIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Offline Location Alarm",
                NotificationManager.IMPORTANCE_HIGH
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        stopAlarmAndService()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
