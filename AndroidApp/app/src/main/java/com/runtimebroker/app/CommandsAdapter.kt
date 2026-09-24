package com.runtimebroker.app

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.runtimebroker.app.databinding.ItemCommandBinding

data class CommandItem(
    val id: String,
    @DrawableRes val iconRes: Int,
    val titleRes: Int,
    val descRes: Int
)

class CommandsAdapter(
    private val onClick: (CommandItem) -> Unit
) : ListAdapter<CommandItem, CommandsAdapter.VH>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemCommandBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    fun moveItem(from: Int, to: Int) {
        val snapshot = currentList.toMutableList()
        val item = snapshot.removeAt(from)
        snapshot.add(to, item)
        submitList(snapshot)
    }

    inner class VH(private val binding: ItemCommandBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: CommandItem) {
            binding.icon.setImageResource(item.iconRes)
            binding.title.setText(item.titleRes)
            binding.desc.setText(item.descRes)
            binding.commandCard.setOnClickListener { onClick(item) }
        }
    }

    companion object {
        private val Diff = object : DiffUtil.ItemCallback<CommandItem>() {
            override fun areItemsTheSame(a: CommandItem, b: CommandItem) = a.id == b.id
            override fun areContentsTheSame(a: CommandItem, b: CommandItem) = a == b
        }
    }
}