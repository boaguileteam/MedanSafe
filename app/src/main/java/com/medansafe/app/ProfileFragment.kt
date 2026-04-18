package com.medansafe.app

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.graphics.toColorInt
import androidx.fragment.app.Fragment
import com.bumptech.glide.Glide
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.medansafe.app.databinding.FragmentProfileBinding
import java.io.ByteArrayOutputStream

class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!
    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private var profileListener: ListenerRegistration? = null

    // Launcher untuk pilih gambar dari galeri
    private val imagePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { processAndUploadImage(it) }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        setupMenu()
        setupActions()
        listenToUserData()
    }

    private fun listenToUserData() {
        val currentUser = auth.currentUser ?: return

        profileListener = db.collection("users").document(currentUser.uid)
            .addSnapshotListener { document, error ->
                if (error != null || _binding == null) return@addSnapshotListener
                
                if (document != null && document.exists()) {
                    val name = document.getString("nama") ?: "User MedanSafe"
                    val email = document.getString("email") ?: currentUser.email ?: ""
                    val reports = document.getLong("totalLaporan") ?: 0L
                    val verified = document.getLong("laporanTerverifikasi") ?: 0L
                    val points = document.getLong("poin") ?: 0L
                    val role = document.getString("role") ?: "user"
                    val profileImg = document.getString("profileImageUrl") ?: ""

                    binding.tvProfileName.text = name
                    binding.tvProfileEmail.text = email
                    binding.tvCountReports.text = reports.toString()
                    binding.tvCountVerified.text = verified.toString()
                    binding.tvCountPoints.text = points.toString()

                    updateProfileImageUI(profileImg, name)
                    updateUserTier(points.toInt(), role)
                }
            }
    }

    private fun updateProfileImageUI(profileImg: String, name: String) {
        if (profileImg.isNotEmpty()) {
            binding.tvAvatarInitial.visibility = View.GONE
            val ivAvatar = binding.cvAvatar.findViewById<ImageView>(R.id.iv_avatar_img) ?: ImageView(requireContext()).apply {
                id = R.id.iv_avatar_img
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                scaleType = ImageView.ScaleType.CENTER_CROP
                binding.cvAvatar.addView(this)
            }
            
            if (profileImg.startsWith("data:image")) {
                try {
                    val base64Data = profileImg.substringAfter(",")
                    val imageBytes = Base64.decode(base64Data, Base64.DEFAULT)
                    Glide.with(this).load(imageBytes).circleCrop().into(ivAvatar)
                } catch (e: Exception) {
                    Glide.with(this).load(android.R.drawable.ic_menu_gallery).circleCrop().into(ivAvatar)
                }
            } else {
                Glide.with(this).load(profileImg).circleCrop().placeholder(android.R.drawable.ic_menu_gallery).into(ivAvatar)
            }
        } else {
            binding.tvAvatarInitial.visibility = View.VISIBLE
            binding.tvAvatarInitial.text = name.split(" ").filter { it.isNotEmpty() }.take(2).map { it[0] }.joinToString("").uppercase()
        }
    }

    private fun processAndUploadImage(uri: Uri) {
        try {
            val inputStream = requireContext().contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            
            // Resize ke HD (1024px) untuk kualitas tajam tapi tetap efisien
            val scaledBitmap = resizeBitmap(bitmap, 1024)
            
            val outputStream = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
            val base64Image = "data:image/jpeg;base64," + Base64.encodeToString(outputStream.toByteArray(), Base64.DEFAULT)
            
            val uid = auth.currentUser?.uid ?: return
            db.collection("users").document(uid).update("profileImageUrl", base64Image)
                .addOnSuccessListener {
                    Toast.makeText(requireContext(), "Foto profil diperbarui!", Toast.LENGTH_SHORT).show()
                }
                .addOnFailureListener {
                    Toast.makeText(requireContext(), "Gagal upload: ${it.message}", Toast.LENGTH_SHORT).show()
                }
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun resizeBitmap(source: Bitmap, maxLength: Int): Bitmap {
        val width = source.width
        val height = source.height
        val ratio = width.toFloat() / height.toFloat()
        
        var targetWidth = maxLength
        var targetHeight = maxLength
        
        if (width > height) {
            targetHeight = (maxLength / ratio).toInt()
        } else {
            targetWidth = (maxLength * ratio).toInt()
        }
        
        return Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
    }

    private fun updateUserTier(points: Int, role: String) {
        val (statusText, statusColor, bgColor) = when {
            role.equals("developer", ignoreCase = true) -> Triple("OFFICIAL DEVELOPER", "#E11D48", "#1AE11D48")
            points >= 1000 -> Triple("PENGAWAL MEDAN", "#10B981", "#1A10B981")
            points >= 500  -> Triple("PELAPOR AKTIF", "#3B82F6", "#1A3B82F6")
            points >= 100  -> Triple("KONTRIBUTOR", "#F59E0B", "#1AF59E0B")
            else           -> Triple("ANGGOTA BARU", "#64748B", "#1A64748B")
        }

        binding.tvBadgeStatus.text = statusText
        binding.tvBadgeStatus.setTextColor(statusColor.toColorInt())
        binding.tvBadgeStatus.backgroundTintList = ColorStateList.valueOf(bgColor.toColorInt())

        binding.flAvatarContainer.backgroundTintList = ColorStateList.valueOf(statusColor.toColorInt())
        binding.viewAvatarGlow.backgroundTintList = ColorStateList.valueOf(statusColor.toColorInt())
        binding.ivProfileDiagramCircle.imageTintList = ColorStateList.valueOf(statusColor.toColorInt())
    }

    private fun setupMenu() {
        binding.menuEditProfile.setOnClickListener { startActivity(Intent(requireContext(), EditProfileActivity::class.java)) }
        binding.menuEmergency.setOnClickListener { startActivity(Intent(requireContext(), EmergencyContactsActivity::class.java)) }
        binding.menuHistory.setOnClickListener { startActivity(Intent(requireContext(), HistoryActivity::class.java)) }
        binding.menuSettings.setOnClickListener { startActivity(Intent(requireContext(), SettingsActivity::class.java)) }
    }

    private fun setupActions() {
        // Klik pada avatar untuk ganti foto
        binding.flAvatarContainer.setOnClickListener {
            imagePickerLauncher.launch("image/*")
        }

        binding.btnLogout.setOnClickListener {
            auth.signOut()
            val intent = Intent(requireContext(), LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            requireActivity().finish()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        profileListener?.remove()
        _binding = null
    }
}
