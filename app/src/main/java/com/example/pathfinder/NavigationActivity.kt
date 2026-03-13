package com.example.pathfinder

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.*
import com.google.android.gms.maps.*
import com.google.android.gms.maps.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.util.*

class NavigationActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var mapView: MapView
    private lateinit var googleMap: GoogleMap
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var tts: TextToSpeech
    private lateinit var txtNavGuide: TextView
    private lateinit var graphHopper: GraphHopperService

    private var destination: LatLng? = null
    private var currentLocation: LatLng? = null
    private var routePolyline: Polyline? = null
    private var routeDrawn = false
    private var stepList: MutableList<GraphHopperStep> = mutableListOf()
    private var currentStepIndex = 0
    private var youMarker: Marker? = null

    // Spoken-once flags, reset every time we enter a new step
    private var spokenEntry    = false  // "Continue straight for X meters"
    private var spokenApproach = false  // "In X meters, [turn instruction]"
    private var spokenAtPoint  = false  // "[turn instruction]" right at the corner

    private val APPROACH_DISTANCE = 20  // meters — start warning about the turn
    private val AT_POINT_DISTANCE = 8   // meters — say the turn now and advance

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) startLocationUpdates()
    }

    // ═════════ Lifecycle ═════════════════════════════════════════════════
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_navigation)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.hide(android.view.WindowInsets.Type.statusBars())
        }

        receiveDestination()

        mapView = findViewById(R.id.mapView)
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync(this)
        txtNavGuide = findViewById(R.id.txtNavGuide)

        graphHopper = GraphHopperService(getString(R.string.graph_api_key))

        findViewById<Button>(R.id.btnEnd).setOnClickListener { finish() }
        setupLocationClient()
        setupTextToSpeech()

        // ─── Where Am I ──────────────────────────────────────────────────
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
                            showLandmarksDialog(streetName, landmarkNames)
                        else
                            Toast.makeText(this@NavigationActivity,
                                "No landmarks found nearby", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@NavigationActivity,
                            "Error fetching landmarks", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume(); mapView.onResume(); checkLocationPermission()
    }
    override fun onPause() {
        super.onPause(); mapView.onPause(); stopLocationUpdates()
    }
    override fun onDestroy() {
        mapView.onDestroy()
        if (::tts.isInitialized) tts.shutdown()
        super.onDestroy()
    }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState); mapView.onSaveInstanceState(outState)
    }

    // ═════════ Permissions + Location ════════════════════════════════════
    private fun checkLocationPermission() {
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) startLocationUpdates()
        else permissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) return
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
            .setMinUpdateIntervalMillis(1000L)
            .build()
        fusedLocationClient.requestLocationUpdates(req, locationCallback, Looper.getMainLooper())
    }

    private fun stopLocationUpdates() {
        if (::fusedLocationClient.isInitialized)
            fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    // ═════════ Setup ══════════════════════════════════════════════════════
    private fun receiveDestination() {
        val lat = intent.getDoubleExtra("dest_lat", 0.0)
        val lng = intent.getDoubleExtra("dest_lng", 0.0)
        if (lat != 0.0 && lng != 0.0) destination = LatLng(lat, lng)
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        googleMap.uiSettings.isCompassEnabled = true
        googleMap.mapType = GoogleMap.MAP_TYPE_NORMAL

        destination?.let {
            googleMap.addMarker(MarkerOptions().position(it).title("Destination"))
            googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(it, 17f))
        }

        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) googleMap.isMyLocationEnabled = true
    }

    private fun setupLocationClient() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                currentLocation = LatLng(location.latitude, location.longitude)
                currentLocation?.let { loc ->
                    googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(loc, 18f))
                    if (!routeDrawn && destination != null) {
                        fetchRoute(loc, destination!!)
                        routeDrawn = true
                    }
                    if (stepList.isNotEmpty()) checkStepProgress()
                }
            }
        }
    }

    private fun setupTextToSpeech() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) tts.language = Locale.US
        }
    }

    // ═════════ Route fetching ═════════════════════════════════════════════
    private fun fetchRoute(origin: LatLng, dest: LatLng) {
        lifecycleScope.launch(Dispatchers.IO) {
            val route = graphHopper.getRoute(
                origin.latitude, origin.longitude,
                dest.latitude,   dest.longitude
            )
            withContext(Dispatchers.Main) {
                if (route == null || route.steps.isEmpty()) {
                    Toast.makeText(this@NavigationActivity,
                        "Could not get route. Check your connection.", Toast.LENGTH_LONG).show()
                    return@withContext
                }

                stepList.clear()
                stepList.addAll(route.steps)

                routePolyline?.remove()
                routePolyline = googleMap.addPolyline(
                    PolylineOptions()
                        .addAll(route.points)
                        .width(14f)
                        .color(0xFF0073E6.toInt())
                )

                findViewById<TextView>(R.id.txtDistance).text = "${route.distanceMeters} m"
                findViewById<TextView>(R.id.txtTime).text     = "${route.durationMs / 60000} min"

                if (route.points.isNotEmpty()) {
                    val b = LatLngBounds.Builder().also { route.points.forEach(it::include) }
                    googleMap.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 150))
                }

                enterStep(0)
            }
        }
    }

    // ═════════ Step entry ════════════════════════════════════════════════
    private fun enterStep(index: Int) {
        if (index >= stepList.size) return

        currentStepIndex = index
        spokenEntry      = false
        spokenApproach   = false
        spokenAtPoint    = false

        val step    = stepList[index]
        val isFinal = isArrival(step.sign) || index == stepList.size - 1

        // Entry cue: always "Continue straight for X meters"
        // We never mention the upcoming turn here — that's what the approach
        // and at-point cues are for. The user just needs to know how far to walk.
        val entryCue = when {
            index == 0 ->
                // First step: use GraphHopper's text (e.g. "Head north on Rizal Avenue")
                // since the user hasn't started walking yet and needs a heading.
                "${step.text} for ${step.distanceMeters} meters."
            isFinal ->
                "Continue straight for ${step.distanceMeters} meters. " +
                        "Your destination is ahead."
            else -> {
                // Include the street name so the user can confirm they're on the right path.
                // GraphHopper's step.text already contains the street name,
                // e.g. "Turn right onto Rizal Avenue" — we extract just the street part.
                val street = extractStreet(step.text)
                if (street != null)
                    "Continue straight on $street for ${step.distanceMeters} meters."
                else
                    "Continue straight for ${step.distanceMeters} meters."
            }
        }

        // Speak entry cue but set label to step.text so it always shows the real instruction
        txtNavGuide.text = "${step.text} — ${step.distanceMeters} m"
        speakInstruction(entryCue)
        spokenEntry = true
    }

    // ═════════ Core navigation tick ═══════════════════════════════════════
    private fun checkStepProgress() {
        val loc = currentLocation ?: return
        if (currentStepIndex >= stepList.size) return

        val step      = stepList[currentStepIndex]
        val distToEnd = distanceTo(loc, step.endPoint)
        val isFinal   = isArrival(step.sign) || currentStepIndex == stepList.size - 1

        // Live distance label — always updating, always showing the current instruction
        txtNavGuide.text = if (isFinal) "Destination — $distToEnd m"
        else "${step.text} — $distToEnd m"

        // ── Approach cue (~20 m out) ──────────────────────────────────────
        // "Approaching a right turn in 18 meters."
        // Describes the turn type using the sign — street name comes at the at-point
        // cue so it's fresh right when the user actually needs to act on it.
        if (!spokenApproach && distToEnd <= APPROACH_DISTANCE) {
            spokenApproach = true
            val cue = if (isFinal)
                "Your destination is in $distToEnd meters."
            else
                "Approaching ${signToApproachPhrase(step.sign)} in $distToEnd meters."
            speakInstruction(cue)
        }

        // ── At-point cue (~8 m out) — full instruction then advance ──────
        // Uses GraphHopper's own text directly, e.g. "Turn right onto Rizal Avenue."
        if (!spokenAtPoint && distToEnd <= AT_POINT_DISTANCE) {
            spokenAtPoint = true
            val cue = if (isFinal)
                "You have arrived at your destination."
            else
                "${step.text}."
            speakInstruction(cue)

            if (!isFinal) {
                txtNavGuide.postDelayed({ enterStep(currentStepIndex + 1) }, 2500L)
            }
        }
    }

    // ═════════ Sign helper ════════════════════════════════════════════════
    // Used only for the approach cue — describes the turn type naturally.
    // e.g. sign 2 → "a right turn", sign -3 → "a sharp left turn"
    private fun signToApproachPhrase(sign: Int) = when (sign) {
        -3 -> "a sharp left turn"
        -2 -> "a left turn"
        -1 -> "a slight left turn"
        0 -> "a straight path"
        1 -> "a slight right turn"
        2 -> "a right turn"
        3 -> "a sharp right turn"
        6 -> "a roundabout"
        7 -> "a keep left"
        8 -> "a keep right"
        else -> "a turn"
    }

    // ═════════ Helpers ════════════════════════════════════════════════════
    private fun isArrival(sign: Int) = sign == 4

    // Extracts the street name from GraphHopper instruction text.
    // "Turn right onto Rizal Avenue"  ->  "Rizal Avenue"
    // "Head north on Session Road"    ->  "Session Road"
    private fun extractStreet(text: String): String? {
        val lower = text.lowercase()
        val ontoIdx   = lower.indexOf(" onto ")
        if (ontoIdx   != -1) return text.substring(ontoIdx   + 6).trim()
        val onIdx     = lower.indexOf(" on ")
        if (onIdx     != -1) return text.substring(onIdx     + 4).trim()
        val towardIdx = lower.indexOf(" toward ")
        if (towardIdx != -1) return text.substring(towardIdx + 8).trim()
        return null
    }

    private fun showAndSpeak(text: String) {
        txtNavGuide.text = text
        speakInstruction(text)
    }

    private fun speakInstruction(text: String) {
        if (::tts.isInitialized)
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
    }

    private fun distanceTo(from: LatLng, to: LatLng): Int {
        val r = FloatArray(1)
        Location.distanceBetween(from.latitude, from.longitude, to.latitude, to.longitude, r)
        return r[0].toInt()
    }

    // ═════════ Landmarks dialog ═══════════════════════════════════════════
    private fun showLandmarksDialog(streetName: String, landmarkNames: List<String>) {
        val adapter = object : ArrayAdapter<String>(
            this, android.R.layout.simple_list_item_1, landmarkNames
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent) as TextView
                view.typeface      = ResourcesCompat.getFont(this@NavigationActivity, R.font.poppins)
                view.textSize      = 18f
                view.gravity       = Gravity.CENTER
                view.textAlignment = View.TEXT_ALIGNMENT_CENTER
                view.setTextColor(Color.BLACK)
                return view
            }
        }

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("You are on $streetName")
            .setAdapter(adapter, null)
            .setPositiveButton("Close", null)
            .setBackground(ContextCompat.getDrawable(this, R.drawable.white_morerounded_corners))
            .create()

        dialog.show()

        val textViewId = dialog.context.resources.getIdentifier("alertTitle", "id", "android")
        dialog.findViewById<TextView>(textViewId)?.apply {
            typeface = ResourcesCompat.getFont(this@NavigationActivity, R.font.poppins_extrabold)
            setTextColor(Color.BLACK)
            textSize = 30f
            (parent as? LinearLayout)?.gravity = Gravity.CENTER
        }

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
            typeface = ResourcesCompat.getFont(this@NavigationActivity, R.font.poppins_bold)
            setTextColor(Color.parseColor("#9b8bf4"))
            textSize = 16f
        }

        dialog.window?.setElevation(30f)

        tts.speak(
            landmarkNames.joinToString(", ",
                prefix = "You are on $streetName. Nearby landmarks are "),
            TextToSpeech.QUEUE_FLUSH, null, "LANDMARKS_UTTERANCE"
        )
        dialog.setOnDismissListener { tts.stop() }
    }
}