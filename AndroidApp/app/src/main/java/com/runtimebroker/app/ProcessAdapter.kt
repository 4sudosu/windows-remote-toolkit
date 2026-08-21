package com.runtimebroker.app

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.runtimebroker.app.databinding.ItemProcessBinding

data class ProcessEntry(
    val pid: Int,
    val name: String,
    val title: String,
    val memMB: Int,
    val cpu: Double,
    val connections: Int,
    val session: Int,
    val hasWindow: Boolean
)

class ProcessAdapter(
    private val onKill: (ProcessEntry) -> Unit
) : RecyclerView.Adapter<ProcessAdapter.VH>() {

    private val items = mutableListOf<ProcessEntry>()

    fun submit(list: List<ProcessEntry>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemProcessBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    inner class VH(private val binding: ItemProcessBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ProcessEntry) {
            binding.name.text = "${item.name} (${item.pid})"
            val context = binding.root.context
            val cat = if (item.hasWindow) context.getString(R.string.cat_app) else context.getString(R.string.cat_bg)
            binding.title.text = item.title.ifBlank { cat }
            val parts = buildList {
                add(context.getString(R.string.size_mb, item.memMB.toString()))
                add(context.getString(R.string.meta_cpu, if (item.cpu >= 0) item.cpu.toString() else "0"))
                if (item.connections > 0) add(context.getString(R.string.meta_net, item.connections))
            }
            binding.meta.text = parts.joinToString(" · ")
            binding.btnKill.setOnClickListener { onKill(item) }
        }
    }
}