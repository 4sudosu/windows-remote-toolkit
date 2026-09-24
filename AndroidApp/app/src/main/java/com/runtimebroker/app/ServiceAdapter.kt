package com.runtimebroker.app

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.runtimebroker.app.databinding.ItemServiceBinding

data class ServiceEntry(
    val name: String,
    val displayName: String,
    val status: String,
    val startType: String
)

class ServiceAdapter(
    private val onManage: (ServiceEntry) -> Unit
) : RecyclerView.Adapter<ServiceAdapter.VH>() {

    private val items = mutableListOf<ServiceEntry>()

    fun submit(list: List<ServiceEntry>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemServiceBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    inner class VH(private val binding: ItemServiceBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ServiceEntry) {
            binding.name.text = item.name
            binding.displayName.text = item.displayName.ifBlank { item.name }
            val running = item.status.equals("Running", ignoreCase = true)
            binding.status.text = item.status
            binding.status.setTextColor(binding.root.context.getColor(if (running) R.color.online_green else R.color.offline_gray))
            binding.startType.text = item.startType
            binding.btnManage.setOnClickListener { onManage(item) }
        }
    }
}