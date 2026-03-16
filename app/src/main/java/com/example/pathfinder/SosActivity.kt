package com.example.pathfinder

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.os.CountDownTimer
import android.telephony.SmsManager
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource

class SosActivity : AppCompatActivity() {

    private lateinit var btnSos: View
    private lateinit var btnSosRing: View
    private lateinit var txtCountdown: TextView
    private lateinit var txtSubtitle: TextView
    private lateinit var rootLayout: View

    private var countDownTimer: CountDownTimer? = null
    private var isCounting = false

    private val smsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) sendSos()
        else Toast.makeText(this, "SMS permission required to send SOS", Toast.LENGTH_LONG).show()
    }

    // ── GestureDetector for double-tap to cancel ────────────────────────
    private lateinit var gestureDetector: GestureDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sos)

        btnSos       = findViewById(R.id.btnSos)
        btnSosRing   = findViewById(R.id.btnSosRing)
        txtCountdown = findViewById(R.id.txtCountdown)
        txtSubtitle  = findViewById(R.id.txtSubtitle)
        rootLayout   = findViewById(R.id.sosRoot)

        // Top inset — push close/settings buttons below status bar
        // Bottom inset — push subtitle above nav bar
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, insets ->
            val statusBar = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBar    = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.setPadding(0, statusBar.top, 0, navBar.bottom)
            insets
        }

        // Close button
        findViewById<ImageButton>(R.id.btnClose).setOnClickListener {
            if (isCounting) cancelCountdown()
            else finish()
        }

        // Settings button — go to emergency contacts
        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, EmergencyContactsActivity::class.java))
        }

        // Double-tap detector for cancel during countdown
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (isCounting) cancelCountdown()
                return true
            }
        })

        // Single tap on button = start countdown
        btnSos.setOnClickListener {
            if (!isCounting) startCountdown()
        }

        // Pass touch events to gesture detector when counting
        btnSos.setOnTouchListener { _, event ->
            if (isCounting) gestureDetector.onTouchEvent(event)
            false
        }
    }

    // ── Countdown ────────────────────────────────────────────────────────
    private fun startCountdown() {
        isCounting = true

        // Switch to dark countdown UI
        rootLayout.setBackgroundColor(0xFF1A1A1A.toInt())
        txtCountdown.visibility = View.VISIBLE
        txtSubtitle.text = "SOS will be sent to emergency contacts and companion"
        btnSos.findViewById<TextView>(R.id.txtSosLabel).text = "Double tap\nto cancel"

        countDownTimer = object : CountDownTimer(10_000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val secondsLeft = (millisUntilFinished / 1000) + 1
                txtCountdown.text = "Sending in $secondsLeft seconds...."
            }
            override fun onFinish() {
                isCounting = false
                checkSmsPermissionAndSend()
            }
        }.start()
    }

    private fun cancelCountdown() {
        countDownTimer?.cancel()
        isCounting = false

        // Restore normal UI
        rootLayout.setBackgroundColor(0xFFFFFFFF.toInt())
        txtCountdown.visibility = View.GONE
        txtSubtitle.text = "SOS will be sent to emergency contacts and companion"
        btnSos.findViewById<TextView>(R.id.txtSosLabel).text = "Tap to\nsend SOS"

        Toast.makeText(this, "SOS cancelled", Toast.LENGTH_SHORT).show()
    }

    // ── SOS Sending ──────────────────────────────────────────────────────
    private fun checkSmsPermissionAndSend() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
            == PackageManager.PERMISSION_GRANTED
        ) sendSos()
        else smsPermissionLauncher.launch(Manifest.permission.SEND_SMS)
    }

    private fun sendSos() {
        val contacts = EmergencyContactsActivity.getContacts(this)
        if (contacts.isEmpty()) {
            Toast.makeText(this,
                "No emergency contacts set. Please add one in Settings.",
                Toast.LENGTH_LONG).show()
            startActivity(Intent(this, EmergencyContactsActivity::class.java))
            return
        }

        // Get last known location then send
        val fusedClient = LocationServices.getFusedLocationProviderClient(this)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            val cts = CancellationTokenSource()
            fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                .addOnSuccessListener { location ->
                    dispatchSms(contacts, location)
                }
                .addOnFailureListener {
                    dispatchSms(contacts, null)
                }
        } else {
            dispatchSms(contacts, null)
        }
    }

    private fun dispatchSms(contacts: List<EmergencyContact>, location: Location?) {
        // Send plain coordinates — no URL, no emoji, guaranteed to pass carrier filters.
        // Coordinates are formatted so that iPhones automatically detect them as
        // a tappable location link in the Messages app.
        val message = if (location != null)
            "SOS! I need help. Location: ${location.latitude},${location.longitude}"
        else
            "SOS! I need help. Location unavailable."

        sendMessages(contacts, message)
    }

    private fun sendMessages(contacts: List<EmergencyContact>, message: String) {

        android.util.Log.d("SOS_DEBUG", "Attempting to send SOS to ${contacts.size} contact(s)")
        android.util.Log.d("SOS_DEBUG", "Message: $message")

        var sentCount = 0

        // Force the SIM that the user has set as default for SMS.
        // On dual-SIM Samsungs, using getDefault() may pick the inactive SIM.
        val defaultSubId = android.telephony.SubscriptionManager.getDefaultSmsSubscriptionId()
        android.util.Log.d("NAV_SOS", "Default SMS SIM subscription ID: $defaultSubId")

        val smsManager = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            getSystemService(SmsManager::class.java).createForSubscriptionId(defaultSubId)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getSmsManagerForSubscriptionId(defaultSubId)
        }

        for (contact in contacts) {
            try {
                android.util.Log.d("SOS_DEBUG", "Sending to ${contact.phone}")
                // Split message in case it's over 160 chars
                val parts = smsManager.divideMessage(message)
                if (parts.size == 1) {
                    smsManager.sendTextMessage(contact.phone, null, message, null, null)
                } else {
                    smsManager.sendMultipartTextMessage(contact.phone, null, parts, null, null)
                }
                sentCount++
                android.util.Log.d("SOS_DEBUG", "Sent successfully to ${contact.phone}")
            } catch (e: Exception) {
                android.util.Log.e("SOS_DEBUG", "Failed to send to ${contact.phone}: ${e.message}")
                e.printStackTrace()
            }
        }

        android.util.Log.d("SOS_DEBUG", "Total sent: $sentCount")

        Toast.makeText(this,
            if (sentCount > 0) "SOS sent to $sentCount contact(s)."
            else "Failed to send SOS. Check SMS permission.",
            Toast.LENGTH_LONG
        ).show()

        finish()
    }

    override fun onDestroy() {
        countDownTimer?.cancel()
        super.onDestroy()
    }
}