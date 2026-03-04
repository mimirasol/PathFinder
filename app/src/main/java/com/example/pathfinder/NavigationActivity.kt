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
import kotlin.math.*

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
    private var lastSpokenStepIndex = -1
    private val preTurnDistanceMeters = 20f
    private val preTurnAlertGivenSteps = mutableSetOf<Int>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) {
            startLocationUpdates()
        }
    }

    data class RouteData(val polylinePoints: List<LatLng>, val distanceText: String, val durationText: String)
    data class NavigationStep(val start: LatLng, val end: LatLng, val instruction: String, val distanceText: String, val path: List<LatLng>)

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

    private fun checkLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates()
        } else {
            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

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

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            googleMap.isMyLocationEnabled = true
        }
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
                    if (currentStepIndex < stepList.size) checkStepProgress()
                }
            }
        }
    }

    private fun calculateTurnType(prevStep: NavigationStep?, currentStep: NavigationStep): String {
        if (prevStep == null) return "straight"

        val prevEndPoints = prevStep.path.takeLast(2)
        val currentStartPoints = currentStep.path.take(2)

        if (prevEndPoints.size < 2 || currentStartPoints.size < 2) return "straight"

        val startBearing = getBearing(prevEndPoints[0], prevEndPoints[1])
        val endBearing = getBearing(currentStartPoints[0], currentStartPoints[1])
        var angleDiff = (endBearing - startBearing + 360) % 360
        if (angleDiff > 180) angleDiff -= 360 // normalize to -180..180

        return when {
            angleDiff in 10.0..45.0 -> "slight right"
            angleDiff in 45.0..180.0 -> "right"
            angleDiff in -45.0..-10.0 -> "slight left"
            angleDiff in -180.0..-45.0 -> "left"
            else -> "straight"
        }
    }

    private fun getBearing(start: LatLng, end: LatLng): Double {
        val lat1 = Math.toRadians(start.latitude)
        val lon1 = Math.toRadians(start.longitude)
        val lat2 = Math.toRadians(end.latitude)
        val lon2 = Math.toRadians(end.longitude)
        val y = sin(lon2 - lon1) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(lon2 - lon1)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    private fun checkStepProgress() {
        if (currentStepIndex >= stepList.size || currentLocation == null) return

        val step = stepList[currentStepIndex]
        val results = FloatArray(1)
        android.location.Location.distanceBetween(
            currentLocation!!.latitude,
            currentLocation!!.longitude,
            step.end.latitude,
            step.end.longitude,
            results
        )
        val distanceMeters = results[0].toInt()
        val isFinalStep = currentStepIndex == stepList.size - 1

        // Determine turn direction
        val prevStep = if (currentStepIndex > 0) stepList[currentStepIndex - 1] else null
        val direction = calculateTurnType(prevStep, step)

        // --- Pre-turn alert ---
        if (!isFinalStep &&
            distanceMeters in 9..preTurnDistanceMeters.toInt() &&
            !preTurnAlertGivenSteps.contains(currentStepIndex)
        ) {
            val preAlert = "Approaching a $direction turn in $distanceMeters meters"
            txtNavGuide.text = preAlert
            speakInstruction(preAlert)
            preTurnAlertGivenSteps.add(currentStepIndex)
        }

        // --- Main instruction at turn ---
        if (distanceMeters <= 8) {
            if (!isFinalStep) {
                val instructionText = "${step.instruction} in $distanceMeters meters"
                txtNavGuide.text = instructionText
                speakInstruction(instructionText)
            } else {
                txtNavGuide.text = "You have arrived!"
                speakInstruction("You have arrived at your destination")
            }

            // Move to next step
            currentStepIndex++
        }
    }

    private fun setupTextToSpeech() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) tts.language = Locale.US
        }
    }

    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L).build()
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
    }

    private fun stopLocationUpdates() {
        if (::fusedLocationClient.isInitialized) fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    private fun fetchWalkingRoute(origin: LatLng, dest: LatLng) {
        val url = "https://maps.googleapis.com/maps/api/directions/json?" +
                "origin=${origin.latitude},${origin.longitude}" +
                "&destination=${dest.latitude},${dest.longitude}" +
                "&mode=walking" +
                "&avoid=ferries|highways" +
                "&key=${getString(R.string.api_key)}"

        val request = Request.Builder().url(url).build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { e.printStackTrace() }
            override fun onResponse(call: Call, response: Response) {
                val bodyString = response.body?.string() ?: return
                drawRouteFromJson(bodyString)
            }
        })
    }

    private fun drawRouteFromJson(jsonData: String) {
        val json = JSONObject(jsonData)
        val routes = json.getJSONArray("routes")
        if (routes.length() == 0) return

        val route = routes.getJSONObject(0)
        val legs = route.getJSONArray("legs").getJSONObject(0)
        val steps = legs.getJSONArray("steps")

        stepList.clear()
        val fullPath = mutableListOf<LatLng>()

        for (i in 0 until steps.length()) {
            val step = steps.getJSONObject(i)
            val startLocation = step.getJSONObject("start_location")
            val endLocation = step.getJSONObject("end_location")
            val instruction = step.getString("html_instructions")
            val distanceText = step.getJSONObject("distance").getString("text")
            val polylinePoints = step.getJSONObject("polyline").getString("points")
            val path = decodePolyline(polylinePoints)

            stepList.add(
                NavigationStep(
                    start = LatLng(startLocation.getDouble("lat"), startLocation.getDouble("lng")),
                    end = LatLng(endLocation.getDouble("lat"), endLocation.getDouble("lng")),
                    instruction = android.text.Html.fromHtml(instruction, android.text.Html.FROM_HTML_MODE_LEGACY).toString(),
                    distanceText = distanceText,
                    path = path
                )
            )
            fullPath.addAll(path)
        }

        val distanceText = legs.getJSONObject("distance").getString("text")
        val durationText = legs.getJSONObject("duration").getString("text")
        currentRoute = RouteData(fullPath, distanceText, durationText)
        currentStepIndex = 0
        lastSpokenStepIndex = -1

        runOnUiThread {
            drawRoute(currentRoute!!)
            updateMapCamera(fullPath)
            if (stepList.isNotEmpty()) speakNextStepInstruction(force = true)
        }
    }

    private fun speakNextStepInstruction(force: Boolean = false) {
        if (currentStepIndex >= stepList.size) return
        if (!force && currentStepIndex == lastSpokenStepIndex) return

        val step = stepList[currentStepIndex]
        val distanceToEnd = FloatArray(1)
        android.location.Location.distanceBetween(
            currentLocation!!.latitude,
            currentLocation!!.longitude,
            step.end.latitude,
            step.end.longitude,
            distanceToEnd
        )
        val distanceMeters = distanceToEnd[0].toInt()
        val instructionText = "${step.instruction} in $distanceMeters meters"
        txtNavGuide.text = instructionText
        speakInstruction(instructionText)
        lastSpokenStepIndex = currentStepIndex
    }

    private fun speakInstruction(text: String) {
        if (::tts.isInitialized) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    private fun drawRoute(route: RouteData) {
        routePolyline?.remove()
        routePolyline = googleMap.addPolyline(
            PolylineOptions().addAll(route.polylinePoints).width(14f).color(0xFF0073E6.toInt())
        )
        findViewById<android.widget.TextView>(R.id.txtDistance).text = route.distanceText
        findViewById<android.widget.TextView>(R.id.txtTime).text = route.durationText
        txtNavGuide.text = "Start walking"
    }

    private fun updateMapCamera(points: List<LatLng>) {
        if (points.isEmpty()) return
        val boundsBuilder = LatLngBounds.Builder()
        points.forEach { boundsBuilder.include(it) }
        googleMap.animateCamera(CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), 150))
    }

    private fun decodePolyline(encoded: String): List<LatLng> {
        val poly = ArrayList<LatLng>()
        var index = 0
        var lat = 0
        var lng = 0
        while (index < encoded.length) {
            var b: Int
            var shift: Int
            var result: Int

            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlat = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lat += dlat

            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlng = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lng += dlng

            poly.add(LatLng(lat / 1E5, lng / 1E5))
        }
        return poly
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
}