package com.medansafe.app

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.cardview.widget.CardView
import androidx.core.graphics.toColorInt
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import org.json.JSONArray

class RiskScoreActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    data class RiskFactor(
        val emoji: String,
        val desc: String,
        val impactScore: Int,
        val colorHex: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_risk_score)

        auth = FirebaseAuth.getInstance()
        db   = FirebaseFirestore.getInstance()

        val startAddr       = intent.getStringExtra("START_ADDR")    ?: "Lokasi Saat Ini"
        val destinationName = intent.getStringExtra("DEST_NAME")     ?: "Tujuan"
        val distance        = intent.getStringExtra("DISTANCE")      ?: "0 km"
        val duration        = intent.getStringExtra("DURATION")      ?: "0 menit"
        val safetyScore     = intent.getIntExtra("SCORE", 100)
        val factorsJson     = intent.getStringExtra("FACTORS")       ?: "[]"
        val currentMode     = intent.getStringExtra("CURRENT_MODE")  ?: "FASTEST"
        val vehicle         = intent.getStringExtra("VEHICLE")       ?: "Motor"

        findViewById<TextView>(R.id.tv_risk_start_addr).text  = startAddr
        findViewById<TextView>(R.id.tv_risk_dest_name).text   = destinationName
        val distVal  = distance.replace(" km", "").trim().toDoubleOrNull() ?: 0.0
        val durVal   = duration.replace(" menit", "").trim().toDoubleOrNull() ?: 1.0
        val avgSpeed = if (durVal > 0) (distVal / durVal * 60).toInt() else 0
        val speedInfo = if (avgSpeed > 0) " • ~$avgSpeed km/j" else ""
        val vehicleName = if (vehicle == "Kereta") "Motor" else "Mobil"
        val modeInfo    = if (currentMode == "SAFEST") "Rute Teraman" else "Rute Tercepat"
        findViewById<TextView>(R.id.tv_risk_route_info).text =
            "$distance • $duration$speedInfo\n$vehicleName — $modeInfo"

        setupViews(safetyScore, factorsJson, currentMode, vehicle)

        // ── Tombol Kembali ─────────────────────────────────────────────────────
        findViewById<CardView>(R.id.btn_back_container).setOnClickListener { finish() }

        // ── Tombol Ganti Lokasi (klik pada card route detail) ─────────────────
        findViewById<CardView>(R.id.cv_route_details).setOnClickListener {
            setResult(RESULT_OK, Intent().apply { putExtra("CHANGE_LOCATION", true) })
            finish()
        }

        // ── Tombol "Cari Rute Lebih Aman" ─────────────────────────────────────
        // Hanya tampil jika mode saat ini FASTEST
        val btnFindSafer = findViewById<AppCompatButton>(R.id.btn_risk_find_safer)
        val cvBtnGlow    = findViewById<View>(R.id.cv_btn_glow_container)

        if (currentMode == "FASTEST") {
            cvBtnGlow.visibility    = View.VISIBLE
            btnFindSafer.visibility = View.VISIBLE
            btnFindSafer.setOnClickListener {
                setResult(RESULT_OK, Intent().apply { putExtra("ROUTE_MODE", "SAFEST") })
                finish()
            }
        } else {
            // Mode sudah SAFEST → sembunyikan tombol
            cvBtnGlow.visibility    = View.GONE
            btnFindSafer.visibility = View.GONE
        }

        // ── Tombol "Tetap Gunakan" / "Kembali ke Tercepat" ────────────────────
        val btnKeep = findViewById<AppCompatButton>(R.id.btn_risk_keep_current)
        btnKeep.text = if (currentMode == "SAFEST") "Kembali ke Rute Tercepat" else "Tetap Gunakan Rute Ini"
        btnKeep.setOnClickListener {
            setResult(RESULT_OK, Intent().apply { putExtra("ROUTE_MODE", "STAY") })
            finish()
        }
    }

    private fun setupViews(score: Int, factorsJson: String, currentMode: String, vehicle: String) {
        val tvScore     = findViewById<TextView>(R.id.tv_risk_score_display)
        val tvLabel     = findViewById<TextView>(R.id.tv_risk_label)
        val tvModeTag   = findViewById<TextView?>(R.id.tv_route_mode_tag)
        val cvOuterGlow = findViewById<CardView>(R.id.cv_score_outer_glow)

        // Tampilkan skor yang sudah dihitung dari HomeActivity
        tvScore.text = score.toString()

        // ── Warna & label berdasarkan skor aktual ─────────────────────────────
        val colorHex: String
        val statusText: String
        val badgeBgColor: String

        when {
            score >= 80 -> {
                colorHex     = "#10B981"   // Hijau
                statusText   = "AMAN"
                badgeBgColor = "#DCFCE7"
            }
            score >= 50 -> {
                colorHex     = "#F59E0B"   // Kuning
                statusText   = "WASPADA"
                badgeBgColor = "#FEF3C7"
            }
            else -> {
                colorHex     = "#E74C3C"   // Merah
                statusText   = "BERBAHAYA"
                badgeBgColor = "#FEE2E2"
            }
        }

        val mainColor = colorHex.toColorInt()
        tvScore.setTextColor(mainColor)
        tvLabel.text = statusText
        tvLabel.setTextColor(mainColor)
        tvLabel.backgroundTintList = ColorStateList.valueOf(badgeBgColor.toColorInt())

        // ── Tag mode & kendaraan ──────────────────────────────────────────────
        tvModeTag?.apply {
            visibility = View.VISIBLE
            val vehicleEmoji = if (vehicle == "Kereta") "🛵" else "🚗"
            val modeLabel = if (currentMode == "SAFEST") "🛡️ Rute Teraman" else "⚡ Rute Tercepat"
            val modeDesc  = if (currentMode == "SAFEST")
                "Menghindari zona berbahaya"
            else
                "Jalur paling efisien"
            text = "$modeLabel  •  $vehicleEmoji $vehicle\n$modeDesc"
            setTextColor(mainColor)
        }

        // ── Render faktor risiko dari data yang dikirim HomeActivity ──────────
        val factors = parseFactors(factorsJson)
        factors.forEachIndexed { index, factor ->
            updateFactorUI(index + 1, factor)
        }

        // ── Shadow warna sesuai skor ───────────────────────────────────────────
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            cvOuterGlow.outlineSpotShadowColor    = mainColor
            cvOuterGlow.outlineAmbientShadowColor = mainColor
        }

        // ── Animasi muncul ────────────────────────────────────────────────────
        cvOuterGlow.alpha  = 0f
        cvOuterGlow.scaleX = 0.9f
        cvOuterGlow.scaleY = 0.9f
        cvOuterGlow.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(800).start()
    }

    private fun parseFactors(json: String): List<RiskFactor> {
        val list = mutableListOf<RiskFactor>()
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    RiskFactor(
                        emoji       = obj.getString("emoji"),
                        desc        = obj.getString("desc"),
                        impactScore = obj.getInt("impact"),
                        colorHex    = obj.getString("color")
                    )
                )
            }
        } catch (_: Exception) {}
        return list
    }

    private fun updateFactorUI(index: Int, factor: RiskFactor) {
        val emojiView: TextView? = when (index) {
            1 -> findViewById(R.id.tv_factor_1_emoji)
            2 -> findViewById(R.id.tv_factor_2_emoji)
            3 -> findViewById(R.id.tv_factor_3_emoji)
            else -> null
        }
        val descView: TextView? = when (index) {
            1 -> findViewById(R.id.tv_factor_1_desc)
            2 -> findViewById(R.id.tv_factor_2_desc)
            3 -> findViewById(R.id.tv_factor_3_desc)
            else -> null
        }
        val pbView: ProgressBar? = when (index) {
            1 -> findViewById(R.id.pb_factor_1)
            2 -> findViewById(R.id.pb_factor_2)
            3 -> findViewById(R.id.pb_factor_3)
            else -> null
        }

        emojiView?.text = factor.emoji
        descView?.text  = factor.desc
        pbView?.apply {
            // Jika aman (impact=0) → tampilkan 20% sebagai indikator positif
            // Jika ada risiko → panjang bar sesuai besarnya penalti
            progress = if (factor.impactScore > 0) factor.impactScore else 20
            progressTintList = ColorStateList.valueOf(factor.colorHex.toColorInt())
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        super.onBackPressed()
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.fade_out, R.anim.slide_in_right)
    }
}