package com.example.locationalarm

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.preference.PreferenceManager
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class MainActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private lateinit var tvSelectedCoords: TextView
    private lateinit var tvRadiusLabel: TextView
    private lateinit var seekBarRadius: SeekBar
    private lateinit var tvStatus: TextView
    private lateinit var btnStartAlarm: Button

    private var selectedPoint: GeoPoint? = null
    private var selectedTargetName: String = ""
    private var selectedRadiusMeters: Float = 500f

    private var targetMarker: Marker? = null
    private var radiusCircle: Polygon? = null
    private var myLocationOverlay: MyLocationNewOverlay? = null

    data class PlaceSuggestion(val displayName: String, val lat: Double, val lon: Double)

    private var searchJob: Job? = null
    private var currentSuggestions: List<PlaceSuggestion> = emptyList()

    // Live receiver for distance updates from LocationAlarmService
    private val distanceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == LocationAlarmService.ACTION_DISTANCE_UPDATE) {
                val stopped = intent.getBooleanExtra(LocationAlarmService.EXTRA_ALARM_STOPPED, false)
                if (stopped) {
                    tvStatus.text = "Status: Idle"
                    btnStartAlarm.text = "Start Alarm"
                } else {
                    val meters = intent.getFloatExtra(LocationAlarmService.EXTRA_DISTANCE_METERS, -1f)
                    if (meters >= 0f) {
                        updateActiveStatusText(meters)
                    }
                }
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        if (fineGranted) {
            enableUserLocationOnMap()
            if (selectedPoint != null) {
                checkBackgroundPermissionAndStart()
            }
        } else {
            Toast.makeText(this, "Fine Location permission is required!", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val osmConfig = Configuration.getInstance()
        osmConfig.load(
            applicationContext,
            PreferenceManager.getDefaultSharedPreferences(applicationContext)
        )
        osmConfig.userAgentValue = "OfflineLocationAlarmAndroid/1.0"

        setContentView(R.layout.activity_main)

        mapView = findViewById(R.id.mapView)
        tvSelectedCoords = findViewById(R.id.tvSelectedCoords)
        tvRadiusLabel = findViewById(R.id.tvRadiusLabel)
        seekBarRadius = findViewById(R.id.seekBarRadius)
        tvStatus = findViewById(R.id.tvStatus)
        btnStartAlarm = findViewById(R.id.btnStartAlarm)

        setupMap()
        setupRadiusSlider()
        setupSearchBarWithAutocomplete()

        btnStartAlarm.setOnClickListener {
            validateAndStart()
        }

        findViewById<Button>(R.id.btnStopAlarm).setOnClickListener {
            val stopIntent = Intent(this, LocationAlarmService::class.java).apply {
                action = LocationAlarmService.ACTION_STOP
            }
            startService(stopIntent)
            tvStatus.text = "Status: Alarm Stopped"
            btnStartAlarm.text = "Start Alarm"
        }

        // Restore active alarm & target location if already running
        restoreSavedAlarmState()

        requestInitialPermissions()
    }

    private fun restoreSavedAlarmState() {
        val prefs = getSharedPreferences(LocationAlarmService.PREFS_NAME, Context.MODE_PRIVATE)
        val isActive = prefs.getBoolean("IS_ACTIVE", false)

        if (isActive) {
            val lat = prefs.getFloat("LAT", 0f).toDouble()
            val lng = prefs.getFloat("LNG", 0f).toDouble()
            val radius = prefs.getFloat("RADIUS", 500f)
            val name = prefs.getString("NAME", "") ?: ""
            val lastDistance = prefs.getFloat("LAST_DISTANCE", -1f)

            selectedRadiusMeters = radius
            seekBarRadius.progress = (radius.toInt() - 100).coerceIn(0, 1900)
            tvRadiusLabel.text = "Alarm Radius: ${radius.toInt()} meters"

            val savedPoint = GeoPoint(lat, lng)
            updateTargetLocation(savedPoint, name)

            // Center map on the active destination so user sees their pin immediately
            mapView.controller.setCenter(savedPoint)
            mapView.controller.setZoom(15.0)

            btnStartAlarm.text = "Update Alarm"
            updateActiveStatusText(if (lastDistance >= 0f) lastDistance else null)
        }
    }

    private fun updateActiveStatusText(remainingMeters: Float?) {
        val point = selectedPoint ?: return
        val distStr = if (remainingMeters != null) {
            if (remainingMeters >= 1000f) {
                String.format("%.2f km (%d m)", remainingMeters / 1000f, remainingMeters.toInt())
            } else {
                "${remainingMeters.toInt()} meters"
            }
        } else {
            "Calculating GPS..."
        }

        tvStatus.text = String.format(
            "Status: Active | Remaining: %s\nTarget: %.4f, %.4f (%dm)",
            distStr,
            point.latitude,
            point.longitude,
            selectedRadiusMeters.toInt()
        )
    }

    private fun setupMap() {
        val esriStreetMap = object : OnlineTileSourceBase(
            "EsriWorldStreetMap",
            0, 19, 256, ".jpg",
            arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/")
        ) {
            override fun getTileURLString(pMapTileIndex: Long): String {
                val z = MapTileIndex.getZoom(pMapTileIndex)
                val x = MapTileIndex.getX(pMapTileIndex)
                val y = MapTileIndex.getY(pMapTileIndex)
                return "$baseUrl$z/$y/$x"
            }
        }

        mapView.setTileSource(esriStreetMap)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(15.0)
        mapView.controller.setCenter(GeoPoint(19.9975, 73.7898))

        val mapEventsReceiver = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                updateTargetLocation(p, "")
                return true
            }

            override fun longPressHelper(p: GeoPoint): Boolean {
                updateTargetLocation(p, "")
                return true
            }
        }
        mapView.overlays.add(MapEventsOverlay(mapEventsReceiver))
    }

    private fun setupSearchBarWithAutocomplete() {
        val etSearchPlace = findViewById<AutoCompleteTextView>(R.id.etSearchPlace)
        val btnSearch = findViewById<Button>(R.id.btnSearch)

        val adapter = ArrayAdapter<String>(
            this,
            android.R.layout.simple_dropdown_item_1line,
            mutableListOf()
        )
        etSearchPlace.setAdapter(adapter)

        etSearchPlace.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim() ?: ""
                searchJob?.cancel()

                if (query.length < 3) return

                searchJob = lifecycleScope.launch {
                    delay(450L)
                    val results = fetchPlacesFromNominatim(query, limit = 5)
                    if (results.isNotEmpty()) {
                        currentSuggestions = results
                        adapter.clear()
                        adapter.addAll(results.map { it.displayName })
                        adapter.notifyDataSetChanged()
                        if (etSearchPlace.hasFocus()) {
                            etSearchPlace.showDropDown()
                        }
                    }
                }
            }
        })

        etSearchPlace.setOnItemClickListener { _, _, position, _ ->
            if (position in currentSuggestions.indices) {
                val chosen = currentSuggestions[position]
                hideKeyboard(etSearchPlace)
                jumpToPlace(chosen)
            }
        }

        btnSearch.setOnClickListener {
            val query = etSearchPlace.text.toString().trim()
            if (query.isNotEmpty()) {
                hideKeyboard(etSearchPlace)
                lifecycleScope.launch {
                    Toast.makeText(this@MainActivity, "Searching...", Toast.LENGTH_SHORT).show()
                    val results = fetchPlacesFromNominatim(query, limit = 1)
                    if (results.isNotEmpty()) {
                        jumpToPlace(results[0])
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            "Place not found. Try adding city name.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }

        etSearchPlace.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                btnSearch.performClick()
                true
            } else {
                false
            }
        }
    }

    private suspend fun fetchPlacesFromNominatim(query: String, limit: Int): List<PlaceSuggestion> =
        withContext(Dispatchers.IO) {
            val list = mutableListOf<PlaceSuggestion>()
            try {
                val encodedQuery = URLEncoder.encode(query, "UTF-8")
                val url = URL("https://nominatim.openstreetmap.org/search?q=$encodedQuery&format=json&limit=$limit")

                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "OfflineLocationAlarmAndroid/1.0")
                    connectTimeout = 8000
                    readTimeout = 8000
                }

                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonArray = JSONArray(responseText)

                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val displayName = obj.optString("display_name", query)
                    val lat = obj.getDouble("lat")
                    val lon = obj.getDouble("lon")
                    list.add(PlaceSuggestion(displayName, lat, lon))
                }
            } catch (_: Exception) {
            }
            list
        }

    private fun jumpToPlace(place: PlaceSuggestion) {
        val foundPoint = GeoPoint(place.lat, place.lon)
        myLocationOverlay?.disableFollowLocation()
        mapView.controller.animateTo(foundPoint, 15.5, 1000L)

        val shortName = place.displayName.split(",").take(2).joinToString(",")
        updateTargetLocation(foundPoint, shortName)
    }

    private fun hideKeyboard(view: AutoCompleteTextView) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun enableUserLocationOnMap() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        val prefs = getSharedPreferences(LocationAlarmService.PREFS_NAME, Context.MODE_PRIVATE)
        val isAlarmActive = prefs.getBoolean("IS_ACTIVE", false)

        if (myLocationOverlay == null) {
            myLocationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(this), mapView).apply {
                enableMyLocation()
                // Only auto-pan to user's current position if there isn't an active alarm pin already shown
                if (!isAlarmActive) {
                    enableFollowLocation()
                }
            }
            mapView.overlays.add(myLocationOverlay)
        }
    }

    private fun updateTargetLocation(point: GeoPoint, placeName: String) {
        selectedPoint = point
        selectedTargetName = placeName

        tvSelectedCoords.text = if (placeName.isNotEmpty()) {
            String.format("%s (%.4f, %.4f)", placeName, point.latitude, point.longitude)
        } else {
            String.format("Target: %.5f, %.5f", point.latitude, point.longitude)
        }

        if (targetMarker == null) {
            targetMarker = Marker(mapView).apply {
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = placeName.ifEmpty { "Alarm Destination" }
            }
            mapView.overlays.add(targetMarker)
        }
        targetMarker?.position = point
        targetMarker?.title = placeName.ifEmpty { "Alarm Destination" }

        drawRadiusCircle(point, selectedRadiusMeters.toDouble())
        showEstimatedDistanceImmediately(point)
        mapView.invalidate()
    }

    // Shows immediate distance preview in the app right after picking a point
    private fun showEstimatedDistanceImmediately(targetPoint: GeoPoint) {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val lastLoc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

        if (lastLoc != null) {
            val targetLoc = Location("").apply {
                latitude = targetPoint.latitude
                longitude = targetPoint.longitude
            }
            val meters = lastLoc.distanceTo(targetLoc)
            val prefs = getSharedPreferences(LocationAlarmService.PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.getBoolean("IS_ACTIVE", false)) {
                updateActiveStatusText(meters)
            } else {
                val distStr = if (meters >= 1000f) {
                    String.format("%.2f km (%d m)", meters / 1000f, meters.toInt())
                } else {
                    "${meters.toInt()} meters"
                }
                tvStatus.text = "Status: Ready | Distance from you: $distStr"
            }
        }
    }

    private fun drawRadiusCircle(center: GeoPoint, radiusInMeters: Double) {
        radiusCircle?.let { mapView.overlays.remove(it) }

        radiusCircle = Polygon().apply {
            points = Polygon.pointsAsCircle(center, radiusInMeters)
            fillPaint.color = Color.argb(50, 33, 150, 243)
            outlinePaint.color = Color.rgb(33, 150, 243)
            outlinePaint.strokeWidth = 4f
        }
        mapView.overlays.add(radiusCircle)
        mapView.invalidate()
    }

    private fun setupRadiusSlider() {
        seekBarRadius.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                selectedRadiusMeters = (progress + 100).toFloat()
                tvRadiusLabel.text = "Alarm Radius: ${selectedRadiusMeters.toInt()} meters"

                selectedPoint?.let { point ->
                    drawRadiusCircle(point, selectedRadiusMeters.toDouble())
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun requestInitialPermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    private fun validateAndStart() {
        if (selectedPoint == null) {
            Toast.makeText(this, "Tap on the map to pick a destination first!", Toast.LENGTH_SHORT).show()
            return
        }

        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Toast.makeText(this, "Please turn ON Location/GPS", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestInitialPermissions()
            return
        }

        checkBackgroundPermissionAndStart()
    }

    private fun checkBackgroundPermissionAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val bgGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            if (!bgGranted) {
                AlertDialog.Builder(this)
                    .setTitle("Background Location Needed")
                    .setMessage("Select 'Allow all the time' on the next screen so the alarm triggers while your screen is locked.")
                    .setPositiveButton("Open Settings") { _, _ ->
                        requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 200)
                    }
                    .setNegativeButton("Start Anyway") { _, _ ->
                        startAlarmService()
                    }
                    .show()
                retur
