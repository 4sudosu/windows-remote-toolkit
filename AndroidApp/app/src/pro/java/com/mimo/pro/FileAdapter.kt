package com.mimo.pro

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.mimo.pro.databinding.ItemFileBinding

data class FileEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val modified: String
)

class FileAdapter(
    private val onClick: (FileEntry) -> Unit
) : RecyclerView.Adapter<FileAdapter.VH>() {

    private val items = mutableListOf<FileEntry>()

    fun submit(list: List<FileEntry>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemFileBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    inner class VH(private val binding: ItemFileBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: FileEntry) {
            binding.name.text = item.name
            val context = binding.root.context
            binding.meta.text = if (item.isDir) {
                context.getString(R.string.folder)
            } else {
                val mb = item.size / (1024 * 1024)
                context.getString(R.string.size_mb, mb.toDouble())
            }
            // Set icon based on file/dir
            binding.icon.setImageResource(if (item.isDir) R.drawable.ic_folder else R.drawable.ic_download)
            binding.root.setOnClickListener { onClick(item) }
        }
    }
}