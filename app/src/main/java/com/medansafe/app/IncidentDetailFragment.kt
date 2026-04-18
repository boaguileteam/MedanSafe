package com.medansafe.app

import android.content.res.ColorStateList
import android.graphics.Color
import android.location.Address
import android.location.Geocoder
import android.os.Bundle
import android.text.format.DateUtils
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.medansafe.app.R
import com.medansafe.app.databinding.FragmentIncidentDetailBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class IncidentDetailFragment : Fragment() {

    private var _binding: FragmentIncidentDetailBinding? = null
    private val binding get() = _binding!!
    private val db = FirebaseFirestore.getInstance()
    private var reporterListener: ListenerRegistration? = null

    companion object {
        private const val ARG_INCIDENT_ID = "incident_id"

        fun newInstance(incidentId: String): IncidentDetailFragment {
            val fragment = IncidentDetailFragment()
            val args = Bundle()
            args.putString(ARG_INCIDENT_ID, incidentId)
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentIncidentDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val incidentId = arguments?.getString(ARG_INCIDENT_ID) ?: return
        loadIncidentData(incidentId)
    }

    private fun loadIncidentData(id: String) {
        db.collection("incidents").document(id).get().addOnSuccessListener { doc ->
            if (!doc.exists() || _binding == null) return@addOnSuccessListener

            val kategori = doc.getString("kategori") ?: "Insiden"
            val bahaya   = doc.getLong("tingkatBahaya")?.toInt() ?: 3
            val score    = (6 - bahaya) * 20
            val uid      = doc.getString("uid") ?: ""
            val loc      = doc.getGeoPoint("lokasi") ?: doc.getGeoPoint("location")

            val (bgColor, textColor) = when {
                bahaya >= 4 -> Pair("#FEE2E2", "#C0392B")
                bahaya == 3 -> Pair("#FEF3C7", "#D97706")
                else        -> Pair("#DCFCE7", "#10B981")
            }

            binding.apply {
                tvDetailType.text = kategori.uppercase()
                tvDetailType.setTextColor(Color.parseColor(textColor))
                tvDetailType.backgroundTintList = ColorStateList.valueOf(Color.parseColor(bgColor))

                llScoreContainer.backgroundTintList = ColorStateList.valueOf(Color.parseColor(bgColor))
                tvIncidentScoreLabel.setTextColor(Color.parseColor(textColor))
                tvIncidentScore.text = "$score/100"
                tvIncidentScore.setTextColor(Color.parseColor(textColor))

                tvReporterName.text = doc.getString("namaUser") ?: "Warga Medan"

                val savedAddr = doc.getString("namaJalan") ?: "Alamat tidak tersedia"
                tvFullAddress.text = savedAddr
                if (loc != null) reverseGeocodeAddress(loc.latitude, loc.longitude)

                tvDetailDesc.text = doc.getString("deskripsi") ?: "Tidak ada keterangan."

                val ts = doc.getTimestamp("timestamp")
                tvReportDate.text = if (ts != null)
                    DateUtils.getRelativeTimeSpanString(ts.toDate().time)
                else "Waktu tidak diketahui"

                // Photo
                val photoBase64 = doc.getString("fotoBase64") ?: ""
                if (photoBase64.isNotEmpty()) {
                    cvIncidentPhoto.visibility = View.VISIBLE
                    llNoPhoto.visibility = View.GONE
                    try {
                        val imageBytes = Base64.decode(photoBase64, Base64.DEFAULT)
                        Glide.with(this@IncidentDetailFragment).load(imageBytes).into(ivIncidentPhoto)
                    } catch (_: Exception) {
                        cvIncidentPhoto.visibility = View.GONE
                        llNoPhoto.visibility = View.VISIBLE
                    }
                } else {
                    cvIncidentPhoto.visibility = View.GONE
                    llNoPhoto.visibility = View.VISIBLE
                }

                // Reporter realtime
                if (uid.isNotEmpty()) {
                    reporterListener?.remove()
                    reporterListener = db.collection("users").document(uid).addSnapshotListener { u, _ ->
                        if (u != null && u.exists() && _binding != null) {
                            val pts      = u.getLong("poin")?.toInt() ?: 0
                            val role     = u.getString("role") ?: "user"
                            val realName = u.getString("nama") ?: doc.getString("namaUser") ?: "Warga Medan"
                            tvReporterName.text = realName

                            val (title, bColor, bBg) = when {
                                role.equals("developer", ignoreCase = true) -> Triple("OFFICIAL DEVELOPER", "#E11D48", "#1AE11D48")
                                pts >= 1000 -> Triple("PENGAWAL MEDAN", "#10B981", "#1A10B981")
                                pts >= 500  -> Triple("PELAPOR AKTIF",  "#3B82F6", "#1A3B82F6")
                                pts >= 100  -> Triple("KONTRIBUTOR",    "#F59E0B", "#1AF59E0B")
                                else        -> Triple("ANGGOTA BARU",   "#64748B", "#1A64748B")
                            }
                            tvReporterTitleBadge.text = title
                            tvReporterTitleBadge.setTextColor(Color.parseColor(bColor))
                            tvReporterTitleBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor(bBg))
                            tvReporterTitleBadge.visibility = View.VISIBLE

                            val profileImg = u.getString("profileImageUrl") ?: u.getString("fotoBase64User")
                            Glide.with(this@IncidentDetailFragment)
                                .load(profileImg).placeholder(R.drawable.ic_person)
                                .circleCrop().into(ivReporterAvatar)
                        }
                    }
                }

                btnUpvoteBottom.text = "Upvote (${doc.getLong("upvote")?.toInt() ?: 0})"
                btnUpvoteBottom.setOnClickListener {
                    db.collection("incidents").document(id).update("upvote", FieldValue.increment(1))
                    it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                }

                btnReportHere.setOnClickListener {
                    (activity as? HomeActivity)?.binding?.bottomNavigation?.selectedItemId = R.id.nav_report
                }
            }
        }
    }

    private fun reverseGeocodeAddress(lat: Double, lng: Double) {
        val context = context ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val geocoder = Geocoder(context, Locale.getDefault())
            try {
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(lat, lng, 1)
                if (!addresses.isNullOrEmpty()) {
                    val detailedAddr = formatAddress(addresses[0])
                    withContext(Dispatchers.Main) {
                        _binding?.tvFullAddress?.text = detailedAddr
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun formatAddress(address: Address): String {
        val parts = mutableListOf<String>()
        val street  = address.thoroughfare
        val feature = address.featureName
        if (!street.isNullOrEmpty()) {
            if (!feature.isNullOrEmpty() && feature != street && !feature.contains("+"))
                parts.add("$street No. $feature")
            else parts.add(street)
        } else if (!feature.isNullOrEmpty() && !feature.contains("+")) {
            parts.add(feature)
        }
        address.subLocality?.let { parts.add(it) }
        address.locality?.let { parts.add("Kec. $it") }
        address.subAdminArea?.let { parts.add(it) }
        return if (parts.isNotEmpty()) parts.joinToString(", ")
        else address.getAddressLine(0)?.replace(Regex("[A-Z0-9]{4,8}\\+[A-Z0-9]{2,4},?\\s?"), "")?.trim() ?: "Lokasi"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        reporterListener?.remove()
        _binding = null
    }
}