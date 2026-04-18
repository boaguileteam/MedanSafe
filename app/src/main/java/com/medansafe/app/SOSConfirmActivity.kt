package com.medansafe.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.location.Geocoder
import android.os.Build
import android.os.Bundle
import android.telephony.SmsManager
import android.util.Log
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import com.google.android.gms.location.LocationServices
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.cos
import kotlin.math.sin

class SOSConfirmActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    
    private var lastLat = 3.5952
    private var lastLng = 98.6722
    private var currentAddressStr = ""

    private val requestSmsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            // Jika diizinkan, langsung coba kirim ulang SMS
            fetchContactsAndSendSms()
        } else {
            Toast.makeText(this, "Izin SMS diperlukan untuk mengirim pesan darurat otomatis", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sos_confirm)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        // Ambil data awal jika ada dari intent
        lastLat = intent.getDoubleExtra("LAT", 3.5952)
        lastLng = intent.getDoubleExtra("LNG", 98.6722)
        currentAddressStr = intent.getStringExtra("ADDR") ?: ""

        // UI References
        val ivCheck: ImageView = findViewById(R.id.iv_check_icon)
        val cvCheckBg: View = findViewById(R.id.cv_check_bg)
        val ringGreen: View = findViewById(R.id.view_ring_green)
        val ringStroke: View = findViewById(R.id.view_ring_stroke)
        val mainGlow: View = findViewById(R.id.view_glow)
        val outerGlow: View = findViewById(R.id.view_glow_outer)
        
        val tvTitle: View = findViewById(R.id.tv_title)
        val tvSubtitle: View = findViewById(R.id.tv_subtitle)
        val cardLoc: View = findViewById(R.id.card_location_detail)
        val layoutContacts: View = findViewById(R.id.layout_contacts)
        val layoutBottom: View = findViewById(R.id.layout_bottom_actions)

        val btnBackToMap: View = findViewById(R.id.btn_back_to_map)
        val btnShare: View = findViewById(R.id.btn_share_location)
        val btnEmergency: View = findViewById(R.id.btn_call_emergency)

        // Particles
        val particles = listOf<View>(
            findViewById(R.id.p1), findViewById(R.id.p2), findViewById(R.id.p3),
            findViewById(R.id.p4), findViewById(R.id.p5), findViewById(R.id.p6)
        )

        // Initial States for Content Animation
        val contents = listOf(tvTitle, tvSubtitle, cardLoc, layoutContacts, layoutBottom)
        contents.forEach { 
            it.alpha = 0f
            it.translationY = 50f
        }

        // 1. Run Premium Animations
        runPremiumSuccessAnimation(ivCheck, cvCheckBg, ringGreen, ringStroke, mainGlow, outerGlow, particles)
        animateContentEntrance(contents)

        // 2. Data Logic & Auto SMS
        syncDataAndSendSms()

        // 3. Navigation & Actions
        btnBackToMap.setOnClickListener {
            val intent = Intent(this, HomeActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
            finish()
        }

        btnShare.setOnClickListener { shareEmergencyLocation() }

        btnEmergency.setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_DIAL, "tel:110".toUri())
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Tidak dapat membuka dialer", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun animateContentEntrance(views: List<View>) {
        views.forEachIndexed { index, view ->
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(800)
                .setStartDelay(400L + (index * 100L))
                .setInterpolator(OvershootInterpolator(0.8f))
                .start()
        }
    }

    private fun runPremiumSuccessAnimation(check: View, whiteBg: View, greenRing: View, burstRing: View, mainGlow: View, outerGlow: View, particles: List<View>) {
        check.scaleX = 0f; check.scaleY = 0f; check.alpha = 0f
        whiteBg.scaleX = 0f; whiteBg.scaleY = 0f; whiteBg.alpha = 0f
        greenRing.scaleX = 0f; greenRing.scaleY = 0f; greenRing.alpha = 0f
        burstRing.scaleX = 1f; burstRing.scaleY = 1f; burstRing.alpha = 0f
        mainGlow.alpha = 0f; outerGlow.alpha = 0f

        greenRing.animate()
            .scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(400)
            .setInterpolator(OvershootInterpolator(1.2f))
            .start()

        whiteBg.animate()
            .scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(500)
            .setInterpolator(OvershootInterpolator(1.4f))
            .setStartDelay(100)
            .start()

        check.animate()
            .scaleX(1.1f).scaleY(1.1f).alpha(1f)
            .setDuration(400)
            .setInterpolator(OvershootInterpolator(2.0f))
            .setStartDelay(300)
            .withEndAction { 
                check.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
                explodeParticles(particles) 
            }
            .start()

        burstRing.alpha = 0.8f
        burstRing.animate()
            .scaleX(3f).scaleY(3f).alpha(0f)
            .setDuration(800)
            .setInterpolator(DecelerateInterpolator())
            .setStartDelay(350)
            .start()

        fun startPulse() {
            mainGlow.scaleX = 1f; mainGlow.scaleY = 1f; mainGlow.alpha = 0.4f
            outerGlow.scaleX = 1f; outerGlow.scaleY = 1f; outerGlow.alpha = 0.2f
            
            mainGlow.animate()
                .scaleX(2.0f).scaleY(2.0f).alpha(0f)
                .setDuration(2000)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
            
            outerGlow.animate()
                .scaleX(2.8f).scaleY(2.8f).alpha(0f)
                .setDuration(2800)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .withEndAction { if(!isFinishing) startPulse() }
                .start()
        }
        
        mainGlow.postDelayed({ startPulse() }, 800)
    }

    private fun explodeParticles(particles: List<View>) {
        val random = Random()
        particles.forEachIndexed { index, view ->
            view.alpha = 1f; view.scaleX = 1f; view.scaleY = 1f
            val angle = Math.toRadians((index * (360.0 / particles.size)) + random.nextInt(30))
            val radius = 300f + random.nextInt(150)
            val tx = (radius * cos(angle)).toFloat()
            val ty = (radius * sin(angle)).toFloat()
            view.animate().translationX(tx).translationY(ty).alpha(0f).scaleX(0f).scaleY(0f)
                .setDuration(1000 + random.nextInt(300).toLong()).setInterpolator(DecelerateInterpolator()).start()
        }
    }

    private fun syncDataAndSendSms() {
        val sdf = SimpleDateFormat("HH:mm:ss 'WIB' \u00B7 dd MMM yyyy", Locale("id", "ID"))
        findViewById<TextView>(R.id.tv_timestamp).text = "Terkirim: ${sdf.format(Date())}"

        if (currentAddressStr.isNotEmpty()) {
            findViewById<TextView>(R.id.tv_location_name).text = currentAddressStr
        }

        val fusedClient = LocationServices.getFusedLocationProviderClient(this)
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedClient.lastLocation.addOnSuccessListener { location ->
                location?.let {
                    lastLat = it.latitude; lastLng = it.longitude
                    findViewById<TextView>(R.id.tv_coordinates).text = String.format(Locale.getDefault(), "%.4f\u00B0N, %.4f\u00B0E", lastLat, lastLng)
                    updateAddressAutomatically(it.latitude, it.longitude)
                }
            }
        }
        fetchContactsAndSendSms()
    }

    private fun updateAddressAutomatically(lat: Double, lng: Double) {
        val geocoder = Geocoder(this, Locale.getDefault())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            geocoder.getFromLocation(lat, lng, 1) { addresses ->
                if (addresses.isNotEmpty()) {
                    runOnUiThread {
                        currentAddressStr = addresses[0].getAddressLine(0)
                        findViewById<TextView>(R.id.tv_location_name).text = currentAddressStr
                    }
                }
            }
        } else {
            try {
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(lat, lng, 1)
                if (!addresses.isNullOrEmpty()) {
                    currentAddressStr = addresses[0].getAddressLine(0)
                    findViewById<TextView>(R.id.tv_location_name).text = currentAddressStr
                }
            } catch (_: Exception) {}
        }
    }

    private fun fetchContactsAndSendSms() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            requestSmsPermissionLauncher.launch(Manifest.permission.SEND_SMS)
            return
        }

        val uid = auth.currentUser?.uid ?: return
        db.collection("users").document(uid).collection("emergency_contacts")
            .get()
            .addOnSuccessListener { docs ->
                val subtitleText = if (docs.isEmpty) "Pesan darurat terkirim ke pusat keamanan" else "Pesan darurat telah sampai ke ${docs.size()} kontak aman"
                findViewById<TextView>(R.id.tv_subtitle).text = subtitleText
                
                docs.documents.forEachIndexed { index, doc ->
                    val name = doc.getString("nama") ?: "Kontak"
                    val phone = doc.getString("noTelpon") ?: ""
                    val colorStr = doc.getString("colorCode") ?: "#C0392B"
                    
                    if (index < 2) {
                        val layout: LinearLayout? = if (index == 0) findViewById(R.id.layout_contact_1) else findViewById(R.id.layout_contact_2)
                        layout?.let {
                            it.visibility = View.VISIBLE
                            val tvName: TextView? = if (index == 0) findViewById(R.id.tv_name_1) else findViewById(R.id.tv_name_2)
                            tvName?.text = name
                            (it.getChildAt(0) as? TextView)?.let { tv ->
                                tv.text = if (name.isNotEmpty()) name.take(1).uppercase() else "?"
                                try { tv.backgroundTintList = ColorStateList.valueOf(colorStr.toColorInt()) } catch (_: Exception) {}
                            }
                        }
                    }
                    
                    // AUTO SEND SMS
                    if (phone.isNotEmpty()) {
                        sendDirectSms(phone, name)
                    }
                }
            }
    }

    private fun sendDirectSms(phoneNumber: String, contactName: String) {
        // Cek izin dilakukan di fetchContactsAndSendSms, di sini hanya pengiriman
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) return

        try {
            val address = if (currentAddressStr.isNotEmpty()) currentAddressStr else "Lokasi tidak terdeteksi"
            val message = "\uD83D\uDEA8 SOS MEDANSAFE! \uD83D\uDEA8\n\n$contactName, saya memerlukan bantuan segera! Lokasi saya:\n\uD83D\uDCCD $address\n\nBuka di Maps:\nhttps://www.google.com/maps?q=$lastLat,$lastLng"
            
            val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                this.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            
            val parts = smsManager.divideMessage(message)
            smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null)
            Log.d("SOS_SMS", "SMS dikirim ke $phoneNumber")
        } catch (e: Exception) {
            Log.e("SOS_SMS", "Gagal kirim SMS: ${e.message}")
        }
    }

    private fun shareEmergencyLocation() {
        try {
            val address = findViewById<TextView>(R.id.tv_location_name).text.toString()
            val shareMessage = "\uD83D\uDEA8 DARURAT MEDANSAFE! \uD83D\uDEA8\n\nSaya memerlukan bantuan segera. Ini lokasi saya saat ini:\n\uD83D\uDCCD $address\n\nCek di Google Maps:\nhttps://www.google.com/maps?q=$lastLat,$lastLng"
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, shareMessage)
            }
            startActivity(Intent.createChooser(shareIntent, "Bagikan ke..."))
        } catch (e: Exception) {}
    }
}
