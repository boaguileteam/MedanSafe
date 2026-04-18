package com.medansafe.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.location.Address
import android.location.Geocoder
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.fragment.app.Fragment
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.GeoPoint
import com.medansafe.app.databinding.FragmentReportBinding
import java.io.ByteArrayOutputStream
import java.util.*

class ReportFragment : Fragment() {

    private var _binding: FragmentReportBinding? = null
    private val binding get() = _binding!!

    private var selectedCategory: String = "Begal"
    private var photoBase64: String = ""
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private var currentGeoPoint: GeoPoint? = null

    private val selectLocationLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val lat = result.data?.getDoubleExtra("SELECTED_LAT", 0.0) ?: 0.0
            val lng = result.data?.getDoubleExtra("SELECTED_LNG", 0.0) ?: 0.0
            val addr = result.data?.getStringExtra("SELECTED_ADDR") ?: "Lokasi Terpilih"
            currentGeoPoint = GeoPoint(lat, lng)
            binding.tvReportAddr.text = addr
        }
    }

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data
            binding.ivReportPreview.setImageURI(uri)
            binding.ivReportPreview.visibility = View.VISIBLE
            binding.llPhotoPlaceholder.visibility = View.GONE
            
            binding.tvPhotoStatus.text = "Foto Berhasil Ditambahkan"
            binding.tvPhotoStatus.setTextColor("#10B981".toColorInt())
            
            uri?.let {
                try {
                    val inputStream = requireContext().contentResolver.openInputStream(it)
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    photoBase64 = processAndEncodeImage(bitmap)
                } catch (_: Exception) {}
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentReportBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireContext())
        
        setupCategorySelection()
        setupPhotoPicker()
        setupLocationLogic()
        setupSeekBarLogic()
        setupSubmitLogic()
    }

    private fun setupCategorySelection() {
        val cards = listOf(binding.catBegal, binding.catGelap, binding.catKecelakaan, binding.catLainnya)
        fun select(card: CardView, name: String) {
            cards.forEach { 
                it.setCardBackgroundColor("#E2E8F0".toColorInt())
                val layout = it.getChildAt(0) as LinearLayout
                (layout.getChildAt(1) as TextView).apply {
                    setTextColor("#334155".toColorInt())
                    typeface = Typeface.DEFAULT
                }
            }
            card.setCardBackgroundColor("#FEE2E2".toColorInt())
            val selectedText = (card.getChildAt(0) as LinearLayout).getChildAt(1) as TextView
            selectedText.setTextColor("#C0392B".toColorInt())
            selectedText.typeface = Typeface.DEFAULT_BOLD
            selectedCategory = name
            binding.etCustomCategory.visibility = if (name == "Lainnya") View.VISIBLE else View.GONE
        }
        binding.catBegal.setOnClickListener { select(binding.catBegal, "Begal") }
        binding.catGelap.setOnClickListener { select(binding.catGelap, "Jalan Gelap") }
        binding.catKecelakaan.setOnClickListener { select(binding.catKecelakaan, "Kecelakaan") }
        binding.catLainnya.setOnClickListener { select(binding.catLainnya, "Lainnya") }
        select(binding.catBegal, "Begal")
    }

    private fun setupPhotoPicker() {
        binding.btnAddReportPhoto.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            pickImageLauncher.launch(intent)
        }
    }

    private fun setupLocationLogic() {
        detectCurrentLocation()
        binding.btnEditReportLoc.setOnClickListener {
            val intent = Intent(requireContext(), SelectLocationActivity::class.java).apply {
                currentGeoPoint?.let {
                    putExtra("INIT_LAT", it.latitude)
                    putExtra("INIT_LNG", it.longitude)
                }
            }
            selectLocationLauncher.launch(intent)
        }
    }

    private fun setupSeekBarLogic() {
        binding.sbDangerLevel.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val color = when {
                    progress <= 40 -> "#10B981".toColorInt()
                    progress <= 70 -> "#F59E0B".toColorInt()
                    else -> "#E74C3C".toColorInt()
                }
                seekBar?.thumb?.setTint(color)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        binding.sbDangerLevel.thumb?.setTint("#F59E0B".toColorInt())
    }

    private fun detectCurrentLocation() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            location?.let {
                currentGeoPoint = GeoPoint(it.latitude, it.longitude)
                val geocoder = Geocoder(requireContext(), Locale.getDefault())
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(it.latitude, it.longitude, 1)
                if (!addresses.isNullOrEmpty()) {
                    binding.tvReportAddr.text = formatAddress(addresses[0])
                }
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

    private fun setupSubmitLogic() {
        binding.btnSendReport.setOnClickListener {
            val desc = binding.etIncidentDesc.text.toString().trim()
            if (desc.isEmpty()) { binding.etIncidentDesc.error = "Isi deskripsi"; return@setOnClickListener }
            if (currentGeoPoint == null) { Toast.makeText(requireContext(), "Tunggu GPS", Toast.LENGTH_SHORT).show(); return@setOnClickListener }

            val user = auth.currentUser ?: return@setOnClickListener
            val incidentId = db.collection("incidents").document().id
            val finalCategory = if (selectedCategory == "Lainnya") binding.etCustomCategory.text.toString() else selectedCategory

            val data = hashMapOf(
                "id" to incidentId,
                "uid" to user.uid,
                "namaUser" to (user.displayName ?: "Warga Medan"),
                "kategori" to finalCategory,
                "namaJalan" to binding.tvReportAddr.text.toString(),
                "lokasi" to currentGeoPoint!!,
                "deskripsi" to desc,
                "tingkatBahaya" to (binding.sbDangerLevel.progress / 20 + 1), 
                "fotoBase64" to photoBase64,
                "status" to "menunggu",
                "upvote" to 0,
                "timestamp" to Timestamp.now(),
                "kota" to "Medan"
            )

            binding.btnSendReport.isEnabled = false
            
            db.collection("incidents").document(incidentId).set(data)
                .addOnSuccessListener {
                    // Increment totalLaporan di dokumen user
                    val uid = auth.currentUser?.uid
                    if (uid != null) {
                        db.collection("users").document(uid)
                            .update("totalLaporan", com.google.firebase.firestore.FieldValue.increment(1))
                    }

                    Toast.makeText(
                        requireContext(),
                        "Laporan Berhasil Terkirim! Menunggu Verifikasi Admin.",
                        Toast.LENGTH_LONG
                    ).show()

                    (activity as? HomeActivity)?.binding?.bottomNavigation?.selectedItemId = R.id.nav_home
                }
                .addOnFailureListener {
                    binding.btnSendReport.isEnabled = true
                    Toast.makeText(
                        requireContext(),
                        "Gagal mengirim laporan: ${it.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
        }
    }

    private fun processAndEncodeImage(bitmap: Bitmap): String {
        // Resize ke resolusi HD (1280px) agar tajam tapi tetap hemat ruang
        val resizedBitmap = resizeBitmap(bitmap, 1280)
        val outputStream = ByteArrayOutputStream()
        // Kualitas 80 memberikan keseimbangan terbaik antara ukuran file dan kejernihan visual
        resizedBitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.DEFAULT)
    }

    private fun resizeBitmap(source: Bitmap, maxLength: Int): Bitmap {
        if (source.width <= maxLength && source.height <= maxLength) return source
        
        val ratio = source.width.toFloat() / source.height.toFloat()
        var targetWidth = maxLength
        var targetHeight = maxLength
        
        if (source.width > source.height) {
            targetHeight = (maxLength / ratio).toInt()
        } else {
            targetWidth = (maxLength * ratio).toInt()
        }
        
        return Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
