package com.example.pathfinder

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

class CompanionActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var auth: FirebaseAuth

    private val db by lazy {
        FirebaseDatabase.getInstance("https://pathfinder-bdc3e-default-rtdb.asia-southeast1.firebasedatabase.app").reference
    }

    private lateinit var mapView: MapView
    private lateinit var googleMap: GoogleMap

    private lateinit var txtCircleCode: TextView
    private lateinit var txtNoCircle: LinearLayout
    private lateinit var mapContainer: LinearLayout
    private lateinit var rvCompanions: RecyclerView

    private var circleCode: String? = null
    private val memberMarkers   = mutableMapOf<String, Marker>()
    private val companionList   = mutableListOf<Pair<String, CompanionUser>>()
    private var membersListener: ValueEventListener? = null
    private val memberListeners = mutableMapOf<String, ValueEventListener>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_companion)

        auth = FirebaseAuth.getInstance()

        if (auth.currentUser == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        mapView = findViewById(R.id.companionMapView)
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync(this)

        txtCircleCode = findViewById(R.id.txtCircleCode)
        txtNoCircle   = findViewById(R.id.txtNoCircle)
        mapContainer  = findViewById(R.id.mapContainer)
        rvCompanions  = findViewById(R.id.rvCompanions)

        rvCompanions.layoutManager = LinearLayoutManager(this)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.companionRoot)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnLogout).setOnClickListener { logout() }

        // Companions only join — no create button needed
        findViewById<Button>(R.id.btnJoinCircle).setOnClickListener { showJoinDialog() }
        findViewById<Button>(R.id.btnLeaveCircle).setOnClickListener { leaveCircle() }
        findViewById<Button>(R.id.btnShareCode).setOnClickListener   { shareCode() }

        // Hide Create Circle button — guardians never create circles
        findViewById<Button>(R.id.btnCreateCircle).visibility = View.GONE

        // Only start location service if permission is already granted
        // If not granted yet, the map permission request will handle it
        if (ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            startService(Intent(this, LocationSharingService::class.java))
        }

        loadUserCircle()
    }

    // ═════════ Circle management ══════════════════════════════════════════

    private fun loadUserCircle() {
        val uid = auth.currentUser?.uid ?: return
        db.child("users").child(uid).child("circleCode")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snap: DataSnapshot) {
                    val code = snap.getValue(String::class.java)
                    if (code.isNullOrEmpty()) {
                        showNoCircleUI()
                    } else {
                        circleCode = code
                        showCircleUI(code)
                        listenToCircleMembers(code)
                    }
                }
                override fun onCancelled(error: DatabaseError) {
                    Toast.makeText(this@CompanionActivity,
                        "DB error: ${error.message}", Toast.LENGTH_LONG).show()
                    showNoCircleUI()
                }
            })
    }

    private fun showJoinDialog() {
        val input = EditText(this).apply {
            hint     = "Enter 6-character code"
            textSize = 16f
            setPadding(48, 32, 48, 32)
            filters  = arrayOf(android.text.InputFilter.AllCaps())
        }

        AlertDialog.Builder(this)
            .setTitle("Join a Circle")
            .setView(input)
            .setPositiveButton("Join") { _, _ ->
                val code = input.text.toString().trim().uppercase()
                if (code.length == 6) joinCircle(code)
                else Toast.makeText(this, "Code must be 6 characters",
                    Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun joinCircle(code: String) {
        val uid = auth.currentUser?.uid ?: return

        db.child("circles").child(code)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snap: DataSnapshot) {
                    if (!snap.exists()) {
                        Toast.makeText(this@CompanionActivity,
                            "Circle not found. Check the code.",
                            Toast.LENGTH_SHORT).show()
                        return
                    }
                    db.child("circles").child(code).child("members").child(uid).setValue(true)
                        .addOnSuccessListener {
                            db.child("users").child(uid).child("circleCode").setValue(code)
                                .addOnSuccessListener {
                                    circleCode = code
                                    showCircleUI(code)
                                    listenToCircleMembers(code)
                                    Toast.makeText(this@CompanionActivity,
                                        "Joined circle!", Toast.LENGTH_SHORT).show()
                                }
                                .addOnFailureListener { e ->
                                    Toast.makeText(this@CompanionActivity,
                                        "Failed to save: ${e.message}",
                                        Toast.LENGTH_LONG).show()
                                }
                        }
                        .addOnFailureListener { e ->
                            Toast.makeText(this@CompanionActivity,
                                "Failed to join: ${e.message}",
                                Toast.LENGTH_LONG).show()
                        }
                }
                override fun onCancelled(error: DatabaseError) {
                    Toast.makeText(this@CompanionActivity,
                        "DB error: ${error.message}", Toast.LENGTH_LONG).show()
                }
            })
    }

    private fun leaveCircle() {
        val uid  = auth.currentUser?.uid ?: return
        val code = circleCode ?: return

        AlertDialog.Builder(this)
            .setTitle("Leave Circle")
            .setMessage("Are you sure you want to leave this circle?")
            .setPositiveButton("Leave") { _, _ ->
                detachAllListeners(code)
                db.child("circles").child(code).child("members").child(uid).removeValue()
                db.child("users").child(uid).child("circleCode").setValue("")
                circleCode = null
                companionList.clear()
                memberMarkers.values.forEach { it.remove() }
                memberMarkers.clear()
                showNoCircleUI()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun shareCode() {
        val code = circleCode ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "Join my Pathfinder circle! Code: $code")
        }
        startActivity(Intent.createChooser(intent, "Share circle code"))
    }

    // ═════════ Real-time listeners ════════════════════════════════════════

    /**
     * Watches all members of the circle — including the navigator.
     * Navigator gets a GREEN marker, companions get VIOLET.
     */
    private fun listenToCircleMembers(code: String) {
        val currentUid = auth.currentUser?.uid ?: return

        val listener = object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                val activeUids = snap.children.mapNotNull { it.key }.toSet()

                (memberListeners.keys - activeUids).forEach { uid ->
                    memberListeners.remove(uid)?.let { l ->
                        db.child("users").child(uid).removeEventListener(l)
                    }
                    memberMarkers.remove(uid)?.remove()
                    companionList.removeAll { it.first == uid }
                }

                activeUids.forEach { uid ->
                    if (uid != currentUid && uid !in memberListeners) {
                        listenToMemberLocation(uid)
                    }
                }

                rvCompanions.adapter = CompanionListAdapter(companionList)
            }
            override fun onCancelled(error: DatabaseError) {}
        }

        membersListener = listener
        db.child("circles").child(code).child("members").addValueEventListener(listener)
    }

    private fun listenToMemberLocation(uid: String) {
        val listener = object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                val user = snap.getValue(CompanionUser::class.java) ?: return
                updateMemberOnMap(uid, user)
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        memberListeners[uid] = listener
        db.child("users").child(uid).addValueEventListener(listener)
    }

    private fun updateMemberOnMap(uid: String, user: CompanionUser) {
        val idx = companionList.indexOfFirst { it.first == uid }
        if (idx >= 0) companionList[idx] = Pair(uid, user)
        else companionList.add(Pair(uid, user))

        if (::googleMap.isInitialized && user.location.lat != 0.0) {
            val pos     = LatLng(user.location.lat, user.location.lng)
            val hue     = if (user.role == "navigator")
                BitmapDescriptorFactory.HUE_GREEN
            else
                BitmapDescriptorFactory.HUE_VIOLET
            val label   = if (user.role == "navigator")
                "📍 ${user.name} (Navigator)"
            else
                user.name
            val snippet = "🔋 ${user.location.battery}%"

            val existing = memberMarkers[uid]
            if (existing != null) {
                existing.position = pos
                existing.title    = label
                existing.snippet  = snippet
            } else {
                val marker = googleMap.addMarker(
                    MarkerOptions()
                        .position(pos)
                        .title(label)
                        .snippet(snippet)
                        .icon(BitmapDescriptorFactory.defaultMarker(hue))
                )
                if (marker != null) memberMarkers[uid] = marker
            }
            fitCamera()
        }

        rvCompanions.adapter = CompanionListAdapter(companionList)
    }

    private fun fitCamera() {
        if (memberMarkers.isEmpty()) return
        val builder = LatLngBounds.Builder()
        memberMarkers.values.forEach { builder.include(it.position) }
        try {
            googleMap.animateCamera(
                CameraUpdateFactory.newLatLngBounds(builder.build(), 200)
            )
        } catch (e: Exception) { e.printStackTrace() }
    }

    // ═════════ UI state ═══════════════════════════════════════════════════

    private fun showNoCircleUI() {
        txtNoCircle.visibility   = View.VISIBLE
        mapContainer.visibility  = View.GONE
        txtCircleCode.visibility = View.GONE
        rvCompanions.visibility  = View.GONE
        findViewById<Button>(R.id.btnLeaveCircle).visibility = View.GONE
        findViewById<Button>(R.id.btnShareCode).visibility   = View.GONE
    }

    private fun showCircleUI(code: String) {
        txtNoCircle.visibility   = View.GONE
        mapContainer.visibility  = View.VISIBLE
        rvCompanions.visibility  = View.VISIBLE
        txtCircleCode.visibility = View.VISIBLE
        txtCircleCode.text       = "Circle code: $code"
        findViewById<Button>(R.id.btnLeaveCircle).visibility = View.VISIBLE
        findViewById<Button>(R.id.btnShareCode).visibility   = View.VISIBLE
    }

    // ═════════ Helpers ════════════════════════════════════════════════════

    private fun detachAllListeners(code: String) {
        membersListener?.let {
            db.child("circles").child(code).child("members").removeEventListener(it)
            membersListener = null
        }
        memberListeners.forEach { (uid, listener) ->
            db.child("users").child(uid).removeEventListener(listener)
        }
        memberListeners.clear()
    }

    private fun logout() {
        stopService(Intent(this, LocationSharingService::class.java))
        auth.signOut()
        startActivity(Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    // ═════════ Map ════════════════════════════════════════════════════════

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        googleMap.uiSettings.isZoomControlsEnabled = true
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            googleMap.isMyLocationEnabled = true
            startService(Intent(this, LocationSharingService::class.java))
        }
    }

    override fun onResume()  { super.onResume();  mapView.onResume()  }
    override fun onPause()   { super.onPause();   mapView.onPause()   }
    override fun onDestroy() {
        circleCode?.let { detachAllListeners(it) }
        mapView.onDestroy()
        super.onDestroy()
    }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }
}

