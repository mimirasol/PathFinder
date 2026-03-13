package com.example.pathfinder

import com.google.android.gms.maps.model.LatLng
import org.json.JSONObject
import java.net.URL

// ── Data returned for the whole route ──────────────────────────────────────
data class GraphHopperRoute(
    val points: List<LatLng>,          // full decoded polyline
    val distanceMeters: Int,
    val durationMs: Long,
    val steps: List<GraphHopperStep>
)

// ── One instruction step ────────────────────────────────────────────────────
data class GraphHopperStep(
    val sign: Int,          // GraphHopper sign (see table below)
    val text: String,       // e.g. "Turn right onto Rizal Avenue"
    val distanceMeters: Int,
    val startPoint: LatLng, // first point of this step
    val endPoint: LatLng,   // last point / maneuver point
    val path: List<LatLng>  // slice of the full polyline for this step
)

// ── GraphHopper sign values ─────────────────────────────────────────────────
//   -3  sharp left
//   -2  left
//   -1  slight left
//    0  straight / continue
//    1  slight right
//    2  right
//    3  sharp right
//    4  finish (arrived)
//    5  via / waypoint reached
//    6  roundabout
//    7  keep left (fork)
//    8  keep right (fork)

class GraphHopperService(private val apiKey: String) {

    /**
     * Fetches a walking route and returns a [GraphHopperRoute] with
     * the full polyline AND turn-by-turn instructions.
     *
     * Must be called from a background thread (uses URL.readText()).
     */
    fun getRoute(
        startLat: Double,
        startLng: Double,
        endLat: Double,
        endLng: Double
    ): GraphHopperRoute? {

        val url =
            "https://graphhopper.com/api/1/route?" +
                    "point=$startLat,$startLng&" +
                    "point=$endLat,$endLng&" +
                    "profile=foot&" +
                    "instructions=true&" +   // ← was missing before
                    "points_encoded=true&" +
                    "key=$apiKey"

        return try {
            val json    = URL(url).readText()
            val jsonObj = JSONObject(json)
            val paths   = jsonObj.getJSONArray("paths")
            if (paths.length() == 0) return null

            val path         = paths.getJSONObject(0)
            val allPoints    = decodePolyline(path.getString("points"))
            val distanceM    = path.getDouble("distance").toInt()
            val durationMs   = path.getLong("time")
            val instructions = path.getJSONArray("instructions")

            val steps = mutableListOf<GraphHopperStep>()

            for (i in 0 until instructions.length()) {
                val inst     = instructions.getJSONObject(i)
                val sign     = inst.optInt("sign", 0)
                val text     = inst.getString("text")
                val stepDist = inst.getDouble("distance").toInt()
                val interval = inst.getJSONArray("interval")
                val si       = interval.getInt(0)
                val ei       = interval.getInt(1)

                // Guard against out-of-bounds slices
                if (si >= allPoints.size || ei >= allPoints.size) continue

                val stepPath = allPoints.subList(si, minOf(ei + 1, allPoints.size))

                steps.add(
                    GraphHopperStep(
                        sign          = sign,
                        text          = text,
                        distanceMeters = stepDist,
                        startPoint    = allPoints[si],
                        endPoint      = allPoints[ei],
                        path          = stepPath
                    )
                )
            }

            GraphHopperRoute(
                points        = allPoints,
                distanceMeters = distanceM,
                durationMs    = durationMs,
                steps         = steps
            )

        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // ── Polyline decoder ────────────────────────────────────────────────────
    private fun decodePolyline(encoded: String): List<LatLng> {
        val poly  = ArrayList<LatLng>()
        var index = 0
        var lat   = 0
        var lng   = 0

        while (index < encoded.length) {
            var b: Int
            var shift  = 0
            var result = 0

            do {
                b      = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)

            lat += if ((result and 1) != 0) (result shr 1).inv() else result shr 1

            shift  = 0
            result = 0

            do {
                b      = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)

            lng += if ((result and 1) != 0) (result shr 1).inv() else result shr 1

            poly.add(LatLng(lat / 1E5, lng / 1E5))
        }

        return poly
    }
}