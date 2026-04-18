package com.medansafe.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.medansafe.app.databinding.ActivityEditProfileBinding
import java.io.ByteArrayOutputStream

class EditProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditProfileBinding
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    
    private val imagePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { processAndUploadImage(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadUserData()
        setupActions()
    }

    private fun loadUserData() {
        val uid = auth.currentUser?.uid ?: return
        db.collection("users").document(uid).get().addOnSuccessListener { doc ->
            if (doc.exists()) {
                val name = doc.getString("nama") ?: ""
                val phone = doc.getString("noTelpon") ?: ""
                val email = doc.getString("email") ?: ""
                val profileImg = doc.getString("profileImageUrl") ?: ""
                val isGoogle = auth.currentUser?.providerData?.any { it.providerId == "google.com" } == true

                binding.etName.setText(name)
                binding.etPhone.setText(phone)
                binding.tvEmail.text = email
                
                updateAvatarUI(profileImg)
                
                if (isGoogle) {
                    binding.tvGoogleConnectedBadge.text = "✓ Terhubung"
                    binding.tvGoogleConnectedBadge.setTextColor(getColor(R.color.notif_green))
                } else {
                    binding.tvGoogleConnectedBadge.text = "Email"
                    binding.tvGoogleConnectedBadge.setTextColor(android.graphics.Color.GRAY)
                }
            }
        }
    }

    private fun updateAvatarUI(profileImg: String) {
        if (profileImg.isNotEmpty()) {
            binding.ivAvatarImgEdit.setPadding(0, 0, 0, 0)
            binding.ivAvatarImgEdit.imageTintList = null
            
            if (profileImg.startsWith("data:image")) {
                try {
                    val base64Data = profileImg.substringAfter(",")
                    val imageBytes = Base64.decode(base64Data, Base64.DEFAULT)
                    Glide.with(this).load(imageBytes).into(binding.ivAvatarImgEdit)
                } catch (e: Exception) {
                    binding.ivAvatarImgEdit.setImageResource(R.drawable.ic_person)
                }
            } else {
                Glide.with(this).load(profileImg).into(binding.ivAvatarImgEdit)
            }
        } else {
            binding.ivAvatarImgEdit.setPadding(60, 60, 60, 60)
            binding.ivAvatarImgEdit.setImageResource(R.drawable.ic_person)
            binding.ivAvatarImgEdit.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#CBD5E1"))
        }
    }

    private fun setupActions() {
        binding.btnBack.setOnClickListener { finish() }
        
        binding.flAvatar.setOnClickListener { imagePickerLauncher.launch("image/*") }
        binding.btnChangePhoto.setOnClickListener { imagePickerLauncher.launch("image/*") }
        
        binding.btnSaveChanges.setOnClickListener { saveChanges() }
        
        binding.btnConnectGoogle.setOnClickListener {
            val isGoogle = auth.currentUser?.providerData?.any { it.providerId == "google.com" } == true
            if (!isGoogle) {
                Toast.makeText(this, "Fitur hubungkan akun Google akan segera hadir", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun processAndUploadImage(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            val scaledBitmap = resizeBitmap(bitmap, 800)
            
            val outputStream = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
            val base64Image = "data:image/jpeg;base64," + Base64.encodeToString(outputStream.toByteArray(), Base64.DEFAULT)
            
            val uid = auth.currentUser?.uid ?: return
            binding.btnSaveChanges.isEnabled = false
            db.collection("users").document(uid).update("profileImageUrl", base64Image)
                .addOnSuccessListener {
                    binding.btnSaveChanges.isEnabled = true
                    Toast.makeText(this, "Foto profil berhasil diubah!", Toast.LENGTH_SHORT).show()
                    loadUserData() // Refresh UI
                }
                .addOnFailureListener {
                    binding.btnSaveChanges.isEnabled = true
                    Toast.makeText(this, "Gagal upload foto", Toast.LENGTH_SHORT).show()
                }
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun resizeBitmap(source: Bitmap, maxLength: Int): Bitmap {
        val width = source.width
        val height = source.height
        val ratio = width.toFloat() / height.toFloat()
        var targetWidth = maxLength
        var targetHeight = maxLength
        if (width > height) targetHeight = (maxLength / ratio).toInt()
        else targetWidth = (maxLength * ratio).toInt()
        return Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
    }

    private fun saveChanges() {
        val uid = auth.currentUser?.uid ?: return
        val newName = binding.etName.text.toString().trim()
        val newPhone = binding.etPhone.text.toString().trim()

        if (newName.isEmpty() || newName.length < 3) {
            Toast.makeText(this, "Nama minimal 3 karakter", Toast.LENGTH_SHORT).show()
            return
        }
        
        if (newPhone.isEmpty() || newPhone.length < 10) {
            Toast.makeText(this, "Nomor HP tidak valid", Toast.LENGTH_SHORT).show()
            return
        }

        val updates = hashMapOf<String, Any>(
            "nama" to newName,
            "noTelpon" to newPhone
        )

        binding.btnSaveChanges.isEnabled = false
        db.collection("users").document(uid).update(updates)
            .addOnSuccessListener {
                Toast.makeText(this, "Profil berhasil diperbarui", Toast.LENGTH_SHORT).show()
                finish()
            }
            .addOnFailureListener {
                binding.btnSaveChanges.isEnabled = true
                Toast.makeText(this, "Gagal memperbarui profil", Toast.LENGTH_SHORT).show()
            }
    }
}
