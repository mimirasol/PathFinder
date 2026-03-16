package com.example.pathfinder

import android.app.Activity
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.json.JSONArray
import org.json.JSONObject

data class EmergencyContact(val name: String, val phone: String)

class EmergencyContactsActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var txtEmpty: TextView
    private val contacts = mutableListOf<EmergencyContact>()

    // Contact picker launcher
    private val contactPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri -> readContact(uri) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_emergency_contacts)

        recyclerView = findViewById(R.id.rvContacts)
        txtEmpty     = findViewById(R.id.txtEmpty)

        recyclerView.layoutManager = LinearLayoutManager(this)

        // Status bar inset — push toolbar below status bar
        val toolbar = findViewById<android.widget.LinearLayout>(R.id.toolbar)
        ViewCompat.setOnApplyWindowInsetsListener(toolbar) { view, insets ->
            val statusBar = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            view.setPadding(view.paddingLeft, statusBar.top + 16.dpToPx(), view.paddingRight, view.paddingBottom)
            insets
        }

        // Nav bar inset — push FAB above nav bar
        val fab = findViewById<FloatingActionButton>(R.id.fabAddContact)
        ViewCompat.setOnApplyWindowInsetsListener(fab) { view, insets ->
            val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val params = view.layoutParams as androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams
            params.bottomMargin = navBar.bottom + 24.dpToPx()
            view.layoutParams = params
            insets
        }

        contacts.addAll(getContacts(this))
        refreshList()

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        // FAB — open contacts picker
        findViewById<FloatingActionButton>(R.id.fabAddContact).setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
            contactPickerLauncher.launch(intent)
        }
    }

    private fun Int.dpToPx(view: android.view.View): Int =
        (this * view.resources.displayMetrics.density).toInt()


    // ── Read selected contact from URI ────────────────────────────────
    private fun readContact(uri: Uri) {
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val cursor: Cursor? = contentResolver.query(uri, projection, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val name  = it.getString(it.getColumnIndexOrThrow(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME))
                val rawPhone = it.getString(it.getColumnIndexOrThrow(
                    ContactsContract.CommonDataKinds.Phone.NUMBER))

                // Clean up the number — remove spaces, dashes, parentheses
                val phone = rawPhone.replace(Regex("[\\s\\-()]"), "")
                val normalized = normalizePhilippineNumber(phone)

                if (normalized == null) {
                    Toast.makeText(this,
                        "Could not normalize number: $rawPhone. Please edit it manually.",
                        Toast.LENGTH_LONG).show()
                    // Add it anyway with the raw number so the user isn't blocked
                    contacts.add(EmergencyContact(name, phone))
                } else {
                    contacts.add(EmergencyContact(name, normalized))
                }

                saveContacts(this, contacts)
                refreshList()
            }
        }
    }

    private fun Int.dpToPx(): Int =
        (this * resources.displayMetrics.density).toInt()

    // ── Normalize PH numbers ──────────────────────────────────────────
    private fun normalizePhilippineNumber(input: String): String? {
        val digits = input.trim().replace(" ", "").replace("-", "")
        return when {
            digits.startsWith("+63") && digits.length == 13 -> digits
            digits.startsWith("63")  && digits.length == 12 -> "+$digits"
            digits.startsWith("09")  && digits.length == 11 -> "+63${digits.substring(1)}"
            digits.startsWith("9")   && digits.length == 10 -> "+63$digits"
            else -> null
        }
    }

    // ── List management ────────────────────────────────────────────────
    private fun refreshList() {
        txtEmpty.visibility = if (contacts.isEmpty()) View.VISIBLE else View.GONE
        recyclerView.adapter = ContactsAdapter(contacts,
            onDelete = { position ->
                contacts.removeAt(position)
                saveContacts(this, contacts)
                refreshList()
            }
        )
    }

    // ── Persistence ───────────────────────────────────────────────────
    companion object {
        private const val PREFS_KEY   = "emergency_contacts"
        private const val CONTACTS_KEY = "contacts"

        fun getContacts(context: android.content.Context): List<EmergencyContact> {
            val prefs = context.getSharedPreferences(PREFS_KEY, android.content.Context.MODE_PRIVATE)
            val json  = prefs.getString(CONTACTS_KEY, "[]") ?: "[]"
            val arr   = JSONArray(json)
            val list  = mutableListOf<EmergencyContact>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(EmergencyContact(obj.getString("name"), obj.getString("phone")))
            }
            return list
        }

        fun saveContacts(context: android.content.Context, contacts: List<EmergencyContact>) {
            val arr = JSONArray()
            for (c in contacts) {
                val obj = JSONObject()
                obj.put("name", c.name)
                obj.put("phone", c.phone)
                arr.put(obj)
            }
            context.getSharedPreferences(PREFS_KEY, android.content.Context.MODE_PRIVATE)
                .edit()
                .putString(CONTACTS_KEY, arr.toString())
                .apply()
        }
    }
}

// ── Adapter ───────────────────────────────────────────────────────────────
class ContactsAdapter(
    private val contacts: List<EmergencyContact>,
    private val onDelete: (Int) -> Unit
) : RecyclerView.Adapter<ContactsAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val txtName:   TextView     = view.findViewById(R.id.txtContactName)
        val txtPhone:  TextView     = view.findViewById(R.id.txtContactPhone)
        val btnDelete: ImageButton  = view.findViewById(R.id.btnDeleteContact)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(LayoutInflater.from(parent.context)
            .inflate(R.layout.item_contact, parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val contact = contacts[position]
        holder.txtName.text  = contact.name
        holder.txtPhone.text = contact.phone
        holder.btnDelete.setOnClickListener { onDelete(position) }
    }

    override fun getItemCount() = contacts.size
}