package com.medansafe.app

import android.Manifest
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.*
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.CompoundButton
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColorInt
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*
import com.google.android.gms.tasks.Task
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.QuerySnapshot
import com.medansafe.app.databinding.ActivityHomeBinding
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.*

class HomeActivity : AppCompatActivity(), SensorEventListener {

    internal lateinit var binding: ActivityHomeBinding
    private lateinit var auth: FirebaseAuth
    private val db = FirebaseFirestore.getInstance()
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var lastKnownLocation: Location? = null

    private lateinit var myLocationOverlay: MyLocationNewOverlay
    private lateinit var bottomSheetBehavior: BottomSheetBehavior<View>
    private lateinit var vehicleSheetBehavior: BottomSheetBehavior<View>

    private var compassMarker: Marker? = null

    // SOS
    private var hasEmergencyContacts = false
    private var hasPhoneNumber       = false

    // ── KENDARAAN ──────────────────────────────────────────────────────────────
    // "Kereta" → OSRM "bike"    : gang, cycleway, jalan kecil
    // "Mobil"  → OSRM "driving" : jalan utama kendaraan roda 4+
    var selectedVehicle = "Kereta"

    // ── SMOOTH MARKER 60fps ────────────────────────────────────────────────────
    private var displayedLat   = 0.0
    private var displayedLng   = 0.0
    private var targetLat      = 0.0
    private var targetLng      = 0.0
    private var isFirstLocation = true
    private var currentAzimuth  = 0f

    private val markerLerpHandler  = Handler(Looper.getMainLooper())
    private val markerLerpInterval = 16L      // ~60 fps
    private val lerpFactor = 0.08f

    private val markerLerpRunnable = object : Runnable {
        override fun run() {
            val dLat = targetLat - displayedLat
            val dLng = targetLng - displayedLng
            if (abs(dLat) > 1e-9 || abs(dLng) > 1e-9) {
                displayedLat += dLat * lerpFactor
                displayedLng += dLng * lerpFactor
                val geo = GeoPoint(displayedLat, displayedLng)
                compassMarker?.position = geo
                userAreaOverlay?.points  = Polygon.pointsAsCircle(geo, 80.0)
                compassMarker?.rotation  = currentAzimuth - binding.map.mapOrientation
                binding.map.postInvalidate()
            }
            markerLerpHandler.postDelayed(this, markerLerpInterval)
        }
    }

    private var userAreaOverlay: Polygon? = null
    private val incidentOverlays = mutableListOf<Overlay>()
    private var lastIncidentSnapshot: QuerySnapshot? = null

    // ── RUTE ──────────────────────────────────────────────────────────────────
    private var roadOverlay: Polyline? = null
    private var fullRoutePoints    = listOf<GeoPoint>()
    private var routeCutIndex      = 0
    private var offRouteFrameCount = 0
    private var lastOffRouteMs     = 0L

    private var pendingDestination: GeoPoint? = null
    private var pendingDestName:    String?   = null
    private var lastCalculatedRiskData: Triple<Int, JSONArray, String>? = null

    private var activeDestLat = 0.0
    private var activeDestLng = 0.0

    // Simpan data FASTEST untuk restore
    private var fastestRoutePoints = listOf<GeoPoint>()
    private var fastestRiskData:    Triple<Int, JSONArray, String>? = null
    private var fastestDist        = 0.0
    private var fastestDur         = 0
    private var fastestStartAddr   = ""
    private var fastestDestName    = ""
    private var fastestScore       = 100

    private var currentRouteMode = "FASTEST"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    // ── SENSOR ────────────────────────────────────────────────────────────────
    private lateinit var sensorManager: SensorManager
    private var accelerometerSensor: Sensor? = null
    private var magnetometerSensor:  Sensor? = null
    private val gravityRaw     = FloatArray(3)
    private val geomagneticRaw = FloatArray(3)
    private val lpAlpha        = 0.07f
    private var smoothedAzimuth = 0f

    private var isHeatmapActive    = false
    private var lastOverlayRefresh = 0L
    private var backPressedTime: Long = 0

    // ── FLAG CRASH-GUARD ──────────────────────────────────────────────────────
    // Mencegah double-commit fragment yang menyebabkan crash / force-close
    private var isIncidentSheetShowing = false
    private var isVehicleSheetShowing  = false

