package com.example.pathfinder

import com.google.android.gms.maps.model.LatLng
import org.json.JSONObject
import java.net.URL

// ── Data returned for the whole route ──────────────────────────────────────
data class GraphHopperRoute(
    val points: List<LatLng>,
    val distanceMeters: Int,
    val durationMs: Long,
    val steps: List<GraphHopperStep>
)

// ── One instruction step ────────────────────────────────────────────────────
data class GraphHopperStep(
    val sign: Int,
    val text: String,
    val distanceMeters: Int,
    val startPoint: LatLng,
    val endPoint: LatLng,
    val path: List<LatLng>,
    // true when this step crosses a major road (primary/secondary/trunk)
    // — used to trigger a road crossing alert
    val crossesMajorRoad: Boolean = false
)

// ── GraphHopper sign values ─────────────────────────────────────────────────
//   -3  sharp left      -2  left       -1  slight left
//    0  straight         1  slight right  2  right
//    3  sharp right      4  finish
//    5  via/waypoint     6  roundabout
//    7  keep left        8  keep right

class GraphHopperService(private val apiKey: String) {

    // Road classes considered "major" — crossing these warrants an alert
    private val MAJOR_ROAD_CLASSES = setOf(
        "primary", "primary_link",
        "secondary", "secondary_link",
        "trunk", "trunk_link",
        "motorway", "motorway_link"
    )

    fun getRoute(
        startLat: Double, startLng: Double,
        endLat: Double,   endLng: Double
    ): GraphHopperRoute? {

        val url =
            "https://graphhopper.com/api/1/route?" +
                    "point=$startLat,$startLng&" +
                    "point=$endLat,$endLng&" +
                    "profile=foot&" +
                    "instructions=true&" +
                    "points_encoded=true&" +
                    "details=road_class&" +   // ← request road class per segment
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

            // Build a point-index → road_class lookup from the details array
            // details.road_class is an array of [fromIndex, toIndex, className]
            val roadClassByIndex = mutableMapOf<Int, String>()
            val details = path.optJSONObject("details")
            val roadClassDetails = details?.optJSONArray("road_class")
            if (roadClassDetails != null) {
                for (i in 0 until roadClassDetails.length()) {
                    val entry = roadClassDetails.getJSONArray(i)
                    val from  = entry.getInt(0)
                    val to    = entry.getInt(1)
                    val cls   = entry.getString(2)
                    for (idx in from until to) {
                        roadClassByIndex[idx] = cls
                    }
                }
            }

            val steps = mutableListOf<GraphHopperStep>()

            for (i in 0 until instructions.length()) {
                val inst     = instructions.getJSONObject(i)
                val sign     = inst.optInt("sign", 0)
                val text     = inst.getString("text")
                val stepDist = inst.getDouble("distance").toInt()
                val interval = inst.getJSONArray("interval")
                val si       = interval.getInt(0)
                val ei       = interval.getInt(1)

                if (si >= allPoints.size || ei >= allPoints.size) continue

                val stepPath = allPoints.subList(si, minOf(ei + 1, allPoints.size))

                // Check if any point in this step's range crosses a major road
                val crossesMajorRoad = (si until minOf(ei, roadClassByIndex.size)).any { idx ->
                    MAJOR_ROAD_CLASSES.contains(roadClassByIndex[idx])
                }

                steps.add(
                    GraphHopperStep(
                        sign           = sign,
                        text           = text,
                        distanceMeters = stepDist,
                        startPoint     = allPoints[si],
                        endPoint       = allPoints[ei],
                        path           = stepPath,
                        crossesMajorRoad = crossesMajorRoad
                    )
                )
            }

            GraphHopperRoute(
                points         = allPoints,
                distanceMeters = distanceM,
                durationMs     = durationMs,
                steps          = steps
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