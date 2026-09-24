package com.runtimebroker.app

import android.graphics.BitmapFactory
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView

/** One photo in the DCIM/RuntimeBroker temp album (ported from WinSysMonitor V2). */
data class AlbumItem(val id: Long, val uri: Uri, val name: String)

/**
 * Temp-album grid: thumbnails of stored captures.
 * Tap a photo to toggle selection (for batch share / clear).
 */
class TempAlbumAdapter(
    private var items: List<AlbumItem>,
    private val onSelectionChanged: (Int) -> Unit
) : RecyclerView.Adapter<TempAlbumAdapter.VH>() {

    val selected = mutableSetOf<Long>() // MediaStore ids

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val img: ImageView = v.findViewById(R.id.imgTemp)
        val check: CheckBox = v.findViewById(R.id.chkTemp)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_temp_photo, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val item = items[position]
        h.img.setImageBitmap(decodeThumb(h, item.uri))
        val sel = selected.contains(item.id)
        h.check.isChecked = sel
        h.img.alpha = if (sel) 0.55f else 1.0f
        h.itemView.setOnClickListener {
            if (selected.contains(item.id)) selected.remove(item.id) else selected.add(item.id)
            notifyItemChanged(position)
            onSelectionChanged(selected.size)
        }
    }

    fun update(newItems: List<AlbumItem>) {
        val kept = newItems.map { it.id }.toSet()
        selected.retainAll(kept)
        items = newItems
        notifyDataSetChanged()
        onSelectionChanged(selected.size)
    }

    fun selectedItems(): List<AlbumItem> = items.filter { selected.contains(it.id) }

    fun clearSelection() {
        selected.clear()
        notifyDataSetChanged()
        onSelectionChanged(0)
    }

    private fun decodeThumb(h: VH, uri: Uri) = try {
        val cr = h.itemView.context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 360 &&
            bounds.outHeight / (sample * 2) >= 360
        ) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    } catch (e: Exception) {
        null
    }
}
