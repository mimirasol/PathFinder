package com.example.pathfinder

import android.app.Application
import com.google.firebase.FirebaseApp

class PathfinderApp : Application() {
    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)
    }
}