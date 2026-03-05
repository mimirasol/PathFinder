package com.example.pathfinder

import com.google.android.gms.maps.model.LatLng
import org.json.JSONObject
import java.net.URL

class GraphHopperService(private val apiKey: String) {

    fun getRoute(
        startLat: Double,
        startLng: Double,
        endLat: Double,
        endLng: Double
    ): List<LatLng> {

        val url =
            "https://graphhopper.com/api/1/route?" +
                    "point=$startLat,$startLng&" +
                    "point=$endLat,$endLng&" +
                    "profile=foot&" +
                    "points_encoded=true&" +
                    "key=$apiKey"

        val json = URL(url).readText()
        val jsonObj = JSONObject(json)

        val paths = jsonObj.getJSONArray("paths")
        if (paths.length() == 0) return emptyList()

        val encoded = paths.getJSONObject(0)
            .getString("points")

        return decodePolyline(encoded)
    }

    private fun decodePolyline(encoded: String): List<LatLng> {
        val poly = ArrayList<LatLng>()
        var index = 0
        var lat = 0
        var lng = 0

        while (index < encoded.length) {
            var b: Int
            var shift = 0
            var result = 0

            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)

            val dlat = if ((result and 1) != 0) (result shr 1).inv() else result shr 1
            lat += dlat

            shift = 0
            result = 0

            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)

            val dlng = if ((result and 1) != 0) (result shr 1).inv() else result shr 1
            lng += dlng

            poly.add(LatLng(lat / 1E5, lng / 1E5))
        }

        return poly
    }
}