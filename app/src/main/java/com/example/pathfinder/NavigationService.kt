package com.example.pathfinder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
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
        fun onRouteReady(route: GraphHopperRoute, startLoc: LatLng)
        fun onArrived()
    }

    private val binder = NavBinder()
    private var callback: NavCallback? = null
    fun setCallback(cb: NavCallback?) { callback = cb }

    // ── Threading ─────────────────────────────────────────────────────────
    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var locationThread: HandlerThread
    private lateinit var locationHandler: Handler
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // ── TTS ───────────────────────────────────────────────────────────────
    private lateinit var tts: TextToSpeech
    private lateinit var graphHopper: GraphHopperService

    // ── Nav state ─────────────────────────────────────────────────────────
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

    private val isRerouting       = AtomicBoolean(false)
    private var lastRerouteTime   = 0L
    private val REROUTE_COOLDOWN  = 8_000L
    private val REROUTE_THRESHOLD = 18f

    companion object {
        const val CHANNEL_NAV      = "nav_channel"
        const val CHANNEL_HAPTIC   = "nav_haptic"
        const val NOTIF_NAV        = 202
        const val NOTIF_HAPTIC     = 203
        const val EXTRA_DEST_LAT   = "dest_lat"
        const val EXTRA_DEST_LNG   = "dest_lng"
        const val EXTRA_API_KEY    = "graph_api_key"

        // Vibration patterns
        val PATTERN_APPROACH = longArrayOf(0, 250)                    // 1 pulse  — approaching
        val PATTERN_TURN     = longArrayOf(0, 250, 150, 250)          // 2 pulses — turn now
        val PATTERN_REROUTE  = longArrayOf(0, 150, 100, 150, 100, 150) // 3 pulses — off route
    }

    // ═════════ Lifecycle ══════════════════════════════════════════════════

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) tts.language = Locale.US
        }

        fusedClient    = LocationServices.getFusedLocationProviderClient(this)
        locationThread = HandlerThread("NavLocationThread").also { it.start() }
        locationHandler = Handler(locationThread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val lat    = intent?.getDoubleExtra(EXTRA_DEST_LAT, 0.0) ?: 0.0
        val lng    = intent?.getDoubleExtra(EXTRA_DEST_LNG, 0.0) ?: 0.0
        val apiKey = intent?.getStringExtra(EXTRA_API_KEY) ?: ""

        if (lat != 0.0 && lng != 0.0) {
            destination = LatLng(lat, lng)
            graphHopper = GraphHopperService(apiKey)
        }

        startForeground(NOTIF_NAV, buildNavNotification("Starting navigation…", ""))
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
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ═════════ Location ═══════════════════════════════════════════════════

    private fun startLocationUpdates() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
            .setMinUpdateIntervalMillis(1000L)
            .setMinUpdateDistanceMeters(5f)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                if (location.hasAccuracy() && location.accuracy > 30f) return

                val newLoc  = LatLng(location.latitude, location.longitude)
                val bearing = if (location.hasSpeed() && location.speed > 0.5f)
                    location.bearing else 0f

                mainHandler.post {
                    currentLocation = newLoc

                    val shouldUpdateCamera = lastCameraLocation?.let { last ->
                        val dist = FloatArray(1)
                        Location.distanceBetween(
                            last.latitude, last.longitude,
                            newLoc.latitude, newLoc.longitude, dist
                        )
                        dist[0] >= CAMERA_MIN_DISTANCE
                    } ?: true

                    if (shouldUpdateCamera) {
                        lastCameraLocation = newLoc
                        callback?.onLocationUpdate(newLoc, bearing)
                    }

                    // Only fetch route once GPS has a good lock (≤20m accuracy)
                    if (!routeDrawn && destination != null &&
                        (!location.hasAccuracy() || location.accuracy <= 20f)
                    ) {
                        routeDrawn = true
                        fetchRoute(newLoc, destination!!)
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
                    speak("Could not get route. Check your connection.")
                    isRerouting.set(false)
                    routeDrawn = false   // allow retry
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

                // Pass current location as startLoc so activity can tilt camera immediately
                val startLoc = currentLocation ?: origin
                callback?.onRouteReady(route, startLoc)
                updateNavNotification(currentGuideText, 0)
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

        val cue = when {
            index == 0 -> "${step.text} for ${step.distanceMeters} meters."
            isFinal    -> "Continue for ${step.distanceMeters} meters. Your destination is ahead."
            else       -> "Continue straight for ${step.distanceMeters} meters."
        }
        currentGuideText = cue.trimEnd('.')
        speak(cue)
        updateNavNotification(currentGuideText, step.distanceMeters)
        callback?.onGuideUpdate(currentGuideText, step.distanceMeters)
        spokenEntry = true
    }

    // ═════════ Navigation tick ════════════════════════════════════════════

    private fun checkStepProgress() {
        val loc = currentLocation ?: return
        if (currentStepIndex >= stepList.size) return

        val step      = stepList[currentStepIndex]
        val distToEnd = distanceTo(loc, step.endPoint)
        val isFinal   = currentStepIndex == stepList.size - 1

        // Off-route check on background thread
        val now = System.currentTimeMillis()
        if (!isRerouting.get() && (now - lastRerouteTime) > REROUTE_COOLDOWN) {
            val snapshot = loc
            serviceScope.launch(Dispatchers.Default) {
                if (isOffRoute(snapshot)) {
                    withContext(Dispatchers.Main) { triggerReroute(snapshot) }
                }
            }
        }

        updateNavNotification(currentGuideText, distToEnd)
        callback?.onGuideUpdate(currentGuideText, distToEnd)

        // Approach cue — 1 haptic buzz
        if (!spokenApproach && distToEnd <= APPROACH_DISTANCE && distToEnd > AT_POINT_DISTANCE) {
            spokenApproach = true
            val nextStep = stepList.getOrNull(currentStepIndex + 1)
            val cue = when {
                isFinal || nextStep == null -> "Your destination is in $distToEnd meters."
                else -> "In $distToEnd meters, ${nextStep.text.lowercase()}."
            }
            currentGuideText = cue.trimEnd('.')
            speak(cue)
            fireHaptic(PATTERN_APPROACH)
        }

        // At-point cue — 2 haptic buzzes
        if (!spokenAtPoint && distToEnd <= AT_POINT_DISTANCE) {
            spokenAtPoint = true
            val nextStep = stepList.getOrNull(currentStepIndex + 1)
            val cue = when {
                isFinal || nextStep == null -> "You have arrived at your destination."
                else -> "${nextStep.text}."
            }
            currentGuideText = cue.trimEnd('.')
            speak(cue)
            fireHaptic(PATTERN_TURN)

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
        speak("Off route. Recalculating.")
        fireHaptic(PATTERN_REROUTE)
        destination?.let { fetchRoute(loc, it) }
    }

    // ═════════ Haptics ════════════════════════════════════════════════════

    /**
     * Fires haptic feedback silently — no notification banner, no sound.
     * Uses a minimal silent notification on a DEFAULT importance channel
     * which triggers vibration but does NOT show a heads-up banner.
     */
    private fun fireHaptic(pattern: LongArray) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            val notification = NotificationCompat.Builder(this, CHANNEL_HAPTIC)
                .setContentTitle("Navigation")
                .setSmallIcon(android.R.drawable.ic_menu_directions)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setVibrate(pattern)
                .setSilent(true)          // no sound
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(this).notify(NOTIF_HAPTIC, notification)
        }
    }

    // ═════════ TTS ════════════════════════════════════════════════════════

    fun speak(text: String) {
        if (::tts.isInitialized)
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
    }

    fun stopSpeaking() {
        if (::tts.isInitialized) tts.stop()
    }

    // ═════════ Helpers ════════════════════════════════════════════════════

    private fun distanceTo(from: LatLng, to: LatLng): Int {
        val r = FloatArray(1)
        Location.distanceBetween(from.latitude, from.longitude, to.latitude, to.longitude, r)
        return r[0].toInt()
    }

    // ═════════ Notifications ══════════════════════════════════════════════

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Persistent navigation bar — silent, no vibration
            val navChannel = NotificationChannel(
                CHANNEL_NAV, "Navigation", NotificationManager.IMPORTANCE_LOW
            ).apply {
                setSound(null, null)
                enableVibration(false)
            }

            // Haptic-only channel — DEFAULT importance vibrates but won't show banner
            val hapticChannel = NotificationChannel(
                CHANNEL_HAPTIC, "Navigation Haptics", NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                setSound(null, null)
                enableVibration(true)
                // Pattern set per-notification via setVibrate()
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(navChannel)
            manager.createNotificationChannel(hapticChannel)
        }
    }

    private fun buildNavNotification(instruction: String, distance: String): Notification {
        val tapIntent = Intent(this, NavigationActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_NAV)
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

    private fun updateNavNotification(instruction: String, distanceM: Int) {
        val distStr = if (distanceM > 0) "$distanceM m" else ""
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(this)
                .notify(NOTIF_NAV, buildNavNotification(instruction, distStr))
        }
    }
}