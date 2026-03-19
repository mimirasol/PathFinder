package com.example.pathfinder

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase

@SuppressLint("CustomSplashScreen")
class SplashScreen : AppCompatActivity() {

    private val db by lazy {
        FirebaseDatabase.getInstance(
            "https://pathfinder-bdc3e-default-rtdb.asia-southeast1.firebasedatabase.app"
        ).reference
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_splash_screen)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            // Not logged in — go straight to login, no delay needed
            go(LoginActivity::class.java)
            return
        }

        // Already logged in — read role and route immediately, no artificial delay
        db.child("users").child(user.uid).child("role")
            .get()
            .addOnSuccessListener { snap ->
                when (snap.getValue(String::class.java)?.lowercase()) {
                    "guardian"  -> go(CompanionActivity::class.java)
                    "navigator" -> go(MainActivity::class.java)
                    else        -> go(LoginActivity::class.java)
                }
            }
            .addOnFailureListener {
                go(LoginActivity::class.java)
            }
    }

    private fun go(cls: Class<*>) {
        startActivity(Intent(this, cls).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }
}