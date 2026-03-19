package com.example.pathfinder

/**
 * Firebase Realtime Database structure:
 *
 * circles/
 *   {circleCode}/
 *     createdBy: uid
 *     members/
 *       {uid}: true
 *
 * users/
 *   {uid}/
 *     name: String
 *     email: String
 *     circleCode: String
 *     location/
 *       lat: Double
 *       lng: Double
 *       battery: Int
 *       lastUpdated: Long
 */

data class CompanionLocation(
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val battery: Int = 0,
    val lastUpdated: Long = 0L
)

data class CompanionUser(
    val name: String = "",
    val email: String = "",
    val role: String = "",          // "navigator" or "companion"
    val circleCode: String = "",
    val location: CompanionLocation = CompanionLocation()
)