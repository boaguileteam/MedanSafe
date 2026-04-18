package com.medansafe.app

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.core.graphics.toColorInt
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.medansafe.app.databinding.ActivityHistoryBinding

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private var allReports = mutableListOf<IncidentModel>()
    private var currentFilter = "Semua"
    private var reportsListener: ListenerRegistration? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupFilters()
        listenToMyReports()

        binding.btnBackHistory.setOnClickListener { finish() }
    }

    private fun listenToMyReports() {
        val userId = auth.currentUser?.uid ?: return
        
        reportsListener = db.collection("incidents")
            .whereEqualTo("uid", userId)
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .addSnapshotListener { value, error ->
                if (error != null) {
                    db.collection("incidents")
                        .whereEqualTo("uid", userId)
                        .addSnapshotListener { value2, _ ->
                            processSnapshot(value2)
                        }
                    return@addSnapshotListener
                }
                processSnapshot(value)
            }
    }

    private fun processSnapshot(value: com.google.firebase.firestore.QuerySnapshot?) {
        allReports.clear()
        value?.documents?.forEach { doc ->
            val id = doc.id
            val category = doc.getString("kategori") ?: doc.getString("category") ?: "Lainnya"
            val address = doc.getString("namaJalan") ?: doc.getString("address") ?: "Lokasi tidak diketahui"
            val desc = doc.getString("deskripsi") ?: doc.getString("description") ?: ""
            val name = doc.getString("namaUser") ?: doc.getString("reporterName") ?: "Anonim"
            val upvote = doc.getLong("upvote")?.toInt() ?: 0
            val danger = doc.getLong("tingkatBahaya")?.toInt() ?: 3
            val photo = doc.getString("fotoBase64") ?: ""
            val loc = doc.getGeoPoint("lokasi") ?: doc.getGeoPoint("location")
            val time = doc.getTimestamp("timestamp") ?: doc.getTimestamp("createdAt")
            
            val status = (doc.getString("status") ?: "menunggu").lowercase()

            val incident = IncidentModel(
                id = id,
                category = category,
                address = address,
                description = desc,
                reporterName = name,
                upvoteCount = upvote,
                areaScore = danger * 20,
                photoBase64 = photo,
                latitude = loc?.latitude ?: 0.0,
                longitude = loc?.longitude ?: 0.0,
                createdAt = time,
                status = status
            )
            allReports.add(incident)
        }
        filterData(currentFilter)
    }

    private fun setupFilters() {
        binding.btnFilterAll.setOnClickListener { filterData("Semua") }
        binding.btnFilterVerified.setOnClickListener { filterData("terverifikasi") }
        binding.btnFilterPending.setOnClickListener { filterData("menunggu") }
        binding.btnFilterRejected.setOnClickListener { filterData("ditolak") }
    }

    private fun filterData(status: String) {
        currentFilter = status
        updateTabStyles(status)

        val filteredList = if (status == "Semua") {
            allReports
        } else {
            allReports.filter { it.status.equals(status, ignoreCase = true) }
        }

        updateUI(filteredList)
    }

    private fun updateTabStyles(selected: String) {
        val activeColor = "#C0392B".toColorInt()
        val inactiveColor = Color.WHITE
        val activeTextColor = Color.WHITE
        val inactiveTextColor = "#64748B".toColorInt()

        fun applyStyle(btn: AppCompatButton, statusKey: String) {
            val isActive = selected.equals(statusKey, ignoreCase = true) || (selected == "Semua" && btn == binding.btnFilterAll)
            btn.backgroundTintList = ColorStateList.valueOf(if (isActive) activeColor else inactiveColor)
            btn.setTextColor(if (isActive) activeTextColor else inactiveTextColor)
        }

        applyStyle(binding.btnFilterAll, "Semua")
        applyStyle(binding.btnFilterVerified, "terverifikasi")
        applyStyle(binding.btnFilterPending, "menunggu")
        applyStyle(binding.btnFilterRejected, "ditolak")
    }

    private fun updateUI(list: List<IncidentModel>) {
        if (list.isEmpty()) {
            binding.rvHistory.visibility = View.GONE
            binding.llEmptyHistory.visibility = View.VISIBLE
        } else {
            binding.rvHistory.visibility = View.VISIBLE
            binding.llEmptyHistory.visibility = View.GONE
            
            binding.rvHistory.layoutManager = LinearLayoutManager(this)
            binding.rvHistory.setHasFixedSize(true)
            binding.rvHistory.adapter = IncidentAdapter(list, isHistory = true) { incident ->
                val intent = Intent(this, IncidentDetailActivity::class.java)
                intent.putExtra("INCIDENT_ID", incident.id)
                startActivity(intent)
            }
        }

        val total = allReports.size
        val verified = allReports.count { it.status.contains("verif", ignoreCase = true) }
        binding.tvStatsHistory.text = "$total laporan • $verified terverifikasi"
    }

    override fun onDestroy() {
        super.onDestroy()
        reportsListener?.remove()
    }
}
