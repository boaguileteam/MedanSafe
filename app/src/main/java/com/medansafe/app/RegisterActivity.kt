package com.medansafe.app

import android.content.Intent
import android.os.Bundle
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.medansafe.app.databinding.ActivityRegisterBinding

class RegisterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRegisterBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private var isPasswordVisible = false
    private var isConfirmPasswordVisible = false

    private val termsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            binding.cbAgree.isChecked = true
            updateRegisterButtonState(true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        setupListeners()
    }

    private fun setupListeners() {
        binding.tvTermsLink.setOnClickListener {
            val intent = Intent(this, TermsActivity::class.java)
            intent.putExtra("from_register", true)
            termsLauncher.launch(intent)
        }

        binding.tvLoginLink.setOnClickListener {
            finish()
        }

        binding.cbAgree.setOnCheckedChangeListener { _, isChecked ->
            updateRegisterButtonState(isChecked)
        }

        binding.ivShowPassword.setOnClickListener {
            isPasswordVisible = !isPasswordVisible
            binding.etPassword.transformationMethod = if (isPasswordVisible) HideReturnsTransformationMethod.getInstance() else PasswordTransformationMethod.getInstance()
            binding.ivShowPassword.setImageResource(if (isPasswordVisible) R.drawable.ic_eye else R.drawable.ic_eye_off)
            binding.ivShowPassword.alpha = if (isPasswordVisible) 1.0f else 0.3f
            binding.etPassword.setSelection(binding.etPassword.text.length)
        }

        binding.ivShowConfirmPassword.setOnClickListener {
            isConfirmPasswordVisible = !isConfirmPasswordVisible
            binding.etKonfirmasi.transformationMethod = if (isConfirmPasswordVisible) HideReturnsTransformationMethod.getInstance() else PasswordTransformationMethod.getInstance()
            binding.ivShowConfirmPassword.setImageResource(if (isConfirmPasswordVisible) R.drawable.ic_eye else R.drawable.ic_eye_off)
            binding.ivShowConfirmPassword.alpha = if (isConfirmPasswordVisible) 1.0f else 0.3f
            binding.etKonfirmasi.setSelection(binding.etKonfirmasi.text.length)
        }

        binding.btnRegister.setOnClickListener {
            if (validateForm()) {
                performRegister()
            }
        }
    }

    private fun updateRegisterButtonState(isActive: Boolean) {
        binding.btnRegister.isEnabled = isActive
        binding.btnRegister.alpha = if (isActive) 1.0f else 0.5f
    }

    private fun validateForm(): Boolean {
        val nama = binding.etName.text.toString().trim()
        val phone = binding.etPhone.text.toString().trim()
        val email = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString()
        val konfirmasi = binding.etKonfirmasi.text.toString()

        if (nama.isEmpty()) {
            binding.etName.error = "Nama tidak boleh kosong"
            return false
        }
        if (phone.isEmpty() || phone.length < 10) {
            binding.etPhone.error = "Nomor HP tidak valid"
            return false
        }
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.etEmail.error = "Format email tidak valid"
            return false
        }
        if (password.length < 8) {
            binding.etPassword.error = "Password minimal 8 karakter"
            return false
        }
        if (password != konfirmasi) {
            binding.etKonfirmasi.error = "Konfirmasi password tidak cocok"
            return false
        }
        if (!binding.cbAgree.isChecked) {
            Toast.makeText(this, "Harap setujui Syarat & Ketentuan", Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    private fun performRegister() {
        val nama = binding.etName.text.toString().trim()
        val phone = binding.etPhone.text.toString().trim()
        val email = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString()

        showLoading(true)

        auth.createUserWithEmailAndPassword(email, password)
            .addOnSuccessListener { result ->
                val userId = result.user?.uid ?: ""
                
                // Standarisasi field dengan SeedDataHelper & Google Login
                val userData = hashMapOf(
                    "uid" to userId,
                    "nama" to nama,
                    "email" to email,
                    "noTelpon" to phone,
                    "totalLaporan" to 0L,
                    "laporanTerverifikasi" to 0L,
                    "poin" to 0L,
                    "upvoteReceived" to 0L,
                    "isPelaporAktif" to false,
                    "isAdmin" to false,
                    "role" to "user",
                    "bio" to "Warga Medan Peduli",
                    "profileImageUrl" to "",
                    "isDisabled" to false,
                    "agreedToTerms" to true,
                    "createdAt" to Timestamp.now()
                )

                db.collection("users").document(userId)
                    .set(userData)
                    .addOnSuccessListener {
                        showLoading(false)
                        Toast.makeText(this, "Pendaftaran Berhasil!", Toast.LENGTH_LONG).show()
                        val intent = Intent(this, HomeActivity::class.java)
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        startActivity(intent)
                        finish()
                    }
                    .addOnFailureListener {
                        showLoading(false)
                        Toast.makeText(this, "Gagal simpan data: ${it.message}", Toast.LENGTH_SHORT).show()
                    }
            }
            .addOnFailureListener {
                showLoading(false)
                Toast.makeText(this, "Gagal Daftar: ${it.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun showLoading(show: Boolean) {
        binding.progressBar.visibility = if (show) View.VISIBLE else View.GONE
        binding.btnRegister.isEnabled = !show
    }
}
