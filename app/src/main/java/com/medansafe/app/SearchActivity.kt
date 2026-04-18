package com.medansafe.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.graphics.toColorInt
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.medansafe.app.databinding.ActivitySearchBinding
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class SearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySearchBinding

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val searchHandler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null

    private val db   = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    private val savedCoordKeys    = mutableSetOf<String>()
    private val savedDocIdByCoord = mutableMapOf<String, String>()
    private val savedNameByCoord  = mutableMapOf<String, String>()

    // ═══════════════════════════════════════════════════════════
    // LOKASI POPULER MEDAN — KOORDINAT TERVERIFIKASI
    //
    // Semua koordinat dicek ulang via OpenStreetMap / data resmi.
    // Lokasi yang koordinatnya meragukan dihapus sepenuhnya.
    //
    // USU: Jl. Dr. T. Mansur No.9, Padang Bulan → 3.5669, 98.6593
    //   (pusat kampus, bukan pintu masuk di Jl. Universitas/Sofyan)
    //
    // Sun Plaza: Jl. H. Zainul Arifin, Medan Polonia → 3.5832, 98.6901
    //   (bukan 3.587 yang meleset ke sebelah barat)
    // ═══════════════════════════════════════════════════════════
    private val popularLocations = listOf(

        // ── PUSAT PERBELANJAAN ────────────────────────────────
        LocationItem(
            "Sun Plaza Medan",
            "Jl. H. Zainul Arifin No.7, Madras Hulu, Medan Polonia",
            3.58325, 98.69014, "🏬"
        ),
        LocationItem(
            "Plaza Medan Fair",
            "Jl. Gatot Subroto No.30, Sekip, Medan Petisah",
            3.59544, 98.66842, "🏬"
        ),
        LocationItem(
            "Grand Palladium Medan",
            "Jl. Kapt. Maulana Lubis No.8, Petisah Tengah, Medan Petisah",
            3.59309, 98.67021, "🏬"
        ),
        LocationItem(
            "Centre Point Mall Medan",
            "Jl. Jawa No.8, Sei Rengas II, Medan Kota",
            3.59191, 98.68142, "🏬"
        ),
        LocationItem(
            "Medan Mall",
            "Jl. M.H. Thamrin No.75, Sei Rengas I, Medan Kota",
            3.58220, 98.68041, "🏬"
        ),
        LocationItem(
            "Pasar Petisah",
            "Jl. Kota Baru, Petisah Tengah, Medan Petisah",
            3.59221, 98.66324, "🛒"
        ),
        LocationItem(
            "Cambridge City Square",
            "Jl. S. Parman No.217, Petisah Tengah, Medan Petisah",
            3.59492, 98.66584, "🏬"
        ),

        // ── WISATA & BUDAYA ───────────────────────────────────
        LocationItem(
            "Istana Maimun",
            "Jl. Brigjend Katamso No.66, A U R, Medan Maimun",
            3.57448, 98.68189, "🏛️"
        ),
        LocationItem(
            "Masjid Raya Al-Mashun Medan",
            "Jl. Sisingamangaraja No.61, Mesjid, Medan Kota",
            3.57724, 98.68652, "🕌"
        ),
        LocationItem(
            "Lapangan Merdeka Medan",
            "Jl. Balai Kota, Kesawan, Medan Barat",
            3.58622, 98.67503, "🌳"
        ),
        LocationItem(
            "Museum Negeri Sumatera Utara",
            "Jl. H.M. Joni No.51, Teladan Barat, Medan Kota",
            3.57155, 98.68927, "🏛️"
        ),
        LocationItem(
            "Rumah Tjong A Fie",
            "Jl. Ahmad Yani No.105, Kesawan, Medan Barat",
            3.58564, 98.67381, "🏛️"
        ),
        LocationItem(
            "Kuil Sri Mariamman",
            "Jl. H. Zainul Arifin, Petisah Hulu, Medan Barat",
            3.58414, 98.68031, "🛕"
        ),

        // ── UNIVERSITAS & PENDIDIKAN ──────────────────────────
        LocationItem(
            // Koordinat: pusat kampus USU Padang Bulan
            // Alamat resmi: Jl. Dr. T. Mansur No.9, Padang Bulan, Medan Baru
            "Universitas Sumatera Utara (USU)",
            "Jl. Dr. T. Mansur No.9, Padang Bulan, Medan Baru",
            3.56685, 98.65914, "🎓"
        ),
        LocationItem(
            "Universitas Negeri Medan (UNIMED)",
            "Jl. William Iskandar Pasar V, Medan Estate, Percut Sei Tuan",
            3.61444, 98.71701, "🎓"
        ),
        LocationItem(
            "Universitas HKBP Nommensen Medan",
            "Jl. Sutomo No.4A, Hamdan, Medan Maimun",
            3.57794, 98.67711, "🎓"
        ),
        LocationItem(
            "Universitas Muhammadiyah Sumatera Utara (UMSU)",
            "Jl. Kapten Muchtar Basri No.3, Glugur Darat II, Medan Timur",
            3.59894, 98.69351, "🎓"
        ),
        LocationItem(
            "Politeknik Negeri Medan",
            "Jl. Almamater No.1, Padang Bulan, Medan Baru",
            3.57011, 98.64788, "🎓"
        ),
        LocationItem(
            "Universitas Islam Negeri Sumatera Utara (UINSU)",
            "Jl. Willem Iskandar Pasar V, Medan Estate, Percut Sei Tuan",
            3.60876, 98.71484, "🎓"
        ),

        // ── RUMAH SAKIT ───────────────────────────────────────
        LocationItem(
            "RSUP H. Adam Malik Medan",
            "Jl. Bunga Lau No.17, Kemenangan Tani, Medan Tuntungan",
            3.56634, 98.63551, "🏥"
        ),
        LocationItem(
            "RS Columbia Asia Medan",
            "Jl. Listrik No.2A, Petisah Tengah, Medan Petisah",
            3.59238, 98.66301, "🏥"
        ),
        LocationItem(
            "RS Permata Bunda Medan",
            "Jl. Sisingamangaraja No.7, Teladan Barat, Medan Kota",
            3.57054, 98.69104, "🏥"
        ),
        LocationItem(
            "RS Murni Teguh Memorial Medan",
            "Jl. Jawa No.2, Petisah Tengah, Medan Petisah",
            3.59294, 98.67734, "🏥"
        ),

        // ── TRANSPORTASI ──────────────────────────────────────
        LocationItem(
            "Stasiun Kereta Api Medan",
            "Jl. Stasiun Kereta Api No.1, Kesawan, Medan Barat",
            3.58889, 98.67348, "🚂"
        ),
        LocationItem(
            "Bandara Internasional Kualanamu",
            "Jl. Tengku Hamzah Bendahara, Beringin, Deli Serdang",
            3.64228, 98.88519, "✈️"
        ),
        LocationItem(
            "Terminal Amplas Medan",
            "Jl. Sisingamangaraja KM 7.5, Harjosari II, Medan Amplas",
            3.54889, 98.69803, "🚌"
        ),
        LocationItem(
            "Terminal Pinang Baris Medan",
            "Jl. Pinang Baris, Lalang, Medan Sunggal",
            3.60931, 98.62847, "🚌"
        ),
        LocationItem(
            "Pelabuhan Belawan",
            "Jl. Sumatera, Belawan Kota, Medan Belawan",
            3.79184, 98.69455, "⚓"
        ),

        // ── WISATA ALAM SEKITAR MEDAN ─────────────────────────
        LocationItem(
            "Bukit Lawang (Orangutan)",
            "Bohorok, Langkat, Sumatera Utara",
            3.53592, 98.12354, "🌿"
        ),
        LocationItem(
            "Kota Berastagi",
            "Jl. Veteran, Berastagi, Karo, Sumatera Utara",
            3.19463, 98.50679, "🏔️"
        ),
        LocationItem(
            "Danau Toba (Parapat)",
            "Parapat, Girsang Sipangan Bolon, Simalungun",
            2.66506, 98.93921, "🏞️"
        ),

        // ── KULINER IKONIK MEDAN ──────────────────────────────
        LocationItem(
            "Durian Ucok Medan",
            "Jl. Iskandar Muda No.15, Petisah Tengah, Medan Petisah",
            3.59204, 98.66601, "🍈"
        ),
        LocationItem(
            "Merdeka Walk Medan",
            "Jl. Balai Kota, Kesawan, Medan Barat",
            3.58471, 98.67529, "🍽️"
        ),
        LocationItem(
            "Bika Ambon Zulaikha",
            "Jl. Mojopahit No.76, Petisah Hulu, Medan Petisah",
            3.59681, 98.67201, "🍰"
        )
    )

    data class LocationItem(
        val name:      String,
        val address:   String,
        val lat:       Double,
        val lng:       Double,
        val emoji:     String  = "📍",
        val isHistory: Boolean = false,
        var isSaved:   Boolean = false,
        val docId:     String  = ""
    )

    private fun coordKey(lat: Double, lng: Double): String =
        "${String.format("%.4f", lat)}_${String.format("%.4f", lng)}"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupViews()
        loadHistoryAndSaved()

        binding.etSearchLocation.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(binding.etSearchLocation, InputMethodManager.SHOW_IMPLICIT)
    }

    // =========================================================
    // SETUP
    // =========================================================
    private fun setupViews() {
        binding.btnBackSearch.setOnClickListener { finish() }

        binding.etSearchLocation.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim() ?: ""
                binding.btnClearSearch.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
                searchRunnable?.let { searchHandler.removeCallbacks(it) }
                if (query.isEmpty()) { showDefaultSections(); return }
                searchRunnable = Runnable { performSearch(query) }
                searchHandler.postDelayed(searchRunnable!!, 500)
            }
        })

        binding.btnClearSearch.setOnClickListener {
            binding.etSearchLocation.setText(""); showDefaultSections()
        }
        binding.btnClearHistory.setOnClickListener { clearAllHistory() }
        binding.btnManageSaved.setOnClickListener {
            Toast.makeText(this, "Klik ikon ★ untuk tambah/hapus favorit", Toast.LENGTH_SHORT).show()
        }

        binding.rvPopularLocations.layoutManager = LinearLayoutManager(this)
        binding.rvHistoryLocations.layoutManager = LinearLayoutManager(this)
        binding.rvSavedLocations.layoutManager   = LinearLayoutManager(this)
    }

    // =========================================================
    // DEFAULT SECTIONS
    // =========================================================
    private fun showDefaultSections() {
        binding.llSectionHistory.visibility = View.VISIBLE
        binding.llSectionSaved.visibility   = View.VISIBLE
        binding.llSectionPopular.visibility = View.VISIBLE
        binding.tvTitlePopular.text = "📍 LOKASI POPULER MEDAN"
        loadPopularLocations()
    }

    // =========================================================
    // LOAD DATA DARI FIRESTORE
    // =========================================================
    private fun loadHistoryAndSaved() {
        val uid = auth.currentUser?.uid ?: run { showDefaultSections(); return }

        db.collection("users").document(uid).collection("search_history")
            .orderBy("timestamp", Query.Direction.DESCENDING).limit(5)
            .addSnapshotListener { docs, _ ->
                if (docs == null) return@addSnapshotListener
                val history = docs.mapNotNull { doc ->
                    val lat = doc.getDouble("lat") ?: return@mapNotNull null
                    val lng = doc.getDouble("lng") ?: return@mapNotNull null
                    if (lat == 0.0 && lng == 0.0) return@mapNotNull null
                    val ck = coordKey(lat, lng)
                    LocationItem(
                        name = doc.getString("name") ?: "", address = doc.getString("address") ?: "",
                        lat = lat, lng = lng, emoji = "🕐", isHistory = true,
                        isSaved = savedCoordKeys.contains(ck), docId = doc.id
                    )
                }
                binding.llSectionHistory.visibility = if (history.isEmpty()) View.GONE else View.VISIBLE
                binding.rvHistoryLocations.adapter = LocationAdapter(history) { returnResult(it) }
            }

        db.collection("users").document(uid).collection("saved_locations")
            .addSnapshotListener { docs, _ ->
                if (docs == null) return@addSnapshotListener
                savedCoordKeys.clear(); savedDocIdByCoord.clear(); savedNameByCoord.clear()
                val saved = docs.mapNotNull { doc ->
                    val lat = doc.getDouble("lat") ?: return@mapNotNull null
                    val lng = doc.getDouble("lng") ?: return@mapNotNull null
                    if (lat == 0.0 && lng == 0.0) return@mapNotNull null
                    val ck = coordKey(lat, lng)
                    savedCoordKeys.add(ck); savedDocIdByCoord[ck] = doc.id
                    savedNameByCoord[ck] = doc.getString("name") ?: ""
                    LocationItem(
                        name = doc.getString("name") ?: "", address = doc.getString("address") ?: "",
                        lat = lat, lng = lng, emoji = "⭐", isSaved = true, docId = doc.id
                    )
                }
                binding.rvSavedLocations.adapter = LocationAdapter(saved) { returnResult(it) }
                refreshAllSavedStatus()
            }

        showDefaultSections()
    }

    private fun refreshAllSavedStatus() {
        listOf(binding.rvPopularLocations, binding.rvHistoryLocations).forEach { rv ->
            (rv.adapter as? LocationAdapter)?.let { adapter ->
                adapter.items.forEach { item -> item.isSaved = savedCoordKeys.contains(coordKey(item.lat, item.lng)) }
                adapter.notifyDataSetChanged()
            }
        }
    }

    // =========================================================
    // LOKASI POPULER
    // =========================================================
    private fun loadPopularLocations() {
        val updated = popularLocations.map { item ->
            item.copy(isSaved = savedCoordKeys.contains(coordKey(item.lat, item.lng)))
        }
        binding.llSectionPopular.visibility = View.VISIBLE
        binding.rvPopularLocations.adapter = LocationAdapter(updated) { item ->
            saveToHistory(item); returnResult(item)
        }
    }

    // =========================================================
    // PENCARIAN (Nominatim OSM)
    // =========================================================
    private fun performSearch(query: String) {
        binding.llSectionHistory.visibility = View.GONE
        binding.llSectionSaved.visibility   = View.GONE
        binding.llSectionPopular.visibility = View.VISIBLE
        binding.tvTitlePopular.text = "🔍 Mencari: \"$query\"..."
        binding.rvPopularLocations.adapter = EmptyAdapter("Mencari lokasi...")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val needsCtx = !query.contains("medan",          ignoreCase = true) &&
                        !query.contains("sumatera utara", ignoreCase = true) &&
                        !query.contains("sumut",          ignoreCase = true) &&
                        !query.contains("deli serdang",   ignoreCase = true) &&
                        !query.contains("karo",           ignoreCase = true) &&
                        !query.contains("langkat",        ignoreCase = true)
                val queryWithCtx = if (needsCtx) "$query, Medan, Sumatera Utara" else query

                val encoded = java.net.URLEncoder.encode(queryWithCtx, "UTF-8")
                val url = "https://nominatim.openstreetmap.org/search" +
                        "?q=$encoded&format=jsonv2&addressdetails=1&limit=15" +
                        "&countrycodes=id&accept-language=id&viewbox=97.8,2.0,100.5,6.5&bounded=0"

                val resp    = httpClient.newCall(Request.Builder().url(url).header("User-Agent", "MedanSafe/1.0 (Android)").build()).execute()
                val bodyStr = resp.body?.string() ?: "[]"
                var results = parseNominatimResults(bodyStr)

                // Fallback tanpa konteks jika kosong
                if (results.isEmpty() && needsCtx) {
                    val enc2 = java.net.URLEncoder.encode(query, "UTF-8")
                    val url2 = "https://nominatim.openstreetmap.org/search?q=$enc2&format=jsonv2&addressdetails=1&limit=10&countrycodes=id&accept-language=id"
                    val resp2 = httpClient.newCall(Request.Builder().url(url2).header("User-Agent", "MedanSafe/1.0").build()).execute()
                    results = parseNominatimResults(resp2.body?.string() ?: "[]")
                }

                withContext(Dispatchers.Main) {
                    binding.tvTitlePopular.text = if (results.isEmpty())
                        "❌ Tidak ditemukan: \"$query\""
                    else
                        "✅ ${results.size} hasil untuk \"${query.take(20)}\""
                    binding.rvPopularLocations.adapter = if (results.isEmpty())
                        EmptyAdapter("Coba kata kunci lain.\nContoh: \"Jl. Gatot\", \"Sun Plaza\", \"USU\"")
                    else
                        LocationAdapter(results) { item -> saveToHistory(item); returnResult(item) }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.tvTitlePopular.text = "⚠️ Gagal terhubung"
                    binding.rvPopularLocations.adapter = EmptyAdapter("Periksa koneksi internet Anda.")
                }
            }
        }
    }

    private fun parseNominatimResults(json: String): List<LocationItem> {
        val list = mutableListOf<LocationItem>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val lat = obj.optDouble("lat", 0.0)
                val lng = obj.optDouble("lon", 0.0)
                if (lat == 0.0 && lng == 0.0) continue

                val type        = obj.optString("type", "")
                val category    = obj.optString("category", "")
                val displayName = obj.optString("display_name", "")
                val parts       = displayName.split(",").map { it.trim() }
                val primary     = parts.firstOrNull() ?: "Lokasi"

                val addrObj       = obj.optJSONObject("address")
                val formattedAddr = if (addrObj != null)
                    buildDetailedAddress(addrObj, displayName)
                else parts.drop(1).take(4).joinToString(", ")

                val emoji = when {
                    type == "hospital" || (category == "amenity" && type.contains("hospital")) -> "🏥"
                    category == "amenity" && (type == "clinic" || type == "doctors")            -> "🏥"
                    type == "university" || type == "college"                                    -> "🎓"
                    type == "school" || type == "kindergarten"                                   -> "🏫"
                    type == "mosque" || type == "church" || type == "place_of_worship"          -> "🕌"
                    type == "restaurant" || type == "cafe"                                       -> "🍽️"
                    type == "fast_food" || type == "food_court"                                  -> "🍜"
                    type == "hotel" || type == "hostel" || type == "guest_house"                -> "🏨"
                    type == "mall" || type == "department_store"                                 -> "🏬"
                    type == "supermarket" || type == "convenience"                               -> "🛒"
                    type == "bank" || type == "atm"                                              -> "🏦"
                    type == "fuel"                                                               -> "⛽"
                    type == "pharmacy"                                                           -> "💊"
                    type == "park" || type == "garden" || type == "nature_reserve"              -> "🌳"
                    type == "bus_station" || type == "bus_stop"                                  -> "🚌"
                    type == "train_station" || type == "station"                                 -> "🚂"
                    type == "aerodrome" || type == "airport"                                     -> "✈️"
                    type == "ferry_terminal" || type == "harbour" || type == "port"             -> "⚓"
                    type == "residential" || type == "house" || type == "apartments"            -> "🏠"
                    category == "highway" || type == "road" || type == "street"                 -> "🛣️"
                    category == "natural" || type == "peak" || type == "lake"                   -> "🏞️"
                    else -> "📍"
                }

                val ck   = coordKey(lat, lng)
                list.add(LocationItem(name = primary, address = formattedAddr, lat = lat, lng = lng,
                    emoji = emoji, isSaved = savedCoordKeys.contains(ck)))
                if (list.size >= 12) break
            }
        } catch (_: Exception) {}
        return list
    }

    private fun buildDetailedAddress(addr: JSONObject, fallback: String): String {
        val parts = mutableListOf<String>()
        val houseNumber = addr.optString("house_number", "")
        val road = addr.optString("road", "")
            .ifEmpty { addr.optString("pedestrian", "") }
            .ifEmpty { addr.optString("street", "") }
            .ifEmpty { addr.optString("path", "") }

        if (road.isNotEmpty()) {
            val roadFmt = when {
                road.startsWith("Jl",    ignoreCase = true) -> road
                road.startsWith("Jalan", ignoreCase = true) -> road
                road.startsWith("Gang",  ignoreCase = true) -> road
                road.startsWith("Gg",    ignoreCase = true) -> road
                else -> "Jl. $road"
            }
            parts.add(if (houseNumber.isNotEmpty()) "$roadFmt No.$houseNumber" else roadFmt)
        } else if (houseNumber.isNotEmpty()) {
            parts.add("No.$houseNumber")
        }

        val neighbourhood = addr.optString("neighbourhood", "")
            .ifEmpty { addr.optString("quarter", "") }
            .ifEmpty { addr.optString("suburb", "") }
        if (neighbourhood.isNotEmpty()) parts.add(neighbourhood)

        val village = addr.optString("village", "").ifEmpty { addr.optString("hamlet", "") }
        if (village.isNotEmpty() && village != neighbourhood) parts.add(village)

        val subdistrict = addr.optString("subdistrict", "")
            .ifEmpty { addr.optString("city_district", "") }
            .ifEmpty { addr.optString("municipality", "") }
        if (subdistrict.isNotEmpty()) {
            parts.add(if (subdistrict.startsWith("Kec", ignoreCase = true)) subdistrict else "Kec. $subdistrict")
        }

        val city = addr.optString("city", "")
            .ifEmpty { addr.optString("town", "") }
            .ifEmpty { addr.optString("county", "") }
        if (city.isNotEmpty()) {
            parts.add(when {
                city.startsWith("Kota", ignoreCase = true) -> city
                city.startsWith("Kabupaten", ignoreCase = true) -> city
                city.startsWith("Kab", ignoreCase = true) -> city
                else -> "Kota $city"
            })
        }

        val state = addr.optString("state", "").ifEmpty { addr.optString("province", "") }
        if (state.isNotEmpty() && state != city) parts.add(state)

        val postcode = addr.optString("postcode", "")
        if (postcode.isNotEmpty() && parts.isNotEmpty()) {
            val last = parts.removeLast(); parts.add("$last $postcode")
        }

        return if (parts.isNotEmpty()) parts.joinToString(", ")
        else fallback.split(",").take(4).joinToString(", ")
    }

    // =========================================================
    // FAVORIT
    // =========================================================
    private fun toggleFavorite(item: LocationItem) {
        val uid = auth.currentUser?.uid ?: return
        val ck  = coordKey(item.lat, item.lng)

        if (savedCoordKeys.contains(ck)) {
            val existingDocId = savedDocIdByCoord[ck]
            if (!existingDocId.isNullOrEmpty()) {
                db.collection("users").document(uid).collection("saved_locations").document(existingDocId)
                    .delete().addOnSuccessListener {
                        Toast.makeText(this, "Dihapus dari favorit", Toast.LENGTH_SHORT).show()
                    }
            } else {
                db.collection("users").document(uid).collection("saved_locations")
                    .whereEqualTo("lat", item.lat).whereEqualTo("lng", item.lng)
                    .get().addOnSuccessListener { docs ->
                        docs.forEach { doc ->
                            db.collection("users").document(uid).collection("saved_locations").document(doc.id).delete()
                        }
                        Toast.makeText(this, "Dihapus dari favorit", Toast.LENGTH_SHORT).show()
                    }
            }
        } else {
            val data = hashMapOf("name" to item.name, "address" to item.address, "lat" to item.lat, "lng" to item.lng)
            db.collection("users").document(uid).collection("saved_locations").document(ck)
                .set(data).addOnSuccessListener {
                    Toast.makeText(this, "Disimpan ke favorit ⭐", Toast.LENGTH_SHORT).show()
                }
        }
    }

    // =========================================================
    // RIWAYAT
    // =========================================================
    private fun saveToHistory(item: LocationItem) {
        val uid = auth.currentUser?.uid ?: return
        val ck  = coordKey(item.lat, item.lng)
        val data = hashMapOf(
            "name" to item.name, "address" to item.address,
            "lat" to item.lat, "lng" to item.lng,
            "timestamp" to com.google.firebase.Timestamp.now()
        )
        db.collection("users").document(uid).collection("search_history").document(ck).set(data)
    }

    private fun clearAllHistory() {
        val uid = auth.currentUser?.uid ?: return
        db.collection("users").document(uid).collection("search_history").get()
            .addOnSuccessListener { docs ->
                val batch = db.batch(); docs.forEach { batch.delete(it.reference) }; batch.commit()
                Toast.makeText(this, "Riwayat dihapus", Toast.LENGTH_SHORT).show()
            }
    }

    private fun returnResult(item: LocationItem) {
        setResult(Activity.RESULT_OK, Intent().apply {
            putExtra("DEST_LAT",  item.lat); putExtra("DEST_LNG",  item.lng)
            putExtra("DEST_NAME", item.name); putExtra("DEST_ADDR", item.address)
        })
        finish()
    }

    // =========================================================
    // ADAPTERS
    // =========================================================
    inner class LocationAdapter(
        val items: List<LocationItem>,
        val onClick: (LocationItem) -> Unit
    ) : RecyclerView.Adapter<LocationAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val emoji:     TextView  = v.findViewById(R.id.tv_location_emoji)
            val name:      TextView  = v.findViewById(R.id.tv_location_name)
            val addr:      TextView  = v.findViewById(R.id.tv_location_address)
            val deleteBtn: ImageView = v.findViewById(R.id.btn_delete_item)
            val starBtn:   ImageView = v.findViewById(R.id.btn_favorite_star)
            val root:      CardView  = v.findViewById(R.id.cv_location_item)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int) =
            VH(layoutInflater.inflate(R.layout.item_location_search, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val item = items[pos]
            h.emoji.text = item.emoji
            h.name.text  = item.name
            h.addr.text  = item.address.ifEmpty { "Medan, Sumatera Utara" }

            if (item.isHistory) {
                h.deleteBtn.visibility = View.VISIBLE
                h.starBtn.visibility   = View.GONE
                h.deleteBtn.setOnClickListener {
                    val uid   = auth.currentUser?.uid ?: return@setOnClickListener
                    val docId = if (item.docId.isNotEmpty()) item.docId else coordKey(item.lat, item.lng)
                    db.collection("users").document(uid).collection("search_history").document(docId).delete()
                }
            } else {
                h.deleteBtn.visibility = View.GONE
                h.starBtn.visibility   = View.VISIBLE
                val ck = coordKey(item.lat, item.lng)
                h.starBtn.setColorFilter(if (savedCoordKeys.contains(ck)) "#FFD700".toColorInt() else "#CBD5E1".toColorInt())
                h.starBtn.setOnClickListener { toggleFavorite(item) }
            }

            h.root.setOnClickListener { onClick(item) }
        }
    }

    inner class EmptyAdapter(private val message: String) : RecyclerView.Adapter<EmptyAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v)
        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int) = VH(
            TextView(this@SearchActivity).apply {
                text = message; textSize = 13f; setPadding(60, 48, 60, 48)
                setTextColor(Color.GRAY); setLineSpacing(4f, 1f)
            }
        )
        override fun getItemCount() = 1
        override fun onBindViewHolder(h: VH, p: Int) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        searchRunnable?.let { searchHandler.removeCallbacks(it) }
    }
}