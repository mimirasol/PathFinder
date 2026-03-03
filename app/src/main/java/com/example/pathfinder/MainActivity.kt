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
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doOnTextChanged
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
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.URL
import java.util.*
import android.widget.LinearLayout
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.lifecycleScope

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var mapView: MapView
    private lateinit var googleMap: GoogleMap
    private lateinit var tts: TextToSpeech
    private lateinit var placesClient: PlacesClient
    private lateinit var txtDestination: MaterialAutoCompleteTextView
    private var destinationMarker: Marker? = null

    private var destinationLatLng: LatLng? = null
    private var currentLocation: LatLng? = null
    private var hasCenteredMap = false
    private var currentPolyline: Polyline? = null
    private var youMarker: Marker? = null

    companion object {
        private const val LOCATION_PERMISSION_REQUEST = 1000
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        mapView = findViewById(R.id.mapView)
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync(this)

        if (!Places.isInitialized()) {
            Places.initialize(applicationContext, getString(R.string.api_key))
        }
        placesClient = Places.createClient(this)

        txtDestination = findViewById(R.id.txtDestination)
        setupDestinationAutocomplete()


        tts = TextToSpeech(this) {
            if (it == TextToSpeech.SUCCESS) {
                tts.language = Locale.US
                tts.setSpeechRate(0.9f)
            }
        }

        val bottomSheet = findViewById<LinearLayout>(R.id.bottomSheet)
        val behavior = BottomSheetBehavior.from(bottomSheet)
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        behavior.isDraggable = false
        behavior.skipCollapsed = true

        val btnWhereAmI = findViewById<Button>(R.id.btnWhereAmI)

        btnWhereAmI.setOnClickListener {
            currentLocation?.let { current ->
                // 1. Center camera immediately (Main Thread)
                googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(current, 17f))

                // 2. Update the "You" marker
                youMarker?.remove()
                youMarker = googleMap.addMarker(MarkerOptions().position(current).title("You"))

                // 3. Move Network Work to Background Thread
                lifecycleScope.launch(Dispatchers.IO) {
                    val placesUrl =
                        "https://maps.googleapis.com/maps/api/place/nearbysearch/json?" +
                                "location=${current.latitude},${current.longitude}" +
                                "&radius=50" +
                                "&type=point_of_interest" +
                                "&key=${getString(R.string.api_key)}"

                    try {
                        val json = URL(placesUrl).readText()
                        val jsonObj = JSONObject(json)
                        val results = jsonObj.getJSONArray("results")
                        val landmarkNames = ArrayList<String>()

                        // Limit loop to avoid index out of bounds if fewer than 5 results exist
                        val count = if (results.length() < 5) results.length() else 5

                        for (i in 0 until count) {
                            val place = results.getJSONObject(i)
                            val name = place.getString("name")
                            val geometry = place.getJSONObject("geometry")
                            val loc = geometry.getJSONObject("location")
                            val lat = loc.getDouble("lat")
                            val lng = loc.getDouble("lng")

                            val resultsArray = FloatArray(1)
                            Location.distanceBetween(
                                current.latitude, current.longitude,
                                lat, lng,
                                resultsArray
                            )
                            val distanceMeters = resultsArray[0].toInt()
                            withContext(Dispatchers.Main) {
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

                        withContext(Dispatchers.Main) {
                            if (landmarkNames.isNotEmpty()) {
                                val adapter = object : ArrayAdapter<String>(
                                    this@MainActivity,
                                    android.R.layout.simple_list_item_1,
                                    landmarkNames
                                ) {
                                    override fun getView(
                                        position: Int,
                                        convertView: View?,
                                        parent: ViewGroup
                                    ): View {
                                        val view =
                                            super.getView(position, convertView, parent) as TextView
                                        view.typeface = ResourcesCompat.getFont(
                                            this@MainActivity,
                                            R.font.poppins
                                        )
                                        view.textSize = 18f
                                        view.gravity = Gravity.CENTER
                                        view.textAlignment = View.TEXT_ALIGNMENT_CENTER
                                        view.setTextColor(Color.BLACK)
                                        return view
                                    }
                                }

                                val dialog =
                                    com.google.android.material.dialog.MaterialAlertDialogBuilder(
                                        this@MainActivity
                                    )
                                        .setTitle("You are within")
                                        .setAdapter(adapter, null)
                                        .setPositiveButton("Close", null)
                                        .setBackground(
                                            ContextCompat.getDrawable(
                                                this@MainActivity,
                                                R.drawable.white_morerounded_corners
                                            )
                                        )
                                        .create()

                                dialog.show()

                                val textViewId = dialog.context.resources.getIdentifier(
                                    "alertTitle",
                                    "id",
                                    "android"
                                )
                                val titleTextView =
                                    dialog.findViewById<android.widget.TextView>(textViewId)

                                titleTextView?.apply {
                                    val font = ResourcesCompat.getFont(
                                        this@MainActivity,
                                        R.font.poppins_extrabold
                                    )

                                    setTextColor(Color.BLACK)
                                    textSize = 30f

                                    (this.parent as? android.view.View)?.let { parentView ->
                                        if (parentView is android.widget.LinearLayout) {
                                            parentView.gravity = Gravity.CENTER
                                        }
                                    }
                                }

                                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                                    .apply {
                                        val parentPanel = this.parent as? android.view.ViewGroup
                                        if (parentPanel is android.widget.LinearLayout) {
                                            parentPanel.gravity = Gravity.CENTER
                                            for (i in 0 until parentPanel.childCount) {
                                                val child = parentPanel.getChildAt(i)
                                                if (child is android.widget.Space) child.visibility =
                                                    View.GONE
                                            }
                                        }

                                        this.typeface = ResourcesCompat.getFont(
                                            this@MainActivity,
                                            R.font.poppins_bold
                                        )
                                        this.setTextColor(Color.parseColor("#9b8bf4"))
                                        this.textSize = 16f
                                    }

                                dialog.window?.setElevation(30f)

                                val toSpeak = landmarkNames.joinToString(
                                    separator = ", ",
                                    prefix = "You are within"
                                )
                                tts.speak(
                                    toSpeak,
                                    TextToSpeech.QUEUE_FLUSH,
                                    null,
                                    "LANDMARKS_UTTERANCE"
                                )

                                dialog.setOnDismissListener { tts.stop() }
                            } else {
                                Toast.makeText(
                                    this@MainActivity,
                                    "No landmarks found nearby",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }

                    } catch (e: Exception) {
                        e.printStackTrace()
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                this@MainActivity,
                                "Error fetching landmarks",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
        }

        val startBtn = findViewById<Button>(R.id.btnStart)

        startBtn.setOnClickListener {
            destinationLatLng?.let { dest ->
                val intent = Intent(this, NavigationActivity::class.java)
                intent.putExtra("dest_lat", dest.latitude)
                intent.putExtra("dest_lng", dest.longitude)
                startActivity(intent)
            }
        }
    }
    // ----------------- FUNCTIONS --------------------------

    private fun setupDestinationAutocomplete() {
        // Listen for input changes
        txtDestination.doOnTextChanged { text, _, _, _ ->
            val query = text.toString()
            if (query.length < 3) return@doOnTextChanged
            fetchAutocomplete(query)
        }

        // Handle dropdown selection
        txtDestination.setOnItemClickListener { parent, _, position, _ ->
            val selectedText = parent.getItemAtPosition(position) as String
            selectAutocomplete(selectedText)
        }

        // Optional: press Enter selects first suggestion
        txtDestination.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                val firstSuggestion = txtDestination.adapter.getItem(0) as? String
                firstSuggestion?.let { selectAutocomplete(it) }
            }
            true
        }
    }

    private fun fetchAutocomplete(query: String) {
        val request = FindAutocompletePredictionsRequest.builder()
            .setQuery(query)
            .build()

        placesClient.findAutocompletePredictions(request)
            .addOnSuccessListener { response ->
                val suggestions =
                    response.autocompletePredictions.map { it.getFullText(null).toString() }
                val adapter =
                    ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, suggestions)
                txtDestination.setAdapter(adapter)
                txtDestination.showDropDown()
            }
            .addOnFailureListener { it.printStackTrace() }
    }

    private fun selectAutocomplete(selectedText: String) {
        val predictionRequest = FindAutocompletePredictionsRequest.builder()
            .setQuery(selectedText)
            .build()

        placesClient.findAutocompletePredictions(predictionRequest)
            .addOnSuccessListener { response ->
                if (response.autocompletePredictions.isEmpty()) return@addOnSuccessListener
                val placeId = response.autocompletePredictions[0].placeId
                val fetchPlaceRequest = FetchPlaceRequest.builder(
                    placeId,
                    listOf(
                        Place.Field.ID,
                        Place.Field.DISPLAY_NAME,
                        Place.Field.LOCATION
                    )
                ).build()

                placesClient.fetchPlace(fetchPlaceRequest)
                    .addOnSuccessListener { placeResponse ->
                        val place = placeResponse.place
                        val name = place.displayName
                        val latLng = place.location

                        destinationLatLng = latLng
                        destinationLatLng?.let { dest ->
                            // 1. Remove the previous marker if it exists
                            destinationMarker?.remove()

                            // 2. Add the new marker and store it in the variable
                            destinationMarker = googleMap.addMarker(
                                MarkerOptions()
                                    .position(dest)
                                    .title(name)
                                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                            )

                            // 3. Draw the route
                            currentLocation?.let { drawRoute(it, dest) }

                            // 4. Update Camera
                            val bounds = LatLngBounds.builder()
                            currentLocation?.let { bounds.include(it) }
                            bounds.include(dest)
                            googleMap.animateCamera(
                                CameraUpdateFactory.newLatLngBounds(bounds.build(), 150)
                            )

                            // 5. TTS
                            tts.speak(
                                "Navigating to ${name}",
                                TextToSpeech.QUEUE_FLUSH,
                                null,
                                "DESTINATION"
                            )
                        }
                    }
                    .addOnFailureListener { it.printStackTrace() }
            }
            .addOnFailureListener { it.printStackTrace() }
    }

    //
    override fun onMapReady(map: GoogleMap) {
        googleMap = map

        // Enable location
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            googleMap.isMyLocationEnabled = true
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST
            )
        }

        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            10000L
        ).setMinUpdateIntervalMillis(5000L).build()

        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val location = locationResult.lastLocation ?: return
                val latLng = LatLng(location.latitude, location.longitude)
                currentLocation = latLng

                if (!hasCenteredMap) {
                    googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, 17f))
                    hasCenteredMap = true

                    youMarker = googleMap.addMarker(
                        MarkerOptions().position(latLng).title("You")
                    )
                }
            }
        }

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )
    }

    // draws the line between current location and destination
    private fun drawRoute(origin: LatLng, destination: LatLng) {
        val url =
            "https://maps.googleapis.com/maps/api/directions/json?" +
                    "origin=${origin.latitude},${origin.longitude}" +
                    "&destination=${destination.latitude},${destination.longitude}" +
                    "&mode=walking" +
                    "&key=${getString(R.string.api_key)}"

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val json = URL(url).readText()
                val jsonObj = JSONObject(json)
                val routes = jsonObj.getJSONArray("routes")
                if (routes.length() > 0) {
                    val points = routes.getJSONObject(0)
                        .getJSONObject("overview_polyline")
                        .getString("points")
                    val decodedPoints = decodePolyline(points)
                    withContext(Dispatchers.Main) {
                        currentPolyline?.remove()
                        currentPolyline = googleMap.addPolyline(
                            PolylineOptions().addAll(decodedPoints).color(Color.BLUE).width(10f)
                        )
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // direction line
    private fun decodePolyline(encoded: String): List<LatLng> {
        val poly = ArrayList<LatLng>()
        var index = 0
        var lat = 0
        var lng = 0

        while (index < encoded.length) {
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

    // Lifecycle
    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause() { super.onPause(); mapView.onPause() }
    override fun onDestroy() { super.onDestroy(); mapView.onDestroy() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }
}