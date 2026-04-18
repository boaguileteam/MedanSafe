package com.medansafe.app

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.storage.FirebaseStorage
import com.medansafe.app.databinding.ActivityAdminVerifikasiBinding
import com.medansafe.app.databinding.ItemAdminLaporanBinding
import java.util.*

class AdminVerifikasiActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdminVerifikasiBinding
    private val db = FirebaseFirestore.getInstance()
    private val storage = FirebaseStorage.getInstance()
    private var listener: ListenerRegistration? = null
    private lateinit var adapter: LaporanAdapter
    private var currentFilter = "Semua"
    private var isDescending = true
    
    private var selectedIncidentId: String? = null
    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { uploadNewPhoto(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdminVerifikasiBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.statusBarColor = Color.parseColor("#0A0E1A")
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        
        setupRecyclerView()
        setupFilters()
        loadLaporan()
        setupClickListeners()
    }

    private fun setupClickListeners() {
        binding.btnBack.setOnClickListener { finish() }
        binding.swipeRefresh.setOnRefreshListener { loadLaporan() }
    }

    private fun setupRecyclerView() {
        adapter = LaporanAdapter(
            onVerifikasi = { incident -> showVerifikasiDialog(incident) },
            onTolak = { incident -> showTolakBottomSheet(incident) },
            onEditPhoto = { id -> 
                selectedIncidentId = id
                pickImageLauncher.launch("image/*")
            }
        )
        binding.rvLaporan.layoutManager = LinearLayoutManager(this)
        binding.rvLaporan.adapter = adapter
    }

    private fun setupFilters() {
        binding.chipGroupFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: R.id.chipAll
            currentFilter = when (checkedId) {
                R.id.chipWaiting -> "menunggu"
                R.id.chipVerified -> "terverifikasi"
                R.id.chipRejected -> "ditolak"
                else -> "Semua"
            }
            loadLaporan()
        }
    }

    private fun loadLaporan() {
        listener?.remove()
        
        db.collection("incidents").get().addOnSuccessListener { snapshot ->
            val waiting = snapshot.count { it.getString("status") == "menunggu" }
            val verified = snapshot.count { it.getString("status") == "terverifikasi" }
            val rejected = snapshot.count { it.getString("status") == "ditolak" }
            
            binding.tvWaitingCountSummary.text = waiting.toString()
            binding.tvVerifiedCountSummary.text = verified.toString()
            binding.tvRejectedCountSummary.text = rejected.toString()
        }

        var query: Query = db.collection("incidents")
        if (currentFilter != "Semua") {
            query = query.whereEqualTo("status", currentFilter)
        }
        val sortDirection = if (isDescending) Query.Direction.DESCENDING else Query.Direction.ASCENDING
        query = query.orderBy("timestamp", sortDirection)

        listener = query.addSnapshotListener { snapshot, e ->
            binding.swipeRefresh.isRefreshing = false
            
            if (e != null) {
                return@addSnapshotListener
            }

            val list = mutableListOf<Map<String, Any>>()
            snapshot?.forEach { doc ->
                val data = doc.data.toMutableMap()
                data["id"] = doc.id
                list.add(data)
            }
            adapter.submitList(list)
        }
    }

    private fun uploadNewPhoto(uri: Uri) {
        val incidentId = selectedIncidentId ?: return
        val ref = storage.reference.child("incidents/${incidentId}_edited_${System.currentTimeMillis()}.jpg")
        
        Toast.makeText(this, "Mengunggah foto baru...", Toast.LENGTH_SHORT).show()
        
        ref.putFile(uri).addOnSuccessListener {
            ref.downloadUrl.addOnSuccessListener { downloadUri ->
                db.collection("incidents").document(incidentId)
                    .update("imageUrl", downloadUri.toString())
                    .addOnSuccessListener {
                        Toast.makeText(this, "Foto berhasil diperbarui", Toast.LENGTH_SHORT).show()
                    }
            }
        }.addOnFailureListener {
            Toast.makeText(this, "Gagal mengunggah foto", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showVerifikasiDialog(incident: Map<String, Any>) {
        val id = incident["id"]?.toString() ?: return
        val jalan = incident["namaJalan"]?.toString() ?: incident["alamat"]?.toString() ?: "Lokasi"
        val uid = incident["uid"]?.toString() ?: return

        AlertDialog.Builder(this)
            .setTitle("Verifikasi Laporan")
            .setMessage("Tandai laporan di $jalan sebagai valid?")
            .setPositiveButton("Verifikasi") { _, _ ->
                val batch = db.batch()
                val incidentRef = db.collection("incidents").document(id)
                val userRef = db.collection("users").document(uid)

                batch.update(incidentRef, mapOf(
                    "status" to "terverifikasi",
                    "verifiedAt" to Timestamp.now()
                ))

                batch.update(userRef, "laporanTerverifikasi", FieldValue.increment(1))
                batch.update(userRef, "poin", FieldValue.increment(10))

                val notifRef = userRef.collection("notifications").document()
                batch.set(notifRef, mapOf(
                    "type" to "verified",
                    "judul" to "✅ Laporan Terverifikasi!",
                    "isi" to "Laporan kamu di $jalan telah diverifikasi. +10 poin!",
                    "timestamp" to Timestamp.now(),
                    "isRead" to false
                ))

                batch.commit().addOnSuccessListener {
                    Snackbar.make(binding.root, "✅ Berhasil diverifikasi!", Snackbar.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showTolakBottomSheet(incident: Map<String, Any>) {
        val id = incident["id"]?.toString() ?: return
        val jalan = incident["namaJalan"]?.toString() ?: incident["alamat"]?.toString() ?: "Lokasi"
        val uid = incident["uid"]?.toString() ?: return

        val bottomSheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_rejection, null)
        bottomSheet.setContentView(view)

        val etReason = view.findViewById<EditText>(R.id.etReason)
        val btnSubmit = view.findViewById<MaterialButton>(R.id.btnTolakLaporan)

        btnSubmit.setOnClickListener {
            val reason = etReason.text.toString().trim()
            if (reason.length < 3) return@setOnClickListener

            val batch = db.batch()
            batch.update(db.collection("incidents").document(id), mapOf(
                "status" to "ditolak",
                "rejectionReason" to reason
            ))

            val userNotifRef = db.collection("users").document(uid).collection("notifications").document()
            batch.set(userNotifRef, mapOf(
                "type" to "rejected",
                "judul" to "❌ Laporan Ditolak",
                "isi" to "Laporan di $jalan ditolak. Alasan: $reason",
                "timestamp" to Timestamp.now(),
                "isRead" to false
            ))

            batch.commit().addOnSuccessListener {
                bottomSheet.dismiss()
                Snackbar.make(binding.root, "Laporan ditolak", Snackbar.LENGTH_LONG).show()
            }
        }
        bottomSheet.show()
    }

    override fun onDestroy() {
        super.onDestroy()
        listener?.remove()
    }

    inner class LaporanAdapter(
        private val onVerifikasi: (Map<String, Any>) -> Unit,
        private val onTolak: (Map<String, Any>) -> Unit,
        private val onEditPhoto: (String) -> Unit
    ) : RecyclerView.Adapter<LaporanAdapter.ViewHolder>() {

        private var items = listOf<Map<String, Any>>()

        fun submitList(newList: List<Map<String, Any>>) {
            items = newList
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val b = ItemAdminLaporanBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(b)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])
        override fun getItemCount() = items.size

        inner class ViewHolder(private val b: ItemAdminLaporanBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(item: Map<String, Any>) {
                val kategori = item["kategori"]?.toString() ?: item["type"]?.toString() ?: "Laporan"
                val status = item["status"]?.toString() ?: "menunggu"
                val bahaya = (item["tingkatBahaya"] as? Long)?.toInt() ?: 3
                val imageUrl = item["imageUrl"]?.toString()
                val fotoBase64 = item["fotoBase64"]?.toString()
                val id = item["id"]?.toString() ?: ""
                
                b.tvCategoryBadge.text = kategori.uppercase()
                
                val (catBg, catText) = when {
                    bahaya >= 4 -> Pair("#FEE2E2", "#C0392B") 
                    bahaya >= 2 -> Pair("#FEF3C7", "#D97706") 
                    else -> Pair("#DCFCE7", "#10B981")        
                }
                b.tvCategoryBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor(catBg))
                b.tvCategoryBadge.setTextColor(Color.parseColor(catText))
                
                val statusColor = when (status) {
                    "terverifikasi" -> "#10B981" 
                    "menunggu" -> "#F59E0B"      
                    "ditolak" -> "#E74C3C"       
                    else -> "#475569"
                }
                
                b.tvStatusBadge.text = status.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
                val statusBg = when (status) {
                    "terverifikasi" -> "#DCFCE7"
                    "menunggu" -> "#FFF7ED"
                    "ditolak" -> "#FEE2E2"
                    else -> "#F1F5F9"
                }
                b.tvStatusBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor(statusBg))
                b.tvStatusBadge.setTextColor(Color.parseColor(statusColor))

                b.tvNamaJalan.text = item["namaJalan"]?.toString() ?: item["alamat"]?.toString() ?: "Lokasi tidak terdeteksi"
                
                val timestamp = item["timestamp"] as? Timestamp
                val waktuStr = if (timestamp != null) {
                    android.text.format.DateUtils.getRelativeTimeSpanString(timestamp.toDate().time)
                } else "Baru saja"
                b.tvTimeAgo.text = waktuStr

                val upvotes = item["upvoteCount"] ?: item["upvotes"] ?: 0
                val skor = item["skorKeamanan"] ?: item["skor"] ?: "--"
                val rawNama = item["namaUser"]?.toString() ?: item["username"]?.toString()
                val namaUser = if (rawNama.isNullOrBlank()) "Anonim" else rawNama
                b.tvPelaporWaktu.text = "$namaUser • Skor: $skor/100 • $upvotes upvote"

                b.tvKeterangan.text = "Keterangan: ${item["keterangan"] ?: item["deskripsi"] ?: "Tidak ada keterangan"}"

                // Image Loading Logic (Prioritize admin edited imageUrl, then reporter's fotoBase64)
                if (!imageUrl.isNullOrEmpty()) {
                    b.ivLaporan.setPadding(0, 0, 0, 0)
                    b.ivLaporan.scaleType = ImageView.ScaleType.CENTER_CROP
                    Glide.with(itemView.context)
                        .load(imageUrl)
                        .centerCrop()
                        .placeholder(R.drawable.ic_menu_camera)
                        .into(b.ivLaporan)
                } else if (!fotoBase64.isNullOrBlank()) {
                    try {
                        val imageBytes = Base64.decode(fotoBase64, Base64.DEFAULT)
                        val decodedImage = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                        b.ivLaporan.setPadding(0, 0, 0, 0)
                        b.ivLaporan.scaleType = ImageView.ScaleType.CENTER_CROP
                        b.ivLaporan.setImageBitmap(decodedImage)
                    } catch (e: Exception) {
                        b.ivLaporan.setPadding(48, 48, 48, 48)
                        b.ivLaporan.scaleType = ImageView.ScaleType.CENTER_INSIDE
                        b.ivLaporan.setImageResource(R.drawable.ic_menu_camera)
                    }
                } else {
                    b.ivLaporan.setPadding(48, 48, 48, 48)
                    b.ivLaporan.scaleType = ImageView.ScaleType.CENTER_INSIDE
                    b.ivLaporan.setImageResource(R.drawable.ic_menu_camera)
                }

                b.cvLaporanImg.setOnClickListener { onEditPhoto(id) }

                b.llActions.visibility = if (status == "menunggu") View.VISIBLE else View.GONE
                b.btnVerif.setOnClickListener { onVerifikasi(item) }
                b.btnTolak.setOnClickListener { onTolak(item) }
            }
        }
    }
}
