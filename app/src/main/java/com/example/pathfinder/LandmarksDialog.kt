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
 */
internal class LandmarksBottomSheetController(
    private val streetText: TextView,
    private val adapter: LandmarkAdapter,
    val dialog: BottomSheetDialog
) {
    fun updateStreetName(name: String) {
        streetText.text = name
    }

    fun updateLandmarks(landmarks: List<String>) {
        adapter.setOnItemClickListener(null)
        if (landmarks.isEmpty()) {
            adapter.updateItems(listOf("-- — No named landmarks found nearby"))
        } else {
            adapter.updateItems(landmarks)
        }
    }

    fun showLoadingState() {
        adapter.setOnItemClickListener(null)
        adapter.updateItems(listOf("... — Searching for nearby landmarks..."))
    }

    fun showErrorState(message: String, onRetry: (() -> Unit)? = null) {
        adapter.setOnItemClickListener {
            onRetry?.invoke()
        }
        adapter.updateItems(listOf("Retry — $message (Tap to retry)"))
    }
}

fun showLandmarksBottomSheet(
    context: Context,
    streetName: String,
    landmarks: List<String>,
    tts: TextToSpeech? = null,
    onSpeak: ((String) -> Unit)? = null,
    onStop: (() -> Unit)? = null
) {
    val dialog = BottomSheetDialog(context)
    val view = LayoutInflater.from(context)
        .inflate(R.layout.dialog_landmarks, null)
    dialog.setContentView(view)

    dialog.behavior.apply {
        skipCollapsed = true
        isDraggable = true
    }

    view.findViewById<TextView>(R.id.txtStreetName).text = streetName

    val rv = view.findViewById<RecyclerView>(R.id.rvLandmarks)
    rv.layoutManager = LinearLayoutManager(context)
    rv.adapter = LandmarkAdapter(landmarks)

    val dismissAction = {
        tts?.stop()
        onStop?.invoke()
        dialog.dismiss()
    }

    view.findViewById<Button>(R.id.btnCloseLandmarks)
        .setOnClickListener { dismissAction() }

    dialog.setOnDismissListener {
        tts?.stop()
        onStop?.invoke()
    }

    dialog.show()

    val speechText = landmarks.joinToString(
        ", ",
        prefix = "You are on $streetName. Nearby landmarks are "
    )

    when {
        onSpeak != null -> onSpeak(speechText)
        tts != null -> tts.speak(
            speechText,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "LANDMARKS"
        )
    }
}

internal fun showLandmarksBottomSheetProgressive(
    context: Context,
    onDismiss: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null
): LandmarksBottomSheetController {
    val dialog = BottomSheetDialog(context)
    val view = LayoutInflater.from(context)
        .inflate(R.layout.dialog_landmarks, null)

    dialog.setContentView(view)

    dialog.behavior.apply {
        skipCollapsed = true
        isDraggable = true
    }

    val streetText = view.findViewById<TextView>(R.id.txtStreetName)
    streetText.text = "Getting your street address..."

    val rv = view.findViewById<RecyclerView>(R.id.rvLandmarks)
    rv.layoutManager = LinearLayoutManager(context)

    val adapter = LandmarkAdapter(
        listOf("... — Searching for nearby landmarks..."),
        onItemClick = if (onRetry != null) {
            { onRetry.invoke() }
        } else {
            null
        }
    )
    rv.adapter = adapter

    view.findViewById<Button>(R.id.btnCloseLandmarks)
        .setOnClickListener { dialog.dismiss() }

    dialog.setOnDismissListener {
        onDismiss?.invoke()
    }

    dialog.show()

    return LandmarksBottomSheetController(streetText, adapter, dialog)
}

internal class LandmarkAdapter(
    private var items: List<String>,
    private var onItemClick: ((String) -> Unit)? = null
) : RecyclerView.Adapter<LandmarkAdapter.VH>() {

    fun updateItems(newItems: List<String>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun setOnItemClickListener(listener: ((String) -> Unit)?) {
        onItemClick = listener
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val txtDistance: TextView = view.findViewById(R.id.txtLandmarkDistance)
        val txtName: TextView = view.findViewById(R.id.txtLandmarkName)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_landmark, parent, false)
        )

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val parts = item.split(" — ", limit = 2)

        if (parts.size == 2) {
            holder.txtDistance.text = parts[0]
            holder.txtName.text = parts[1]
        } else {
            holder.txtDistance.text = "..."
            holder.txtName.text = item
        }

        holder.itemView.setOnClickListener {
            onItemClick?.invoke(item)
        }
    }

    override fun getItemCount() = items.size
}
