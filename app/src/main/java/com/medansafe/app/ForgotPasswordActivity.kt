package com.medansafe.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.view.animation.AnticipateOvershootInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.medansafe.app.databinding.ActivityForgotPasswordBinding
import java.net.URLEncoder

class ForgotPasswordActivity : AppCompatActivity() {

    private lateinit var binding: ActivityForgotPasswordBinding
    private lateinit var auth: FirebaseAuth
    private val db = FirebaseFirestore.getInstance()
    private var resendTimer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityForgotPasswordBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()

        setupUI()
        setupBackNavigation()
    }

    private fun setupUI() {
        binding.btnBackContainer.setOnClickListener { handleBackNavigation() }
        binding.btnBackToLogin.setOnClickListener { handleBackNavigation() }
        binding.btnFinalBack.setOnClickListener { handleBackNavigation() }

        binding.cardEmail.setOnClickListener {
            binding.containerInputEmail.visibility = View.VISIBLE
            binding.containerInputSms.visibility = View.GONE
            binding.cardEmail.setBackgroundResource(R.drawable.sc0392bsw1cr14)
            binding.cardSms.setBackgroundResource(R.drawable.se2e8f0sw1cr14bf8fafc)
        }

        binding.cardSms.setOnClickListener {
            binding.containerInputSms.visibility = View.VISIBLE
            binding.containerInputEmail.visibility = View.GONE
            binding.cardSms.setBackgroundResource(R.drawable.sc10b981sw1cr14bf8fafc)
            binding.cardEmail.setBackgroundResource(R.drawable.se2e8f0sw1cr14bf8fafc)
        }

        binding.btnSendEmail.setOnClickListener {
            val email = binding.etResetEmail.text.toString().trim()
            if (email.isEmpty()) {
                Toast.makeText(this, "Masukkan email Anda", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showLoading(true)
            auth.sendPasswordResetEmail(email).addOnSuccessListener {
                showLoading(false)
                showSuccessState(email)
            }.addOnFailureListener {
                showLoading(false)
                Toast.makeText(this, "Gagal: ${it.message}", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnSendSms.setOnClickListener {
            val phone = binding.etResetPhone.text.toString().trim()
            if (phone.isEmpty()) {
                Toast.makeText(this, "Masukkan nomor HP Anda", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            var adminWA = "+6289636216602"
            db.collection("app_config").document("contact").get().addOnSuccessListener { doc ->
                if (doc.exists()) adminWA = doc.getString("admin_whatsapp") ?: adminWA
                try {
                    val message = "Halo Admin MedanSafe, saya lupa password akun saya.\nNomor HP: $phone"
                    val url = "https://api.whatsapp.com/send?phone=$adminWA&text=${URLEncoder.encode(message, "UTF-8")}"
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (e: Exception) {
                    Toast.makeText(this, "WhatsApp tidak ditemukan", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnOpenEmail.setOnClickListener {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_APP_EMAIL)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                startActivity(intent)
            } catch (e: Exception) {
                val fallback = packageManager.getLaunchIntentForPackage("com.google.android.gm")
                if (fallback != null) startActivity(fallback)
                else Toast.makeText(this, "Aplikasi email tidak ditemukan", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnResend.setOnClickListener {
            val email = binding.etResetEmail.text.toString().trim()
            auth.sendPasswordResetEmail(email).addOnSuccessListener {
                Toast.makeText(this, "Tautan berhasil dikirim ulang!", Toast.LENGTH_SHORT).show()
                startResendTimer()
            }
        }
    }

    private fun showSuccessState(email: String) {
        binding.stateInput.visibility = View.GONE
        binding.stateSuccess.visibility = View.VISIBLE
        binding.tvSuccessDesc.text = "Cek inbox $email.\nTautan reset password telah dikirim."
        animateSuccess()
        startResendTimer()
    }

    private fun animateSuccess() {
        binding.iconContainerSuccess.apply {
            scaleX = 0f; scaleY = 0f; alpha = 0f
            animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(500).setInterpolator(OvershootInterpolator()).start()
        }
        binding.ivCheckSuccess.apply {
            scaleX = 0f; scaleY = 0f; rotation = -45f
            animate().scaleX(1f).scaleY(1f).rotation(0f).setDuration(600).setStartDelay(200).setInterpolator(AnticipateOvershootInterpolator()).start()
        }
    }

    private fun startResendTimer() {
        resendTimer?.cancel()
        binding.btnResend.isEnabled = false
        resendTimer = object : CountDownTimer(60000, 1000) {
            override fun onTick(ms: Long) { binding.btnResend.text = "Kirim Ulang (${ms/1000}s)" }
            override fun onFinish() { binding.btnResend.isEnabled = true; binding.btnResend.text = "Kirim Ulang" }
        }.start()
    }

    private fun showLoading(@Suppress("UNUSED_PARAMETER") show: Boolean) {
        // Implementation if progress bar added to layout
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackNavigation()
            }
        })
    }

    private fun handleBackNavigation() {
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.fade_in, R.anim.slide_out_right)
    }

    override fun onDestroy() {
        super.onDestroy()
        resendTimer?.cancel()
    }
}
