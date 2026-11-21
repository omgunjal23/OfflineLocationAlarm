package com.example.locationalarm

import android.Manifest
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
    private var targetName: String = "Selected Target"

    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var isAlarmRinging = false

    companion object {
        const val CHANNEL_ID = "LocationAlarmChannel"
        const val NOTIFICATION_ID = 101
        const val ACTION_STOP = "ACTION_STOP_ALARM"
        const val ACTION_DISTANCE_UPDATE = "com.example.locationalarm.DISTANCE_UPDATE"
        const val EXTRA_DISTANCE_METERS = "EXTRA_DISTANCE_METERS"
        const val EXTRA_ALARM_STOPPED = "EXTRA_ALARM_STOPPED"
        const val PREFS_NAME = "LocationAlarmPrefs"
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

        // Stop any existing alarm sound/updates before applying updated target
        ringtone?.stop()
        vibrator?.cancel()
        isAlarmRinging = false
        locationManager.removeUpdates(this)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        targetLat = intent?.getDoubleExtra("LAT", prefs.getFloat("LAT", 0f).toDouble()) ?: 0.0
        targetLng = intent?.getDoubleExtra("LNG", prefs.getFloat("LNG", 0f).toDouble()) ?: 0.0
        targetRadius = intent?.getFloatExtra("RADIUS", prefs.getFloat("RADIUS", 500f)) ?: 500f
        targetName = intent?.getStringExtra("NAME") ?: prefs.getString("NAME", "Selected Target") ?: "Selected Target"

        // Save active state so reopening the app restores everything
        prefs.edit()
            .putBoolean("IS_ACTIVE", true)
            .putFloat("LAT", targetLat.toFloat())
            .putFloat("LNG", targetLng.toFloat())
            .putFloat("RADIUS", targetRadius)
            .putString("NAME", targetName)
            .apply()

        val notification = buildNotification("Searching for GPS satellites...")
        startForeground(NOTIFICATION_ID, notification)

        startOfflineGpsTracking()
        return START_STICKY
    }

    private fun startOfflineGpsTracking() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            stopSelf()
            return
        }

        // Immediately check last known GPS location so distance displays right away
        locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { lastLoc ->
            onLocationChanged(lastLoc)
        }

        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            3000L,
            5f,
            this
        )
    }

    override fun onLocationChanged(currentLocation: Location) {
        val targetLocation = Location("").apply {
            latitude = targetLat
            longitude = targetLng
        }

        val distanceInMeters = currentLocation.distanceTo(targetLocation)
        val formattedDistance = formatDistance(distanceInMeters)

        // Save latest distance to SharedPreferences for when app is reopened
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat("LAST_DISTANCE", distanceInMeters)
            .apply()

        // Broadcast live distance to MainActivity if open
        val updateIntent = Intent(ACTION_DISTANCE_UPDATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_DISTANCE_METERS, distanceInMeters)
        }
        sendBroadcast(updateIntent)

        // Update persistent notification
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(
            NOTIFICATION_ID,
            buildNotification("Remaining: $formattedDistance ($targetName)")
        )

        if (distanceInMeters <= targetRadius && !isAlarmRinging) {
            triggerLoudAlarm()
        }
    }

    private fun formatDistance(meters: Float): String {
        return if (meters >= 1000f) {
            String.format("%.2f km (%d m)", meters / 1000f, meters.toInt())
        } else {
            "${meters.toInt()} meters"
        }
    }

    private fun triggerLoudAlarm() {
        isAlarmRinging = true

        val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

        ringtone = RingtoneManager.getRingtone(applicationContext, alarmUri).apply {
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

        // Clear active alarm state
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("IS_ACTIVE", false)
            .remove("LAST_DISTANCE")
            .apply()

        // Notify MainActivity if open
        val stoppedIntent = Intent(ACTION_DISTANCE_UPDATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_ALARM_STOPPED, true)
        }
        sendBroadcast(stoppedIntent)

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(contentText: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
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
        ringtone?.stop()
        vibrator?.cancel()
        locationManager.removeUpdates(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
