package com.medansafe.app

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.medansafe.app.databinding.ActivityAdminDashboardBinding

class AdminDashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdminDashboardBinding
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private val listeners = mutableListOf<ListenerRegistration>()
    private lateinit var activityAdapter: AdminActivityAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdminDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkAdminAccess()
        setupRecyclerView()
        setupStats()
        setupMenu()
    }

    private fun checkAdminAccess() {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            goToLogin()
            return
        }
        db.collection("users").document(uid).get()
            .addOnSuccessListener { doc ->
                val isAdmin = doc.getBoolean("isAdmin") ?: false
                if (!isAdmin) {
                    Toast.makeText(this, "Akses ditolak", Toast.LENGTH_SHORT).show()
                    startActivity(Intent(this, HomeActivity::class.java))
                    finish()
                }
            }
            .addOnFailureListener {
                goToLogin()
            }
    }

    private fun setupRecyclerView() {
        activityAdapter = AdminActivityAdapter()
        binding.rvRecentActivity.apply {
            layoutManager = LinearLayoutManager(this@AdminDashboardActivity)
            adapter = activityAdapter
            isNestedScrollingEnabled = false
        }

        val activityListener = db.collection("incidents")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(5)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    db.collection("incidents")
                        .limit(5)
                        .get()
                        .addOnSuccessListener { fallbackSnapshot ->
                            val activities = fallbackSnapshot.documents
                                .mapNotNull { doc ->
                                    val type = doc.getString("kategori") ?: "Aman"
                                    val location = doc.getString("namaJalan") ?: "Lokasi tidak diketahui"
                                    val reporter = doc.getString("namaUser") ?: "Warga Medan"
                                    val time = doc.getTimestamp("timestamp") ?: doc.getTimestamp("createdAt")
                                    AdminActivity(
                                        title = "$type Baru",
                                        subtext = "$location · $reporter",
                                        timestamp = time,
                                        kategori = type
                                    )
                                }
                            activityAdapter.submitList(activities)
                        }
                    return@addSnapshotListener
                }

                val activities = snapshot?.documents?.mapNotNull { doc ->
                    val type = doc.getString("kategori") ?: "Aman"
                    val location = doc.getString("namaJalan") ?: "Lokasi tidak diketahui"
                    val reporter = doc.getString("namaUser") ?: "Warga Medan"
                    val time = doc.getTimestamp("timestamp") ?: doc.getTimestamp("createdAt")
                    AdminActivity(
                        title = "$type Baru",
                        subtext = "$location · $reporter",
                        timestamp = time,
                        kategori = type
                    )
                } ?: emptyList()
                activityAdapter.submitList(activities)
            }
        listeners.add(activityListener)
    }

    private fun setupStats() {
        // Total Laporan
        val l1 = db.collection("incidents").addSnapshotListener { snapshot, _ ->
            val count = snapshot?.size() ?: 0
            binding.tvTotalLaporan.text = count.toString()
            val tvNewBadge = binding.root.findViewById<TextView>(R.id.tvNewLaporanBadge)
            tvNewBadge?.text = count.toString()
        }
        listeners.add(l1)

        // Waiting Verif
        val l2 = db.collection("incidents")
            .whereEqualTo("status", "menunggu")
            .addSnapshotListener { snapshot, _ ->
                val count = snapshot?.size() ?: 0
                binding.tvWaitingVerif.text = count.toString()
            }
        listeners.add(l2)

        // Verified
        val l3 = db.collection("incidents")
            .whereEqualTo("status", "terverifikasi")
            .addSnapshotListener { snapshot, _ ->
                val verifiedCount = snapshot?.size() ?: 0
                binding.tvVerifiedCount.text = verifiedCount.toString()

                db.collection("incidents").get().addOnSuccessListener { totalSnapshot ->
                    val total = totalSnapshot.size()
                    val percent = if (total > 0) (verifiedCount * 100) / total else 0
                    binding.tvVerifiedPercentBadge.text = "$percent%"
                }
            }
        listeners.add(l3)

        // Total User (Non-Admin)
        val l4 = db.collection("users")
            .whereEqualTo("isAdmin", false)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    db.collection("users").get()
                        .addOnSuccessListener { all ->
                            val count = all.documents.count { doc ->
                                doc.getBoolean("isAdmin") != true
                            }
                            binding.tvTotalUser.text = count.toString()
                            binding.tvNewUserBadge.text = count.toString()
                        }
                    return@addSnapshotListener
                }
                val userCount = snapshot?.size() ?: 0
                binding.tvTotalUser.text = userCount.toString()
                binding.tvNewUserBadge.text = userCount.toString()
            }
        listeners.add(l4)
    }

    private fun setupMenu() {
        binding.btnVerifikasiLaporan.setOnClickListener {
            startActivity(Intent(this, AdminVerifikasiActivity::class.java))
        }

        binding.btnLihatSemua.setOnClickListener {
            startActivity(Intent(this, AdminVerifikasiActivity::class.java))
        }

        binding.btnKeluarAdmin.setOnClickListener {
            showLogoutDialog()
        }
    }

    private fun showLogoutDialog() {
        AlertDialog.Builder(this)
            .setTitle("Keluar Admin Panel")
            .setMessage("Apakah Anda yakin ingin keluar ke dashboard utama?")
            .setPositiveButton("Ya, Keluar") { _, _ ->
                startActivity(Intent(this, HomeActivity::class.java))
                finish()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun goToLogin() {
        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        listeners.forEach { it.remove() }
    }
}
