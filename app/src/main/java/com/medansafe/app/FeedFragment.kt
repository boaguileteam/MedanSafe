package com.medansafe.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatButton
import androidx.core.graphics.toColorInt
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.medansafe.app.databinding.FragmentFeedBinding
import java.text.SimpleDateFormat
import java.util.*

class FeedFragment : Fragment() {

    private var _binding: FragmentFeedBinding? = null
    private val binding get() = _binding!!
    
    private lateinit var adapter: IncidentAdapter
    private val db = FirebaseFirestore.getInstance()
    private var allIncidents = mutableListOf<IncidentModel>()
    private var filteredList = mutableListOf<IncidentModel>()
    private var currentFilter = "Semua"
    private var incidentsListener: ListenerRegistration? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentFeedBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupSwipeRefresh()
        setupFilters()
        listenToIncidents()
    }

    private fun setupRecyclerView() {
        if (context == null) return
        binding.rvFeed.layoutManager = LinearLayoutManager(requireContext())
        adapter = IncidentAdapter(filteredList, isHistory = false) { incident ->
            val intent = Intent(requireContext(), IncidentDetailActivity::class.java)
            intent.putExtra("INCIDENT_ID", incident.id)
            startActivity(intent)
        }
        binding.rvFeed.adapter = adapter
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefreshFeed.setColorSchemeColors("#C0392B".toColorInt())
        binding.swipeRefreshFeed.setOnRefreshListener { listenToIncidents() }
    }

    private fun listenToIncidents() {
        incidentsListener?.remove()
        if (_binding != null) binding.swipeRefreshFeed.isRefreshing = true
        
        incidentsListener = db.collection("incidents")
            .whereEqualTo("status", "terverifikasi")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .addSnapshotListener { value, error ->
                if (_binding == null || context == null) return@addSnapshotListener
                binding.swipeRefreshFeed.isRefreshing = false
                
                if (error != null) return@addSnapshotListener

                allIncidents.clear()
                value?.documents?.forEach { doc ->
                    val id = doc.id
                    val category = doc.getString("kategori") ?: ""
                    val address = doc.getString("namaJalan") ?: ""
                    val upvote = doc.getLong("upvote")?.toInt() ?: 0
                    val danger = doc.getLong("tingkatBahaya")?.toInt() ?: 3
                    val photo = doc.getString("fotoBase64") ?: ""
                    val loc = doc.getGeoPoint("lokasi")
                    val time = doc.getTimestamp("timestamp")

                    allIncidents.add(IncidentModel(
                        id = id, category = category, address = address,
                        description = doc.getString("deskripsi") ?: "",
                        reporterName = doc.getString("namaUser") ?: "Warga",
                        upvoteCount = upvote, areaScore = (6 - danger) * 20,
                        photoBase64 = photo, latitude = loc?.latitude ?: 0.0,
                        longitude = loc?.longitude ?: 0.0, createdAt = time, status = "terverifikasi"
                    ))
                }
                filterData(currentFilter)
            }
    }

    private fun setupFilters() {
        val filterButtons = mapOf(
            binding.btnFilterAll to "Semua",
            binding.btnFilterBegal to "Begal",
            binding.btnFilterDark to "Jalan Gelap",
            binding.btnFilterAccident to "Kecelakaan",
            binding.btnFilterDamagedRoad to "Lainnya"
        )
        filterButtons.forEach { (button, category) ->
            button.setOnClickListener {
                currentFilter = category
                updateFilterUI(button, filterButtons.keys.toList())
                filterData(category)
            }
        }
    }

    private fun filterData(category: String) {
        if (_binding == null) return
        filteredList.clear()
        
        when (category) {
            "Semua" -> filteredList.addAll(allIncidents)
            "Lainnya" -> {
                // Kategori Utama: Begal, Jalan Gelap, Kecelakaan
                // Selain itu, masuk ke filter "Lainnya" (Banjir, Macet, dll)
                val mainCategories = listOf("Begal", "Jalan Gelap", "Kecelakaan")
                filteredList.addAll(allIncidents.filter { incident ->
                    !mainCategories.any { main -> incident.category.equals(main, ignoreCase = true) }
                })
            }
            else -> {
                filteredList.addAll(allIncidents.filter { it.category.equals(category, ignoreCase = true) })
            }
        }

        adapter.notifyDataSetChanged()
        binding.rvFeed.visibility = if (filteredList.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun updateFilterUI(selected: AppCompatButton, all: List<AppCompatButton>) {
        val primaryColor = "#C0392B".toColorInt()
        all.forEach { btn ->
            if (btn == selected) {
                btn.backgroundTintList = android.content.res.ColorStateList.valueOf(primaryColor)
                btn.setTextColor(Color.WHITE)
            } else {
                btn.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                btn.setTextColor("#64748B".toColorInt())
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        incidentsListener?.remove()
        _binding = null
    }
}
