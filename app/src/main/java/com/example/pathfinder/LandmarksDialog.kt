package com.example.pathfinder

import android.content.Context
import android.speech.tts.TextToSpeech
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * Shared landmarks bottom sheet used by MainActivity and NavigationActivity.
 *
 * @param context     Activity context
 * @param streetName  Name of the street the user is on
 * @param landmarks   List of "DISTANCE — NAME" strings e.g. "42 m — Jollibee"
 * @param tts         Optional TTS instance — if provided, will speak the landmarks
 */
fun showLandmarksBottomSheet(
    context: Context,
    streetName: String,
    landmarks: List<String>,
    tts: TextToSpeech? = null
) {
    val dialog = BottomSheetDialog(context)
    val view   = LayoutInflater.from(context).inflate(R.layout.dialog_landmarks, null)
    dialog.setContentView(view)

    // Make it feel like a proper bottom sheet with rounded top corners
    dialog.behavior.apply {
        skipCollapsed  = true
        isDraggable    = true
    }

    view.findViewById<TextView>(R.id.txtStreetName).text = streetName

    val rv = view.findViewById<RecyclerView>(R.id.rvLandmarks)
    rv.layoutManager = LinearLayoutManager(context)
    rv.adapter       = LandmarkAdapter(landmarks)

    view.findViewById<Button>(R.id.btnCloseLandmarks).setOnClickListener {
        tts?.stop()
        dialog.dismiss()
    }

    dialog.setOnDismissListener { tts?.stop() }

    dialog.show()

    // Speak after showing
    tts?.speak(
        landmarks.joinToString(", ", prefix = "You are on $streetName. Nearby landmarks are "),
        TextToSpeech.QUEUE_FLUSH, null, "LANDMARKS"
    )
}

// ── Adapter ───────────────────────────────────────────────────────────────

private class LandmarkAdapter(
    private val items: List<String>
) : RecyclerView.Adapter<LandmarkAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val txtDistance: TextView = view.findViewById(R.id.txtLandmarkDistance)
        val txtName:     TextView = view.findViewById(R.id.txtLandmarkName)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context)
            .inflate(R.layout.item_landmark, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        // Items are formatted as "42 m — Jollibee"
        val parts = items[position].split(" — ", limit = 2)
        holder.txtDistance.text = if (parts.size == 2) parts[0] else "? m"
        holder.txtName.text     = if (parts.size == 2) parts[1] else items[position]
    }

    override fun getItemCount() = items.size
}
