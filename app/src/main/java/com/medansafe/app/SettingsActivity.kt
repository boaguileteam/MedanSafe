package com.medansafe.app

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import com.medansafe.app.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var sharedPreferences: SharedPreferences
    
    // Mapping progress to non-linear radius values
    private val radiusValues = listOf(0.5f, 1.0f, 2.0f, 3.0f, 4.0f, 5.0f)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sharedPreferences = getSharedPreferences("MedanSafeSettings", MODE_PRIVATE)

        setupUI()
        setupActions()
    }

    private fun setupUI() {
        binding.switchInsiden.isChecked = sharedPreferences.getBoolean("pref_incident", true)
        binding.switchVerifikasi.isChecked = sharedPreferences.getBoolean("pref_verification", true)
        binding.switchNama.isChecked = sharedPreferences.getBoolean("pref_show_name", false)

        val savedRadius = sharedPreferences.getFloat("pref_radius", 2.0f)
        val initialProgress = radiusValues.indexOf(savedRadius).let { if (it == -1) 2 else it }
        
        binding.seekBarRadius.max = radiusValues.size - 1
        binding.seekBarRadius.progress = initialProgress
        updateRadiusLabel(radiusValues[initialProgress])
    }

    private fun setupActions() {
        binding.btnBack.setOnClickListener { finish() }

        // VERSION CLICK LOGIC (Cleaned from Seed/Demo)
        binding.llVersion.setOnClickListener {
            Toast.makeText(this, "Aplikasi sudah dalam versi terbaru", Toast.LENGTH_SHORT).show()
        }

        binding.switchInsiden.setOnCheckedChangeListener { _, isChecked -> savePreference("pref_incident", isChecked) }
        binding.switchVerifikasi.setOnCheckedChangeListener { _, isChecked -> savePreference("pref_verification", isChecked) }

        binding.seekBarRadius.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val radiusValue = radiusValues[progress]
                updateRadiusLabel(radiusValue)
                savePreference("pref_radius", radiusValue)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.switchNama.setOnCheckedChangeListener { _, isChecked -> savePreference("pref_show_name", isChecked) }

        // Row clicks for accessibility
        binding.itemIncident.setOnClickListener { binding.switchInsiden.toggle() }
        binding.itemVerification.setOnClickListener { binding.switchVerifikasi.toggle() }
        binding.itemPrivacyName.setOnClickListener { binding.switchNama.toggle() }

        binding.llMenuPrivacy.setOnClickListener {
            startActivity(Intent(this, PrivacyPolicyActivity::class.java))
        }

        binding.llMenuTerms.setOnClickListener {
            startActivity(Intent(this, TermsActivity::class.java))
        }
    }

    private fun updateRadiusLabel(value: Float) {
        val label = if (value < 1.0f) "${(value * 1000).toInt()} m" else "${value.toInt()} km"
        binding.tvRadiusValue.text = label
        
        binding.tvRadiusValue.animate().scaleX(1.2f).scaleY(1.2f).setDuration(100).withEndAction {
            binding.tvRadiusValue.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
        }.start()
    }

    private fun savePreference(key: String, value: Any) {
        sharedPreferences.edit {
            when (value) {
                is Boolean -> putBoolean(key, value)
                is Float -> putFloat(key, value)
            }
        }
    }
}
