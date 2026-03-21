package com.example.pathfinder

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.maps.*
import com.google.android.gms.maps.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

class NavigationActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var mapView: MapView
    private lateinit var googleMap: GoogleMap
    private lateinit var txtNavGuide: TextView

    private var navService: NavigationService? = null
    private var isBound = false
    private var navArrowMarker: Marker? = null
    private var youMarker: Marker? = null
    private var routePolyline: Polyline? = null
    private var currentLocation: LatLng? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) startNavService()
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val nb = binder as NavigationService.NavBinder
            navService = nb.getService()
            isBound    = true

            navService?.setCallback(object : NavigationService.NavCallback {

                override fun onGuideUpdate(text: String, distanceM: Int) {
                    txtNavGuide.text = if (distanceM > 0) "$text — $distanceM m" else text
                    findViewById<TextView>(R.id.txtDistance).text = "$distanceM m"
                }

                override fun onLocationUpdate(loc: LatLng, bearing: Float) {
                    currentLocation = loc
                    if (!::googleMap.isInitialized) return

                    // Tilted 3D camera — follows user with bearing
                    googleMap.animateCamera(
                        CameraUpdateFactory.newCameraPosition(
                            CameraPosition.Builder()
                                .target(loc)
                                .zoom(19f)
                                .tilt(60f)
                                .bearing(bearing)
                                .build()
                        )
                    )

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
                }

                override fun onRouteReady(route: GraphHopperRoute, startLoc: LatLng) {
                    if (!::googleMap.isInitialized) return

                    // Draw route polyline
                    routePolyline?.remove()
                    routePolyline = googleMap.addPolyline(
                        PolylineOptions()
                            .addAll(route.points)
                            .width(14f)
                            .color(0xFF0073E6.toInt())
                    )
                    findViewById<TextView>(R.id.txtDistance).text = "${route.distanceMeters} m"
                    findViewById<TextView>(R.id.txtTime).text     = "${route.durationMs / 60000} min"

                    // Immediately lock camera to tilted 3D nav view at user's location
                    // This fires as soon as route is ready — no waiting for first movement
                    googleMap.animateCamera(
                        CameraUpdateFactory.newCameraPosition(
                            CameraPosition.Builder()
                                .target(startLoc)
                                .zoom(19f)
                                .tilt(60f)
                                .bearing(0f)
                                .build()
                        )
                    )
                }

                override fun onArrived() {
                    Toast.makeText(
                        this@NavigationActivity,
                        "You have arrived!", Toast.LENGTH_LONG
                    ).show()
                    finish()
                }
            })
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            navService = null
            isBound    = false
        }
    }

    // ═════════ Lifecycle ══════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_navigation)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.hide(android.view.WindowInsets.Type.statusBars())
        }

        mapView     = findViewById(R.id.mapView)
        txtNavGuide = findViewById(R.id.txtNavGuide)
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync(this)

        val bottomBar = findViewById<CardView>(R.id.bottomBar)
        ViewCompat.setOnApplyWindowInsetsListener(bottomBar) { view, insets ->
            val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.setPadding(
                view.paddingLeft, view.paddingTop, view.paddingRight,
                navBar.bottom + 16.dpToPx()
            )
            insets
        }

        findViewById<Button>(R.id.btnEnd).setOnClickListener {
            stopNavService()
            finish()
        }

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
                            showLandmarksBottomSheet(
                                context    = this@NavigationActivity,
                                streetName = streetName,
                                landmarks  = landmarkNames,
                                onSpeak    = { navService?.speak(it) },
                                onStop     = { navService?.stopSpeaking() }
                            )
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

        checkPermissionAndStart()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        if (!isBound) bindToService()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
        if (isBound) {
            navService?.setCallback(null)
            unbindService(serviceConnection)
            isBound = false
        }
    }

    override fun onDestroy() {
        mapView.onDestroy()
        super.onDestroy()
    }

    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    // ═════════ Map ════════════════════════════════════════════════════════

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        googleMap.uiSettings.isCompassEnabled = true
        googleMap.mapType = GoogleMap.MAP_TYPE_NORMAL

        val lat = intent.getDoubleExtra("dest_lat", 0.0)
        val lng = intent.getDoubleExtra("dest_lng", 0.0)
        if (lat != 0.0 && lng != 0.0) {
            val dest = LatLng(lat, lng)
            googleMap.addMarker(MarkerOptions().position(dest).title("Destination"))
            googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(dest, 17f))
        }

        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            googleMap.isMyLocationEnabled = false
            googleMap.uiSettings.isMyLocationButtonEnabled = false
        }
    }

    // ═════════ Service management ═════════════════════════════════════════

    private fun checkPermissionAndStart() {
        val needed = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            needed.add(android.Manifest.permission.POST_NOTIFICATIONS)

        val allGranted = needed.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) startNavService()
        else permissionLauncher.launch(needed.toTypedArray())
    }

    private fun startNavService() {
        val lat = intent.getDoubleExtra("dest_lat", 0.0)
        val lng = intent.getDoubleExtra("dest_lng", 0.0)
        val si  = Intent(this, NavigationService::class.java).apply {
            putExtra(NavigationService.EXTRA_DEST_LAT, lat)
            putExtra(NavigationService.EXTRA_DEST_LNG, lng)
            putExtra(NavigationService.EXTRA_API_KEY, getString(R.string.graph_api_key))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(si)
        else startService(si)
        bindToService()
    }

    private fun bindToService() {
        bindService(
            Intent(this, NavigationService::class.java),
            serviceConnection, BIND_AUTO_CREATE
        )
    }

    private fun stopNavService() {
        if (isBound) {
            navService?.setCallback(null)
            unbindService(serviceConnection)
            isBound = false
        }
        stopService(Intent(this, NavigationService::class.java))
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

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()
}