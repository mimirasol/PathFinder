package com.example.pathfinder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.os.Bundle
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doOnTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.*
import com.google.android.gms.maps.*
import com.google.android.gms.maps.model.*
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.net.FetchPlaceRequest
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest
import com.google.android.libraries.places.api.net.PlacesClient
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.util.*

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var mapView: MapView
    private lateinit var googleMap: GoogleMap
    private lateinit var tts: TextToSpeech
    private lateinit var placesClient: PlacesClient
    private lateinit var txtDestination: MaterialAutoCompleteTextView
    private lateinit var graphHopperService: GraphHopperService

    private var destinationMarker: Marker? = null
    private var youMarker: Marker? = null
    private var currentPolyline: Polyline? = null
    private var destinationLatLng: LatLng? = null
    private var currentLocation: LatLng? = null
    private var hasCenteredMap = false

    private val db by lazy {
        FirebaseDatabase.getInstance(
            "https://pathfinder-bdc3e-default-rtdb.asia-southeast1.firebasedatabase.app"
        ).reference
    }
    private var circleCode: String? = null
    private val companionData    = mutableMapOf<String, CompanionUser>()
    private val companionMarkers = mutableMapOf<String, Marker>()
    private val memberListeners  = mutableMapOf<String, ValueEventListener>()
    private var membersListener: ValueEventListener? = null
    private lateinit var btnCompanion: Button

    companion object {
        private const val LOCATION_PERMISSION_REQUEST = 1000
        private const val ONLINE_THRESHOLD_MS = 5 * 60 * 1000L
    }

    // ═════════ Lifecycle ══════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        graphHopperService = GraphHopperService(getString(R.string.graph_api_key))

        mapView = findViewById(R.id.mapView)
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync(this)

        if (!Places.isInitialized()) {
            Places.initialize(applicationContext, getString(R.string.api_key))
        }
        placesClient = Places.createClient(this)

        txtDestination = findViewById(R.id.txtDestination)
        setupDestinationAutocomplete()

        val btnClearSearch = findViewById<ImageButton>(R.id.btnClearSearch)
        txtDestination.doOnTextChanged { text, _, _, _ ->
            btnClearSearch.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        btnClearSearch.setOnClickListener {
            txtDestination.text?.clear()
            destinationLatLng = null
            destinationMarker?.remove(); destinationMarker = null
            currentPolyline?.remove();   currentPolyline = null
        }

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.US
                tts.setSpeechRate(0.9f)
            }
        }

        val bottomSheet = findViewById<LinearLayout>(R.id.bottomSheet)
        BottomSheetBehavior.from(bottomSheet).apply {
            state           = BottomSheetBehavior.STATE_COLLAPSED
            isDraggable     = true
            isFitToContents = true
            isHideable      = false
        }

        ViewCompat.setOnApplyWindowInsetsListener(bottomSheet) { view, insets ->
            val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.setPadding(
                view.paddingLeft, view.paddingTop, view.paddingRight,
                navBar.bottom + resources.getDimensionPixelSize(R.dimen.bottom_sheet_padding)
            )
            insets
        }

        btnCompanion = findViewById(R.id.btnCompanion)

        findViewById<Button>(R.id.btnWhereAmI).setOnClickListener {
            val current = currentLocation ?: return@setOnClickListener
            googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(current, 17f))
            youMarker?.remove()
            youMarker = googleMap.addMarker(MarkerOptions().position(current).title("You"))

            lifecycleScope.launch(Dispatchers.IO) {
                var streetName = "your current location"
                try {
                    val geoUrl =
                        "https://maps.googleapis.com/maps/api/geocode/json?" +
                                "latlng=${current.latitude},${current.longitude}" +
                                "&key=${getString(R.string.api_key)}"
                    val geoObj  = JSONObject(URL(geoUrl).readText())
                    val results = geoObj.getJSONArray("results")
                    if (results.length() > 0) {
                        val components =
                            results.getJSONObject(0).getJSONArray("address_components")
                        outer@ for (i in 0 until components.length()) {
                            val comp  = components.getJSONObject(i)
                            val types = comp.getJSONArray("types")
                            for (j in 0 until types.length()) {
                                if (types.getString(j) == "route") {
                                    streetName = comp.getString("long_name")
                                    break@outer
                                }
                            }
                        }
                    }
                } catch (e: Exception) { e.printStackTrace() }

                try {
                    val placesUrl =
                        "https://maps.googleapis.com/maps/api/place/nearbysearch/json?" +
                                "location=${current.latitude},${current.longitude}" +
                                "&radius=50&type=point_of_interest" +
                                "&key=${getString(R.string.api_key)}"
                    val jsonObj       = JSONObject(URL(placesUrl).readText())
                    val results       = jsonObj.getJSONArray("results")
                    val landmarkNames = ArrayList<String>()

                    for (i in 0 until minOf(results.length(), 5)) {
                        val place   = results.getJSONObject(i)
                        val name    = place.getString("name")
                        val loc     = place.getJSONObject("geometry").getJSONObject("location")
                        val lat     = loc.getDouble("lat")
                        val lng     = loc.getDouble("lng")
                        val distArr = FloatArray(1)
                        Location.distanceBetween(
                            current.latitude, current.longitude, lat, lng, distArr
                        )
                        withContext(Dispatchers.Main) {
                            googleMap.addMarker(
                                MarkerOptions()
                                    .position(LatLng(lat, lng))
                                    .title(name)
                                    .icon(BitmapDescriptorFactory.defaultMarker(
                                        BitmapDescriptorFactory.HUE_AZURE))
                            )
                        }
                        landmarkNames.add("${distArr[0].toInt()} m — $name")
                    }

                    withContext(Dispatchers.Main) {
                        if (landmarkNames.isNotEmpty())
                            showLandmarksBottomSheet(this@MainActivity, streetName, landmarkNames, tts)
                        else
                            Toast.makeText(this@MainActivity,
                                "No landmarks found nearby", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity,
                            "Error fetching landmarks", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        findViewById<Button>(R.id.btnSOS).setOnClickListener {
            startActivity(Intent(this, SosActivity::class.java))
        }

        btnCompanion.setOnClickListener {
            val code = circleCode
            if (code.isNullOrEmpty()) createCircle()
            else showCircleDialog(code)
        }

        findViewById<Button>(R.id.btnLogout).setOnClickListener {
            FirebaseAuth.getInstance().signOut()
            startActivity(Intent(this, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
            finish()
        }

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            val dest = destinationLatLng ?: run {
                Toast.makeText(this, "Please select a destination first.",
                    Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startActivity(Intent(this, NavigationActivity::class.java).apply {
                putExtra("dest_lat", dest.latitude)
                putExtra("dest_lng", dest.longitude)
            })
        }
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        loadCircleAndListen()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
        detachAllListeners()
    }

    override fun onDestroy() {
        detachAllListeners()
        mapView.onDestroy()
        if (::tts.isInitialized) tts.shutdown()
        super.onDestroy()
    }

    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    // ═════════ Map ════════════════════════════════════════════════════════

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            if (::googleMap.isInitialized && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) googleMap.isMyLocationEnabled = true
            startService(Intent(this, LocationSharingService::class.java))
        }
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map

        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            googleMap.isMyLocationEnabled = true
            startService(Intent(this, LocationSharingService::class.java))

            val fusedClient = LocationServices.getFusedLocationProviderClient(this)
            val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10_000L)
                .setMinUpdateIntervalMillis(5_000L).build()

            fusedClient.requestLocationUpdates(locationRequest, object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val loc    = result.lastLocation ?: return
                    val latLng = LatLng(loc.latitude, loc.longitude)
                    currentLocation = latLng
                    if (!hasCenteredMap) {
                        googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, 17f))
                        hasCenteredMap = true
                        youMarker?.remove()
                        youMarker = googleMap.addMarker(
                            MarkerOptions().position(latLng).title("You")
                        )
                    }
                }
            }, Looper.getMainLooper())
        } else {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST)
        }
    }

    // ═════════ Route preview ══════════════════════════════════════════════

    private fun drawRoute(origin: LatLng, destination: LatLng) {
        lifecycleScope.launch(Dispatchers.IO) {
            val route = graphHopperService.getRoute(
                origin.latitude, origin.longitude,
                destination.latitude, destination.longitude
            )
            withContext(Dispatchers.Main) {
                if (route != null && route.points.isNotEmpty()) {
                    currentPolyline?.remove()
                    currentPolyline = googleMap.addPolyline(
                        PolylineOptions().addAll(route.points).color(Color.BLUE).width(10f)
                    )
                } else {
                    Toast.makeText(this@MainActivity, "Route not found", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ═════════ Circle ═════════════════════════════════════════════════════

    private fun loadCircleAndListen() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        db.child("users").child(uid).child("circleCode")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snap: DataSnapshot) {
                    val code = snap.getValue(String::class.java)
                    if (!code.isNullOrEmpty()) {
                        circleCode = code
                        listenToCircleMembers(code)
                    }
                    updateCompanionButton()
                }
                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun createCircle() {
        val uid  = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val code = generateCircleCode()
        db.child("circles").child(code).updateChildren(
            mapOf("createdBy" to uid, "members/$uid" to true)
        ).addOnSuccessListener {
            db.child("users").child(uid).child("circleCode").setValue(code)
                .addOnSuccessListener {
                    circleCode = code
                    listenToCircleMembers(code)
                    updateCompanionButton()
                    showCircleDialog(code)
                }
                .addOnFailureListener { e ->
                    Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
        }.addOnFailureListener { e ->
            Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun leaveCircle() {
        val uid  = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val code = circleCode ?: return
        detachAllListeners()
        db.child("circles").child(code).child("members").child(uid).removeValue()
        db.child("users").child(uid).child("circleCode").setValue("")
        circleCode = null
        companionData.clear()
        companionMarkers.values.forEach { it.remove() }
        companionMarkers.clear()
        updateCompanionButton()
        Toast.makeText(this, "Left circle", Toast.LENGTH_SHORT).show()
    }

    private fun updateCompanionButton() {
        val code = circleCode
        if (code.isNullOrEmpty()) {
            btnCompanion.text = "Companion"
            return
        }
        val onlineCount = companionData.values.count { isOnline(it) }
        val total       = companionData.size
        btnCompanion.text = if (total == 0) "Companion (0)"
        else "Companion ($onlineCount/$total)"
    }

    private fun isOnline(user: CompanionUser): Boolean {
        // lastUpdated == 0 means they just joined and haven't sent location yet
        // treat as online (pending) so they don't show as offline immediately
        if (user.location.lastUpdated == 0L) return true
        return (System.currentTimeMillis() - user.location.lastUpdated) < ONLINE_THRESHOLD_MS
    }


    private fun showCircleDialog(code: String) {
        val uid       = FirebaseAuth.getInstance().currentUser?.uid
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 32, 64, 16)
        }

        container.addView(TextView(this).apply {
            text          = code
            textSize      = 38f
            gravity       = Gravity.CENTER
            setTextColor(Color.parseColor("#0073E6"))
            typeface      = android.graphics.Typeface.MONOSPACE
            letterSpacing = 0.3f
            setPadding(0, 0, 0, 24)
        })

        container.addView(TextView(this).apply {
            text      = "Circle members"
            textSize  = 13f
            gravity   = Gravity.CENTER
            setTextColor(Color.parseColor("#888888"))
            setPadding(0, 0, 0, 16)
        })

        fun addRow(name: String, dotColor: String, statusTxt: String) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity     = Gravity.CENTER_VERTICAL
                setPadding(0, 8, 0, 8)
            }
            row.addView(TextView(this).apply {
                text      = "●"
                textSize  = 14f
                setTextColor(Color.parseColor(dotColor))
                setPadding(0, 0, 16, 0)
            })
            row.addView(TextView(this).apply {
                text     = name
                textSize = 16f
                setTextColor(Color.BLACK)
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            })
            row.addView(TextView(this).apply {
                text      = statusTxt
                textSize  = 13f
                setTextColor(Color.parseColor(dotColor))
            })
            container.addView(row)
        }

        if (uid != null) {
            db.child("users").child(uid)
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snap: DataSnapshot) {
                        val self = snap.getValue(CompanionUser::class.java)
                        if (self != null) {
                            // Navigator is always online — they just opened this dialog
                            addRow(
                                name      = self.name,
                                dotColor  = "#0073E6",
                                statusTxt = "You · Online"
                            )
                        }
                        if (companionData.isEmpty()) {
                            container.addView(TextView(this@MainActivity).apply {
                                text      = "No companions have joined yet"
                                textSize  = 13f
                                gravity   = Gravity.CENTER
                                setTextColor(Color.parseColor("#AAAAAA"))
                                setPadding(0, 8, 0, 0)
                            })
                        } else {
                            companionData.values.forEach { user ->
                                val online  = isOnline(user)
                                val pending = user.location.lastUpdated == 0L
                                addRow(
                                    name      = user.name.ifBlank { "Companion" },
                                    dotColor  = when {
                                        pending -> "#F59E0B"   // amber — waiting for first location
                                        online  -> "#4CAF50"   // green — active
                                        else    -> "#AAAAAA"   // grey — offline
                                    },
                                    statusTxt = when {
                                        pending -> "Joining…"
                                        online  -> "Online"
                                        else    -> "Offline"
                                    }
                                )
                            }
                        }
                    }
                    override fun onCancelled(error: DatabaseError) {}
                })
        }

        AlertDialog.Builder(this)
            .setTitle("Your Circle")
            .setView(container)
            .setPositiveButton("Share Code") { _, _ ->
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "Join my Pathfinder circle! Code: $code")
                }
                startActivity(Intent.createChooser(intent, "Share circle code"))
            }
            .setNegativeButton("Leave Circle") { _, _ -> leaveCircle() }
            .setNeutralButton("Close", null)
            .show()
    }

    // ═════════ Real-time tracking ═════════════════════════════════════════

    private fun listenToCircleMembers(code: String) {
        val currentUid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val listener = object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                val activeUids = snap.children.mapNotNull { it.key }.toSet()
                (memberListeners.keys - activeUids).forEach { uid ->
                    memberListeners.remove(uid)?.let { l ->
                        db.child("users").child(uid).removeEventListener(l)
                    }
                    companionMarkers.remove(uid)?.remove()
                    companionData.remove(uid)
                }
                activeUids.forEach { uid ->
                    if (uid != currentUid && uid !in memberListeners) {
                        listenToMemberLocation(uid)
                    }
                }
                updateCompanionButton()
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
                companionData[uid] = user
                if (::googleMap.isInitialized && user.location.lat != 0.0) {
                    val pos     = LatLng(user.location.lat, user.location.lng)
                    val title   = "👥 ${user.name}"
                    val snippet = "🔋 ${user.location.battery}%"
                    val existing = companionMarkers[uid]
                    if (existing != null) {
                        existing.position = pos
                        existing.title    = title
                        existing.snippet  = snippet
                    } else {
                        val marker = googleMap.addMarker(
                            MarkerOptions()
                                .position(pos).title(title).snippet(snippet)
                                .icon(BitmapDescriptorFactory.defaultMarker(
                                    BitmapDescriptorFactory.HUE_VIOLET))
                        )
                        if (marker != null) companionMarkers[uid] = marker
                    }
                }
                updateCompanionButton()
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        memberListeners[uid] = listener
        db.child("users").child(uid).addValueEventListener(listener)
    }

    private fun detachAllListeners() {
        val code = circleCode
        if (code != null) {
            membersListener?.let {
                db.child("circles").child(code).child("members").removeEventListener(it)
                membersListener = null
            }
        }
        memberListeners.forEach { (uid, l) ->
            db.child("users").child(uid).removeEventListener(l)
        }
        memberListeners.clear()
    }

    private fun generateCircleCode(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return (1..6).map { chars.random() }.joinToString("")
    }

    // ═════════ Autocomplete ═══════════════════════════════════════════════

    private fun setupDestinationAutocomplete() {
        txtDestination.doOnTextChanged { text, _, _, _ ->
            if (text.toString().length >= 3) fetchAutocomplete(text.toString())
        }
        txtDestination.setOnItemClickListener { parent, _, position, _ ->
            selectAutocomplete(parent.getItemAtPosition(position) as String)
        }
        txtDestination.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                val first = txtDestination.adapter?.getItem(0) as? String
                first?.let { selectAutocomplete(it) }
            }
            true
        }
    }

    private fun fetchAutocomplete(query: String) {
        val request = FindAutocompletePredictionsRequest.builder().setQuery(query).build()
        placesClient.findAutocompletePredictions(request)
            .addOnSuccessListener { response ->
                val suggestions =
                    response.autocompletePredictions.map { it.getFullText(null).toString() }
                txtDestination.setAdapter(
                    ArrayAdapter(this, R.layout.item_autocomplete, suggestions)
                )
                txtDestination.showDropDown()
            }
            .addOnFailureListener { it.printStackTrace() }
    }

    private fun selectAutocomplete(selectedText: String) {
        placesClient.findAutocompletePredictions(
            FindAutocompletePredictionsRequest.builder().setQuery(selectedText).build()
        ).addOnSuccessListener { response ->
            val prediction =
                response.autocompletePredictions.firstOrNull() ?: return@addOnSuccessListener
            placesClient.fetchPlace(
                FetchPlaceRequest.builder(
                    prediction.placeId,
                    listOf(Place.Field.ID, Place.Field.DISPLAY_NAME, Place.Field.LOCATION)
                ).build()
            ).addOnSuccessListener { placeResponse ->
                val place  = placeResponse.place
                val name   = place.displayName ?: "Destination"
                val latLng = place.location ?: return@addOnSuccessListener
                destinationLatLng = latLng
                currentPolyline?.remove(); currentPolyline = null
                destinationMarker?.remove()
                destinationMarker = googleMap.addMarker(
                    MarkerOptions().position(latLng).title(name)
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                )
                currentLocation?.let { drawRoute(it, latLng) }
                val b = LatLngBounds.builder().include(latLng)
                currentLocation?.let { b.include(it) }
                googleMap.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 150))
                tts.speak("Navigating to $name", TextToSpeech.QUEUE_FLUSH, null, "DESTINATION")
            }.addOnFailureListener { it.printStackTrace() }
        }.addOnFailureListener { it.printStackTrace() }
    }
}