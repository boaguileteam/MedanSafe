package com.medansafe.app

import android.content.Intent
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.location.LocationServices
import com.medansafe.app.databinding.ActivitySelectLocationBinding
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import java.util.*

class SelectLocationActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySelectLocationBinding
    private var selectedLat: Double = 0.0
    private var selectedLng: Double = 0.0
    private var timer: Timer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        binding = ActivitySelectLocationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val initLat = intent.getDoubleExtra("INIT_LAT", 0.0)
        val initLng = intent.getDoubleExtra("INIT_LNG", 0.0)

        setupMap()

        if (initLat != 0.0 && initLng != 0.0) {
            val point = GeoPoint(initLat, initLng)
            binding.mapSelect.controller.setCenter(point)
            selectedLat = initLat
            selectedLng = initLng
            getAddressFromLocation(selectedLat, selectedLng)
        } else {
            detectCurrentLocation()
        }

        binding.btnConfirmLocation.setOnClickListener {
            val resultIntent = Intent()
            resultIntent.putExtra("SELECTED_LAT", selectedLat)
            resultIntent.putExtra("SELECTED_LNG", selectedLng)
            resultIntent.putExtra("SELECTED_ADDR", binding.tvSelectedAddr.text.toString())
            setResult(RESULT_OK, resultIntent)
            finish()
        }

        binding.btnGpsSelect.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            detectCurrentLocation()
        }
    }

    private fun setupMap() {
        binding.mapSelect.setTileSource(TileSourceFactory.MAPNIK)
        binding.mapSelect.setMultiTouchControls(true)
        binding.mapSelect.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        binding.mapSelect.controller.setZoom(17.5)

        binding.mapSelect.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent): Boolean {
                scheduleUpdate()
                return true
            }
            override fun onZoom(event: ZoomEvent): Boolean {
                scheduleUpdate()
                return true
            }
        })
    }

    private fun scheduleUpdate() {
        timer?.cancel()
        timer = Timer()
        timer?.schedule(object : TimerTask() {
            override fun run() {
                runOnUiThread { updateSelectedLocation() }
            }
        }, 350)
    }

    private fun updateSelectedLocation() {
        val center = binding.mapSelect.mapCenter as? GeoPoint ?: return
        selectedLat = center.latitude
        selectedLng = center.longitude
        getAddressFromLocation(selectedLat, selectedLng)
    }

    private fun detectCurrentLocation() {
        val fusedClient = LocationServices.getFusedLocationProviderClient(this)
        try {
            fusedClient.lastLocation.addOnSuccessListener { location ->
                location?.let {
                    val point = GeoPoint(it.latitude, it.longitude)
                    binding.mapSelect.controller.animateTo(point)
                    selectedLat = it.latitude
                    selectedLng = it.longitude
                    getAddressFromLocation(selectedLat, selectedLng)
                }
            }
        } catch (_: SecurityException) {}
    }

    private fun getAddressFromLocation(lat: Double, lng: Double) {
        val geocoder = Geocoder(this, Locale.getDefault())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            geocoder.getFromLocation(lat, lng, 1) { addresses ->
                if (addresses.isNotEmpty()) {
                    val addr = formatAddress(addresses[0])
                    runOnUiThread { binding.tvSelectedAddr.text = addr }
                }
            }
        } else {
            try {
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(lat, lng, 1)
                if (!addresses.isNullOrEmpty()) {
                    binding.tvSelectedAddr.text = formatAddress(addresses[0])
                }
            } catch (_: Exception) {
                binding.tvSelectedAddr.text = String.format(Locale.getDefault(), "%.4f, %.4f", lat, lng)
            }
        }
    }

    private fun formatAddress(address: Address): String {
        val parts = mutableListOf<String>()
        
        val street = address.thoroughfare
        val feature = address.featureName
        
        if (!street.isNullOrEmpty()) {
            if (!feature.isNullOrEmpty() && feature != street && !feature.contains("+")) {
                parts.add("$street No. $feature")
            } else {
                parts.add(street)
            }
        } else if (!feature.isNullOrEmpty() && !feature.contains("+")) {
            parts.add(feature)
        }
        
        address.subLocality?.let { parts.add(it) }
        address.locality?.let { parts.add("Kec. $it") }
        address.subAdminArea?.let { parts.add(it) }
        
        return if (parts.isNotEmpty()) {
            parts.joinToString(", ")
        } else {
            address.getAddressLine(0)?.replace(Regex("[A-Z0-9]{4,8}\\+[A-Z0-9]{2,4},?\\s?"), "")?.trim() ?: "Lokasi tidak diketahui"
        }
    }

    override fun onResume() { super.onResume(); binding.mapSelect.onResume() }
    override fun onPause() { super.onPause(); binding.mapSelect.onPause() }
}
