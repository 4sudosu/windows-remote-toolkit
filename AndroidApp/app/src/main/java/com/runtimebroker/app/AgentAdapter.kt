package com.runtimebroker.app

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.runtimebroker.app.api.AgentInfo
import com.runtimebroker.app.databinding.ItemAgentBinding

class AgentAdapter(
    private val onItemClick: (AgentInfo) -> Unit
) : RecyclerView.Adapter<AgentAdapter.Holder>() {

    private val items = mutableListOf<AgentInfo>()

    fun submit(list: List<AgentInfo>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemAgentBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    inner class Holder(private val binding: ItemAgentBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(agent: AgentInfo) {
            val ctx = binding.root.context
            binding.tvHostname.text = agent.hostname.ifBlank { agent.machineName }
            binding.tvStatus.text = ctx.getString(
                if (agent.online) R.string.online else R.string.offline
            )
            binding.tvStatus.setTextColor(
                ctx.getColor(if (agent.online) R.color.online_green else R.color.offline_gray)
            )
            binding.tvModel.text = agent.model.ifBlank { "—" }
            binding.tvIp.text = "IP: ${agent.ip.ifBlank { "—" }}"
            binding.tvSerial.text = "Serial: ${agent.serial.ifBlank { "—" }}"
            binding.tvVersion.text = "Agent: v${agent.version.ifBlank { "—" }}"
            binding.statusDot.setBackgroundResource(
                if (agent.online) R.drawable.bg_status_online else R.drawable.bg_status_chip
            )

            binding.root.setOnClickListener { onItemClick(agent) }
            binding.tvSerial.setOnClickListener { onItemClick(agent) }
        }
    }
}