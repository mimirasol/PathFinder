package com.example.pathfinder

import android.Manifest
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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource

class SosActivity : AppCompatActivity() {

    private lateinit var btnSos: View
    private lateinit var btnSosRing: View
    private lateinit var txtCountdown: TextView
    private lateinit var txtSubtitle: TextView
    private lateinit var txtSosLabel: TextView
    private lateinit var txtSosSub: TextView
    private lateinit var rootLayout: View

    private var countDownTimer: CountDownTimer? = null
    private var isCounting = false

    // Normal state colors
    private val colorNormalBg    = 0xFFFFF5F5.toInt()   // #FFF5F5 — warm light red tint
    private val colorCountdownBg = 0xFF1A0000.toInt()   // deep dark red during countdown

    private val smsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) sendSos()
        else Toast.makeText(this, "SMS permission required to send SOS", Toast.LENGTH_LONG).show()
    }

    private lateinit var gestureDetector: GestureDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sos)

        btnSos       = findViewById(R.id.btnSos)
        btnSosRing   = findViewById(R.id.btnSosRing)
        txtCountdown = findViewById(R.id.txtCountdown)
        txtSubtitle  = findViewById(R.id.txtSubtitle)
        txtSosLabel  = btnSos.findViewById(R.id.txtSosLabel)
        txtSosSub    = btnSos.findViewById(R.id.txtSosSub)
        rootLayout   = findViewById(R.id.sosRoot)

        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, insets ->
            val statusBar = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBar    = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.setPadding(0, statusBar.top, 0, navBar.bottom)
            insets
        }

        findViewById<ImageButton>(R.id.btnClose).setOnClickListener {
            if (isCounting) cancelCountdown() else finish()
        }

        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, EmergencyContactsActivity::class.java))
        }

        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (isCounting) cancelCountdown()
                return true
            }
        })

        btnSos.setOnClickListener {
            if (!isCounting) startCountdown()
        }

        btnSos.setOnTouchListener { _, event ->
            if (isCounting) gestureDetector.onTouchEvent(event)
            false
        }
    }

    // ── Countdown ─────────────────────────────────────────────────────────

    private fun startCountdown() {
        isCounting = true

        // Dark dramatic background during countdown
        rootLayout.setBackgroundColor(colorCountdownBg)

        // Make ring and glow more visible on dark bg
        btnSosRing.alpha = 0.85f

        // Countdown text visible, white on dark
        txtCountdown.visibility = View.VISIBLE
        txtCountdown.setTextColor(0xFFFF4444.toInt())  // bright red on dark

        // Subtitle
        txtSubtitle.text = "Tap exit to cancel"
        txtSubtitle.setTextColor(0xFFFF9999.toInt())

        // Button label update
        txtSosLabel.text = "SOS"
        txtSosSub.text   = "Tap exit to cancel"
        txtSosSub.setTextColor(0xFFFFAAAA.toInt())

        countDownTimer = object : CountDownTimer(10_000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val secondsLeft = (millisUntilFinished / 1000) + 1
                txtCountdown.text = "Sending in $secondsLeft seconds…"
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
        rootLayout.setBackgroundColor(colorNormalBg)
        btnSosRing.alpha     = 0.55f

        txtCountdown.visibility = View.GONE

        txtSubtitle.text = "Your location will be sent to your emergency contacts and companion"
        txtSubtitle.setTextColor(0xFFB05050.toInt())

        txtSosLabel.text = "SOS"
        txtSosSub.text   = "Hold to send"
        txtSosSub.setTextColor(0xFFFFCCCC.toInt())

        Toast.makeText(this, "SOS cancelled", Toast.LENGTH_SHORT).show()
    }

    // ── SOS Sending ───────────────────────────────────────────────────────

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

        val fusedClient = LocationServices.getFusedLocationProviderClient(this)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            val cts = CancellationTokenSource()
            fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                .addOnSuccessListener { location -> dispatchSms(contacts, location) }
                .addOnFailureListener  { dispatchSms(contacts, null) }
        } else {
            dispatchSms(contacts, null)
        }
    }

    private fun dispatchSms(contacts: List<EmergencyContact>, location: Location?) {
        val message = if (location != null)
            "SOS! I need help. Location: ${location.latitude},${location.longitude}"
        else
            "SOS! I need help. Location unavailable."
        sendMessages(contacts, message)
    }

    private fun sendMessages(contacts: List<EmergencyContact>, message: String) {
        android.util.Log.d("SOS_DEBUG", "Sending SOS to ${contacts.size} contact(s): $message")

        var sentCount = 0
        val defaultSubId =
            android.telephony.SubscriptionManager.getDefaultSmsSubscriptionId()

        val smsManager = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            getSystemService(SmsManager::class.java).createForSubscriptionId(defaultSubId)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getSmsManagerForSubscriptionId(defaultSubId)
        }

        for (contact in contacts) {
            try {
                val parts = smsManager.divideMessage(message)
                if (parts.size == 1) smsManager.sendTextMessage(contact.phone, null, message, null, null)
                else smsManager.sendMultipartTextMessage(contact.phone, null, parts, null, null)
                sentCount++
            } catch (e: Exception) {
                android.util.Log.e("SOS_DEBUG", "Failed to send to ${contact.phone}: ${e.message}")
            }
        }

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