// ═════════ Adapter ════════════════════════════════════════════════════════

class CompanionListAdapter(
    private val companions: List<Pair<String, CompanionUser>>
) : RecyclerView.Adapter<CompanionListAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val txtName:     TextView = view.findViewById(R.id.txtCompanionName)
        val txtBattery:  TextView = view.findViewById(R.id.txtCompanionBattery)
        val txtLastSeen: TextView = view.findViewById(R.id.txtCompanionLastSeen)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_companion, parent, false)
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val (_, user) = companions[position]
        val isNavigator = user.role == "navigator"

        // Name + role tag
        holder.txtName.text = if (isNavigator) "📍 ${user.name}  🧭 Navigator" else user.name
        holder.txtName.setTextColor(
            if (isNavigator)
                android.graphics.Color.parseColor("#0073E6")   // blue for navigator
            else
                android.graphics.Color.BLACK
        )

        // Battery
        holder.txtBattery.text = "🔋 ${user.location.battery}%"

        // Last seen
        val diff = System.currentTimeMillis() - user.location.lastUpdated
        holder.txtLastSeen.text = when {
            user.location.lastUpdated == 0L -> "Location unknown"
            diff < 60_000                   -> "Just now"
            diff < 3_600_000                -> "${diff / 60_000} min ago"
            else                            -> "${diff / 3_600_000} hr ago"
        }
    }

    override fun getItemCount() = companions.size
}