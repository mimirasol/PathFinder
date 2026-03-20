package com.example.pathfinder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.android.gms.location.*
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.*
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

class NavigationService : Service() {

    // ── Binder ────────────────────────────────────────────────────────────
    inner class NavBinder : Binder() {
        fun getService(): NavigationService = this@NavigationService
    }

    interface NavCallback {
        fun onGuideUpdate(text: String, distanceM: Int)
        fun onLocationUpdate(loc: LatLng, bearing: Float)
        fun onRouteReady(route: GraphHopperRoute)
        fun onArrived()
        fun onBearingUpdate(bearing: Float)
    }

    private val binder = NavBinder()
    private var callback: NavCallback? = null
    fun setCallback(cb: NavCallback?) { callback = cb }

    // ── Location ──────────────────────────────────────────────────────────
    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var locationThread: HandlerThread
    private lateinit var locationHandler: Handler
    private val mainHandler = Handler(Looper.getMainLooper())

    // ── Compass sensors ───────────────────────────────────────────────────
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var magnetometer: Sensor? = null
    private val gravity     = FloatArray(3)
    private val geomagnetic = FloatArray(3)
    private var compassBearing = 0f
    private var gpsBearing     = 0f
    private var hasGpsBearing  = false

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER ->
                    System.arraycopy(event.values, 0, gravity, 0, gravity.size)
                Sensor.TYPE_MAGNETIC_FIELD ->
                    System.arraycopy(event.values, 0, geomagnetic, 0, geomagnetic.size)
            }
            val R = FloatArray(9)
            val I = FloatArray(9)
            if (SensorManager.getRotationMatrix(R, I, gravity, geomagnetic)) {
                val orientation = FloatArray(3)
                SensorManager.getOrientation(R, orientation)
                val azimuth = Math.toDegrees(orientation[0].toDouble()).toFloat()
                compassBearing = (azimuth + 360f) % 360f
                // When still, use compass; when moving, GPS bearing is more accurate
                val effectiveBearing = if (hasGpsBearing) gpsBearing else compassBearing
                callback?.onBearingUpdate(effectiveBearing)
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    // ── Nav state ─────────────────────────────────────────────────────────
    private lateinit var tts: TextToSpeech
    private lateinit var vibrator: Vibrator
    private lateinit var graphHopper: GraphHopperService

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var destination: LatLng? = null
    private var currentLocation: LatLng? = null
    private var lastCameraLocation: LatLng? = null
    private var stepList: MutableList<GraphHopperStep> = mutableListOf()
    private var currentStepIndex = 0
    private var routeDrawn = false

    private var currentGuideText = ""
    private var spokenEntry    = false
    private var spokenApproach = false
    private var spokenAtPoint  = false

    private val APPROACH_DISTANCE   = 20
    private val AT_POINT_DISTANCE   = 8
    private val CAMERA_MIN_DISTANCE = 3f

    private val isRerouting = AtomicBoolean(false)
    private var lastRerouteTime   = 0L
    private val REROUTE_COOLDOWN  = 8_000L
    private val REROUTE_THRESHOLD = 18f

    companion object {
        const val CHANNEL_ID      = "navigation_channel"
        const val NOTIFICATION_ID = 202
        const val EXTRA_DEST_LAT  = "dest_lat"
        const val EXTRA_DEST_LNG  = "dest_lng"
        const val EXTRA_API_KEY   = "graph_api_key"
    }

    // ═════════ Lifecycle ══════════════════════════════════════════════════

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) tts.language = Locale.US
        }

        fusedClient = LocationServices.getFusedLocationProviderClient(this)

        locationThread = HandlerThread("NavLocationThread").also { it.start() }
        locationHandler = Handler(locationThread.looper)

        // Compass sensors
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        magnetometer  = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        accelerometer?.let {
            sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
        }
        magnetometer?.let {
            sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val lat    = intent?.getDoubleExtra(EXTRA_DEST_LAT, 0.0) ?: 0.0
        val lng    = intent?.getDoubleExtra(EXTRA_DEST_LNG, 0.0) ?: 0.0
        val apiKey = intent?.getStringExtra(EXTRA_API_KEY) ?: ""

        if (lat != 0.0 && lng != 0.0) {
            destination = LatLng(lat, lng)
            graphHopper = GraphHopperService(apiKey)
        }

        startForeground(NOTIFICATION_ID, buildNotification("Starting navigation…", ""))
        startLocationUpdates()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        serviceScope.cancel()
        if (::fusedClient.isInitialized && ::locationCallback.isInitialized)
            fusedClient.removeLocationUpdates(locationCallback)
        if (::tts.isInitialized) tts.shutdown()
        if (::locationThread.isInitialized) locationThread.quitSafely()
        if (::sensorManager.isInitialized) sensorManager.unregisterListener(sensorListener)
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ═════════ Location ═══════════════════════════════════════════════════

    private fun startLocationUpdates() {
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
            .setMinUpdateIntervalMillis(1000L)
            .setMinUpdateDistanceMeters(2f)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                val newLoc   = LatLng(location.latitude, location.longitude)

                // Update GPS bearing state
                hasGpsBearing = location.hasSpeed() && location.speed > 0.5f
                if (hasGpsBearing) gpsBearing = location.bearing
                val bearing = if (hasGpsBearing) gpsBearing else compassBearing

                // Post all nav logic to main thread
                mainHandler.post {
                    currentLocation = newLoc

                    val shouldNotifyCamera = lastCameraLocation?.let { last ->
                        val dist = FloatArray(1)
                        Location.distanceBetween(
                            last.latitude, last.longitude,
                            newLoc.latitude, newLoc.longitude, dist
                        )
                        dist[0] >= CAMERA_MIN_DISTANCE
                    } ?: true

                    if (shouldNotifyCamera) {
                        lastCameraLocation = newLoc
                        callback?.onLocationUpdate(newLoc, bearing)
                    }

                    if (!routeDrawn && destination != null) {
                        fetchRoute(newLoc, destination!!)
                        routeDrawn = true
                    }

                    if (stepList.isNotEmpty()) checkStepProgress()
                }
            }
        }

        try {
            fusedClient.requestLocationUpdates(req, locationCallback, locationHandler.looper)
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    // ═════════ Route fetching ═════════════════════════════════════════════

    fun fetchRoute(origin: LatLng, dest: LatLng) {
        serviceScope.launch(Dispatchers.IO) {
            val route = graphHopper.getRoute(
                origin.latitude, origin.longitude,
                dest.latitude,   dest.longitude
            )
            withContext(Dispatchers.Main) {
                if (route == null || route.steps.isEmpty()) {
                    sayAndNotify("Could not get route. Check your connection.", 0)
                    isRerouting.set(false)
                    return@withContext
                }
                stepList.clear()
                stepList.addAll(route.steps)
                isRerouting.set(false)
                currentStepIndex   = 0
                spokenEntry        = false
                spokenApproach     = false
                spokenAtPoint      = false
                lastCameraLocation = null
                callback?.onRouteReady(route)
                enterStep(0)
            }
        }
    }

    // ═════════ Step entry ═════════════════════════════════════════════════

    private fun enterStep(index: Int) {
        if (index >= stepList.size) return
        currentStepIndex = index
        spokenEntry      = false
        spokenApproach   = false
        spokenAtPoint    = false

        val step    = stepList[index]
        val isFinal = index == stepList.size - 1

        val entryCue = when {
            index == 0 -> "${step.text} for ${step.distanceMeters} meters."
            isFinal    -> "Continue for ${step.distanceMeters} meters. Your destination is ahead."
            else       -> "Continue straight for ${step.distanceMeters} meters."
        }
        sayAndNotify(entryCue, step.distanceMeters)
        spokenEntry = true
    }

    // ═════════ Navigation tick ════════════════════════════════════════════

    private fun checkStepProgress() {
        val loc = currentLocation ?: return
        if (currentStepIndex >= stepList.size) return

        val step      = stepList[currentStepIndex]
        val distToEnd = distanceTo(loc, step.endPoint)
        val isFinal   = currentStepIndex == stepList.size - 1

        val now = System.currentTimeMillis()
        if (!isRerouting.get() && (now - lastRerouteTime) > REROUTE_COOLDOWN) {
            val locSnapshot = loc
            serviceScope.launch(Dispatchers.Default) {
                if (isOffRoute(locSnapshot)) {
                    withContext(Dispatchers.Main) { triggerReroute(locSnapshot) }
                }
            }
        }

        updateNotification(currentGuideText, distToEnd)
        callback?.onGuideUpdate(currentGuideText, distToEnd)

        if (!spokenApproach && distToEnd <= APPROACH_DISTANCE && distToEnd > AT_POINT_DISTANCE) {
            spokenApproach = true
            val nextStep = stepList.getOrNull(currentStepIndex + 1)
            val cue = when {
                isFinal || nextStep == null -> "Your destination is in $distToEnd meters."
                else -> "In $distToEnd meters, ${nextStep.text.lowercase()}."
            }
            sayAndNotify(cue, distToEnd)
            vibrateApproach()
        }

        if (!spokenAtPoint && distToEnd <= AT_POINT_DISTANCE) {
            spokenAtPoint = true
            val nextStep = stepList.getOrNull(currentStepIndex + 1)
            val cue = when {
                isFinal || nextStep == null -> "You have arrived at your destination."
                else -> "${nextStep.text}."
            }
            sayAndNotify(cue, distToEnd)
            vibrateAtPoint()
            if (isFinal) {
                callback?.onArrived()
            } else {
                mainHandler.postDelayed({ enterStep(currentStepIndex + 1) }, 2500L)
            }
        }
    }

    // ═════════ Off-route ══════════════════════════════════════════════════

    private fun isOffRoute(loc: LatLng): Boolean {
        var minDist = Float.MAX_VALUE
        for (step in stepList) {
            val path = step.path
            if (path.size < 2) continue
            for (i in 0 until path.size - 1) {
                val a = path[i]; val b = path[i + 1]
                val abLat   = b.latitude  - a.latitude
                val abLng   = b.longitude - a.longitude
                val apLat   = loc.latitude  - a.latitude
                val apLng   = loc.longitude - a.longitude
                val abLenSq = abLat * abLat + abLng * abLng
                val t = if (abLenSq == 0.0) 0.0
                else ((apLat * abLat + apLng * abLng) / abLenSq).coerceIn(0.0, 1.0)
                val dist = FloatArray(1)
                Location.distanceBetween(
                    loc.latitude, loc.longitude,
                    a.latitude + t * abLat, a.longitude + t * abLng, dist
                )
                if (dist[0] < minDist) minDist = dist[0]
                if (minDist < 8f) return false
            }
        }
        return minDist > REROUTE_THRESHOLD
    }

    private fun triggerReroute(loc: LatLng) {
        if (!isRerouting.compareAndSet(false, true)) return
        lastRerouteTime = System.currentTimeMillis()
        routeDrawn = false
        sayAndNotify("Off route. Recalculating.", 0)
        destination?.let { fetchRoute(loc, it) }
    }

    // ═════════ Public TTS for activity ════════════════════════════════════

    fun speak(text: String) {
        if (::tts.isInitialized)
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
    }

    fun stopSpeaking() {
        if (::tts.isInitialized) tts.stop()
    }

    // ═════════ Helpers ════════════════════════════════════════════════════

    private fun sayAndNotify(text: String, distanceM: Int) {
        currentGuideText = text.trimEnd('.')
        if (::tts.isInitialized)
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
        updateNotification(currentGuideText, distanceM)
        callback?.onGuideUpdate(currentGuideText, distanceM)
    }

    private fun distanceTo(from: LatLng, to: LatLng): Int {
        val r = FloatArray(1)
        Location.distanceBetween(from.latitude, from.longitude, to.latitude, to.longitude, r)
        return r[0].toInt()
    }

    private fun vibrateApproach() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            vibrator.vibrate(VibrationEffect.createOneShot(200L, VibrationEffect.DEFAULT_AMPLITUDE))
        else { @Suppress("DEPRECATION") vibrator.vibrate(200L) }
    }

    private fun vibrateAtPoint() {
        val pattern = longArrayOf(0, 250, 150, 250)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        else { @Suppress("DEPRECATION") vibrator.vibrate(pattern, -1) }
    }

    // ═════════ Notification ═══════════════════════════════════════════════

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Navigation", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Turn-by-turn navigation guidance"
                setSound(null, null)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(instruction: String, distance: String): Notification {
        val tapIntent = Intent(this, NavigationActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(
                if (distance.isNotEmpty()) "$instruction  ·  $distance" else instruction
            )
            .setContentText("Tap to return to navigation")
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotification(instruction: String, distanceM: Int) {
        val distStr = if (distanceM > 0) "$distanceM m" else ""
        val manager = NotificationManagerCompat.from(this)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            manager.notify(NOTIFICATION_ID, buildNotification(instruction, distStr))
        }
    }
}