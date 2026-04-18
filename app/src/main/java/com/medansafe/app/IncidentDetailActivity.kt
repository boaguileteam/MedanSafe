package com.medansafe.app

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.medansafe.app.databinding.ActivityIncidentDetailBinding
import com.medansafe.app.databinding.ItemCommentBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.overlay.Marker
import java.util.Date
import java.util.Locale

class IncidentDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityIncidentDetailBinding
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private var incidentLat: Double = 0.0
    private var incidentLng: Double = 0.0
    private var currentIncidentId: String = ""
    
    private var incidentListener: ListenerRegistration? = null
    private var reporterSyncListener: ListenerRegistration? = null
    private var commentsListener: ListenerRegistration? = null
    
    private lateinit var commentAdapter: CommentAdapter
    private var incidentMarker: Marker? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName
        
        binding = ActivityIncidentDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupOsmMap()

        currentIncidentId = intent.getStringExtra("INCIDENT_ID") ?: intent.getStringExtra("incident_id") ?: ""

        if (currentIncidentId.isNotEmpty()) {
            observeIncidentDetail(currentIncidentId)
            setupCommentsRecyclerView(currentIncidentId)
            setupCommentInput(currentIncidentId)
        } else {
            Toast.makeText(this, "Data tidak ditemukan", Toast.LENGTH_SHORT).show()
            finish()
        }

        binding.btnBackDetail.setOnClickListener { finish() }

        binding.btnViewOnMap.setOnClickListener {
            if (incidentLat != 0.0 && incidentLng != 0.0) {
                val intent = Intent(this, HomeActivity::class.java).apply {
                    putExtra("LATITUDE", incidentLat)
                    putExtra("LONGITUDE", incidentLng)
                    putExtra("INCIDENT_NAME", binding.tvDetailTitle.text.toString())
                    putExtra("FOCUS_LOCATION", true)
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                startActivity(intent)
                finish()
            } else {
                Toast.makeText(this, "Lokasi tidak tersedia", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupOsmMap() {
        binding.miniMap.setTileSource(TileSourceFactory.MAPNIK)
        binding.miniMap.setMultiTouchControls(false)
        binding.miniMap.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        binding.miniMap.controller.setZoom(18.0)
    }

    private fun updateMapLocation(lat: Double, lng: Double, markerIconRes: Int) {
        val point = GeoPoint(lat, lng)
        if (incidentMarker == null) {
            incidentMarker = Marker(binding.miniMap)
            incidentMarker?.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            binding.miniMap.overlays.add(incidentMarker)
        }
        incidentMarker?.position = point
        incidentMarker?.icon = ContextCompat.getDrawable(this, markerIconRes)
        binding.miniMap.controller.setCenter(point)
        binding.miniMap.invalidate()
    }

    private fun observeIncidentDetail(incidentId: String) {
        incidentListener = db.collection("incidents").document(incidentId)
            .addSnapshotListener { doc, error ->
                if (error != null || doc == null || !doc.exists()) return@addSnapshotListener
                
                val kategori = doc.getString("kategori") ?: doc.getString("category") ?: "Insiden"
                val savedAddress = doc.getString("namaJalan") ?: doc.getString("address") ?: "Lokasi"
                val desc = doc.getString("deskripsi") ?: doc.getString("description") ?: "-"
                val name = doc.getString("namaUser") ?: "Warga Medan"
                val upvote = doc.getLong("upvote")?.toInt() ?: 0
                val bahaya = doc.getLong("tingkatBahaya")?.toInt() ?: 3
                val photo = doc.getString("fotoBase64") ?: ""
                val loc = doc.getGeoPoint("lokasi") ?: doc.getGeoPoint("location")
                val time = doc.getTimestamp("timestamp") ?: doc.getTimestamp("createdAt")
                val uid = doc.getString("uid") ?: ""

                incidentLat = loc?.latitude ?: 0.0
                incidentLng = loc?.longitude ?: 0.0

                val theme = when {
                    bahaya >= 4 -> Triple("#FEE2E2", "#C0392B", R.drawable.ic_marker_red)
                    bahaya >= 3 -> Triple("#FEF3C7", "#D97706", R.drawable.ic_marker_orange)
                    else -> Triple("#DCFCE7", "#10B981", R.drawable.ic_marker_green)
                }

                binding.tvDetailTypeBadge.text = kategori.uppercase()
                binding.tvDetailTypeBadge.setTextColor(Color.parseColor(theme.second))
                binding.tvDetailTypeBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(theme.first))

                val reportScore = (6 - bahaya) * 20
                binding.tvDetailScoreBadge.text = "$reportScore/100"
                binding.tvDetailScoreBadge.setTextColor(Color.parseColor(theme.second))
                binding.tvDetailScoreLabel.setTextColor(Color.parseColor(theme.second))
                binding.llScoreBadgeContainer.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(theme.first))

                binding.tvDetailTitle.text = savedAddress
                
                // Fetch Detailed Address from Geocoder to ensure it's "Lengkap"
                if (loc != null) {
                    reverseGeocodeAddress(loc.latitude, loc.longitude)
                }

                binding.tvDetailReporter.text = name
                binding.tvDetailDescription.text = desc
                binding.tvDetailUpvotesCount.text = "$upvote Upvotes"
                binding.btnUpvoteDetail.text = "Upvote ($upvote)"
                binding.btnUpvoteDetail.setOnClickListener { upvoteIncident(incidentId) }

                // SYNC REPORTER PROFILE DATA (LATEST) - REALTIME
                if (uid.isNotEmpty()) {
                    reporterSyncListener?.remove()
                    reporterSyncListener = db.collection("users").document(uid).addSnapshotListener { u, _ ->
                        if (u != null && u.exists() && !isFinishing) {
                            val pts = u.getLong("poin")?.toInt() ?: 0
                            val role = u.getString("role") ?: "user"
                            val realName = u.getString("nama") ?: name
                            val avatar = u.getString("profileImageUrl") ?: u.getString("fotoBase64User")
                            
                            binding.tvDetailReporter.text = realName
                            loadAvatar(avatar, binding.ivDetailReporterAvatar)

                            val badge = binding.tvDetailReporterTitleBadge
                            val (title, bColor, bBg) = when {
                                role.equals("developer", ignoreCase = true) -> Triple("OFFICIAL DEVELOPER", "#E11D48", "#1AE11D48")
                                pts >= 1000 -> Triple("PENGAWAL MEDAN", "#10B981", "#1A10B981")
                                pts >= 500  -> Triple("PELAPOR AKTIF",  "#3B82F6", "#1A3B82F6")
                                pts >= 100  -> Triple("KONTRIBUTOR",    "#F59E0B", "#1AF59E0B")
                                else        -> Triple("ANGGOTA BARU",   "#64748B", "#1A64748B")
                            }
                            badge.text = title
                            badge.setTextColor(bColor.toColorInt())
                            badge.backgroundTintList = android.content.res.ColorStateList.valueOf(bBg.toColorInt())
                            badge.visibility = View.VISIBLE
                        }
                    }
                }

                time?.let {
                    binding.tvDetailTime.text = DateUtils.getRelativeTimeSpanString(
                        it.toDate().time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
                    )
                }

                binding.tvAreaRiskScore.text = "$reportScore/100"
                binding.tvAreaRiskScore.setTextColor(Color.parseColor(theme.second))
                
                fetchNearbyIncidentsCount(incidentLat, incidentLng)

                decodeAndSetImage(photo)
                if (incidentLat != 0.0 && incidentLng != 0.0) {
                    updateMapLocation(incidentLat, incidentLng, theme.third)
                }
            }
    }

    private fun reverseGeocodeAddress(lat: Double, lng: Double) {
        lifecycleScope.launch(Dispatchers.IO) {
            val geocoder = Geocoder(this@IncidentDetailActivity, Locale.getDefault())
            try {
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(lat, lng, 1)
                if (!addresses.isNullOrEmpty()) {
                    val detailedAddr = formatAddress(addresses[0])
                    withContext(Dispatchers.Main) {
                        binding.tvDetailTitle.text = detailedAddr
                    }
                }
            } catch (_: Exception) {}
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
            address.getAddressLine(0)?.replace(Regex("[A-Z0-9]{4,8}\\+[A-Z0-9]{2,4},?\\s?"), "")?.trim() ?: "Lokasi"
        }
    }

    private fun loadAvatar(imageUrl: String?, imageView: ImageView) {
        if (imageUrl.isNullOrEmpty()) {
            Glide.with(this).load(R.drawable.ic_person).circleCrop().into(imageView)
            return
        }
        if (imageUrl.startsWith("data:image")) {
            try {
                val base64Data = imageUrl.substringAfter(",")
                val imageBytes = Base64.decode(base64Data, Base64.DEFAULT)
                Glide.with(this).load(imageBytes).circleCrop().into(imageView)
            } catch (_: Exception) {
                Glide.with(this).load(R.drawable.ic_person).circleCrop().into(imageView)
            }
        } else {
            Glide.with(this).load(imageUrl).placeholder(R.drawable.ic_person).circleCrop().into(imageView)
        }
    }

    private fun fetchNearbyIncidentsCount(lat: Double, lng: Double) {
        val offset = 0.01
        db.collection("incidents")
            .whereGreaterThan("lokasi", com.google.firebase.firestore.GeoPoint(lat - offset, lng - offset))
            .whereLessThan("lokasi", com.google.firebase.firestore.GeoPoint(lat + offset, lng + offset))
            .get()
            .addOnSuccessListener { snapshot ->
                val count = snapshot.size()
                binding.tvAreaMonthlyIncidents.text = "$count Laporan"
            }
    }

    private fun upvoteIncident(incidentId: String) {
        db.collection("incidents").document(incidentId)
            .update("upvote", com.google.firebase.firestore.FieldValue.increment(1))
            .addOnSuccessListener {
                binding.btnUpvoteDetail.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            }
    }

    private fun decodeAndSetImage(base64: String) {
        if (base64.isEmpty()) {
            binding.ivDetailImage.setImageResource(R.drawable.bg_danger_gradient)
            binding.tvNoPhotoHint.visibility = View.VISIBLE
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                withContext(Dispatchers.Main) {
                    binding.ivDetailImage.setImageBitmap(bitmap)
                    binding.tvNoPhotoHint.visibility = View.GONE
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    binding.ivDetailImage.setImageResource(R.drawable.bg_danger_gradient)
                    binding.tvNoPhotoHint.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun setupCommentsRecyclerView(incidentId: String) {
        commentAdapter = CommentAdapter()
        binding.rvComments.layoutManager = LinearLayoutManager(this)
        binding.rvComments.adapter = commentAdapter

        commentsListener = db.collection("incidents").document(incidentId).collection("comments")
            .orderBy("timestamp", Query.Direction.ASCENDING)
            .addSnapshotListener { value, error ->
                if (error != null) return@addSnapshotListener
                val list = mutableListOf<CommentModel>()
                value?.forEach { doc ->
                    val c = CommentModel(
                        userId = doc.getString("userId") ?: doc.getString("uid") ?: "",
                        userName = doc.getString("namaUser") ?: "User",
                        content = doc.getString("isi") ?: "",
                        timeAgo = DateUtils.getRelativeTimeSpanString(doc.getTimestamp("timestamp")?.toDate()?.time ?: Date().time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
                    )
                    list.add(c)
                }
                commentAdapter.submitList(list)
            }
    }

    private fun setupCommentInput(incidentId: String) {
        binding.btnSendCommentDetail.setOnClickListener { sendComment(incidentId) }
        binding.etCommentDetail.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendComment(incidentId)
                true
            } else false
        }
    }

    private fun sendComment(incidentId: String) {
        val content = binding.etCommentDetail.text.toString().trim()
        if (content.isEmpty()) return

        val user = auth.currentUser
        if (user == null) {
            Toast.makeText(this, "Silakan login terlebih dahulu", Toast.LENGTH_SHORT).show()
            return
        }

        val commentData = hashMapOf(
            "namaUser" to (user.displayName ?: "Warga Medan"),
            "isi" to content,
            "timestamp" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
            "userId" to user.uid
        )

        db.collection("incidents").document(incidentId)
            .collection("comments")
            .add(commentData)
            .addOnSuccessListener { binding.etCommentDetail.setText("") }
    }

    override fun onResume() { super.onResume(); binding.miniMap.onResume() }
    override fun onPause() { super.onPause(); binding.miniMap.onPause() }
    override fun onDestroy() { 
        super.onDestroy()
        incidentListener?.remove()
        reporterSyncListener?.remove()
        commentsListener?.remove()
    }

    inner class CommentAdapter : RecyclerView.Adapter<CommentAdapter.VH>() {
        private var items = listOf<CommentModel>()
        
        fun submitList(l: List<CommentModel>) { items = l; notifyDataSetChanged() }
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(ItemCommentBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        
        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
        
        override fun getItemCount() = items.size
        
        inner class VH(val b: ItemCommentBinding) : RecyclerView.ViewHolder(b.root) {
            private var profileListener: ListenerRegistration? = null
            
            fun bind(c: CommentModel) {
                b.tvCommentUser.text = c.userName
                b.tvCommentText.text = c.content
                b.tvCommentTime.text = c.timeAgo
                
                // Clear previous image to avoid flickering from recycled views
                b.ivCommentUser.setImageResource(R.drawable.ic_person)
                
                // Real-time Sync for Commenter's Profile
                if (c.userId.isNotEmpty()) {
                    profileListener?.remove()
                    profileListener = db.collection("users").document(c.userId).addSnapshotListener { u, _ ->
                        if (u != null && u.exists()) {
                            val avatar = u.getString("profileImageUrl") ?: u.getString("fotoBase64User")
                            val realName = u.getString("nama") ?: c.userName
                            b.tvCommentUser.text = realName
                            
                            // Load Commenter Avatar with Base64 support - REALTIME
                            loadAvatar(avatar, b.ivCommentUser)
                        }
                    }
                }
            }

            fun cleanup() {
                profileListener?.remove()
            }
        }

        override fun onViewRecycled(holder: VH) {
            super.onViewRecycled(holder)
            holder.cleanup()
        }
    }
}
