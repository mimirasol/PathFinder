package com.example.pathfinder

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase

class LoginActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth

    private val db by lazy {
        FirebaseDatabase.getInstance("https://pathfinder-bdc3e-default-rtdb.asia-southeast1.firebasedatabase.app").reference
    }

    private lateinit var etName: EditText
    private lateinit var etEmail: EditText
    private lateinit var etPassword: EditText
    private lateinit var btnLogin: Button
    private lateinit var btnSignup: Button
    private lateinit var txtToggle: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var txtNameLabel: TextView

    private lateinit var roleSection: LinearLayout
    private lateinit var btnRoleNavigator: android.widget.LinearLayout
    private lateinit var btnRoleGuardian: android.widget.LinearLayout
    private lateinit var txtRoleTitle: TextView

    private var isLoginMode = true
    private var pendingUid: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        auth = FirebaseAuth.getInstance()

        if (auth.currentUser != null) {
            redirectByRole(auth.currentUser!!.uid)
            return
        }

        etName           = findViewById(R.id.etName)
        etEmail          = findViewById(R.id.etEmail)
        etPassword       = findViewById(R.id.etPassword)
        btnLogin         = findViewById(R.id.btnLogin)
        btnSignup        = findViewById(R.id.btnSignup)
        txtToggle        = findViewById(R.id.txtToggle)
        progressBar      = findViewById(R.id.progressBar)
        txtNameLabel     = findViewById(R.id.txtNameLabel)
        roleSection      = findViewById(R.id.roleSection)
        btnRoleNavigator = findViewById(R.id.btnRoleNavigator)
        btnRoleGuardian  = findViewById(R.id.btnRoleGuardian)
        txtRoleTitle     = findViewById(R.id.txtRoleTitle)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.loginRoot)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        updateUiMode()

        btnLogin.setOnClickListener  { handleLogin() }
        btnSignup.setOnClickListener { handleSignup() }
        txtToggle.setOnClickListener {
            isLoginMode = !isLoginMode
            updateUiMode()
        }

        btnRoleNavigator.setOnClickListener { saveRoleAndGo("navigator") }
        btnRoleGuardian.setOnClickListener  { saveRoleAndGo("guardian") }
    }

    // ── UI mode ───────────────────────────────────────────────────────────

    private fun updateUiMode() {
        val tilName = findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.tilName)
        roleSection.visibility = View.GONE
        if (isLoginMode) {
            tilName.visibility      = View.GONE
            txtNameLabel.visibility = View.GONE
            btnLogin.visibility     = View.VISIBLE
            btnSignup.visibility    = View.GONE
            txtToggle.text          = "Don't have an account? Sign up"
        } else {
            tilName.visibility      = View.VISIBLE
            txtNameLabel.visibility = View.VISIBLE
            btnLogin.visibility     = View.GONE
            btnSignup.visibility    = View.VISIBLE
            txtToggle.text          = "Already have an account? Log in"
        }
    }

    private fun showRoleSelection(uid: String) {
        pendingUid = uid
        val tilName = findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.tilName)
        tilName.visibility      = View.GONE
        txtNameLabel.visibility = View.GONE
        etEmail.visibility      = View.GONE
        etPassword.visibility   = View.GONE
        btnLogin.visibility     = View.GONE
        btnSignup.visibility    = View.GONE
        txtToggle.visibility    = View.GONE
        progressBar.visibility  = View.GONE
        roleSection.visibility  = View.VISIBLE
    }

    // ── Auth ──────────────────────────────────────────────────────────────

    private fun handleLogin() {
        val email    = etEmail.text.toString().trim()
        val password = etPassword.text.toString().trim()
        if (email.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Please fill in all fields", Toast.LENGTH_SHORT).show()
            return
        }
        setLoading(true)
        auth.signInWithEmailAndPassword(email, password)
            .addOnSuccessListener { result ->
                setLoading(false)
                val uid = result.user?.uid ?: return@addOnSuccessListener
                redirectByRole(uid)
            }
            .addOnFailureListener { e ->
                setLoading(false)
                Toast.makeText(this, "Login failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun handleSignup() {
        val name     = etName.text.toString().trim()
        val email    = etEmail.text.toString().trim()
        val password = etPassword.text.toString().trim()

        if (name.isEmpty() || email.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Please fill in all fields", Toast.LENGTH_SHORT).show()
            return
        }
        if (password.length < 6) {
            Toast.makeText(this, "Password must be at least 6 characters", Toast.LENGTH_SHORT).show()
            return
        }

        setLoading(true)
        auth.createUserWithEmailAndPassword(email, password)
            .addOnSuccessListener { result ->
                setLoading(false)
                val uid  = result.user?.uid ?: return@addOnSuccessListener

                // Save profile using updateChildren — name is never accidentally wiped
                db.child("users").child(uid).updateChildren(mapOf(
                    "name"       to name,
                    "email"      to email,
                    "circleCode" to "",
                    "role"       to ""
                ))

                // Go straight to role selection immediately
                Toast.makeText(this,
                    "Account created! Please select your role.",
                    Toast.LENGTH_SHORT).show()
                showRoleSelection(uid)
            }
            .addOnFailureListener { e ->
                setLoading(false)
                Toast.makeText(this, "Sign up failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    // ── Role ──────────────────────────────────────────────────────────────

    private fun saveRoleAndGo(role: String) {
        val uid = pendingUid ?: auth.currentUser?.uid ?: run {
            Toast.makeText(this, "Session expired. Please log in again.",
                Toast.LENGTH_SHORT).show()
            return
        }

        // Save role then navigate — role must be in DB before next screen reads it
        db.child("users").child(uid).child("role").setValue(role)
            .addOnSuccessListener {
                navigateByRole(role)
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Failed to save role: ${e.message}",
                    Toast.LENGTH_LONG).show()
            }
    }

    private fun redirectByRole(uid: String) {
        db.child("users").child(uid).child("role")
            .get()
            .addOnSuccessListener { snap ->
                val role = snap.getValue(String::class.java)
                if (role.isNullOrEmpty()) {
                    showRoleSelection(uid)
                } else {
                    navigateByRole(role)
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(this,
                    "Could not load profile: ${e.message}",
                    Toast.LENGTH_LONG).show()
                setLoading(false)
            }
    }

    private fun navigateByRole(role: String) {
        val intent = when (role.lowercase()) {
            "guardian" -> Intent(this, CompanionActivity::class.java)
            else       -> Intent(this, MainActivity::class.java)
        }
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun setLoading(loading: Boolean) {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        btnLogin.isEnabled     = !loading
        btnSignup.isEnabled    = !loading
    }
}