package com.runtimebroker.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.runtimebroker.app.databinding.ActivityMediaLibraryBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lists everything the app recorded/saved on this device (camera photos &
 * videos, mic recordings, screenshots, transferred files) straight from
 * MediaStore, with preview / share / delete per item.
 */
class MediaLibraryActivity : BaseActivity() {

    private lateinit var binding: ActivityMediaLibraryBinding
    private lateinit var adapter: MediaAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMediaLibraryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        adapter = MediaAdapter(
            onOpen = { open(it) },
            onShare = { share(it) },
            onDelete = { confirmDelete(it) }
        )
        binding.mediaList.layoutManager = LinearLayoutManager(this)
        binding.mediaList.adapter = adapter

        binding.btnRefresh.setOnClickListener { loadMedia() }
    }

    override fun onResume() {
        super.onResume()
        loadMedia()
    }

    private fun loadMedia() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            binding.statusText.text = getString(R.string.media_needs_q)
            return
        }
        val items = mutableListOf<MediaItem>()
        val collection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED
        )
        try {
            contentResolver.query(
                collection,
                projection,
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                arrayOf("%RuntimeBroker%"),
                "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    items.add(
                        MediaItem(
                            uri = ContentUris.withAppendedId(collection, id),
                            name = c.getString(1) ?: "file",
                            path = c.getString(2) ?: "",
                            mime = c.getString(3) ?: "application/octet-stream",
                            size = c.getLong(4),
                            added = c.getLong(5) * 1000L
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }

        adapter.submit(items)
        binding.statusText.text = getString(R.string.media_count, items.size)
        binding.emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun open(item: MediaItem) {
        // Audio plays right inside the app — guaranteed to work no matter
        // which players the device ships with.
        if (item.mime.startsWith("audio/")) {
            toggleInAppAudio(item)
            return
        }
        // Video/images: exact MIME first, then broader types — some OEM
        // players don't register audio/mp4 but do handle audio/*.
        val candidates = mutableListOf(item.mime)
        when {
            item.mime.startsWith("video/") -> candidates.addAll(listOf("video/*", "*/*"))
            item.mime.startsWith("image/") -> candidates.add("image/*")
        }
        for (type in candidates) {
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(item.uri, type)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(intent)
                return
            } catch (e: ActivityNotFoundException) {
                // try next candidate
            } catch (e: Exception) {
                break
            }
        }
        Toast.makeText(this, R.string.media_open_failed, Toast.LENGTH_SHORT).show()
    }

    // ---- in-app audio playback -------------------------------------------
    private var mediaPlayer: android.media.MediaPlayer? = null
    private var playingUri: Uri? = null

    private fun toggleInAppAudio(item: MediaItem) {
        if (playingUri == item.uri && mediaPlayer != null) {
            stopAudio()
            return
        }
        stopAudio()
        try {
            playingUri = item.uri
            val player = android.media.MediaPlayer()
            player.setDataSource(this, item.uri)
            player.setOnCompletionListener { stopAudio() }
            player.setOnErrorListener { _, _, _ -> stopAudio(); true }
            player.prepare()
            player.start()
            mediaPlayer = player
            binding.statusText.text = getString(R.string.media_playing, item.name)
            Toast.makeText(this, R.string.media_stop_hint, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            stopAudio()
            Toast.makeText(this, R.string.media_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopAudio() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {
        }
        mediaPlayer = null
        playingUri = null
        binding.statusText.text = ""
    }

    override fun onDestroy() {
        stopAudio()
        super.onDestroy()
    }

    private fun share(item: MediaItem) {
        try {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = item.mime
                putExtra(Intent.EXTRA_STREAM, item.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, getString(R.string.media_share)))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.media_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDelete(item: MediaItem) {
        AlertDialog.Builder(this)
            .setTitle(R.string.media_delete)
            .setMessage(getString(R.string.media_delete_confirm, item.name))
            .setPositiveButton(R.string.delete) { _, _ -> delete(item) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun delete(item: MediaItem) {
        try {
            val deleted = contentResolver.delete(item.uri, null, null)
            if (deleted > 0) {
                Toast.makeText(this, R.string.media_deleted, Toast.LENGTH_SHORT).show()
                loadMedia()
            } else {
                Toast.makeText(this, R.string.media_delete_failed, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, R.string.media_delete_failed, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    data class MediaItem(
        val uri: Uri,
        val name: String,
        val path: String,
        val mime: String,
        val size: Long,
        val added: Long
    )

    class MediaAdapter(
        private val onOpen: (MediaItem) -> Unit,
        private val onShare: (MediaItem) -> Unit,
        private val onDelete: (MediaItem) -> Unit
    ) : ListAdapter<MediaItem, MediaAdapter.VH>(Diff) {

        var items: List<MediaItem> = emptyList()

        fun submit(list: List<MediaItem>) {
            items = list
            submitList(list)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = com.runtimebroker.app.databinding.ItemMediaBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

        override fun getItemCount(): Int = items.size

        inner class VH(private val b: com.runtimebroker.app.databinding.ItemMediaBinding) :
            RecyclerView.ViewHolder(b.root) {

            fun bind(item: MediaItem) {
                b.tvName.text = item.name
                val date = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault()).format(Date(item.added))
                b.tvMeta.text = "${item.path.trimEnd('/').substringAfterLast('/')} • ${formatSize(item.size)} • $date"

                loadThumb(b.imgThumb, item)

                b.btnPlay.setOnClickListener { onOpen(item) }
                b.root.setOnClickListener { onOpen(item) }
                b.btnShare.setOnClickListener { onShare(item) }
                b.btnDelete.setOnClickListener { onDelete(item) }
            }

            private fun loadThumb(iv: ImageView, item: MediaItem) {
                when {
                    item.mime.startsWith("image/") || item.mime.startsWith("video/") -> {
                        iv.post {
                            try {
                                val sz = android.util.Size(128, 128)
                                val bmp: Bitmap? =
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                                        itemView.context.contentResolver.loadThumbnail(item.uri, sz, null)
                                    else null
                                if (bmp != null) iv.setImageBitmap(bmp)
                                else iv.setImageResource(
                                    if (item.mime.startsWith("video/")) R.drawable.ic_video else R.drawable.ic_capture
                                )
                            } catch (e: Exception) {
                                iv.setImageResource(
                                    if (item.mime.startsWith("video/")) R.drawable.ic_video else R.drawable.ic_capture
                                )
                            }
                        }
                    }
                    item.mime.startsWith("audio/") -> iv.setImageResource(R.drawable.ic_audio)
                    else -> iv.setImageResource(R.drawable.ic_folder)
                }
            }

            private fun formatSize(bytes: Long): String = when {
                bytes >= 1 shl 20 -> "%.1f MB".format(bytes / 1048576.0)
                bytes >= 1 shl 10 -> "%.0f KB".format(bytes / 1024.0)
                else -> "$bytes B"
            }
        }

        object Diff : DiffUtil.ItemCallback<MediaItem>() {
            override fun areItemsTheSame(a: MediaItem, b: MediaItem) = a.uri == b.uri
            override fun areContentsTheSame(a: MediaItem, b: MediaItem) = a == b
        }
    }
}
