package com.medansafe.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.auth.api.identity.GetSignInIntentRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.medansafe.app.databinding.ActivityLoginBinding
import com.medansafe.app.databinding.ItemEmergencyContactBinding

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private var isPasswordVisible = false
    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    // Complete Profile Dialog & Contact Picker logic
    private var completeProfileDialog: android.app.Dialog? = null
    private var tempContacts = mutableListOf<EmergencyContactsActivity.ContactModel>()
    private var llContactsContainer: LinearLayout? = null

    private val pickContactLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val contactUri: Uri? = result.data?.data
            contactUri?.let { getContactDetails(it) }
        }
    }

    private val requestContactsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) openContactPicker()
        else Toast.makeText(this, "Izin kontak diperlukan untuk memilih dari buku telepon", Toast.LENGTH_SHORT).show()
    }

    private val googleLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            try {
                val signInCredential = Identity.getSignInClient(this).getSignInCredentialFromIntent(result.data)
                val idToken = signInCredential.googleIdToken
                if (idToken != null) {
                    firebaseAuthWithGoogle(idToken)
                }
            } catch (_: ApiException) {
                Toast.makeText(this, "Gagal masuk dengan Google", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        binding.btnGoogle.setOnClickListener {
            val request = GetSignInIntentRequest.builder()
                .setServerClientId(getString(R.string.default_web_client_id))
                .build()

            Identity.getSignInClient(this)
                .getSignInIntent(request)
                .addOnSuccessListener { pendingIntent ->
                    val requestSender = IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                    googleLauncher.launch(requestSender)
                }
                .addOnFailureListener { e ->
                    Log.e("Login", "GetSignInIntent failed: ${e.message}")
                    Toast.makeText(this, "Gagal memulai login Google", Toast.LENGTH_SHORT).show()
                }
        }

        binding.tvForgotPassword.setOnClickListener {
            val intent = Intent(this, ForgotPasswordActivity::class.java)
            startActivity(intent)
        }

        binding.ivShowPassword.setOnClickListener {
            isPasswordVisible = !isPasswordVisible
            binding.etPassword.transformationMethod = if (isPasswordVisible) HideReturnsTransformationMethod.getInstance() else PasswordTransformationMethod.getInstance()
            binding.ivShowPassword.setImageResource(if (isPasswordVisible) R.drawable.ic_eye else R.drawable.ic_eye_off)
            binding.ivShowPassword.alpha = if (isPasswordVisible) 1.0f else 0.3f
            binding.etPassword.setSelection(binding.etPassword.text.length)
        }

        binding.tvRegisterLink.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }

        binding.btnLogin.setOnClickListener {
            val email = binding.etEmail.text.toString().trim()
            val password = binding.etPassword.text.toString().trim()

            if (email.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Email dan Password tidak boleh kosong", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            showLoading(true)
            auth.signInWithEmailAndPassword(email, password)
                .addOnSuccessListener { checkUserRoleAndStatus(it.user?.uid ?: "") }
                .addOnFailureListener {
                    showLoading(false)
                    Toast.makeText(this, "Login Gagal: ${it.message}", Toast.LENGTH_SHORT).show()
                }
        }

        if (intent.getBooleanExtra("FORCE_COMPLETE_PROFILE", false)) {
            showCompleteProfileBottomSheet()
        }
    }

    private fun checkUserRoleAndStatus(uid: String) {
        db.collection("users").document(uid).get()
            .addOnSuccessListener { document ->
                showLoading(false)
                if (document.exists()) {
                    if (document.getBoolean("isDisabled") == true) {
                        auth.signOut()
                        AlertDialog.Builder(this).setTitle("Akun Dinonaktifkan").setMessage("Hubungi admin MedanSafe.").setPositiveButton("OK", null).show()
                        return@addOnSuccessListener
                    }
                    val intent = if (document.getBoolean("isAdmin") == true) Intent(this, AdminDashboardActivity::class.java) else Intent(this, HomeActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)
                    finish()
                } else {
                    showCompleteProfileBottomSheet()
                }
            }
            .addOnFailureListener { 
                showLoading(false)
                Toast.makeText(this, "Gagal memverifikasi status akun", Toast.LENGTH_SHORT).show()
            }
    }

    private fun firebaseAuthWithGoogle(idToken: String) {
        showLoading(true)
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential)
            .addOnSuccessListener { result ->
                checkUserRoleAndStatus(result.user?.uid ?: "")
            }
            .addOnFailureListener { 
                showLoading(false)
                Toast.makeText(this, "Gagal login Google: ${it.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun showCompleteProfileBottomSheet() {
        completeProfileDialog = android.app.Dialog(this, R.style.FullScreenDialogTheme)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_complete_profile, null)
        completeProfileDialog?.setContentView(view)
        completeProfileDialog?.setCancelable(false)
        completeProfileDialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        val user = auth.currentUser
        val name = user?.displayName ?: "User MedanSafe"
        val email = user?.email ?: ""
        val photoUrl = user?.photoUrl?.toString() ?: ""

        val tvName = view.findViewById<TextView>(R.id.tvGoogleName)
        val tvInitial = view.findViewById<TextView>(R.id.tvProfileInitial)
        val etPhone = view.findViewById<EditText>(R.id.etProfilePhone)
        llContactsContainer = view.findViewById(R.id.llEmergencyContactList)
        val btnAdd = view.findViewById<View>(R.id.btnAddEmergency)
        val btnSave = view.findViewById<Button>(R.id.btnSaveProfile)
        val btnBackToLogin = view.findViewById<View>(R.id.btnBackToLoginComplete)

        tvName.text = name
        tvInitial.text = name.split(" ").filter { it.isNotEmpty() }.take(2).map { it[0] }.joinToString("").uppercase()

        tempContacts.clear()
        renderTempContacts()

        btnAdd.setOnClickListener {
            if (tempContacts.size >= 3) {
                Toast.makeText(this, "Maksimal 3 kontak", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showAddContactDialog()
        }

        btnBackToLogin.setOnClickListener {
            auth.signOut()
            completeProfileDialog?.dismiss()
            Toast.makeText(this, "Pendaftaran dibatalkan", Toast.LENGTH_SHORT).show()
        }

        btnSave.setOnClickListener {
            val phone = etPhone.text.toString().trim()
            if (phone.isEmpty() || phone == "+62") {
                Toast.makeText(this, "Nomor HP wajib diisi", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (tempContacts.isEmpty()) {
                Toast.makeText(this, "Minimal 1 kontak darurat wajib diisi", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            showLoading(true)
            val uid = user?.uid ?: ""
            val userData = hashMapOf(
                "uid" to uid,
                "nama" to name,
                "email" to email,
                "noTelpon" to phone,
                "totalLaporan" to 0L,
                "laporanTerverifikasi" to 0L,
                "poin" to 0L,
                "upvoteReceived" to 0L,
                "isPelaporAktif" to false,
                "isAdmin" to false,
                "role" to "user",
                "bio" to "Warga Medan Peduli",
                "profileImageUrl" to photoUrl,
                "isDisabled" to false,
                "createdAt" to Timestamp.now()
            )

            db.collection("users").document(uid).set(userData)
                .addOnSuccessListener {
                    val batch = db.batch()
                    tempContacts.forEach { contact ->
                        val ref = db.collection("users").document(uid).collection("emergency_contacts").document()
                        batch.set(ref, mapOf("id" to ref.id, "nama" to contact.name, "noTelpon" to contact.phone))
                    }
                    batch.commit().addOnSuccessListener {
                        showLoading(false)
                        completeProfileDialog?.dismiss()
                        val intent = Intent(this@LoginActivity, HomeActivity::class.java)
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        startActivity(intent)
                        finish()
                    }.addOnFailureListener {
                        showLoading(false)
                        Toast.makeText(this, "Gagal menyimpan kontak darurat", Toast.LENGTH_SHORT).show()
                    }
                }
                .addOnFailureListener {
                    showLoading(false)
                    Toast.makeText(this, "Gagal menyimpan profil", Toast.LENGTH_SHORT).show()
                }
        }
        completeProfileDialog?.show()
    }

    private fun renderTempContacts() {
        llContactsContainer?.removeAllViews()
        tempContacts.forEachIndexed { index, contact ->
            val itemBinding = ItemEmergencyContactBinding.inflate(layoutInflater, llContactsContainer, false)
            itemBinding.tvContactName.text = contact.name
            itemBinding.tvContactPhone.text = contact.phone
            val initials = if (contact.name.isNotEmpty()) {
                contact.name.split(" ").filter { it.isNotEmpty() }.take(2).map { it[0] }.joinToString("").uppercase()
            } else "?"
            itemBinding.tvContactInitial.text = initials
            itemBinding.tvBtnDelete.setOnClickListener {
                tempContacts.removeAt(index)
                renderTempContacts()
            }
            llContactsContainer?.addView(itemBinding.root)
        }
    }

    private fun showAddContactDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_contact, null)
        val etName = dialogView.findViewById<EditText>(R.id.etContactName)
        val etPhone = dialogView.findViewById<EditText>(R.id.etContactPhone)
        val btnPicker = dialogView.findViewById<LinearLayout>(R.id.btnPickFromPhonebook)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Tambah Kontak Baru")
            .setView(dialogView)
            .setPositiveButton("Tambah") { _, _ ->
                val n = etName.text.toString().trim()
                val p = etPhone.text.toString().trim()
                if (n.isNotEmpty() && p.isNotEmpty()) {
                    tempContacts.add(EmergencyContactsActivity.ContactModel("", n, p))
                    renderTempContacts()
                }
            }
            .setNegativeButton("Batal", null)
            .create()

        btnPicker.setOnClickListener {
            checkContactsPermission()
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun checkContactsPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            openContactPicker()
        } else {
            requestContactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
        }
    }

    private fun openContactPicker() {
        val intent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
        pickContactLauncher.launch(intent)
    }

    private fun getContactDetails(contactUri: Uri) {
        val cursor: Cursor? = contentResolver.query(contactUri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val name = it.getString(it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME))
                val number = it.getString(it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER))
                tempContacts.add(EmergencyContactsActivity.ContactModel("", name, number))
                renderTempContacts()
            }
        }
    }

    private fun showLoading(show: Boolean) {
        binding.progressBar.visibility = if (show) View.VISIBLE else View.GONE
        binding.btnLogin.isEnabled = !show
    }
}
