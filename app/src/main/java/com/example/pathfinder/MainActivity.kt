
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

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URLEncoder

private data class OSMPlace(
    val name: String,
    val latitude: Double,
    val longitude: Double
)

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var mapView: MapView
    private lateinit var googleMap: GoogleMap
    private lateinit var tts: TextToSpeech
    private lateinit var txtDestination: MaterialAutoCompleteTextView
    private lateinit var graphHopperService: GraphHopperService

    private var destinationMarker: Marker? = null
    private var youMarker: Marker? = null
    private var currentPolyline: Polyline? = null
    private var destinationLatLng: LatLng? = null
    private var currentLocation: LatLng? = null
    private var hasCenteredMap = false
    private var autocompleteJob: Job? = null
    private var autocompleteResults = emptyList<OSMPlace>()
    private var isSelectingDestination = false
    private var isFindingLandmarks = false

    private val db by lazy {
        FirebaseDatabase.getInstance(
            "https://pathfinder-bdc3e-default-rtdb.asia-southeast1.firebasedatabase.app"
        ).reference
    }
    private var circleCode: String? = null
    private val companionData = mutableMapOf<String, CompanionUser>()
    private val companionMarkers = mutableMapOf<String, Marker>()
    private val memberListeners = mutableMapOf<String, ValueEventListener>()
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

        txtDestination = findViewById(R.id.txtDestination)
        setupDestinationAutocomplete()

        val btnClearSearch = findViewById<ImageButton>(R.id.btnClearSearch)
        txtDestination.doOnTextChanged { text, _, _, _ ->
            btnClearSearch.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        btnClearSearch.setOnClickListener {
            txtDestination.text?.clear()
            destinationLatLng = null
            destinationMarker?.remove()
            destinationMarker = null
            currentPolyline?.remove()
            currentPolyline = null
        }

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.US
                tts.setSpeechRate(0.9f)
            }
        }

        val bottomSheet = findViewById<LinearLayout>(R.id.bottomSheet)
        BottomSheetBehavior.from(bottomSheet).apply {
            state = BottomSheetBehavior.STATE_COLLAPSED
            isDraggable = true
            isFitToContents = true
            isHideable = false
        }

        ViewCompat.setOnApplyWindowInsetsListener(bottomSheet) { view, insets ->
            val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.setPadding(
                view.paddingLeft,
                view.paddingTop,
                view.paddingRight,
                navBar.bottom + resources.getDimensionPixelSize(R.dimen.bottom_sheet_padding)
            )
            insets
        }

        btnCompanion = findViewById(R.id.btnCompanion)

        findViewById<Button>(R.id.btnWhereAmI).setOnClickListener {
            val current = currentLocation ?: run {
                Toast.makeText(
                    this,
                    "Waiting for your location...",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            if (isFindingLandmarks) return@setOnClickListener
            isFindingLandmarks = true

            googleMap.animateCamera(
                CameraUpdateFactory.newLatLngZoom(current, 17f)
            )

            youMarker?.remove()
            youMarker = googleMap.addMarker(
                MarkerOptions().position(current).title("You")
            )

            // Show the bottom sheet immediately.
            val locationSheet = showLandmarksBottomSheetProgressive(this)

            // Initial information while requests are running.
            locationSheet.updateStreetName("Your current location")
            locationSheet.updateLandmarks(
                listOf("Searching nearby landmarks...")
            )

            lifecycleScope.launch {
                try {
                    // Fetch the street name.
                    val streetName = withContext(Dispatchers.IO) {
                        fetchStreetName(current)
                    }

                    // Update the bottom sheet as soon as the street name is available.
                    locationSheet.updateStreetName(streetName)

                    // Fetch nearby landmarks with one retry for temporary server errors.
                    val landmarks = try {
                        fetchNearbyLandmarksWithRetry(current)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        emptyList()
                    }

                    val landmarkNames = ArrayList<String>()

                    landmarks.forEach { place ->
                        val position = LatLng(
                            place.latitude,
                            place.longitude
                        )

                        val distance = FloatArray(1)

                        Location.distanceBetween(
                            current.latitude,
                            current.longitude,
                            place.latitude,
                            place.longitude,
                            distance
                        )

                        googleMap.addMarker(
                            MarkerOptions()
                                .position(position)
                                .title(place.name)
                                .icon(
                                    BitmapDescriptorFactory.defaultMarker(
                                        BitmapDescriptorFactory.HUE_AZURE
                                    )
                                )
                        )

                        landmarkNames.add(
                            "${distance[0].toInt()} m — ${place.name}"
                        )
                    }

                    // Update the bottom sheet when landmarks finish loading.
                    if (landmarkNames.isNotEmpty()) {
                        locationSheet.updateLandmarks(landmarkNames)

                        // Preserve the original voice announcement.
                        if (::tts.isInitialized) {
                            val speechText = landmarkNames.joinToString(
                                ", ",
                                prefix = "You are on $streetName. Nearby landmarks are "
                            )

                            tts.speak(
                                speechText,
                                TextToSpeech.QUEUE_FLUSH,
                                null,
                                "LANDMARKS"
                            )
                        }
                    } else {
                        locationSheet.updateLandmarks(
                            listOf(
                                "Nearby landmarks are temporarily unavailable. Please try again later."
                            )
                        )
                    }

                } catch (e: Exception) {
                    e.printStackTrace()

                    locationSheet.updateLandmarks(
                        listOf("Unable to find landmarks: ${e.message}")
                    )

                } finally {
                    isFindingLandmarks = false
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
                Toast.makeText(
                    this,
                    "Please select a destination first.",
                    Toast.LENGTH_SHORT
                ).show()
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

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    // ═════════ Map ════════════════════════════════════════════════════════

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            if (::googleMap.isInitialized &&
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                googleMap.isMyLocationEnabled = true
            }
            startService(Intent(this, LocationSharingService::class.java))
        }
    }

    private fun fetchStreetName(current: LatLng): String {
        val url = URL(
            "https://nominatim.openstreetmap.org/reverse" +
                    "?lat=${current.latitude}" +
                    "&lon=${current.longitude}" +
                    "&format=jsonv2"
        )

        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.setRequestProperty(
                "User-Agent",
                "PathfinderAndroid/1.0"
            )

            val responseCode = connection.responseCode

            if (responseCode != HttpURLConnection.HTTP_OK) {
                val errorBody = connection.errorStream?.bufferedReader()?.use {
                    it.readText()
                }

                throw Exception("HTTP $responseCode: ${errorBody ?: "No details"}")
            }

            val response = connection.inputStream.bufferedReader().use {
                it.readText()
            }

            val json = JSONObject(response)
            val address = json.optJSONObject("address")

            return address?.optString("road")?.takeIf { it.isNotBlank() }
                ?: address?.optString("pedestrian")?.takeIf { it.isNotBlank() }
                ?: address?.optString("footway")?.takeIf { it.isNotBlank() }
                ?: json.optString("display_name", "your current location")

        } finally {
            connection.disconnect()
        }
    }

    private fun fetchNearbyLandmarks(current: LatLng): List<OSMPlace> {
        val query = """
            [out:json][timeout:25];
            nwr(around:50,${current.latitude},${current.longitude})["name"];
            out center;
        """.trimIndent()

        val connection = URL(
            "https://overpass-api.de/api/interpreter"
        ).openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 15000
            connection.readTimeout = 40000

            connection.setRequestProperty(
                "User-Agent",
                "PathfinderAndroid/1.0"
            )
            connection.setRequestProperty(
                "Content-Type",
                "application/x-www-form-urlencoded; charset=UTF-8"
            )

            val requestBody = "data=" + URLEncoder.encode(query, "UTF-8")

            connection.outputStream.use {
                it.write(requestBody.toByteArray(Charsets.UTF_8))
            }

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw Exception("Landmark request failed: ${connection.responseCode}")
            }

            val response = connection.inputStream.bufferedReader().use {
                it.readText()
            }

            val elements = JSONObject(response).getJSONArray("elements")
            val places = mutableListOf<OSMPlace>()

            for (i in 0 until elements.length()) {
                val item = elements.getJSONObject(i)
                val name = item.optJSONObject("tags")
                    ?.optString("name")
                    ?.takeIf { it.isNotBlank() }
                    ?: continue

                val latitude: Double
                val longitude: Double

                if (item.has("lat") && item.has("lon")) {
                    latitude = item.getDouble("lat")
                    longitude = item.getDouble("lon")
                } else {
                    val center = item.optJSONObject("center") ?: continue
                    latitude = center.getDouble("lat")
                    longitude = center.getDouble("lon")
                }

                places.add(
                    OSMPlace(
                        name = name,
                        latitude = latitude,
                        longitude = longitude
                    )
                )
            }

            return places
                .distinctBy { it.name }
                .sortedBy { place ->
                    val distance = FloatArray(1)
                    Location.distanceBetween(
                        current.latitude,
                        current.longitude,
                        place.latitude,
                        place.longitude,
                        distance
                    )
                    distance[0]
                }
                .take(5)

        } finally {
            connection.disconnect()
        }
    }

    // Retry the landmark request once if Overpass returns a temporary server error.
    private suspend fun fetchNearbyLandmarksWithRetry(
        current: LatLng
    ): List<OSMPlace> {
        return try {
            withContext(Dispatchers.IO) {
                fetchNearbyLandmarks(current)
            }
        } catch (e: Exception) {
            val isTemporaryServerError =
                e.message?.contains("502") == true ||
                        e.message?.contains("503") == true ||
                        e.message?.contains("504") == true

            if (!isTemporaryServerError) {
                throw e
            }

            // Wait 2 seconds before retrying.
            delay(2000)

            // Retry the original request once.
            withContext(Dispatchers.IO) {
                fetchNearbyLandmarks(current)
            }
        }
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            googleMap.isMyLocationEnabled = true
            startService(Intent(this, LocationSharingService::class.java))

            val fusedClient = LocationServices.getFusedLocationProviderClient(this)
            val locationRequest = LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                10_000L
            )
                .setMinUpdateIntervalMillis(5_000L)
                .setMinUpdateDistanceMeters(5f)
                .build()

            fusedClient.requestLocationUpdates(
                locationRequest,
                object : LocationCallback() {
                    override fun onLocationResult(result: LocationResult) {
                        val loc = result.lastLocation ?: return

                        // Ignore inaccurate fixes — cell tower / WiFi positions can be 100m+ off.
                        if (loc.hasAccuracy() && loc.accuracy > 50f) return

                        val latLng = LatLng(loc.latitude, loc.longitude)
                        currentLocation = latLng

                        if (!hasCenteredMap) {
                            googleMap.moveCamera(
                                CameraUpdateFactory.newLatLngZoom(latLng, 17f)
                            )
                            hasCenteredMap = true
                            youMarker?.remove()
                            youMarker = googleMap.addMarker(
                                MarkerOptions().position(latLng).title("You")
                            )
                        }
                    }
                },
                Looper.getMainLooper()
            )
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST
            )
        }
    }

    // ═════════ Route preview ══════════════════════════════════════════════

    private fun drawRoute(origin: LatLng, destination: LatLng) {
        lifecycleScope.launch(Dispatchers.IO) {
            val route = graphHopperService.getRoute(
                origin.latitude,
                origin.longitude,
                destination.latitude,
                destination.longitude
            )
            withContext(Dispatchers.Main) {
                if (route != null && route.points.isNotEmpty()) {
                    currentPolyline?.remove()
                    currentPolyline = googleMap.addPolyline(
                        PolylineOptions()
                            .addAll(route.points)
                            .color(Color.BLUE)
                            .width(10f)
                    )
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        "Route not found",
                        Toast.LENGTH_SHORT
                    ).show()
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
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
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
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
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
        val total = companionData.size

        btnCompanion.text = if (total == 0) "Companion (0)"
        else "Companion ($onlineCount/$total)"
    }

    private fun isOnline(user: CompanionUser): Boolean {
        // lastUpdated == 0 means they just joined and haven't sent location yet.
        // Treat as online (pending) so they don't show as offline immediately.
        if (user.location.lastUpdated == 0L) return true
        return (System.currentTimeMillis() - user.location.lastUpdated) < ONLINE_THRESHOLD_MS
    }

    private fun showCircleDialog(code: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 32, 64, 16)
        }

        container.addView(TextView(this).apply {
            text = code
            textSize = 38f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#0073E6"))
            typeface = android.graphics.Typeface.MONOSPACE
            letterSpacing = 0.3f
            setPadding(0, 0, 0, 24)
        })

        container.addView(TextView(this).apply {
            text = "Circle members"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#888888"))
            setPadding(0, 0, 0, 16)
        })

        fun addRow(name: String, dotColor: String, statusTxt: String) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 8, 0, 8)
            }

            row.addView(TextView(this).apply {
                text = "●"
                textSize = 14f
                setTextColor(Color.parseColor(dotColor))
                setPadding(0, 0, 16, 0)
            })

            row.addView(TextView(this).apply {
                text = name
                textSize = 16f
                setTextColor(Color.BLACK)
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            })

            row.addView(TextView(this).apply {
                text = statusTxt
                textSize = 13f
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
                            // Navigator is always online — they just opened this dialog.
                            addRow(
                                name = self.name,
                                dotColor = "#0073E6",
                                statusTxt = "You · Online"
                            )
                        }

                        if (companionData.isEmpty()) {
                            container.addView(TextView(this@MainActivity).apply {
                                text = "No companions have joined yet"
                                textSize = 13f
                                gravity = Gravity.CENTER
                                setTextColor(Color.parseColor("#AAAAAA"))
                                setPadding(0, 8, 0, 0)
                            })
                        } else {
                            companionData.values.forEach { user ->
                                val online = isOnline(user)
                                val pending = user.location.lastUpdated == 0L

                                addRow(
                                    name = user.name.ifBlank { "Companion" },
                                    dotColor = when {
                                        pending -> "#F59E0B"
                                        online -> "#4CAF50"
                                        else -> "#AAAAAA"
                                    },
                                    statusTxt = when {
                                        pending -> "Joining…"
                                        online -> "Online"
                                        else -> "Offline"
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
                    val pos = LatLng(user.location.lat, user.location.lng)
                    val title = "👥 ${user.name}"
                    val snippet = "🔋 ${user.location.battery}%"
                    val existing = companionMarkers[uid]

                    if (existing != null) {
                        existing.position = pos
                        existing.title = title
                        existing.snippet = snippet
                    } else {
                        val marker = googleMap.addMarker(
                            MarkerOptions()
                                .position(pos)
                                .title(title)
                                .snippet(snippet)
                                .icon(
                                    BitmapDescriptorFactory.defaultMarker(
                                        BitmapDescriptorFactory.HUE_VIOLET
                                    )
                                )
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
            if (isSelectingDestination) return@doOnTextChanged

            // Invalidate the previous destination when the user edits the search.
            destinationLatLng = null
            destinationMarker?.remove()
            destinationMarker = null
            currentPolyline?.remove()
            currentPolyline = null

            val query = text.toString().trim()

            if (query.length >= 3) {
                fetchAutocomplete(query)
            } else {
                autocompleteJob?.cancel()
                autocompleteResults = emptyList()

                txtDestination.setAdapter(
                    ArrayAdapter(this, R.layout.item_autocomplete, emptyList<String>())
                )
                txtDestination.dismissDropDown()
            }
        }

        txtDestination.setOnItemClickListener { _, _, position, _ ->
            autocompleteResults.getOrNull(position)?.let {
                selectAutocomplete(it)
            }
        }

        txtDestination.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                autocompleteResults.firstOrNull()?.let {
                    selectAutocomplete(it)
                }
            }
            true
        }
    }

    private fun fetchAutocomplete(query: String) {
        autocompleteJob?.cancel()

        autocompleteJob = lifecycleScope.launch {
            // Debounce requests so we don't send one for every keystroke.
            delay(1000)

            try {
                val results = withContext(Dispatchers.IO) {
                    val encodedQuery = URLEncoder.encode(query, "UTF-8")
                    val url = URL(
                        "https://nominatim.openstreetmap.org/search" +
                                "?q=$encodedQuery&format=jsonv2&limit=5"
                    )

                    val connection = url.openConnection() as HttpURLConnection

                    try {
                        connection.requestMethod = "GET"
                        connection.connectTimeout = 10000
                        connection.readTimeout = 10000
                        connection.setRequestProperty(
                            "User-Agent",
                            "PathfinderAndroid/1.0"
                        )

                        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                            throw Exception("Search request failed: ${connection.responseCode}")
                        }

                        val response = connection.inputStream.bufferedReader().use {
                            it.readText()
                        }

                        val jsonArray = JSONArray(response)

                        (0 until jsonArray.length()).map { index ->
                            val item = jsonArray.getJSONObject(index)

                            OSMPlace(
                                name = item.getString("display_name"),
                                latitude = item.getDouble("lat"),
                                longitude = item.getDouble("lon")
                            )
                        }
                    } finally {
                        connection.disconnect()
                    }
                }

                // Don't show results for an outdated query.
                if (txtDestination.text.toString().trim() == query) {
                    autocompleteResults = results

                    txtDestination.setAdapter(
                        ArrayAdapter(
                            this@MainActivity,
                            R.layout.item_autocomplete,
                            results.map { it.name }
                        )
                    )

                    if (results.isNotEmpty()) {
                        txtDestination.showDropDown()
                    }
                }

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun selectAutocomplete(place: OSMPlace) {
        val name = place.name
        val latLng = LatLng(place.latitude, place.longitude)

        isSelectingDestination = true
        txtDestination.setText(name, false)
        isSelectingDestination = false

        destinationLatLng = latLng

        currentPolyline?.remove()
        currentPolyline = null

        destinationMarker?.remove()
        destinationMarker = googleMap.addMarker(
            MarkerOptions()
                .position(latLng)
                .title(name)
                .icon(
                    BitmapDescriptorFactory.defaultMarker(
                        BitmapDescriptorFactory.HUE_RED
                    )
                )
        )

        currentLocation?.let {
            drawRoute(it, latLng)

            val bounds = LatLngBounds.builder()
                .include(it)
                .include(latLng)
                .build()

            googleMap.animateCamera(
                CameraUpdateFactory.newLatLngBounds(bounds, 150)
            )
        } ?: run {
            googleMap.animateCamera(
                CameraUpdateFactory.newLatLngZoom(latLng, 16f)
            )
        }

        if (::tts.isInitialized) {
            tts.speak(
                "Navigating to $name",
                TextToSpeech.QUEUE_FLUSH,
                null,
                "DESTINATION"
            )
        }
    }
}