    // =========================================================
    // LAUNCHERS
    // =========================================================
    private val requestLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            checkGpsEnabled()
        } else {
            Toast.makeText(this,
                "Izin lokasi diperlukan agar aplikasi berjalan dengan baik.",
                Toast.LENGTH_LONG).show()
        }
    }

    // Launcher untuk dialog aktifkan GPS dari sistem
    private val locationSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            enableMyLocation()
        } else {
            // User menolak — tampilkan fallback dialog manual
            showManualGpsDialog()
        }
    }

    private val riskResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val mode      = result.data?.getStringExtra("ROUTE_MODE") ?: "STAY"
            val changeLoc = result.data?.getBooleanExtra("CHANGE_LOCATION", false) ?: false
            when {
                changeLoc || mode == "CHANGE" -> {
                    searchResultLauncher.launch(Intent(this, SearchActivity::class.java))
                }
                mode == "SAFEST" -> {
                    activeDestLat = 0.0; activeDestLng = 0.0
                    pendingDestination?.let { dest ->
                        drawRoute(dest, "SAFEST",
                            pendingDestName ?: getString(R.string.location), false)
                    }
                }
                mode == "STAY" -> {
                    if (currentRouteMode == "SAFEST" && fastestRoutePoints.isNotEmpty())
                        restoreFastestRoute()
                }
            }
        }
    }

    private val searchResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val lat  = result.data?.getDoubleExtra("DEST_LAT",  0.0) ?: 0.0
            val lon  = result.data?.getDoubleExtra("DEST_LNG",  0.0) ?: 0.0
            val name = result.data?.getStringExtra("DEST_NAME") ?: getString(R.string.location)
            if (lat != 0.0 && lon != 0.0) {
                val dest = GeoPoint(lat, lon)
                pendingDestination = dest; pendingDestName = name
                resetRouteState()
                binding.map.controller.animateTo(dest, 18.0, 1000L)
                drawRoute(dest, "FASTEST", name, false)
            }
        }
    }

    // =========================================================
    // LIFECYCLE
    // =========================================================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, getPreferences(MODE_PRIVATE))
        Configuration.getInstance().tileDownloadThreads         = 4
        Configuration.getInstance().tileFileSystemCacheMaxBytes = 50L * 1024 * 1024
        Configuration.getInstance().osmdroidTileCache           = java.io.File(cacheDir, "osmdroid")

        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth                = FirebaseAuth.getInstance()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        sensorManager       = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        magnetometerSensor  = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        setupMap()
        setupUI()
        setupNavigation()
        setupBottomSheet()
        loadUserProfileImage()
        checkLocationPermission()
        listenToIncidents()
        listenToSosRequirements()
        setupBackPressHandler()
        handleIntentExtras(intent)
        markerLerpHandler.post(markerLerpRunnable)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent); handleIntentExtras(intent)
    }

    override fun onResume() {
        super.onResume()
        binding.map.onResume()
        accelerometerSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        magnetometerSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onPause() {
        super.onPause(); binding.map.onPause(); sensorManager.unregisterListener(this)
    }

    override fun onDestroy() {
        super.onDestroy(); markerLerpHandler.removeCallbacks(markerLerpRunnable)
    }

    // =========================================================
    // GPS DIALOG  –  mirip dialog "Aktifkan Lokasi" di Maps
    // =========================================================

    /**
     * Cek apakah GPS aktif menggunakan LocationSettingsRequest dari GMS.
     * Jika belum aktif → sistem menampilkan dialog resmi GMS (seperti Google Maps)
     * yang bisa langsung diaktifkan tanpa masuk ke Settings.
     */
    private fun checkGpsEnabled() {
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000).build()
        val settingsReq = LocationSettingsRequest.Builder()
            .addLocationRequest(req)
            .setAlwaysShow(true)   // selalu tampilkan dialog meski user pernah menolak
            .build()

        val task: Task<LocationSettingsResponse> =
            LocationServices.getSettingsClient(this).checkLocationSettings(settingsReq)

        task.addOnSuccessListener {
            // GPS sudah aktif
            enableMyLocation()
        }
        task.addOnFailureListener { exception ->
            if (exception is ResolvableApiException) {
                try {
                    // Dialog sistem resmi GMS — user tinggal klik OK
                    locationSettingsLauncher.launch(
                        IntentSenderRequest.Builder(exception.resolution.intentSender).build()
                    )
                } catch (sendEx: IntentSender.SendIntentException) {
                    showManualGpsDialog()
                }
            } else {
                showManualGpsDialog()
            }
        }
    }

    /** Fallback dialog jika GMS dialog tidak tersedia */
    private fun showManualGpsDialog() {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle("Aktifkan Lokasi")
            .setMessage("Aplikasi memerlukan lokasi GPS untuk menampilkan posisi Anda di peta dan menghitung rute yang aman.")
            .setPositiveButton("Buka Pengaturan") { _, _ ->
                startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
            .setNegativeButton("Nanti") { dialog, _ -> dialog.dismiss() }
            .setCancelable(false)
            .show()
    }

    // =========================================================
    // SOS
    // =========================================================
    private fun listenToSosRequirements() {
        val uid = auth.currentUser?.uid ?: return
        db.collection("users").document(uid).addSnapshotListener { doc, _ ->
            if (doc != null && doc.exists()) {
                val phone = doc.getString("noTelpon") ?: ""
                hasPhoneNumber = phone.isNotEmpty() && phone.length >= 10
                updateSosUI()
            }
        }
        db.collection("users").document(uid).collection("emergency_contacts")
            .addSnapshotListener { snap, _ ->
                hasEmergencyContacts = snap != null && !snap.isEmpty
                updateSosUI()
            }
    }

    private fun updateSosUI() {
        val active = hasPhoneNumber && hasEmergencyContacts
        if (active) {
            binding.btnSosHome.setCardBackgroundColor("#C0392B".toColorInt())
            binding.vSosRing.backgroundTintList = ColorStateList.valueOf("#C0392B".toColorInt())
            binding.tvSosLabel.setTextColor(Color.WHITE)
            binding.tvSosDisabledTop.visibility = View.GONE
            binding.cvSosTooltip.visibility     = View.GONE
        } else {
            binding.btnSosHome.setCardBackgroundColor("#CBD5E1".toColorInt())
            binding.vSosRing.backgroundTintList = ColorStateList.valueOf("#CBD5E1".toColorInt())
            binding.tvSosLabel.setTextColor("#94A3B8".toColorInt())
            binding.tvSosDisabledTop.visibility = View.VISIBLE
        }
    }

    // =========================================================
    // MAP SETUP  –  hardware layer, fling, rotation smooth
    // =========================================================
    private fun setupMap() {
        binding.map.setTileSource(TileSourceFactory.MAPNIK)
        binding.map.setMultiTouchControls(true)
        binding.map.isTilesScaledToDpi = true
        // Hardware GPU layer → rotasi dan scroll tidak glitch
        binding.map.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        // Tile cache agar tidak blank saat diputar
        binding.map.setHasTransientState(true)
        @Suppress("DEPRECATION")
        binding.map.setBuiltInZoomControls(false)
        binding.map.setFlingEnabled(true)
        binding.map.setUseDataConnection(true)
        binding.map.isHorizontalMapRepetitionEnabled = false
        binding.map.isVerticalMapRepetitionEnabled   = false
        binding.map.controller.setZoom(17.5)
        binding.map.controller.setCenter(GeoPoint(3.5952, 98.6722))
        binding.map.mapOrientation = 0f

        // RotationGestureOverlay → gesture 2 jari smooth
        val rotOverlay = RotationGestureOverlay(binding.map)
        rotOverlay.isEnabled = true
        binding.map.overlays.add(rotOverlay)

        // Tap overlay (paling bawah)
        binding.map.overlays.add(0, MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                dismissAllSheets()
                binding.cvSosTooltip.visibility = View.GONE
                return false
            }
            override fun longPressHelper(p: GeoPoint?) = false
        }))

        myLocationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(this), binding.map)
        myLocationOverlay.setDrawAccuracyEnabled(false)
        val transparent = createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        myLocationOverlay.setPersonIcon(transparent)
        myLocationOverlay.setDirectionIcon(transparent)
        myLocationOverlay.enableMyLocation()
        binding.map.overlays.add(myLocationOverlay)

        binding.map.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean {
                compensateMarkerRotation(); return false
            }
            override fun onZoom(event: ZoomEvent?): Boolean {
                refreshIncidentOverlays(); rebuildCompassIcon(); compensateMarkerRotation()
                return false
            }
        })
    }

    private fun compensateMarkerRotation() {
        compassMarker?.rotation = currentAzimuth - binding.map.mapOrientation
        binding.map.postInvalidate()
    }

    private fun dismissAllSheets() {
        if (bottomSheetBehavior.state != BottomSheetBehavior.STATE_HIDDEN) {
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
        }
        if (vehicleSheetBehavior.state != BottomSheetBehavior.STATE_HIDDEN) {
            vehicleSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
        }
    }

    // =========================================================
    // COMPASS MARKER
    // =========================================================
    private fun compassSizeDp(): Float = when {
        binding.map.zoomLevelDouble >= 17.0 -> 72f
        binding.map.zoomLevelDouble >= 15.0 -> 58f
        binding.map.zoomLevelDouble >= 13.0 -> 44f
        binding.map.zoomLevelDouble >= 12.0 -> 32f
        binding.map.zoomLevelDouble >= 11.0 -> 22f
        else -> 0f
    }

    private fun initCompassMarker(location: GeoPoint) {
        val sz = compassSizeDp().let { if (it == 0f) 72f else it }
        compassMarker = Marker(binding.map).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            infoWindow = null; position = location
            icon     = buildCompassIcon(resources.displayMetrics.density, sz)
            rotation = currentAzimuth - binding.map.mapOrientation
        }
        binding.map.overlays.add(compassMarker)
        binding.map.postInvalidate()
    }

    private fun rebuildCompassIcon() {
        val m  = compassMarker ?: return
        val sz = compassSizeDp()
        if (sz == 0f) {
            binding.map.overlays.remove(m); compassMarker = null
            binding.map.postInvalidate(); return
        }
        m.icon = buildCompassIcon(resources.displayMetrics.density, sz)
    }

    private fun buildCompassIcon(density: Float, sizeDp: Float = 72f): android.graphics.drawable.BitmapDrawable {
        val size = (sizeDp * density).toInt().coerceAtLeast(20)
        val bmp  = createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cv   = Canvas(bmp); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val cx = size / 2f; val cy = size / 2f; val s = sizeDp / 120f

        p.shader = LinearGradient(cx, 2*density, cx, cy,
            intArrayOf(Color.TRANSPARENT, "#25E74C3C".toColorInt(), "#88C0392B".toColorInt()),
            floatArrayOf(0f, .45f, 1f), Shader.TileMode.CLAMP)
        cv.drawPath(Path().apply {
            moveTo(cx, 2*density)
            lineTo(cx-(sizeDp/3.5f)*density, cy)
            lineTo(cx+(sizeDp/3.5f)*density, cy)
            close()
        }, p); p.shader = null

        val aT=cy-46*density*s; val aB=cy-13*density*s; val aW=13*density*s
        val arrow = Path().apply {
            moveTo(cx,aT); lineTo(cx-aW,aB); lineTo(cx-5.5f*density*s,aB)
            lineTo(cx-5.5f*density*s,cy+2*density*s)
            lineTo(cx+5.5f*density*s,cy+2*density*s)
            lineTo(cx+5.5f*density*s,aB); lineTo(cx+aW,aB); close()
        }
        p.style=Paint.Style.FILL; p.color="#C0392B".toColorInt(); cv.drawPath(arrow,p)
        p.style=Paint.Style.STROKE; p.strokeWidth=1.8f*density*s
        p.color=Color.WHITE; cv.drawPath(arrow,p)
        p.style=Paint.Style.FILL; p.color=Color.BLACK; p.alpha=50
        cv.drawCircle(cx,cy+2*density*s,18*density*s,p)
        val rR=RectF(cx-16*density*s,cy-16*density*s,cx+16*density*s,cy+16*density*s)
        p.style=Paint.Style.STROKE; p.strokeWidth=5.5f*density*s; p.alpha=255
        p.color="#E74C3C".toColorInt(); cv.drawArc(rR,210f,120f,false,p)
        p.color="#F59E0B".toColorInt(); cv.drawArc(rR,330f,120f,false,p)
        p.color="#10B981".toColorInt(); cv.drawArc(rR,90f,120f,false,p)
        p.style=Paint.Style.FILL; p.color=Color.WHITE
        cv.drawCircle(cx,cy,11*density*s,p)
        p.color="#4285F4".toColorInt(); cv.drawCircle(cx,cy,5*density*s,p)
        return android.graphics.drawable.BitmapDrawable(resources, bmp)
    }

    // =========================================================
    // SENSOR
    // =========================================================
    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER  -> { for (i in 0..2) gravityRaw[i]     = lpAlpha*event.values[i]+(1f-lpAlpha)*gravityRaw[i] }
            Sensor.TYPE_MAGNETIC_FIELD -> { for (i in 0..2) geomagneticRaw[i] = lpAlpha*event.values[i]+(1f-lpAlpha)*geomagneticRaw[i] }
            else -> return
        }
        val rot=FloatArray(9); val inc=FloatArray(9)
        if (!SensorManager.getRotationMatrix(rot,inc,gravityRaw,geomagneticRaw)) return
        val ori=FloatArray(3); SensorManager.getOrientation(rot,ori)
        var raw = Math.toDegrees(ori[0].toDouble()).toFloat()
        raw=(raw+360f)%360f; raw=(360f-raw)%360f
        var d=raw-smoothedAzimuth
        if (d>180f) d-=360f; if (d<-180f) d+=360f
        smoothedAzimuth+=d*0.15f; smoothedAzimuth=(smoothedAzimuth+360f)%360f
        currentAzimuth=smoothedAzimuth; compensateMarkerRotation()
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // =========================================================
    // ROUTE  –  4 skenario: Kereta/Mobil × Fastest/Safest
    // =========================================================

    /**
     * ══════════════════════════════════════════════════════════
     * PERBEDAAN SISTEM RUTE
     * ══════════════════════════════════════════════════════════
     * KERETA + FASTEST  → OSRM "bike"    index-0, skor JUJUR (radius 120m, penalti besar)
     * KERETA + SAFEST   → OSRM "bike"    alt-terbaik, skor KETAT (radius 60m, penalti kecil)
     * MOBIL  + FASTEST  → OSRM "driving" index-0, skor JUJUR
     * MOBIL  + SAFEST   → OSRM "driving" alt-terbaik, skor KETAT
     *
     * Karena profile OSRM beda (bike≠driving) → jalur fisik PASTI beda.
     * Karena isSafestMode beda → skor PASTI beda (Safest ≥ Fastest).
     * ══════════════════════════════════════════════════════════
     */
    private fun drawRoute(
        destination:  GeoPoint,
        mode:         String,
        destName:     String,
        autoOpenRisk: Boolean = false
    ) {
        if (mode == "FASTEST"
            && roadOverlay != null
            && currentRouteMode == "FASTEST"
            && activeDestLat == destination.latitude
            && activeDestLng == destination.longitude
        ) return

        val start = myLocationOverlay.myLocation
            ?: lastKnownLocation?.let { GeoPoint(it.latitude, it.longitude) }
            ?: GeoPoint(3.5952, 98.6722)

        roadOverlay?.let { binding.map.overlays.remove(it) }
        roadOverlay = null

        Log.d("Route", "drawRoute mode=$mode vehicle=$selectedVehicle")

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // "bike"    → Kereta: gang, cycleway, jalan kecil (BERBEDA dari driving)
                // "driving" → Mobil: hanya jalan kendaraan roda 4+
                val profile = if (selectedVehicle == "Kereta") "bike" else "driving"

                val extraParams = if (selectedVehicle == "Kereta") {
                    "&continue_straight=false"
                } else {
                    "&continue_straight=true"
                }

                val url = String.format(Locale.US,
                    "https://router.project-osrm.org/route/v1/%s/%.6f,%.6f;%.6f,%.6f" +
                            "?overview=full&geometries=polyline&alternatives=true&steps=true$extraParams",
                    profile,
                    start.longitude, start.latitude,
                    destination.longitude, destination.latitude
                )

                val resp = httpClient.newCall(
                    Request.Builder().url(url).header("User-Agent","MedanSafe/1.0").build()
                ).execute()

                var body = resp.body?.string() ?: ""
                var finalResp = resp

                if (!finalResp.isSuccessful || body.isEmpty() || body.contains("\"code\":\"NoRoute\"")) {
                    Log.w("Route", "Rute gagal, mencoba fallback...")
                    val fallbackUrl = String.format(Locale.US,
                        "https://router.project-osrm.org/route/v1/%s/%.6f,%.6f;%.6f,%.6f" +
                                "?overview=full&geometries=polyline&alternatives=true&steps=true",
                        profile,
                        start.longitude, start.latitude,
                        destination.longitude, destination.latitude
                    )
                    try {
                        val fallbackResp = httpClient.newCall(
                            Request.Builder().url(fallbackUrl).header("User-Agent","MedanSafe/1.0").build()
                        ).execute()
                        val fallbackBody = fallbackResp.body?.string() ?: ""
                        if (fallbackResp.isSuccessful && fallbackBody.isNotEmpty()) {
                            body = fallbackBody
                            finalResp = fallbackResp
                        }
                    } catch (_: Exception) {}
                }

                if (!finalResp.isSuccessful || body.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@HomeActivity,
                            "Rute tidak tersedia. Coba lagi.", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                processOsrmResponse(body, mode, destName, start, destination, autoOpenRisk)

            } catch (e: Exception) {
                Log.e("Route", "Error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@HomeActivity,
                        "Gagal memuat rute", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Proses response OSRM.
     *
     * FASTEST (isSafestMode=false):
     *   • Index 0 dari OSRM
     *   • Radius insiden 120m → banyak terdeteksi
     *   • Penalti berbahaya -35/insiden, max -80
     *   • Penalti waspada   -18/insiden, max -50
     *   → Skor RENDAH karena jalur tercepat mungkin rawan
     *
     * SAFEST (isSafestMode=true):
     *   • Evaluasi semua alt, penalti -20 untuk index-0
     *   • Radius insiden 60m → hanya yang benar-benar dilewati
     *   • Penalti berbahaya -22/insiden, max -60
     *   • Penalti waspada   -10/insiden, max -35
     *   → Skor TINGGI karena jalur dipilih menghindari insiden
     */
    private suspend fun processOsrmResponse(
        body: String, mode: String, destName: String,
        start: GeoPoint, destination: GeoPoint, autoOpenRisk: Boolean
    ) {
        val json = try { JSONObject(body) } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@HomeActivity, "Format rute tidak valid", Toast.LENGTH_SHORT).show()
            }
            return
        }

        if (json.optString("code") != "Ok") {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@HomeActivity, "Rute tidak ditemukan", Toast.LENGTH_SHORT).show()
            }
            return
        }

        val routesArr = json.optJSONArray("routes")
        if (routesArr == null || routesArr.length() == 0) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@HomeActivity, "Tidak ada rute tersedia", Toast.LENGTH_SHORT).show()
            }
            return
        }

        val total    = routesArr.length()
        val isSafest = (mode == "SAFEST")

        val selectedIndex: Int = when {
            !isSafest -> 0
            else -> {
                data class Cand(val idx: Int, val adj: Double)
                val cands = (0 until total).map { i ->
                    val ro    = routesArr.getJSONObject(i)
                    val pts   = decodePolyline(ro.getString("geometry"))
                    val legs  = ro.getJSONArray("legs")
                    val steps = if (legs.length() > 0)
                        legs.getJSONObject(0).optJSONArray("steps") ?: JSONArray() else JSONArray()
                    val narrow = detectNarrowRoad(steps)
                    val (raw,_,_) = calculateDetailedRisk(pts, narrow, isSafestMode = true)
                    val durationPenalty = if (i == 0) 0.0 else {
                        val altDist  = routesArr.getJSONObject(i).getDouble("distance")
                        val baseDist = routesArr.getJSONObject(0).getDouble("distance")
                        if (altDist <= baseDist * 1.30) 8.0 else 0.0
                    }
                    val adj = when {
                        i == 0 -> raw.toDouble() - 30.0
                        else   -> raw.toDouble() + durationPenalty
                    }
                    Cand(i, adj)
                }
                val best = cands.maxByOrNull { it.adj }!!.idx
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@HomeActivity,
                        if (best == 0) "Rute saat ini sudah paling aman dari zona berbahaya."
                        else "✅ Jalur lebih aman ditemukan (menghindari zona rawan).",
                        Toast.LENGTH_SHORT).show()
                }
                best
            }
        }

        val sel    = routesArr.getJSONObject(selectedIndex)
        val pts    = decodePolyline(sel.getString("geometry"))
        val legs   = sel.getJSONArray("legs")
        val steps  = if (legs.length() > 0)
            legs.getJSONObject(0).optJSONArray("steps") ?: JSONArray() else JSONArray()
        val narrow = detectNarrowRoad(steps)

        // Skor akhir sesuai mode
        val riskData  = calculateDetailedRisk(pts, narrow, isSafestMode = isSafest)
        val distKm = sel.getDouble("distance") / 1000.0
        val dist   = distKm

        // Kecepatan rata-rata realistis kondisi Medan
        // Kereta/motor: lebih lincah, bisa potong gang
        // Mobil: tergantung kondisi lalu lintas
        val baseSpeedKmh = when {
            selectedVehicle == "Kereta" && isSafest -> 22.0
            selectedVehicle == "Kereta"             -> 28.0
            selectedVehicle == "Mobil"  && isSafest -> 20.0
            selectedVehicle == "Mobil"              -> 25.0
            else                                    -> 24.0
        }
        // Buffer kecil hanya untuk berhenti dan persimpangan
        // Sebelumnya distKm * 0.5 → terlalu besar
        // Sekarang: +1 menit per 5km (lebih realistis)
        val bufferMinutes = (distKm / 5.0).toInt().coerceAtLeast(0)
        val correctedDur: Int = (((distKm / baseSpeedKmh) * 60.0).toInt() + bufferMinutes)
            .coerceAtLeast(1)
        val startAddr = getAddressFromLocation(start.latitude, start.longitude)

        fullRoutePoints        = pts
        routeCutIndex          = 0; offRouteFrameCount = 0
        lastCalculatedRiskData = riskData
        activeDestLat          = destination.latitude
        activeDestLng          = destination.longitude
        currentRouteMode       = mode

        if (!isSafest) {
            fastestRoutePoints = pts; fastestRiskData  = riskData
            fastestDist        = dist; fastestDur = correctedDur
            fastestStartAddr   = startAddr; fastestDestName = destName
            fastestScore       = riskData.first
        }

        withContext(Dispatchers.Main) {
            renderRouteOnMap(pts, riskData.first, destName, startAddr, dist, correctedDur)
            if (autoOpenRisk) openRiskScore(riskData.first, destName, startAddr, dist, correctedDur)
        }
    }

    private fun restoreFastestRoute() {
        if (fastestRoutePoints.isEmpty()) return
        val rd    = fastestRiskData ?: return
        val score = rd.first
        val color = routeColor(score)

        roadOverlay?.let { binding.map.overlays.remove(it) }
        roadOverlay = Polyline().apply {
            setPoints(fastestRoutePoints)
            outlinePaint.color = color.toColorInt(); outlinePaint.strokeWidth = if (selectedVehicle == "Mobil") 13f else 11f
            outlinePaint.strokeJoin = Paint.Join.ROUND; outlinePaint.strokeCap = Paint.Cap.ROUND
            setOnClickListener { _, _, _ ->
                openRiskScore(score,fastestDestName,fastestStartAddr,fastestDist,fastestDur); true
            }
        }
        binding.map.overlays.add(roadOverlay); binding.map.invalidate()

        fullRoutePoints        = fastestRoutePoints
        routeCutIndex          = 0; offRouteFrameCount = 0
        currentRouteMode       = "FASTEST"
        lastCalculatedRiskData = fastestRiskData

        binding.tvNavInstruction.text = getString(R.string.route_to_format, fastestDestName)
        val vehicleEmoji2 = if (selectedVehicle == "Kereta") "🛵" else "🚗"
        binding.tvNavSafetyStatus.text =
            "$vehicleEmoji2 ⚡ Tercepat  •  Skor: $score  •  ${String.format(Locale.US,"%.1f",fastestDist)} km  •  $fastestDur mnt"
        binding.tvNavSafetyStatus.setTextColor(color.toColorInt())
        binding.cvNavInstruction.setOnClickListener {
            openRiskScore(score,fastestDestName,fastestStartAddr,fastestDist,fastestDur)
        }
        Toast.makeText(this, "Kembali ke rute tercepat.", Toast.LENGTH_SHORT).show()
    }

    private fun routeColor(score: Int) = when {
        score >= 80 -> "#10B981"
        score >= 50 -> "#F59E0B"
        else        -> "#E74C3C"
    }

    private fun renderRouteOnMap(
        points: List<GeoPoint>, score: Int, destName: String,
        startAddr: String, dist: Double, dur: Int
    ) {
        val color = routeColor(score)
        val strokeWidth = if (selectedVehicle == "Mobil") 13f else 11f
        val routeColorHex = if (selectedVehicle == "Mobil") {
            when {
                score >= 80 -> "#0D9488"
                score >= 50 -> "#D97706"
                else        -> "#DC2626"
            }
        } else color

        roadOverlay = Polyline().apply {
            setPoints(points)
            outlinePaint.color       = routeColorHex.toColorInt()
            outlinePaint.strokeWidth = strokeWidth
            outlinePaint.strokeJoin  = Paint.Join.ROUND
            outlinePaint.strokeCap   = Paint.Cap.ROUND
            setOnClickListener { _, _, _ -> openRiskScore(score,destName,startAddr,dist,dur); true }
        }
        binding.map.overlays.add(roadOverlay); binding.map.invalidate()

        binding.cvNavInstruction.visibility   = View.VISIBLE
        binding.cvNavInstruction.translationY = -400f
        binding.cvNavInstruction.animate()
            .translationY(0f).setDuration(600).setInterpolator(OvershootInterpolator()).start()

        binding.tvNavInstruction.text = getString(R.string.route_to_format, destName)
        val modeLabel = if (currentRouteMode == "SAFEST") "🛡️ Teraman" else "⚡ Tercepat"
        val vehicleEmoji = if (selectedVehicle == "Kereta") "🛵" else "🚗"
        binding.tvNavSafetyStatus.text =
            "$vehicleEmoji $modeLabel  •  Skor: $score  •  ${String.format(Locale.US,"%.1f",dist)} km  •  $dur mnt"
        binding.tvNavSafetyStatus.setTextColor(color.toColorInt())
        binding.cvNavInstruction.setOnClickListener { openRiskScore(score,destName,startAddr,dist,dur) }
    }

    private fun openRiskScore(score: Int, destName: String, startAddr: String, dist: Double, dur: Int) {
        riskResultLauncher.launch(
            Intent(this, RiskScoreActivity::class.java).apply {
                putExtra("START_ADDR",   startAddr)
                putExtra("DEST_NAME",    destName)
                putExtra("DISTANCE",     String.format(Locale.US,"%.1f km",dist))
                putExtra("DURATION",     "$dur menit")
                putExtra("SCORE",        score)
                putExtra("FACTORS",      lastCalculatedRiskData?.second.toString())
                putExtra("CURRENT_MODE", currentRouteMode)
                putExtra("VEHICLE",      selectedVehicle)
            }
        )
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.slide_in_right, R.anim.fade_out)
    }

    fun drawRouteFromFragment(destination: GeoPoint, destName: String) {
        pendingDestination = destination; pendingDestName = destName
        resetRouteState(); drawRoute(destination, "FASTEST", destName, false)
    }

    private fun resetRouteState() {
        activeDestLat = 0.0; activeDestLng = 0.0; currentRouteMode = "FASTEST"
        fastestRoutePoints = listOf(); fastestRiskData = null
        routeCutIndex = 0; offRouteFrameCount = 0
    }

    // =========================================================
    // ROUTE HELPERS
    // =========================================================
    private fun detectNarrowRoad(stepsArray: JSONArray): Boolean {
        val kw = listOf("gang","gg.","gg ","lorong","track","footway","path",
            "service","residential","unclassified","living_street","alley","cycleway")
        for (i in 0 until stepsArray.length()) {
            val step = stepsArray.getJSONObject(i)
            val name = step.optString("name","").lowercase()
            val ref  = step.optString("ref","").lowercase()
            if (kw.any { name.contains(it) || ref.contains(it) }) return true
            if (step.optString("mode","").contains("pushing")) return true
        }
        return false
    }

    /**
     * ══════════════════════════════════════════════════════════
     * PERBEDAAN PENILAIAN FASTEST vs SAFEST
     * ══════════════════════════════════════════════════════════
     *
     * isSafestMode=FALSE (FASTEST):
     *   radius=120m  penaltiBahaya=-35 max-80  penaltiWaspada=-18 max-50
     *   → Laporan waspada pun ikut turunkan skor → skor RENDAH & JUJUR
     *
     * isSafestMode=TRUE (SAFEST):
     *   radius=60m   penaltiBahaya=-22 max-60  penaltiWaspada=-10 max-35
     *   → Jalur ini dipilih justru karena bebas insiden → skor TINGGI
     *
     * Hasilnya: skor Safest SELALU >= skor Fastest untuk tujuan sama.
     * Warna rute Safest lebih hijau dari Fastest.
     * ══════════════════════════════════════════════════════════
     *
     * Faktor kendaraan:
     * Kereta: lebih sensitif begal/gelap; gang sempit = INFO biru (TIDAK penalti)
     * Mobil:  lebih sensitif laka/rusak;  gang sempit = PENALTI -30
     */
    private fun calculateDetailedRisk(
        points:       List<GeoPoint>,
        hasNarrow:    Boolean = false,
        isSafestMode: Boolean = false
    ): Triple<Int, JSONArray, String> {
        val factors = JSONArray()
        var score   = 100

        val hour    = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val isNight = hour in 19..23 || hour in 0..5
        val ago30   = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)

        // Radius lebih presisi agar hanya jalur yang benar dilalui
        val radius = if (isSafestMode) 55.0 else 85.0
        val counted = mutableSetOf<String>()

        // Akumulator penalti per insiden (gradasi bh 1–5)
        var totalPenaltyFromIncidents = 0
        var highestBhFound = 0
        var incidentCount = 0
        val incidentDescList = mutableListOf<String>()

        lastIncidentSnapshot?.documents?.forEach { doc ->
            val loc = doc.getGeoPoint("lokasi") ?: return@forEach
            val ts  = doc.getTimestamp("timestamp")?.toDate()?.time ?: 0L
            if (ts < ago30 || doc.id in counted) return@forEach

            val pt       = GeoPoint(loc.latitude, loc.longitude)
            val bh       = doc.getLong("tingkatBahaya")?.toInt() ?: 3
            val kategori = doc.getString("kategori") ?: ""

            if (points.any { p -> distanceBetween(p, pt) < radius }) {
                counted.add(doc.id)

                val isBegal = kategori.contains("Begal", ignoreCase = true)
                val isGelap = kategori.contains("Gelap", ignoreCase = true)
                val isLaka  = kategori.contains("Kecelakaan", ignoreCase = true) ||
                    kategori.contains("Laka", ignoreCase = true)
                val isRusak = kategori.contains("Rusak", ignoreCase = true)

                val finalBh = when (selectedVehicle) {
                    "Kereta" -> if ((isBegal || isGelap) && bh >= 3) bh + 1 else bh
                    "Mobil"  -> if ((isLaka || isRusak) && bh >= 3) bh + 1 else bh
                    else     -> bh
                }.coerceIn(1, 5)

                // SAFEST: abaikan laporan bh<=2 (aman/rendah)
                // FASTEST: hitung semua laporan
                if (isSafestMode && finalBh <= 2) return@forEach

                // Gradasi penalti baru — lebih tegas:
                // bh=1 (aman)   →  0 poin
                // bh=2 (rendah) →  5 FASTEST,  0 SAFEST
                // bh=3 (sedang) → 15 FASTEST,  7 SAFEST
                // bh=4 (tinggi) → 28 FASTEST, 15 SAFEST
                // bh=5 (kritis) → 45 FASTEST, 25 SAFEST
                //
                // Contoh hasil baru:
                // 1 titik bh=5 FASTEST → -45 → skor 55 (WASPADA mendekati BERBAHAYA)
                // 2 titik bh=5 FASTEST → -90 → dibatasi maxPenalty → skor rendah
                // 1 titik bh=3 FASTEST → -15 → skor 85 (masih AMAN tapi terasa)
                // 1 titik bh=3 SAFEST  →  -7 → skor 93 (AMAN, wajar)
                val penaltyPerIncident = when (finalBh) {
                    1    -> 0
                    2    -> if (isSafestMode) 0 else 5
                    3    -> if (isSafestMode) 7 else 15
                    4    -> if (isSafestMode) 15 else 28
                    5    -> if (isSafestMode) 25 else 45
                    else -> 0
                }

                totalPenaltyFromIncidents += penaltyPerIncident
                incidentCount++
                if (finalBh > highestBhFound) highestBhFound = finalBh

                val bhLabel = when (finalBh) {
                    1 -> "Aman"
                    2 -> "Rendah"
                    3 -> "Sedang"
                    4 -> "Tinggi"
                    5 -> "Kritis"
                    else -> ""
                }
                incidentDescList.add("$kategori [Bahaya $bhLabel]")
            }
        }

        // Batasi total penalti dari insiden
        val maxPenalty = if (isSafestMode) 50 else 90
        val actualPenalty = totalPenaltyFromIncidents.coerceAtMost(maxPenalty)
        score -= actualPenalty

        // Emoji dan deskripsi berdasarkan tingkat bahaya tertinggi
        if (incidentCount > 0) {
            val (emoji, color, label) = when {
                highestBhFound >= 5 -> Triple("🚨", "#E74C3C", "KRITIS")
                highestBhFound >= 4 -> Triple("🔴", "#E74C3C", "BERBAHAYA")
                highestBhFound >= 3 -> Triple("⚠️", "#F59E0B", "WASPADA")
                highestBhFound >= 2 -> Triple("🟡", "#F59E0B", "RENDAH")
                else                -> Triple("🟢", "#10B981", "AMAN")
            }
            val desc = if (isSafestMode)
                "$incidentCount laporan terdeteksi dekat jalur (Tertinggi: $label)"
            else
                "$incidentCount laporan di jalur — Bahaya Tertinggi: $label (-$actualPenalty poin)"
            factors.put(JSONObject().apply {
                put("emoji", emoji)
                put("desc",  desc)
                put("impact", actualPenalty)
                put("color",  color)
            })
        } else {
            factors.put(JSONObject().apply {
                put("emoji", "✅")
                put("desc", if (isSafestMode)
                    "Bebas Insiden — Jalur Aman Terpilih"
                else
                    "Tidak Ada Laporan Insiden di Jalur Ini")
                put("impact", 0)
                put("color", "#10B981")
            })
        }

        // ── Faktor 2: Tipe Jalan ───────────────────────────────────────────────
        when (selectedVehicle) {
            "Mobil" -> {
                if (hasNarrow) {
                    score -= 20
                    factors.put(JSONObject().apply {
                        put("emoji","🚧"); put("desc","Melewati Jalan Sempit (Tidak Ideal Mobil)")
                        put("impact",20); put("color","#E74C3C")
                    })
                } else {
                    factors.put(JSONObject().apply {
                        put("emoji","🛣️"); put("desc","Jalur Utama (Ideal untuk Mobil)")
                        put("impact",0); put("color","#10B981")
                    })
                }
            }
            "Kereta" -> {
                // Motor bisa lewat gang → bukan penalti, hanya info
                if (hasNarrow) {
                    factors.put(JSONObject().apply {
                        put("emoji","🛵"); put("desc","Lewat Gang/Jalan Kecil (Normal untuk Kereta)")
                        put("impact",0); put("color","#3B82F6")
                    })
                } else {
                    factors.put(JSONObject().apply {
                        put("emoji","🛣️"); put("desc","Menggunakan Jalur Utama")
                        put("impact",0); put("color","#10B981")
                    })
                }
            }
            else -> {
                if (hasNarrow) {
                    score -= 10
                    factors.put(JSONObject().apply {
                        put("emoji","🚧"); put("desc","Melewati Lorong/Jalan Sempit")
                        put("impact",10); put("color","#F59E0B")
                    })
                } else {
                    factors.put(JSONObject().apply {
                        put("emoji","🛣️"); put("desc","Menggunakan Jalur Utama (Lebar)")
                        put("impact",0); put("color","#10B981")
                    })
                }
            }
        }

        // ── Faktor 3: Waktu ────────────────────────────────────────────────────
        if (isNight) {
            val nightPenalty = if (isSafestMode) 5 else 8
            score -= nightPenalty
            factors.put(JSONObject().apply {
                put("emoji","🌙"); put("desc","Risiko Jalan Sepi (Malam)")
                put("impact", nightPenalty); put("color","#F59E0B")
            })
        } else {
            factors.put(JSONObject().apply {
                put("emoji","👥"); put("desc","Area Ramai Aktivitas (Siang)")
                put("impact",0); put("color","#10B981")
            })
        }

        val fs = score.coerceIn(0,100)
        return Triple(fs, factors, when { fs>=80->"AMAN"; fs>=50->"WASPADA"; else->"BERBAHAYA" })
    }

    private fun getAddressFromLocation(lat: Double, lng: Double): String {
        return try {
            @Suppress("DEPRECATION")
            Geocoder(this, Locale.getDefault()).getFromLocation(lat, lng, 1)
                ?.get(0)?.getAddressLine(0) ?: getString(R.string.your_location)
        } catch (_: Exception) { getString(R.string.your_location) }
    }

    // =========================================================
    // INCIDENTS
    // =========================================================
    private fun listenToIncidents() {
        db.collection("incidents").whereEqualTo("status","terverifikasi")
            .addSnapshotListener { value, _ ->
                lastIncidentSnapshot = value
                refreshIncidentOverlays()
                updateSecuritySummary(value)
            }
    }

    private fun updateSecuritySummary(snapshot: QuerySnapshot?) {
        if (snapshot == null) return
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_WEEK, cal.firstDayOfWeek)
        cal.set(Calendar.HOUR_OF_DAY,0); cal.set(Calendar.MINUTE,0); cal.set(Calendar.SECOND,0)
        val sw = cal.timeInMillis
        var total=0; var begal=0; var laka=0; var gelap=0
        snapshot.documents.forEach { doc ->
            val ts = doc.getTimestamp("timestamp")?.toDate()?.time ?: 0L
            if (ts >= sw) {
                total++
                val k = doc.getString("kategori") ?: ""
                when {
                    k.contains("Begal",      ignoreCase=true) -> begal++
                    k.contains("Kecelakaan", ignoreCase=true) ||
                            k.contains("Laka",        ignoreCase=true) -> laka++
                    k.contains("Gelap",       ignoreCase=true) -> gelap++
                }
            }
        }
        try {
            binding.tvCountBegal.text      = begal.toString()
            binding.tvCountKecelakaan.text = laka.toString()
            binding.tvCountGelap.text      = gelap.toString()
            binding.tvSummaryStats.text    = "Tingkat Keamanan · $total insiden"
            when {
                total == 0 -> {
                    binding.tvSecurityLabelBadge.text = "AMAN"
                    binding.tvSecurityLabelBadge.backgroundTintList =
                        ColorStateList.valueOf("#DCFCE7".toColorInt())
                    binding.tvSecurityLabelBadge.setTextColor("#10B981".toColorInt())
                }
                begal > 0 || maxOf(begal,laka,gelap) > 3 -> {
                    binding.tvSecurityLabelBadge.text = "BERBAHAYA"
                    binding.tvSecurityLabelBadge.backgroundTintList =
                        ColorStateList.valueOf("#FEE2E2".toColorInt())
                    binding.tvSecurityLabelBadge.setTextColor("#EF4444".toColorInt())
                }
                else -> {
                    binding.tvSecurityLabelBadge.text = "WASPADA"
                    binding.tvSecurityLabelBadge.backgroundTintList =
                        ColorStateList.valueOf("#FEF3C7".toColorInt())
                    binding.tvSecurityLabelBadge.setTextColor("#F59E0B".toColorInt())
                }
            }
        } catch (_: Exception) {}
    }

    private fun refreshIncidentOverlays() {
        val now = System.currentTimeMillis()
        if (now - lastOverlayRefresh < 500) return
        lastOverlayRefresh = now
        incidentOverlays.forEach { binding.map.overlays.remove(it) }
        incidentOverlays.clear()
        if (binding.map.zoomLevelDouble < 11.0) { binding.map.invalidate(); return }
        val zoom = binding.map.zoomLevelDouble
        val sz: Float = when {
            zoom>=17.0->38f; zoom>=15.0->30f; zoom>=13.0->22f
            zoom>=12.0->16f; zoom>=11.0->10f; else->0f
        }
        lastIncidentSnapshot?.documents?.forEach { doc ->
            val loc = doc.getGeoPoint("lokasi") ?: return@forEach
            val pt  = GeoPoint(loc.latitude, loc.longitude)
            val bh  = doc.getLong("tingkatBahaya")?.toInt() ?: 3
            val rad = doc.getDouble("radiusMeters") ?: 250.0
            if (isHeatmapActive) {
                val c = when { bh>=4->"#77E74C3C"; bh>=3->"#77F59E0B"; else->"#7710B981" }
                val circle = Polygon().apply {
                    points = Polygon.pointsAsCircle(pt, rad)
                    fillPaint.color    = c.toColorInt()
                    outlinePaint.color = c.replace("#77","#BB").toColorInt()
                    outlinePaint.strokeWidth = 3f
                    setOnClickListener { _, _, _ -> openIncidentDetail(doc.id); true }
                }
                incidentOverlays.add(circle); binding.map.overlays.add(circle)
            } else {
                if (sz == 0f) return@forEach
                val mc = when { bh>=4->"#E74C3C"; bh==3->"#F59E0B"; else->"#10B981" }
                val marker = Marker(binding.map).apply {
                    position = pt; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    icon = buildIncidentMarkerIcon(mc, sz)
                    setOnMarkerClickListener { _, _ -> openIncidentDetail(doc.id); true }
                }
                incidentOverlays.add(marker); binding.map.overlays.add(marker)
            }
        }
        binding.map.invalidate()
    }

    /**
     * Buka detail insiden di BottomSheet.
     * CRASH-GUARD: cek isIncidentSheetShowing agar tidak double-commit fragment.
     * Semua commit dibungkus try-catch agar tidak crash saat lifecycle tidak valid.
     */
    private fun openIncidentDetail(incidentId: String) {
        if (isFinishing || isDestroyed) return

        try {
            // Tutup vehicle sheet dulu jika terbuka
            if (vehicleSheetBehavior.state != BottomSheetBehavior.STATE_HIDDEN) {
                vehicleSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
            }

            // Replace fragment
            supportFragmentManager.beginTransaction()
                .replace(binding.incidentDetailContainer.id,
                    IncidentDetailFragment.newInstance(incidentId))
                .commitAllowingStateLoss()   // ← mencegah crash saat state sudah tersimpan

            isIncidentSheetShowing = true
            // Buka sheet ke setengah
            Handler(Looper.getMainLooper()).postDelayed({
                if (!isFinishing && !isDestroyed) {
                    bottomSheetBehavior.state = BottomSheetBehavior.STATE_HALF_EXPANDED
                }
            }, 100)

        } catch (e: Exception) {
            Log.e("Sheet", "openIncidentDetail error: ${e.message}", e)
        }
    }

    private fun buildIncidentMarkerIcon(colorHex: String, sizeDp: Float): android.graphics.drawable.BitmapDrawable {
        val d = resources.displayMetrics.density
        val w = (sizeDp*d).toInt().coerceAtLeast(8); val h=(sizeDp*1.45f*d).toInt().coerceAtLeast(10)
        val bmp=createBitmap(w,h,Bitmap.Config.ARGB_8888); val cv=Canvas(bmp)
        val p=Paint(Paint.ANTI_ALIAS_FLAG); val cx=w/2f; val r=w/2f-d
        p.style=Paint.Style.FILL; p.color=colorHex.toColorInt(); cv.drawCircle(cx,r+d,r,p)
        val cb=r*2+d
        cv.drawPath(Path().apply {
            moveTo(cx-r*0.55f,cb-r*0.35f); lineTo(cx,h.toFloat()-d*0.5f)
            lineTo(cx+r*0.55f,cb-r*0.35f); close()
        },p)
        p.style=Paint.Style.STROKE; p.color=Color.WHITE; p.strokeWidth=(1.2f*d).coerceAtLeast(1f)
        cv.drawCircle(cx,r+d,r-p.strokeWidth/2,p)
        if (sizeDp>=16f){p.style=Paint.Style.FILL;p.color=Color.WHITE;cv.drawCircle(cx,r+d,r*0.32f,p)}
        return android.graphics.drawable.BitmapDrawable(resources,bmp)
    }

    // =========================================================
    // UI
    // =========================================================
    private fun setupUI() {
        binding.llToggleSummary.setOnClickListener {
            binding.llSummaryContent.visibility =
                if (binding.llSummaryContent.isVisible) View.GONE else View.VISIBLE
        }

        // ── Tombol kendaraan ──────────────────────────────────────────────────
        binding.btnVehicleType.setOnClickListener {
            if (isFinishing || isDestroyed) return@setOnClickListener
            try {
                // Tutup incident sheet dulu
                if (bottomSheetBehavior.state != BottomSheetBehavior.STATE_HIDDEN)
                    bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN

                val frag = VehicleSelectionFragment.newInstance(selectedVehicle)
                frag.onVehicleSelected = { vehicle ->
                    selectedVehicle = vehicle
                    updateVehicleUI(vehicle)
                    vehicleSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
                    pendingDestination?.let { dest ->
                        activeDestLat=0.0; activeDestLng=0.0
                        fastestRoutePoints=listOf(); fastestRiskData=null; routeCutIndex=0
                        drawRoute(dest, currentRouteMode, pendingDestName ?: "", false)
                    }
                }

                supportFragmentManager.beginTransaction()
                    .replace(binding.vehicleSelectionContainer.id, frag)
                    .commitAllowingStateLoss()   // ← mencegah crash

                isVehicleSheetShowing = true
                Handler(Looper.getMainLooper()).postDelayed({
                    if (!isFinishing && !isDestroyed) {
                        vehicleSheetBehavior.state = BottomSheetBehavior.STATE_HALF_EXPANDED
                    }
                }, 100)

            } catch (e: Exception) {
                Log.e("Sheet", "btnVehicleType error: ${e.message}", e)
            }
        }

        binding.llSearchMode.setOnClickListener {
            if (!isHeatmapActive)
                searchResultLauncher.launch(Intent(this, SearchActivity::class.java))
        }

        setupSosHeartbeatAnimation()

        binding.btnSosHome.setOnClickListener {
            if (hasPhoneNumber && hasEmergencyContacts)
                startActivity(Intent(this, PanicModeActivity::class.java))
            else
                binding.cvSosTooltip.visibility = View.VISIBLE
        }
        binding.btnAddContactNow.setOnClickListener {
            binding.cvSosTooltip.visibility = View.GONE
            startActivity(Intent(this, EmergencyContactsActivity::class.java))
        }

        binding.btnCloseNav.setOnClickListener {
            roadOverlay?.let { binding.map.overlays.remove(it) }
            roadOverlay = null
            binding.cvNavInstruction.visibility = View.GONE
            binding.map.invalidate()
            fullRoutePoints = listOf(); resetRouteState()
        }

        binding.btnFilterHeatmap.setOnClickListener {
            isHeatmapActive = !isHeatmapActive
            binding.btnFilterHeatmap.setCardBackgroundColor(
                if (isHeatmapActive) "#C0392B".toColorInt() else Color.WHITE)
            binding.ivFilterHeatmap.setColorFilter(
                if (isHeatmapActive) Color.WHITE else "#475569".toColorInt())
            binding.switchHeatmapHome.visibility = if (isHeatmapActive) View.VISIBLE else View.GONE
            binding.switchHeatmapHome.isChecked  = isHeatmapActive
            binding.tvSearchPlaceholder.text     =
                if (isHeatmapActive) "Heatmap: AKTIF" else getString(R.string.search_destination_placeholder)
            binding.ivSearchIcon.imageTintList   = ColorStateList.valueOf(
                if (isHeatmapActive) "#C0392B".toColorInt() else "#94A3B8".toColorInt())
            binding.cvLegend.isVisible = isHeatmapActive
            refreshIncidentOverlays()
        }

        binding.switchHeatmapHome.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            if (checked != isHeatmapActive) binding.btnFilterHeatmap.performClick()
        }

        binding.btnGps.setOnClickListener {
            ValueAnimator.ofObject(ArgbEvaluator(), Color.WHITE, "#FFEBEB".toColorInt(), Color.WHITE).apply {
                duration = 300
                addUpdateListener { a -> binding.btnGps.setCardBackgroundColor(a.animatedValue as Int) }
                start()
            }
            binding.ivGpsIcon.setColorFilter("#C0392B".toColorInt())
            binding.ivGpsIcon.postDelayed({ binding.ivGpsIcon.setColorFilter("#475569".toColorInt()) }, 300)
            val target = when {
                displayedLat != 0.0 && displayedLng != 0.0 -> GeoPoint(displayedLat, displayedLng)
                lastKnownLocation != null -> GeoPoint(lastKnownLocation!!.latitude, lastKnownLocation!!.longitude)
                else -> null
            }
            target?.let { binding.map.controller.animateTo(it, 17.5, 800L) }
        }

        binding.ivProfileHome.setOnClickListener {
            binding.bottomNavigation.selectedItemId = R.id.nav_profile
        }
    }

    private fun updateVehicleUI(vehicle: String) {
        val isK = vehicle == "Kereta"
        binding.tvSelectedVehicle.text    = vehicle
        binding.tvVehicleIconTop.text     = if (isK) "🛵" else "🚗"
        binding.tvVehicleIconSummary.text = if (isK) "🛵" else "🚗"
    }

    private fun setupSosHeartbeatAnimation() {
        ValueAnimator.ofFloat(0.9f, 1.15f).apply {
            duration=2000; repeatCount=ValueAnimator.INFINITE; repeatMode=ValueAnimator.REVERSE
            interpolator=AccelerateDecelerateInterpolator()
            addUpdateListener { a ->
                val sc = a.animatedValue as Float
                binding.vSosRing.scaleX=sc; binding.vSosRing.scaleY=sc
                binding.vSosRing.alpha=0.5f*(sc-0.75f)
            }; start()
        }
    }

    // =========================================================
    // NAVIGATION & FRAGMENT
    // =========================================================
    private fun setupNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            dismissAllSheets()
            binding.cvSosTooltip.visibility = View.GONE
            when (item.itemId) {
                R.id.nav_home          -> { hideAllFragments(); true }
                R.id.nav_report        -> { showFragment(ReportFragment()); true }
                R.id.nav_notifications -> { showFragment(FeedFragment()); true }
                R.id.nav_profile       -> { showFragment(ProfileFragment()); true }
                else -> false
            }
        }
    }

    private fun showFragment(fragment: Fragment) {
        if (isFinishing || isDestroyed) return
        binding.fragmentContainer.visibility = View.VISIBLE
        binding.clMapContent.visibility      = View.GONE
        try {
            supportFragmentManager.beginTransaction()
                .replace(binding.fragmentContainer.id, fragment)
                .commitAllowingStateLoss()
        } catch (e: Exception) { Log.e("Fragment", "showFragment error: ${e.message}") }
    }

    private fun hideAllFragments() {
        if (isFinishing || isDestroyed) return
        binding.fragmentContainer.visibility = View.GONE
        binding.clMapContent.visibility      = View.VISIBLE
        try {
            supportFragmentManager.findFragmentById(binding.fragmentContainer.id)
                ?.let { supportFragmentManager.beginTransaction().remove(it).commitAllowingStateLoss() }
        } catch (e: Exception) { Log.e("Fragment", "hideAllFragments error: ${e.message}") }
    }

    // =========================================================
    // BOTTOM SHEETS  –  smooth swipe, skipCollapsed=false
    // =========================================================
    private fun setupBottomSheet() {
        // ── Incident BottomSheet ───────────────────────────────────────────────
        bottomSheetBehavior = BottomSheetBehavior.from(binding.incidentDetailContainer)
        bottomSheetBehavior.state             = BottomSheetBehavior.STATE_HIDDEN
        bottomSheetBehavior.peekHeight        = 0
        bottomSheetBehavior.isHideable        = true
        bottomSheetBehavior.isFitToContents   = false
        bottomSheetBehavior.halfExpandedRatio = 0.65f
        // skipCollapsed=false → user bisa swipe setengah, lalu swipe lagi ke bawah untuk tutup
        bottomSheetBehavior.skipCollapsed     = false
        bottomSheetBehavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(v: View, s: Int) {
                if (s == BottomSheetBehavior.STATE_HIDDEN) {
                    isIncidentSheetShowing = false
                    try {
                        supportFragmentManager.findFragmentById(binding.incidentDetailContainer.id)
                            ?.let { supportFragmentManager.beginTransaction()
                                .remove(it).commitAllowingStateLoss() }
                    } catch (_: Exception) {}
                }
            }
            override fun onSlide(v: View, o: Float) {}
        })

        // ── Vehicle BottomSheet ────────────────────────────────────────────────
        vehicleSheetBehavior = BottomSheetBehavior.from(binding.vehicleSelectionContainer)
        vehicleSheetBehavior.state             = BottomSheetBehavior.STATE_HIDDEN
        vehicleSheetBehavior.peekHeight        = 0
        vehicleSheetBehavior.isHideable        = true
        vehicleSheetBehavior.isFitToContents   = false
        vehicleSheetBehavior.halfExpandedRatio = 0.70f
        vehicleSheetBehavior.skipCollapsed     = false
        vehicleSheetBehavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(v: View, s: Int) {
                if (s == BottomSheetBehavior.STATE_HIDDEN) {
                    isVehicleSheetShowing = false
                    try {
                        supportFragmentManager.findFragmentById(binding.vehicleSelectionContainer.id)
                            ?.let { supportFragmentManager.beginTransaction()
                                .remove(it).commitAllowingStateLoss() }
                    } catch (_: Exception) {}
                }
            }
            override fun onSlide(v: View, o: Float) {}
        })
    }

    private fun loadUserProfileImage() {
        val uid = auth.currentUser?.uid ?: return
        db.collection("users").document(uid).addSnapshotListener { doc, _ ->
            if (doc != null && doc.exists()) {
                val img = doc.getString("profileImageUrl") ?: ""
                if (img.isEmpty()) return@addSnapshotListener
                if (img.startsWith("data:image"))
                    Glide.with(this).load(Base64.decode(img.substringAfter(","), Base64.DEFAULT))
                        .circleCrop().into(binding.ivProfileHome)
                else
                    Glide.with(this).load(img).circleCrop()
                        .placeholder(R.drawable.ic_person).into(binding.ivProfileHome)
            }
        }
    }

    // =========================================================
    // LOCATION
    // =========================================================
    private fun checkLocationPermission() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED -> checkGpsEnabled()
            else -> requestLocationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableMyLocation() {
        myLocationOverlay.enableMyLocation()
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
            .setMinUpdateDistanceMeters(0f)
            .setWaitForAccurateLocation(true)
            .build()

        fusedLocationClient.requestLocationUpdates(req, object : LocationCallback() {
            override fun onLocationResult(res: LocationResult) {
                val loc = res.lastLocation ?: return
                lastKnownLocation = loc; myLocationOverlay.onLocationChanged(loc, null)
                if (isFirstLocation) {
                    displayedLat = loc.latitude; displayedLng = loc.longitude
                    targetLat    = loc.latitude; targetLng    = loc.longitude
                    isFirstLocation = false
                    val geo = GeoPoint(loc.latitude, loc.longitude)
                    initCompassMarker(geo); initUserAreaOverlay(geo)
                    binding.map.controller.animateTo(geo, 17.5, 800L)
                } else {
                    // Update TARGET saja → Lerp 60fps yang urus smooth movement
                    targetLat = loc.latitude; targetLng = loc.longitude
                }
                updateRouteCuttingSmooth(loc)
            }
        }, Looper.getMainLooper())
    }

    private fun initUserAreaOverlay(point: GeoPoint) {
        if (userAreaOverlay != null) return
        userAreaOverlay = Polygon().apply {
            fillPaint.color          = "#154285F4".toColorInt()
            outlinePaint.color       = "#404285F4".toColorInt()
            outlinePaint.strokeWidth = 1.5f
            points = Polygon.pointsAsCircle(point, 80.0)
            binding.map.overlays.add(0, this)
        }
    }

    /**
     * Smart Route Cutting + Off-Route Detection
     * (sistem navigasi mirip Gojek/Maps, tanpa copyright)
     *
     * 1. SMOOTH CUTTING:
     *    Scan maks 120 titik ke depan dari routeCutIndex.
     *    Jika user ≤ 50m dari jalur → potong bagian yang sudah dilewati.
     *    Polyline terpangkas smooth, marker bergerak 60fps via Lerp.
     *
     * 2. OFF-ROUTE:
     *    Jika user > 100m selama 4 update GPS (~4 detik) → recalculate.
     *    Throttle 12 detik agar tidak spam request OSRM.
     */
    private fun updateRouteCuttingSmooth(loc: Location) {
        if (fullRoutePoints.isEmpty() || roadOverlay == null) return

        // PENTING: Gunakan posisi yang DITAMPILKAN (displayedLat/Lng)
        // bukan posisi GPS mentah, agar route cutting sinkron
        // dengan marker yang terlihat di layar
        val dispLat = if (displayedLat != 0.0) displayedLat else loc.latitude
        val dispLng = if (displayedLng != 0.0) displayedLng else loc.longitude
        val geo     = GeoPoint(dispLat, dispLng)

        val safeIdx = routeCutIndex.coerceAtMost(fullRoutePoints.size - 1)
        var bestIdx = safeIdx
        var minDist = distanceBetween(geo, fullRoutePoints[safeIdx])
        val scanEnd = minOf(safeIdx + 120, fullRoutePoints.size)

        for (i in (safeIdx + 1) until scanEnd) {
            val d = distanceBetween(geo, fullRoutePoints[i])
            if (d < minDist) { minDist = d; bestIdx = i }
            else if (d > minDist + 80.0) break
        }

        // Potong rute jika marker sudah melewati titik tersebut
        if (minDist <= 50.0 && bestIdx > routeCutIndex) {
            routeCutIndex = bestIdx
            val rem = fullRoutePoints.subList(routeCutIndex, fullRoutePoints.size)
            if (rem.size >= 2) {
                roadOverlay?.setPoints(rem)
                binding.map.postInvalidate()
            }
        }

        // Off-route detection menggunakan posisi GPS asli (loc)
        // bukan displayed position, agar lebih akurat
        val geoRaw = GeoPoint(loc.latitude, loc.longitude)
        val rawSafeIdx = routeCutIndex.coerceAtMost(fullRoutePoints.size - 1)
        var rawMinDist = distanceBetween(geoRaw, fullRoutePoints[rawSafeIdx])
        val rawScanEnd = minOf(rawSafeIdx + 30, fullRoutePoints.size)
        for (i in (rawSafeIdx + 1) until rawScanEnd) {
            val d = distanceBetween(geoRaw, fullRoutePoints[i])
            if (d < rawMinDist) rawMinDist = d
        }

        if (rawMinDist > 100.0) offRouteFrameCount++ else offRouteFrameCount = 0

        val now = System.currentTimeMillis()
        if (offRouteFrameCount >= 4 && (now - lastOffRouteMs) > 12_000L) {
            lastOffRouteMs     = now
            offRouteFrameCount = 0
            val dest = pendingDestination ?: return
            Toast.makeText(
                this,
                "📍 Keluar jalur — menghitung rute baru...",
                Toast.LENGTH_SHORT
            ).show()
            activeDestLat   = 0.0
            activeDestLng   = 0.0
            routeCutIndex   = 0
            fullRoutePoints = listOf()
            drawRoute(
                GeoPoint(dest.latitude, dest.longitude),
                currentRouteMode,
                pendingDestName ?: getString(R.string.location),
                false
            )
        }
    }

    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    bottomSheetBehavior.state != BottomSheetBehavior.STATE_HIDDEN ->
                        bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
                    vehicleSheetBehavior.state != BottomSheetBehavior.STATE_HIDDEN ->
                        vehicleSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
                    binding.cvSosTooltip.isVisible ->
                        binding.cvSosTooltip.visibility = View.GONE
                    binding.bottomNavigation.selectedItemId != R.id.nav_home ->
                        binding.bottomNavigation.selectedItemId = R.id.nav_home
                    System.currentTimeMillis() - backPressedTime < 2000 -> finish()
                    else -> {
                        Toast.makeText(this@HomeActivity,
                            getString(R.string.back_press_exit), Toast.LENGTH_SHORT).show()
                        backPressedTime = System.currentTimeMillis()
                    }
                }
            }
        })
    }

    private fun handleIntentExtras(intent: Intent) {
        val focus = intent.getBooleanExtra("FOCUS_LOCATION", false)
        val lat   = intent.getDoubleExtra("LATITUDE", 0.0)
        val lng   = intent.getDoubleExtra("LONGITUDE", 0.0)
        val name  = intent.getStringExtra("INCIDENT_NAME") ?: getString(R.string.incident)
        if (focus && lat != 0.0 && lng != 0.0) {
            val dest = GeoPoint(lat, lng)
            pendingDestination = dest; pendingDestName = name; resetRouteState()
            binding.bottomNavigation.selectedItemId = R.id.nav_home
            binding.map.postDelayed({
                binding.map.controller.animateTo(dest, 17.5, 1000L)
                drawRoute(dest, "FASTEST", name, false)
            }, 500)
        }
    }

    // =========================================================
    // UTILITY
    // =========================================================
    private fun distanceBetween(p1: GeoPoint, p2: GeoPoint): Double {
        val r    = 6371e3
        val dLat = (p2.latitude  - p1.latitude)  * Math.PI / 180
        val dLon = (p2.longitude - p1.longitude) * Math.PI / 180
        val a    = sin(dLat/2).pow(2) +
                cos(p1.latitude*Math.PI/180) *
                cos(p2.latitude*Math.PI/180) *
                sin(dLon/2).pow(2)
        return r * 2 * atan2(sqrt(a), sqrt(1-a))
    }

    private fun decodePolyline(encoded: String): List<GeoPoint> {
        val poly=ArrayList<GeoPoint>(); var index=0; var lat=0; var lng=0
        while (index < encoded.length) {
            var b: Int; var shift=0; var result=0
            do { b=encoded[index++].code-63; result=result or((b and 0x1f) shl shift); shift+=5 } while (b>=0x20)
            lat += if (result and 1!=0) (result shr 1).inv() else result shr 1
            shift=0; result=0
            do { b=encoded[index++].code-63; result=result or((b and 0x1f) shl shift); shift+=5 } while (b>=0x20)
            lng += if (result and 1!=0) (result shr 1).inv() else result shr 1
            poly.add(GeoPoint(lat.toDouble()/1E5, lng.toDouble()/1E5))
        }
        return poly
    }
}