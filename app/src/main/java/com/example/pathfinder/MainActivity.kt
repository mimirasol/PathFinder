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
import androidx.core.content.res.ResourcesCompat
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
    private var pendingRouteDestination: LatLng? = null
    private var currentLocation: LatLng? = null
    private var hasCenteredMap = false

    companion object {
        private const val LOCATION_PERMISSION_REQUEST = 1000
    }

    // ═════════ Lifecycle ════════════════════════════════════
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

        // Show/hide X button based on whether there is text
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
            state           = BottomSheetBehavior.STATE_COLLAPSED
            isDraggable     = true
            isFitToContents = true
            isHideable      = false
        }

        // Apply bottom inset so the sheet content isn't hidden behind the nav bar
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

        // ─── Where Am I ───────────────────────────────────────────────────
        findViewById<Button>(R.id.btnWhereAmI).setOnClickListener {
            val current = currentLocation ?: return@setOnClickListener

            googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(current, 17f))
            youMarker?.remove()
            youMarker = googleMap.addMarker(MarkerOptions().position(current).title("You"))

            lifecycleScope.launch(Dispatchers.IO) {
                var streetName = "your current location"

                // Reverse geocoding
                try {
                    val geoUrl =
                        "https://maps.googleapis.com/maps/api/geocode/json?" +
                                "latlng=${current.latitude},${current.longitude}" +
                                "&key=${getString(R.string.api_key)}"
                    val geoObj  = JSONObject(URL(geoUrl).readText())
                    val results = geoObj.getJSONArray("results")
                    if (results.length() > 0) {
                        val components = results.getJSONObject(0).getJSONArray("address_components")
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

                // Nearby landmarks
                try {
                    val placesUrl =
                        "https://maps.googleapis.com/maps/api/place/nearbysearch/json?" +
                                "location=${current.latitude},${current.longitude}" +
                                "&radius=50&type=point_of_interest" +
                                "&key=${getString(R.string.api_key)}"
                    val jsonObj     = JSONObject(URL(placesUrl).readText())
                    val results     = jsonObj.getJSONArray("results")
                    val landmarkNames = ArrayList<String>()

                    for (i in 0 until minOf(results.length(), 5)) {
                        val place = results.getJSONObject(i)
                        val name  = place.getString("name")
                        val loc   = place.getJSONObject("geometry").getJSONObject("location")
                        val lat   = loc.getDouble("lat")
                        val lng   = loc.getDouble("lng")
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
                        if (landmarkNames.isNotEmpty()) {
                            showLandmarksDialog(streetName, landmarkNames)
                        } else {
                            Toast.makeText(this@MainActivity,
                                "No landmarks found nearby", Toast.LENGTH_SHORT).show()
                        }
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

        // ─── SOS ──────────────────────────────────────────────────────────
        findViewById<Button>(R.id.btnSOS).setOnClickListener {
            startActivity(Intent(this, SosActivity::class.java))
        }

        // ─── Companion ────────────────────────────────────────────────────
        findViewById<Button>(R.id.btnCompanion).setOnClickListener {
            startActivity(Intent(this, CompanionActivity::class.java))
        }

        // ─── Logout ───────────────────────────────────────────────────────
        findViewById<Button>(R.id.btnLogout).setOnClickListener {
            FirebaseAuth.getInstance().signOut()
            startActivity(Intent(this, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
            finish()
        }

        // ─── Start Navigation ─────────────────────────────────────────────
        findViewById<Button>(R.id.btnStart).setOnClickListener {
            val dest = destinationLatLng ?: run {
                Toast.makeText(this, "Please select a destination first.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startActivity(
                Intent(this, NavigationActivity::class.java).apply {
                    putExtra("dest_lat", dest.latitude)
                    putExtra("dest_lng", dest.longitude)
                }
            )
        }
    }

    // ═════════ Autocomplete ═══════════════════════════════════════════════
    private fun setupDestinationAutocomplete() {
        txtDestination.doOnTextChanged { text, _, _, _ ->
            val query = text.toString()
            if (query.length >= 3) fetchAutocomplete(query)
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
                val adapter = ArrayAdapter(this, R.layout.item_autocomplete, suggestions)
                txtDestination.setAdapter(adapter)
                txtDestination.showDropDown()
            }
            .addOnFailureListener { it.printStackTrace() }
    }

    private fun selectAutocomplete(selectedText: String) {
        val predictionRequest =
            FindAutocompletePredictionsRequest.builder().setQuery(selectedText).build()

        placesClient.findAutocompletePredictions(predictionRequest)
            .addOnSuccessListener { response ->
                val prediction = response.autocompletePredictions.firstOrNull() ?: return@addOnSuccessListener
                val fetchRequest = FetchPlaceRequest.builder(
                    prediction.placeId,
                    listOf(Place.Field.ID, Place.Field.DISPLAY_NAME, Place.Field.LOCATION)
                ).build()

                placesClient.fetchPlace(fetchRequest)
                    .addOnSuccessListener { placeResponse ->
                        val place  = placeResponse.place
                        val name   = place.displayName ?: "Destination"
                        val latLng = place.location ?: return@addOnSuccessListener

                        destinationLatLng = latLng

                        // Remove old polyline before drawing new one
                        currentPolyline?.remove()
                        currentPolyline = null

                        // Update destination marker
                        destinationMarker?.remove()
                        destinationMarker = googleMap.addMarker(
                            MarkerOptions()
                                .position(latLng)
                                .title(name)
                                .icon(BitmapDescriptorFactory.defaultMarker(
                                    BitmapDescriptorFactory.HUE_RED))
                        )

                        // Draw preview route — if location not ready yet,
                        // it will draw when location first arrives in locationCallback
                        currentLocation?.let { drawRoute(it, latLng) }
                        // Store destination so locationCallback can draw it when ready
                        pendingRouteDestination = latLng

                        // Fit camera to show both current location and destination
                        val boundsBuilder = LatLngBounds.builder().include(latLng)
                        currentLocation?.let { boundsBuilder.include(it) }
                        googleMap.animateCamera(
                            CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), 150)
                        )

                        tts.speak(
                            "Navigating to $name",
                            TextToSpeech.QUEUE_FLUSH, null, "DESTINATION"
                        )
                    }
                    .addOnFailureListener { it.printStackTrace() }
            }
            .addOnFailureListener { it.printStackTrace() }
    }

    // ═════════ Map ════════════════════════════════════════════════════════
    override fun onMapReady(map: GoogleMap) {
        googleMap = map

        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
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
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000L)
            .setMinUpdateIntervalMillis(5000L)
            .build()

        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                val latLng   = LatLng(location.latitude, location.longitude)
                currentLocation = latLng

                if (!hasCenteredMap) {
                    googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, 17f))
                    hasCenteredMap = true
                    youMarker?.remove()
                    youMarker = googleMap.addMarker(
                        MarkerOptions().position(latLng).title("You")
                    )
                }

                // Draw route if destination was selected before location was ready
                pendingRouteDestination?.let { dest ->
                    pendingRouteDestination = null
                    if (currentPolyline == null) drawRoute(latLng, dest)
                }
            }
        }

        fusedLocationClient.requestLocationUpdates(
            locationRequest, locationCallback, Looper.getMainLooper()
        )
    }

    // ═════════ Route preview ══════════════════════════════════════════════
    private fun drawRoute(origin: LatLng, destination: LatLng) {
        // Guard — don't attempt if map isn't ready yet
        if (!::googleMap.isInitialized) return

        lifecycleScope.launch(Dispatchers.IO) {
            val route = graphHopperService.getRoute(
                origin.latitude,      origin.longitude,
                destination.latitude, destination.longitude
            )
            withContext(Dispatchers.Main) {
                if (!::googleMap.isInitialized) return@withContext
                if (route != null && route.points.isNotEmpty()) {
                    currentPolyline?.remove()
                    currentPolyline = googleMap.addPolyline(
                        PolylineOptions()
                            .addAll(route.points)
                            .color(Color.BLUE)
                            .width(10f)
                    )
                } else {
                    Toast.makeText(this@MainActivity, "Route not found", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ═════════ Landmarks dialog ═══════════════════════════════════════════
    private fun showLandmarksDialog(streetName: String, landmarkNames: List<String>) {
        val adapter = object : ArrayAdapter<String>(
            this,
            android.R.layout.simple_list_item_1,
            landmarkNames
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent) as TextView
                view.typeface  = ResourcesCompat.getFont(this@MainActivity, R.font.poppins)
                view.textSize  = 18f
                view.gravity   = Gravity.CENTER
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
            typeface = ResourcesCompat.getFont(this@MainActivity, R.font.poppins_extrabold)
            setTextColor(Color.BLACK)
            textSize = 30f
            (parent as? LinearLayout)?.gravity = Gravity.CENTER
        }

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
            typeface = ResourcesCompat.getFont(this@MainActivity, R.font.poppins_bold)
            setTextColor(Color.parseColor("#9b8bf4"))
            textSize = 16f
        }

        dialog.window?.setElevation(30f)

        tts.speak(
            landmarkNames.joinToString(separator = ", ",
                prefix = "You are on $streetName. Nearby landmarks are "),
            TextToSpeech.QUEUE_FLUSH, null, "LANDMARKS_UTTERANCE"
        )
        dialog.setOnDismissListener { tts.stop() }
    }

    // ═════════ Lifecycle ══════════════════════════════════════════════════
    override fun onResume()    { super.onResume();    mapView.onResume()  }
    override fun onPause()     { super.onPause();     mapView.onPause()   }
    override fun onDestroy()   { super.onDestroy();   mapView.onDestroy() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }
}