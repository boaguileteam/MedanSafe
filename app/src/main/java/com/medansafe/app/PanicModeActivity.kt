package com.medansafe.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Looper
import android.telephony.SmsManager
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.util.*

class PanicModeActivity : AppCompatActivity() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var countDownTimer: CountDownTimer? = null

    private lateinit var mapView: MapView
    private lateinit var myLocationOverlay: MyLocationNewOverlay

    data class EmergencyContact(
        val name: String,
        val phone: String
    ) {
        fun getInitial(): String {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) return "??"
            val words = trimmed.split("\\s+".toRegex())
            return if (words.size >= 2) {
                (words[0].take(1) + words[1].take(1)).uppercase()
            } else {
                trimmed.take(2).uppercase()
            }
        }
    }

    private var contactList = mutableListOf<EmergencyContact>()
    private var currentAddress = ""
    private var currentCity = ""
    private var lastLocation: Location? = null

    private val requestSmsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCountdown()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Izin Diperlukan")
                .setMessage("MedanSafe memerlukan izin SMS untuk mengirim pesan darurat ke kontak Anda secara otomatis saat mode panik aktif.")
                .setPositiveButton("Mengerti") { _, _ -> finish() }
                .show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName
        setContentView(R.layout.activity_panic)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        mapView = findViewById(R.id.map)
        setupOsmMap()

        prepareInitialUI()
        startAnimations()

        findViewById<LinearLayout>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<MaterialCardView>(R.id.btn_sos).setOnClickListener {
            if (contactList.isEmpty()) {
                Toast.makeText(this, "Atur kontak darurat di Profil dulu", Toast.LENGTH_LONG).show()
            } else {
                checkSmsPermission()
            }
        }
        findViewById<MaterialButton>(R.id.btn_cancel).setOnClickListener {
            countDownTimer?.cancel()
            finish()
        }

        startLocationUpdates()
        syncWithUserSystem()
    }

    private fun checkSmsPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
            startCountdown()
        } else {
            requestSmsPermissionLauncher.launch(Manifest.permission.SEND_SMS)
        }
    }

    private fun setupOsmMap() {
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        mapView.controller.setZoom(16.0)
        myLocationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(this), mapView)
        myLocationOverlay.enableMyLocation()
        mapView.overlays.add(myLocationOverlay)
    }

    private fun prepareInitialUI() {
        findViewById<TextView>(R.id.tv_location_name).text = ""
        findViewById<TextView>(R.id.tv_location_detail).text = "Mencari lokasi akurat..."
        findViewById<MaterialCardView>(R.id.location_card).alpha = 0.5f
    }

    private fun startAnimations() {
        startHeartbeatAnimation(findViewById(R.id.btn_sos))
        startRippleAnimation(findViewById(R.id.ring_1), 0)
        startRippleAnimation(findViewById(R.id.ring_2), 800)
        startRippleAnimation(findViewById(R.id.ring_3), 1600)
        startBlinkingLive(findViewById(R.id.live_dot))
    }

    private fun startLocationUpdates() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(res: LocationResult) {
                res.lastLocation?.let { loc ->
                    lastLocation = loc
                    updateAddressUI(loc)
                    mapView.controller.animateTo(GeoPoint(loc.latitude, loc.longitude))
                }
            }
        }
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000).build()
        try { fusedLocationClient.requestLocationUpdates(req, locationCallback, Looper.getMainLooper()) } catch (_: SecurityException) {}
    }

    private fun updateAddressUI(loc: Location) {
        val geocoder = Geocoder(this, Locale.getDefault())
        try {
            @Suppress("DEPRECATION")
            val addr = geocoder.getFromLocation(loc.latitude, loc.longitude, 1)
            if (!addr.isNullOrEmpty()) {
                val address = addr[0]
                currentAddress = formatAddress(address)
                currentCity = address.locality ?: address.subAdminArea ?: "Medan"

                runOnUiThread {
                    findViewById<TextView>(R.id.tv_location_name).text = currentAddress
                    findViewById<TextView>(R.id.tv_location_detail).text = if (address.subLocality != null) "Kec. ${address.locality}, ${address.subLocality}" else currentCity
                    findViewById<MaterialCardView>(R.id.location_card).alpha = 1f
                }
            }
        } catch (_: Exception) {}
    }

    private fun formatAddress(address: Address): String {
        val parts = mutableListOf<String>()
        val street = address.thoroughfare
        val feature = address.featureName
        if (!street.isNullOrEmpty()) {
            if (!feature.isNullOrEmpty() && feature != street && !feature.contains("+")) parts.add("$street No. $feature")
            else parts.add(street)
        } else if (!feature.isNullOrEmpty() && !feature.contains("+")) parts.add(feature)
        address.subLocality?.let { parts.add(it) }
        address.locality?.let { parts.add("Kec. $it") }
        return if (parts.isNotEmpty()) parts.joinToString(", ")
        else address.getAddressLine(0)?.replace(Regex("[A-Z0-9]{4,8}\\+[A-Z0-9]{2,4},?\\s?"), "")?.trim() ?: "Lokasi"
    }

    private fun syncWithUserSystem() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        FirebaseFirestore.getInstance().collection("users").document(uid).collection("emergency_contacts")
            .get().addOnSuccessListener { docs ->
                contactList.clear()
                docs.forEach { doc ->
                    val n = doc.getString("nama") ?: ""; val p = doc.getString("noTelpon") ?: ""
                    if (n.isNotEmpty() && p.isNotEmpty()) contactList.add(EmergencyContact(n, p))
                }
                updateContactsUI()
            }
    }

    private fun updateContactsUI() {
        val label = findViewById<TextView>(R.id.contacts_label)
        val c1 = findViewById<View>(R.id.contact_1)
        val c2 = findViewById<View>(R.id.contact_2)
        val c3 = findViewById<View>(R.id.contact_3)
        if (contactList.isEmpty()) {
            label.text = "Belum ada kontak terhubung"
            c1.visibility = View.GONE; c2.visibility = View.GONE; c3.visibility = View.GONE
            return
        }
        label.text = "${contactList.size} Kontak Darurat Siaga"
        if (contactList.size >= 1) {
            c1.visibility = View.VISIBLE
            findViewById<TextView>(R.id.tv_name_1).text = contactList[0].name
            findViewById<TextView>(R.id.tv_phone_1).text = contactList[0].phone
            findViewById<TextView>(R.id.tv_initial_1).text = contactList[0].getInitial()
        } else c1.visibility = View.GONE
        if (contactList.size >= 2) {
            c2.visibility = View.VISIBLE
            findViewById<TextView>(R.id.tv_name_2).text = contactList[1].name
            findViewById<TextView>(R.id.tv_phone_2).text = contactList[1].phone
            findViewById<TextView>(R.id.tv_initial_2).text = contactList[1].getInitial()
        } else c2.visibility = View.GONE
        if (contactList.size >= 3) {
            c3.visibility = View.VISIBLE
            findViewById<TextView>(R.id.tv_name_3).text = contactList[2].name
            findViewById<TextView>(R.id.tv_phone_3).text = contactList[2].phone
            findViewById<TextView>(R.id.tv_initial_3).text = contactList[2].getInitial()
        } else c3.visibility = View.GONE
    }

    private fun startCountdown() {
        countDownTimer = object : CountDownTimer(3000, 1000) {
            override fun onTick(ms: Long) {
                Toast.makeText(this@PanicModeActivity, "Sinyal SOS akan dikirim dalam ${(ms/1000)+1}...", Toast.LENGTH_SHORT).show()
            }
            override fun onFinish() {
                sendAutoSms()
                goToConfirmSOS()
            }
        }.start()
    }

    private fun sendAutoSms() {
        val lat = lastLocation?.latitude ?: 0.0
        val lng = lastLocation?.longitude ?: 0.0
        val googleMapsUrl = "https://www.google.com/maps/search/?api=1&query=$lat,$lng"

        // Pesan disesuaikan dengan konteks MedanSafe
        val message = "DARURAT! Saya butuh bantuan segera. Lokasi saya di: $currentAddress ($googleMapsUrl). Dikirim via fitur SOS MedanSafe."

        try {
            val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                this.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }

            // Membagi pesan jika lebih dari 160 karakter agar tidak gagal kirim
            val parts = smsManager.divideMessage(message)
            contactList.forEach { contact ->
                // Bersihkan nomor HP dari karakter non-angka
                val cleanPhone = contact.phone.replace("[^0-9+]".toRegex(), "")
                if (cleanPhone.isNotEmpty()) {
                    if (parts.size > 1) {
                        smsManager.sendMultipartTextMessage(cleanPhone, null, parts, null, null)
                    } else {
                        smsManager.sendTextMessage(cleanPhone, null, message, null, null)
                    }
                }
            }
            Log.d("SOS", "SMS Darurat berhasil diproses untuk dikirim.")
        } catch (e: Exception) {
            Log.e("SOS", "Gagal mengirim SMS otomatis: ${e.message}")
            Toast.makeText(this, "Gagal kirim SMS: Pastikan pulsa tersedia.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun goToConfirmSOS() {
        val intent = Intent(this, SOSConfirmActivity::class.java).apply {
            putExtra("LAT", lastLocation?.latitude ?: 0.0)
            putExtra("LNG", lastLocation?.longitude ?: 0.0)
            putExtra("ADDR", currentAddress)
        }
        startActivity(intent)
        finish()
    }

    private fun startHeartbeatAnimation(v: View) {
        v.animate().scaleX(1.1f).scaleY(1.1f).setDuration(800).withEndAction {
            v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(800).withEndAction { startHeartbeatAnimation(v) }.start()
        }.start()
    }

    private fun startRippleAnimation(v: View, d: Long) {
        v.animate().scaleX(2.2f).scaleY(2.2f).alpha(0f).setDuration(2500).setStartDelay(d).withEndAction {
            v.scaleX = 0.8f; v.scaleY = 0.8f; v.alpha = 0.5f; startRippleAnimation(v, 0)
        }.start()
    }

    private fun startBlinkingLive(v: View?) {
        v?.animate()?.alpha(0.2f)?.setDuration(700)?.withEndAction {
            v.animate()?.alpha(1f)?.setDuration(700)?.withEndAction { startBlinkingLive(v) }?.start()
        }?.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::fusedLocationClient.isInitialized && ::locationCallback.isInitialized) fusedLocationClient.removeLocationUpdates(locationCallback)
        countDownTimer?.cancel()
    }
}
