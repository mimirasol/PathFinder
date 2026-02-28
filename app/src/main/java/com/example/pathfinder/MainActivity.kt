package com.example.pathfinder

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Geocoder
import android.location.Location
import android.os.Bundle
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import android.widget.LinearLayout
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.gms.location.*
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.model.*
import com.google.android.material.bottomsheet.BottomSheetBehavior
import org.json.JSONObject
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity(), OnMapReadyCallback {
    private lateinit var mapView: MapView
    private lateinit var googleMap: GoogleMap
    private lateinit var etDestination: EditText
    private lateinit var btnSearch: Button
    private lateinit var tts: TextToSpeech

    private var destinationLatLng: LatLng? = null
    private var currentLocation: LatLng? = null
    private var hasCenteredMap = false
    private var currentPolyline: Polyline? = null
    private var youMarker: Marker? = null


    // used to identify which permission request is being made (e.g., camera, storage, location)
    // in this case, it's for location
    companion object {
        private const val LOCATION_PERMISSION_REQUEST = 1000
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        // sets padding to avoid system bars overlapping the content
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        mapView = findViewById(R.id.mapView)
        mapView.onCreate(savedInstanceState) // creates map view
        mapView.getMapAsync(this) // gets the map ready to be used
        etDestination = findViewById(R.id.etDestination)
        btnSearch = findViewById(R.id.btnSearch)
        btnSearch.isEnabled = false // disable search until current location is ready

        btnSearch.setOnClickListener {
            val query = etDestination.text.toString()
            if (query.isNotEmpty() && currentLocation != null) { // only search if location is ready
                searchLocation(query)// search for location using Geocoder
            }
        }

        // ---- Where Am I feature ----
        val btnWhereAmI = findViewById<Button>(R.id.btnWhereAmI)

        btnWhereAmI.setOnClickListener {
            currentLocation?.let { current -> //checks if location is ready
                // Center camera on the user
                googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(current, 17f))

                // Update the "You" marker
                youMarker?.remove()
                youMarker = googleMap.addMarker(MarkerOptions().position(current).title("You"))

                // Fetch nearby landmarks dynamically
                val placesUrl =
                    "https://maps.googleapis.com/maps/api/place/nearbysearch/json?" +
                            "location=${current.latitude},${current.longitude}" +
                            "&radius=50" +
                            "&type=point_of_interest" +
                            "&key=AIzaSyBVFzL3QYBsPplfc3vSddoGzv9zeeq0NzM"

                thread {
                    try {
                        val json = URL(placesUrl).readText()
                        val jsonObj = JSONObject(json)
                        val results = jsonObj.getJSONArray("results")
                        val landmarkNames = ArrayList<String>()

                        for (i in 0 until results.length()) {
                            val place = results.getJSONObject(i)
                            val name = place.getString("name")
                            val loc = place.getJSONObject("geometry").getJSONObject("location")
                            val lat = loc.getDouble("lat")
                            val lng = loc.getDouble("lng")

                            val resultsArray = FloatArray(1)
                            Location.distanceBetween(
                                currentLocation!!.latitude,
                                currentLocation!!.longitude, // user's current location
                                lat,
                                lng,                                                // landmark location
                                resultsArray                                             // output
                            )
                            val distanceMeters = resultsArray[0].toInt() // distance in meters

                            // add markers for each landmark
                            runOnUiThread {
                                googleMap.addMarker(
                                    MarkerOptions()
                                        .position(LatLng(lat, lng))
                                        .title(name)
                                        .icon(
                                            BitmapDescriptorFactory.defaultMarker(
                                                BitmapDescriptorFactory.HUE_AZURE
                                            )
                                        )
                                )
                            }

                            landmarkNames.add("$distanceMeters m - $name")
                        }

                        // Show a popup with landmark names
                        if (landmarkNames.isNotEmpty()) {
                            runOnUiThread {
                                val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
                                    .setTitle("You are within")
                                    .setItems(landmarkNames.toTypedArray(), null)
                                    .setPositiveButton("Close", null)
                                    .create()
                                dialog.show()

                                // Combine all landmark names into a single string
                                val toSpeak = landmarkNames.joinToString(separator = ", ", prefix = "You are within")

                                // Speak the landmarks
                                tts.speak(
                                    toSpeak,
                                    TextToSpeech.QUEUE_FLUSH,
                                    null,
                                    "LANDMARKS_UTTERANCE"
                                )

                                dialog.setCanceledOnTouchOutside(true)
                                dialog.setOnDismissListener {
                                    tts.stop()
                                }
                            }
                        } else {
                            runOnUiThread {
                                Toast.makeText(
                                    this,
                                    "No landmarks found nearby",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        runOnUiThread {
                            Toast.makeText(this, "Error fetching landmarks", Toast.LENGTH_SHORT)
                                .show()
                        }
                    }
                }
            }
        }

        // ---- TTS ----
        tts = TextToSpeech(this) { status -> // initialize TTS
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.US // set language to English
                tts.setSpeechRate(0.9f) //
            } else {
                Toast.makeText(this, "TTS initialization failed", Toast.LENGTH_SHORT).show()
            }
        }

        // ---- bottom sheeet ----
        val bottomSheet = findViewById<LinearLayout>(R.id.bottomSheet)
        val behavior = BottomSheetBehavior.from(bottomSheet)

        behavior.peekHeight = 200
        behavior.state = BottomSheetBehavior.STATE_HALF_EXPANDED
    }

    // when the map is ready to be used, calls getMapAsync(this)
    override fun onMapReady(map: GoogleMap) {
        googleMap = map

        // check if user has granted location permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            googleMap.isMyLocationEnabled = true // enable MyLocation if permission granted
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST
            )
        }

        // gets location by choosing GPS, Wi-Fi, or cell towers
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // defines how frequently and how accurate location updates should be
        val locationRequest = LocationRequest.create().apply {
            interval = 10000L // 10 seconds
            fastestInterval = 5000L // optional, fastest update
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }

        // user location update
        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) { // every time new location data is available
                val location = locationResult.lastLocation // latest location
                location?.let {
                    val latLng = LatLng(it.latitude, it.longitude)
                    currentLocation = latLng // important, save current location

                    if (!hasCenteredMap) { // center map only once
                        googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, 17f))
                        hasCenteredMap = true
                        btnSearch.isEnabled = true // enable search now that location is ready
                        youMarker = googleMap.addMarker(
                            MarkerOptions().position(latLng).title("You")
                        ) // add marker for "You"
                    }
                }
            }
        }

        // start location updates
        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )
    }

    // map lifecycle methods
    override fun onResume() {
        super.onResume(); mapView.onResume()
    }

    override fun onPause() {
        super.onPause(); mapView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy(); mapView.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory(); mapView.onLowMemory()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState); mapView.onSaveInstanceState(outState)
    }



    // after the user responds to the permission request, reloads the map so location is enabled
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST && // checks if the permission request is for location
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) { // if user granted permission
            mapView.getMapAsync(this) // reload the map with location enabled
        }
    }



    // search for location using Geocoder
    private fun searchLocation(query: String) {
        val geocoder = Geocoder(this)
        val addressList = geocoder.getFromLocationName(query, 1) // gets the first result
        try {
            if (!addressList.isNullOrEmpty()) { // checks if address is not null or empty
                val address = addressList[0]
                destinationLatLng = LatLng(address.latitude, address.longitude)

                // add destination marker
                googleMap.addMarker(MarkerOptions().position(destinationLatLng!!).title(query))

                // draw route if current location is available
                currentLocation?.let { origin ->
                    drawRoute(origin, destinationLatLng!!)
                }

                // adjust camera to fit both points
                val bounds = LatLngBounds.builder()
                currentLocation?.let { bounds.include(it) }
                bounds.include(destinationLatLng!!)
                googleMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 150))
            } else {
                Toast.makeText(this, "Location not found", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error fetching location: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // decode polyline string from Google Directions API into list of LatLng points
    private fun decodePolyline(encoded: String): List<LatLng> {
        val poly = ArrayList<LatLng>()
        var index = 0
        val len = encoded.length
        var lat = 0
        var lng = 0

        while (index < len) {
            var b: Int
            var shift = 0
            var result = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlat = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lat += dlat

            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlng = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lng += dlng

            poly.add(LatLng(lat / 1E5, lng / 1E5))
        }

        return poly
    }

    // draw route between origin and destination using Directions API
    private fun drawRoute(origin: LatLng, destination: LatLng) {
        val url = "https://maps.googleapis.com/maps/api/directions/json?" +
                "origin=${origin.latitude},${origin.longitude}" +
                "&destination=${destination.latitude},${destination.longitude}" +
                "&mode=walking" +
                "&key=AIzaSyBVFzL3QYBsPplfc3vSddoGzv9zeeq0NzM"

        // run HTTP request asynchronously
        thread {
            try {
                val json = URL(url).readText()
                val jsonObj = JSONObject(json)
                val routes = jsonObj.getJSONArray("routes")
                if (routes.length() > 0) {
                    val points = routes.getJSONObject(0)
                        .getJSONObject("overview_polyline")
                        .getString("points")
                    val decodedPoints = decodePolyline(points)

                    runOnUiThread {
                        // remove previous polyline only
                        currentPolyline?.remove()

                        // draw new polyline
                        currentPolyline = googleMap.addPolyline(
                            PolylineOptions()
                                .addAll(decodedPoints)
                                .color(Color.BLUE)
                                .width(10f)
                        )
                    }
                } else {
                    println("No routes found: $json")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}