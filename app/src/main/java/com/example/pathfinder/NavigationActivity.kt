package com.example.pathfinder

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.google.android.gms.location.*
import com.google.android.gms.maps.*
import com.google.android.gms.maps.model.*
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

class NavigationActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var mapView: MapView
    private lateinit var googleMap: GoogleMap
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    private var destination: LatLng? = null
    private var currentLocation: LatLng? = null
    private lateinit var tts: TextToSpeech
    private lateinit var txtNavGuide: android.widget.TextView

    private val httpClient = OkHttpClient()
    private var routePolyline: Polyline? = null
    private var currentRoute: RouteData? = null
    private var routeDrawn = false
    private var stepList: MutableList<NavigationStep> = mutableListOf()
    private var currentStepIndex = 0

    // ── Phase tracking ───────────────────────────────────────────────────────
    //
    //  ANNOUNCE  → fires once when a step becomes active.
    //              Uses live GPS distance so the number is always accurate.
    //              Step 0 : "Starting navigation. Continue straight for 80 meters."
    //              Step N : "Turn left now. Continue straight for 55 meters."
    //              Final  : "Turn right now. Your destination is 30 meters ahead."
    //
    //  CRUISING  → silent. UI shows live countdown.
    //              → PRE_TURN when distLeft ≤ PRE_TURN_DISTANCE
    //
    //  PRE_TURN  → speaks once: "Approaching a left turn in X meters."
    //              → TURN_NOW when distLeft ≤ TURN_NOW_DISTANCE
    //
    //  TURN_NOW  → speaks once: "Turn left now."
    //              → advances step when user passes the point (closest-approach logic)
    //
    private enum class Phase { ANNOUNCE, CRUISING, PRE_TURN, TURN_NOW }
    private var phase = Phase.ANNOUNCE
    private var announceSpoken = false

    // Distance thresholds (metres). Generous to account for pedestrian GPS drift.
    private val PRE_TURN_DISTANCE = 20
    private val TURN_NOW_DISTANCE = 10

    // Closest-approach tracker.
    // GPS on foot is ±5-15 m, so "distLeft <= 15" may never happen even when
    // the user is right at the turn. Instead we also watch for the user reaching
    // their closest point to step.end and then moving away — that means they passed it.
    private var closestDistSeen = Int.MAX_VALUE
    private var distRisingTicks = 0
    private val RISING_TICKS_NEEDED = 2   // 2 consecutive ticks moving away = passed the point

    // ── Permission launcher ──────────────────────────────────────────────────
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) startLocationUpdates()
    }

    // ── Data classes ─────────────────────────────────────────────────────────
    data class RouteData(
        val polylinePoints: List<LatLng>,
        val distanceText: String,
        val durationText: String
    )

    data class NavigationStep(
        val start: LatLng,
        val end: LatLng,
        val instruction: String,
        val distanceMeters: Int,
        val path: List<LatLng>
    )

    // ════════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ════════════════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_navigation)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }

        receiveDestination()

        mapView = findViewById(R.id.mapView)
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync(this)
        txtNavGuide = findViewById(R.id.txtNavGuide)

        findViewById<android.widget.Button>(R.id.btnEnd).setOnClickListener { finish() }

        setupLocationClient()
        setupTextToSpeech()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        checkLocationPermission()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
        stopLocationUpdates()
    }

    override fun onDestroy() {
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

    // ════════════════════════════════════════════════════════════════════════
    // Permissions + location
    // ════════════════════════════════════════════════════════════════════════

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
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L).build()
        fusedLocationClient.requestLocationUpdates(req, locationCallback, Looper.getMainLooper())
    }

    private fun stopLocationUpdates() {
        if (::fusedLocationClient.isInitialized)
            fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    // ════════════════════════════════════════════════════════════════════════
    // Setup
    // ════════════════════════════════════════════════════════════════════════

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
                        fetchWalkingRoute(loc, destination!!)
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

    // ════════════════════════════════════════════════════════════════════════
    // Navigation logic
    // ════════════════════════════════════════════════════════════════════════

    private fun checkStepProgress() {
        val loc = currentLocation ?: return
        if (currentStepIndex >= stepList.size) return

        val step     = stepList[currentStepIndex]
        val distLeft = distanceTo(loc, step.end)
        val isFinal  = currentStepIndex == stepList.size - 1

        // ── Closest-approach tracking ──────────────────────────────────────
        // Update how close we've ever been to this step's endpoint.
        // Once we start moving away consistently, the user has passed the point.
        if (distLeft < closestDistSeen) {
            closestDistSeen = distLeft
            distRisingTicks = 0
        } else if (distLeft > closestDistSeen) {
            distRisingTicks++
        }

        // Has the user passed the step endpoint? True when:
        //  (a) they got within TURN_NOW_DISTANCE, OR
        //  (b) they reached a closest point then moved away for RISING_TICKS_NEEDED ticks
        //      AND they were at least somewhat close (within 2× TURN_NOW_DISTANCE)
        val userPassedPoint = distLeft <= TURN_NOW_DISTANCE ||
                (distRisingTicks >= RISING_TICKS_NEEDED && closestDistSeen <= TURN_NOW_DISTANCE * 2)

        when (phase) {

            // ── ANNOUNCE ──────────────────────────────────────────────────
            Phase.ANNOUNCE -> {
                if (!announceSpoken) {
                    announceSpoken = true
                    showAndSpeak(buildAnnouncement(currentStepIndex, distLeft, isFinal))
                }
                // Fall through into CRUISING immediately so short steps don't get skipped
                phase = Phase.CRUISING
                showDistanceLabel(step, distLeft, isFinal)
                when {
                    distLeft <= TURN_NOW_DISTANCE -> {
                        phase = Phase.TURN_NOW
                        showAndSpeak(if (isFinal) "You have arrived at your destination" else turnNowMessage(step.instruction))
                    }
                    distLeft <= PRE_TURN_DISTANCE -> {
                        phase = Phase.PRE_TURN
                        showAndSpeak(if (isFinal) "Your destination is in $distLeft meters" else buildApproachingMessage(step.instruction, distLeft))
                    }
                }
            }

            // ── CRUISING ──────────────────────────────────────────────────
            Phase.CRUISING -> {
                showDistanceLabel(step, distLeft, isFinal)
                when {
                    distLeft <= TURN_NOW_DISTANCE -> {
                        phase = Phase.TURN_NOW
                        showAndSpeak(if (isFinal) "You have arrived at your destination" else turnNowMessage(step.instruction))
                    }
                    distLeft <= PRE_TURN_DISTANCE -> {
                        phase = Phase.PRE_TURN
                        showAndSpeak(if (isFinal) "Your destination is in $distLeft meters" else buildApproachingMessage(step.instruction, distLeft))
                    }
                }
            }

            // ── PRE_TURN ──────────────────────────────────────────────────
            Phase.PRE_TURN -> {
                showDistanceLabel(step, distLeft, isFinal)
                if (distLeft <= TURN_NOW_DISTANCE) {
                    phase = Phase.TURN_NOW
                    showAndSpeak(if (isFinal) "You have arrived at your destination" else turnNowMessage(step.instruction))
                }
            }

            // ── TURN_NOW ──────────────────────────────────────────────────
            // Advance once user has passed the point (either by distance or closest-approach).
            Phase.TURN_NOW -> {
                if (isFinal) return   // arrived message already spoken
                if (userPassedPoint) advanceToNextStep()
            }
        }
    }

    /** Reset closest-approach state and move to next step. */
    private fun advanceToNextStep() {
        currentStepIndex++
        phase           = Phase.ANNOUNCE
        announceSpoken  = false
        closestDistSeen = Int.MAX_VALUE
        distRisingTicks = 0
        if (currentStepIndex < stepList.size) checkStepProgress()
    }

    // ════════════════════════════════════════════════════════════════════════
    // Message builders
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Post-turn announcement for step [stepIndex].
     * Uses live [distLeft] (GPS distance from current position to step end)
     * so the quoted distance is always accurate even if the user is mid-step.
     *
     * Step 0 → "Starting navigation. Continue straight for 80 meters."
     * Step N → "Turn left now. Continue straight for 55 meters."
     * Final  → "Turn right now. Your destination is 30 meters ahead."
     */
    private fun buildAnnouncement(stepIndex: Int, distLeft: Int, isFinal: Boolean): String {
        val current = stepList[stepIndex]

        val ahead = when {
            isFinal ->
                "Your destination is $distLeft meters ahead."
            isStraightInstruction(current.instruction) ->
                // GraphHopper gave a straight/head/continue instruction — use it for direction context
                // e.g. "Head northeast for 80 meters", "Continue straight for 55 meters"
                "${current.instruction} for $distLeft meters."
            else ->  // turn instruction — announce it later via PRE_TURN/TURN_NOW
                // For all other steps: the user just turned and now needs to walk straight.
                // The current step's instruction describes the NEXT turn (at the end of this
                // step), which will be announced later via PRE_TURN / TURN_NOW.
                "Continue straight for $distLeft meters."
        }

        return if (stepIndex == 0)
            "Starting navigation. $ahead"
        else {
            val turnDone = turnNowMessage(stepList[stepIndex - 1].instruction)
            "$turnDone. $ahead"
        }
    }

    /** Display-only label while cruising. Shows direction if step is straight, else generic. */
    private fun showDistanceLabel(step: NavigationStep, distLeft: Int, isFinal: Boolean) {
        txtNavGuide.text = when {
            isFinal -> "Destination — $distLeft m"
            isStraightInstruction(step.instruction) -> "${step.instruction} — $distLeft m"
            else -> "Continue straight — $distLeft m"
        }
    }

    /**
     * Returns true if the GraphHopper instruction is a straight/heading instruction
     * rather than a turn. These are safe to speak as-is after a turn.
     * e.g. "Head northeast", "Continue straight", "Head north on Main St"
     */
    private fun isStraightInstruction(instruction: String): Boolean {
        val s = instruction.lowercase()
        return s.contains("head") || s.contains("continue") || s.contains("straight")
    }

    /** "Continue straight", "Turn left", etc. — capitalised, for display and speech. */
    private fun verboseAction(instruction: String): String {
        val s = instruction.lowercase()
        return when {
            s.contains("arrive") || s.contains("destination") -> "Arriving at destination"
            s.contains("slight left")  -> "Turn slight left"
            s.contains("sharp left")   -> "Turn sharp left"
            s.contains("left")         -> "Turn left"
            s.contains("slight right") -> "Turn slight right"
            s.contains("sharp right")  -> "Turn sharp right"
            s.contains("right")        -> "Turn right"
            s.contains("u-turn")       -> "Make a U-turn"
            s.contains("straight") || s.contains("continue") || s.contains("head") -> "Continue straight"
            else -> instruction.replaceFirstChar { it.uppercase() }
        }
    }

    /** "Approaching a left turn in 13 meters" */
    private fun buildApproachingMessage(instruction: String, distLeft: Int): String {
        val s = instruction.lowercase()
        val what = when {
            s.contains("slight left")  -> "slight left turn"
            s.contains("sharp left")   -> "sharp left turn"
            s.contains("left")         -> "left turn"
            s.contains("slight right") -> "slight right turn"
            s.contains("sharp right")  -> "sharp right turn"
            s.contains("right")        -> "right turn"
            s.contains("u-turn")       -> "U-turn"
            else                       -> "turn"
        }
        return "Approaching a $what in $distLeft meters"
    }

    /** "Turn left now" — also used in ANNOUNCE to confirm the previous step. */
    private fun turnNowMessage(instruction: String): String {
        val s = instruction.lowercase()
        return when {
            s.contains("slight left")  -> "Turn slight left now"
            s.contains("sharp left")   -> "Turn sharp left now"
            s.contains("left")         -> "Turn left now"
            s.contains("slight right") -> "Turn slight right now"
            s.contains("sharp right")  -> "Turn sharp right now"
            s.contains("right")        -> "Turn right now"
            s.contains("u-turn")       -> "Make a U-turn now"
            else                       -> "Continue straight"
        }
    }

    // ── UI + TTS ─────────────────────────────────────────────────────────────
    private fun showAndSpeak(text: String) {
        txtNavGuide.text = text
        speakInstruction(text)
    }

    private fun speakInstruction(text: String) {
        if (::tts.isInitialized) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    private fun distanceTo(from: LatLng, to: LatLng): Int {
        val r = FloatArray(1)
        android.location.Location.distanceBetween(
            from.latitude, from.longitude,
            to.latitude, to.longitude, r
        )
        return r[0].toInt()
    }

    // ════════════════════════════════════════════════════════════════════════
    // Route fetching (GraphHopper)
    // ════════════════════════════════════════════════════════════════════════

    private fun fetchWalkingRoute(origin: LatLng, dest: LatLng) {
        val url = "https://graphhopper.com/api/1/route?" +
                "point=${origin.latitude},${origin.longitude}&" +
                "point=${dest.latitude},${dest.longitude}&" +
                "profile=foot&" +
                "instructions=true&" +
                "points_encoded=true&" +
                "key=${getString(R.string.graph_api_key)}"

        httpClient.newCall(Request.Builder().url(url).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { e.printStackTrace() }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: return
                try {
                    val json  = JSONObject(body)
                    val paths = json.getJSONArray("paths")
                    if (paths.length() == 0) return

                    val path          = paths.getJSONObject(0)
                    val decodedPoints = decodePolyline(path.getString("points"))
                    val instructions  = path.getJSONArray("instructions")

                    stepList.clear()

                    for (i in 0 until instructions.length()) {
                        val inst     = instructions.getJSONObject(i)
                        val interval = inst.getJSONArray("interval")
                        val si       = interval.getInt(0)
                        val ei       = interval.getInt(1)
                        if (si >= decodedPoints.size || ei >= decodedPoints.size) continue

                        stepList.add(
                            NavigationStep(
                                start          = decodedPoints[si],
                                end            = decodedPoints[ei],
                                instruction    = inst.getString("text"),
                                distanceMeters = inst.getDouble("distance").toInt(),
                                path           = decodedPoints.subList(si, ei + 1)
                            )
                        )
                    }

                    currentRoute = RouteData(
                        decodedPoints,
                        "${path.getDouble("distance").toInt()} m",
                        "${path.getLong("time") / 60000} min"
                    )

                    // Reset all nav state
                    currentStepIndex = 0
                    phase            = Phase.ANNOUNCE
                    announceSpoken   = false
                    closestDistSeen  = Int.MAX_VALUE
                    distRisingTicks  = 0

                    runOnUiThread {
                        drawRoute(currentRoute!!)
                        updateMapCamera(decodedPoints)
                        if (stepList.isNotEmpty()) checkStepProgress()
                    }

                } catch (e: Exception) { e.printStackTrace() }
            }
        })
    }

    // ════════════════════════════════════════════════════════════════════════
    // Map drawing
    // ════════════════════════════════════════════════════════════════════════

    private fun drawRoute(route: RouteData) {
        routePolyline?.remove()
        routePolyline = googleMap.addPolyline(
            PolylineOptions()
                .addAll(route.polylinePoints)
                .width(14f)
                .color(0xFF0073E6.toInt())
        )
        findViewById<android.widget.TextView>(R.id.txtDistance).text = route.distanceText
        findViewById<android.widget.TextView>(R.id.txtTime).text     = route.durationText
        txtNavGuide.text = "Route found. Starting navigation."
    }

    private fun updateMapCamera(points: List<LatLng>) {
        if (points.isEmpty()) return
        val b = LatLngBounds.Builder().also { points.forEach(it::include) }
        googleMap.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 150))
    }

    // ════════════════════════════════════════════════════════════════════════
    // Polyline decoder
    // ════════════════════════════════════════════════════════════════════════

    private fun decodePolyline(encoded: String): List<LatLng> {
        val poly = ArrayList<LatLng>()
        var index = 0; var lat = 0; var lng = 0
        while (index < encoded.length) {
            var b: Int; var shift = 0; var result = 0
            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1

            shift = 0; result = 0
            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            lng += if (result and 1 != 0) (result shr 1).inv() else result shr 1

            poly.add(LatLng(lat / 1E5, lng / 1E5))
        }
        return poly
    }
}