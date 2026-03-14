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
    private var navArrowMarker: Marker? = null
    private var youMarker: Marker? = null

    // What the guidebox is currently showing (without the distance suffix)
    private var currentGuideText = ""

    // Spoken-once flags per step
    private var spokenEntry    = false
    private var spokenApproach = false
    private var spokenAtPoint  = false

    // Distance thresholds
    private val APPROACH_DISTANCE = 20  // meters — "Approaching a turn in X meters"
    private val AT_POINT_DISTANCE = 8   // meters — "Turn right onto Rizal Avenue" + advance

    // Rerouting
    private var isRerouting      = false
    private var lastRerouteTime  = 0L
    private val REROUTE_COOLDOWN = 15_000L
    private val REROUTE_THRESHOLD = 50f  // meters off route

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

    override fun onResume() { super.onResume(); mapView.onResume(); checkLocationPermission() }
    override fun onPause()  { super.onPause();  mapView.onPause();  stopLocationUpdates() }
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
        ) {
            googleMap.isMyLocationEnabled = false
            googleMap.uiSettings.isMyLocationButtonEnabled = false
        }
    }

    private fun setupLocationClient() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                currentLocation = LatLng(location.latitude, location.longitude)
                currentLocation?.let { loc ->
                    val bearing = if (location.hasBearing()) location.bearing else 0f

                    val camPosition = CameraPosition.Builder()
                        .target(loc)
                        .zoom(19f)
                        .tilt(60f)
                        .bearing(bearing)
                        .build()
                    googleMap.animateCamera(CameraUpdateFactory.newCameraPosition(camPosition))

                    if (navArrowMarker == null) {
                        navArrowMarker = googleMap.addMarker(
                            MarkerOptions()
                                .position(loc)
                                .icon(vectorToBitmapDescriptor(R.drawable.nav_arrow))
                                .rotation(bearing)
                                .anchor(0.5f, 0.5f)
                                .flat(true)
                                .zIndex(1f)
                        )
                    } else {
                        navArrowMarker!!.position = loc
                        navArrowMarker!!.rotation = bearing
                    }

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
                isRerouting = false

                // TEMPORARY DEBUG — remove after confirming steps are correct
                for ((i, s) in route.steps.withIndex()) {
                    android.util.Log.d("NAV_STEPS",
                        "Step $i | sign=${s.sign} | dist=${s.distanceMeters}m | text=${s.text}")
                }

                routePolyline?.remove()
                routePolyline = googleMap.addPolyline(
                    PolylineOptions()
                        .addAll(route.points)
                        .width(14f)
                        .color(0xFF0073E6.toInt())
                )

                findViewById<TextView>(R.id.txtDistance).text = "${route.distanceMeters} m"
                findViewById<TextView>(R.id.txtTime).text     = "${route.durationMs / 60000} min"

                currentLocation?.let { loc ->
                    val camPosition = CameraPosition.Builder()
                        .target(loc).zoom(19f).tilt(60f).bearing(0f).build()
                    googleMap.moveCamera(CameraUpdateFactory.newCameraPosition(camPosition))
                }

                enterStep(0)
            }
        }
    }

    // ═════════ Step entry ════════════════════════════════════════════════
    /**
     * Called once when entering a new step.
     * GraphHopper's step.text already says exactly what to do, e.g.:
     *   "Head north on Session Road"
     *   "Turn right onto Rizal Avenue"
     *   "Continue straight"
     *   "Arrive at destination"
     *
     * Entry cue just says "Continue straight for X meters" so the user
     * knows how far to walk before anything happens.
     * The actual turn instruction fires later in the approach/at-point cues.
     */
    private fun enterStep(index: Int) {
        if (index >= stepList.size) return

        currentStepIndex = index
        spokenEntry      = false
        spokenApproach   = false
        spokenAtPoint    = false

        val step    = stepList[index]
        val isFinal = index == stepList.size - 1

        val entryCue = when {
            index == 0 ->
                // First step: GraphHopper gives the heading, e.g. "Head north on Session Road"
                "${step.text} for ${step.distanceMeters} meters."
            isFinal ->
                "Continue for ${step.distanceMeters} meters. Your destination is ahead."
            else ->
                // Middle steps: just tell them to keep walking.
                // Don't mention the turn yet — approach cue handles that.
                "Continue straight for ${step.distanceMeters} meters."
        }

        sayAndShow(entryCue)
        spokenEntry = true
    }

    // ═════════ Navigation tick ════════════════════════════════════════════
    /**
     * Called on every GPS update.
     *
     * distToEnd = live GPS distance to the END POINT of the current step.
     * That end point is exactly where the turn/maneuver happens.
     *
     * Cues:
     *   Entry    → spoken once on step entry (how far to walk)
     *   Approach → spoken at ~20m (warning: turn coming)
     *   AtPoint  → spoken at ~8m  (the actual GraphHopper instruction)
     *              then advances to the next step
     */
    private fun checkStepProgress() {
        val loc = currentLocation ?: return
        if (currentStepIndex >= stepList.size) return

        val step      = stepList[currentStepIndex]
        val distToEnd = distanceTo(loc, step.endPoint)
        val isFinal   = currentStepIndex == stepList.size - 1

        // Off-route check
        val now = System.currentTimeMillis()
        if (!isRerouting && (now - lastRerouteTime) > REROUTE_COOLDOWN) {
            checkIfOffRoute(loc)
        }

        // Live distance label — always shows current guide text + distance
        txtNavGuide.text = "$currentGuideText — $distToEnd m"

        // ── Approach cue: ~20m before the end point ─────────────────────
        // The NEXT step's text is the maneuver at the end of the current leg.
        // e.g. current step = "Continue onto Kamuning Road"
        //      next step    = "Turn right onto 11th Jamboree Street"  ← this is what to say
        if (!spokenApproach && distToEnd <= APPROACH_DISTANCE) {
            spokenApproach = true
            val nextStep = stepList.getOrNull(currentStepIndex + 1)
            val cue = when {
                isFinal      -> "Your destination is in $distToEnd meters."
                nextStep == null -> "Your destination is in $distToEnd meters."
                else         -> "In $distToEnd meters, ${nextStep.text.lowercase()}."
            }
            sayAndShow(cue)
        }

        // ── At-point cue: ~8m — say the maneuver, then advance ───────────
        if (!spokenAtPoint && distToEnd <= AT_POINT_DISTANCE) {
            spokenAtPoint = true
            val nextStep = stepList.getOrNull(currentStepIndex + 1)
            val cue = when {
                isFinal          -> "You have arrived at your destination."
                nextStep == null -> "You have arrived at your destination."
                else             -> "${nextStep.text}."
            }
            sayAndShow(cue)

            if (!isFinal) {
                txtNavGuide.postDelayed({ enterStep(currentStepIndex + 1) }, 2500L)
            }
        }
    }

    // ═════════ Sync helper ════════════════════════════════════════════════
    private fun sayAndShow(text: String) {
        currentGuideText = text.trimEnd('.')
        txtNavGuide.text = currentGuideText
        if (::tts.isInitialized)
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
    }

    // ═════════ Off-route ══════════════════════════════════════════════════
    private fun checkIfOffRoute(loc: LatLng) {
        var minDist = Float.MAX_VALUE

        for (step in stepList) {
            val path = step.path
            if (path.size < 2) continue
            for (i in 0 until path.size - 1) {
                val a = path[i]; val b = path[i + 1]
                val abLat = b.latitude  - a.latitude
                val abLng = b.longitude - a.longitude
                val apLat = loc.latitude  - a.latitude
                val apLng = loc.longitude - a.longitude
                val abLenSq = abLat * abLat + abLng * abLng
                val t = if (abLenSq == 0.0) 0.0
                else ((apLat * abLat + apLng * abLng) / abLenSq).coerceIn(0.0, 1.0)
                val dist = FloatArray(1)
                Location.distanceBetween(
                    loc.latitude, loc.longitude,
                    a.latitude + t * abLat, a.longitude + t * abLng,
                    dist
                )
                if (dist[0] < minDist) minDist = dist[0]
            }
        }

        if (minDist > REROUTE_THRESHOLD) triggerReroute(loc)
    }

    private fun triggerReroute(loc: LatLng) {
        if (isRerouting) return
        isRerouting    = true
        lastRerouteTime = System.currentTimeMillis()
        sayAndShow("Off route. Recalculating.")
        destination?.let { fetchRoute(loc, it) }
    }

    // ═════════ Helpers ════════════════════════════════════════════════════
    private fun vectorToBitmapDescriptor(drawableResId: Int): BitmapDescriptor {
        val drawable = ContextCompat.getDrawable(this, drawableResId)!!
        val bitmap   = android.graphics.Bitmap.createBitmap(
            drawable.intrinsicWidth, drawable.intrinsicHeight,
            android.graphics.Bitmap.Config.ARGB_8888
        )
        val canvas = android.graphics.Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }

    private fun distanceTo(from: LatLng, to: LatLng): Int {
        val r = FloatArray(1)
        Location.distanceBetween(from.latitude, from.longitude, to.latitude, to.longitude, r)
        return r[0].toInt()
    }

    private fun speakInstruction(text: String) {
        if (::tts.isInitialized)
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
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

        speakInstruction(
            landmarkNames.joinToString(", ",
                prefix = "You are on $streetName. Nearby landmarks are ")
        )
        dialog.setOnDismissListener { tts.stop() }
    }
}