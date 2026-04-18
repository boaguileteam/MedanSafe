package com.medansafe.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.medansafe.app.databinding.ActivityEmergencyContactsBinding
import com.medansafe.app.databinding.ItemEmergencyContactBinding

class EmergencyContactsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEmergencyContactsBinding
    private val contactsList = mutableListOf<ContactModel>()
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private var contactsListener: ListenerRegistration? = null

    data class ContactModel(val id: String = "", val name: String = "", val phone: String = "")

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
        else Toast.makeText(this, "Izin kontak diperlukan", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEmergencyContactsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        listenToContacts()
        setupActions()
    }

    private fun setupActions() {
        binding.btnBack.setOnClickListener { finish() }
        
        binding.btnAddContactContainer.setOnClickListener {
            if (contactsList.size >= 3) {
                Toast.makeText(this, "Maksimal 3 kontak darurat", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showAddContactDialog()
        }

        binding.btnTestSms.setOnClickListener {
            if (contactsList.isEmpty()) {
                Toast.makeText(this, "Tambahkan kontak dulu", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            shareTestMessage()
        }
    }

    private fun shareTestMessage() {
        val phones = contactsList.joinToString(",") { it.phone }
        val message = "[MedanSafe TEST] Ini pesan uji SOS. Kontak kamu terdaftar sebagai kontak darurat saya di MedanSafe."
        
        // Use standard share intent for professional Gen Z feel (WhatsApp, Telegram, etc)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "$message\n\nSent to: $phones")
        }
        startActivity(Intent.createChooser(intent, "Test Kirim Pesan Uji"))
    }

    private fun showAddContactDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_contact, null)
        val etName = dialogView.findViewById<EditText>(R.id.etContactName)
        val etPhone = dialogView.findViewById<EditText>(R.id.etContactPhone)
        val btnPicker = dialogView.findViewById<LinearLayout>(R.id.btnPickFromPhonebook)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Tambah Kontak Baru")
            .setView(dialogView)
            .setPositiveButton("Simpan") { _, _ ->
                val name = etName.text.toString().trim()
                val phone = etPhone.text.toString().trim()
                if (name.isNotEmpty() && phone.isNotEmpty()) {
                    saveContactToFirestore(name, phone)
                } else {
                    Toast.makeText(this, "Nama dan nomor harus diisi", Toast.LENGTH_SHORT).show()
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
                saveContactToFirestore(name, number)
            }
        }
    }

    private fun saveContactToFirestore(name: String, phone: String) {
        val uid = auth.currentUser?.uid ?: return
        val contactId = db.collection("users").document(uid).collection("emergency_contacts").document().id
        val data = mapOf(
            "id" to contactId,
            "nama" to name,
            "noTelpon" to phone
        )
        db.collection("users").document(uid).collection("emergency_contacts").document(contactId)
            .set(data)
            .addOnSuccessListener {
                Toast.makeText(this, "Kontak berhasil disimpan", Toast.LENGTH_SHORT).show()
            }
    }

    private fun listenToContacts() {
        val uid = auth.currentUser?.uid ?: return
        contactsListener = db.collection("users").document(uid).collection("emergency_contacts")
            .addSnapshotListener { documents, e ->
                if (e != null) return@addSnapshotListener
                
                contactsList.clear()
                documents?.forEach { doc ->
                    contactsList.add(ContactModel(
                        doc.id,
                        doc.getString("nama") ?: "",
                        doc.getString("noTelpon") ?: ""
                    ))
                }
                renderContacts()
            }
    }

    private fun renderContacts() {
        binding.llContactList.removeAllViews()
        binding.tvCountLabel.text = "KONTAK TERSIMPAN (${contactsList.size}/3)"

        contactsList.forEach { contact ->
            val itemBinding = ItemEmergencyContactBinding.inflate(LayoutInflater.from(this), binding.llContactList, false)
            itemBinding.tvContactName.text = contact.name
            itemBinding.tvContactPhone.text = contact.phone
            
            val initials = if (contact.name.isNotEmpty()) {
                contact.name.split(" ").filter { it.isNotEmpty() }.take(2).map { it[0] }.joinToString("").uppercase()
            } else "?"
            itemBinding.tvContactInitial.text = initials

            itemBinding.tvBtnDelete.setOnClickListener {
                showDeleteConfirmation(contact)
            }
            binding.llContactList.addView(itemBinding.root)
        }
    }

    private fun showDeleteConfirmation(contact: ContactModel) {
        AlertDialog.Builder(this)
            .setTitle("Hapus Kontak")
            .setMessage("Apakah kamu yakin ingin menghapus ${contact.name}?")
            .setPositiveButton("Hapus") { _, _ -> deleteContact(contact.id) }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun deleteContact(id: String) {
        val uid = auth.currentUser?.uid ?: return
        db.collection("users").document(uid).collection("emergency_contacts").document(id)
            .delete()
            .addOnSuccessListener {
                Toast.makeText(this, "Kontak dihapus", Toast.LENGTH_SHORT).show()
            }
    }

    override fun onDestroy() {
        super.onDestroy()
        contactsListener?.remove()
    }
}
