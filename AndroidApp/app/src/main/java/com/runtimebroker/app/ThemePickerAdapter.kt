package com.runtimebroker.app

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.runtimebroker.app.databinding.ItemThemeBinding

class ThemePickerAdapter(
    private val onSelect: (Int) -> Unit
) : RecyclerView.Adapter<ThemePickerAdapter.VH>() {

    private val items = ThemeManager.themes
    private var selected = 0

    fun setSelected(index: Int) {
        val prev = selected
        selected = index
        notifyItemChanged(prev)
        notifyItemChanged(selected)
    }

    inner class VH(val binding: ItemThemeBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemThemeBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val root = holder.binding.root
        holder.binding.name.text = item.name

        val swatch = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 30f
            setColor(item.accent)
            setStroke(2, 0x33FFFFFF.toInt())
        }
        holder.binding.swatch.background = swatch

        val isSelected = position == selected
        holder.binding.check.visibility = if (isSelected) View.VISIBLE else View.GONE
        root.isSelected = isSelected
        root.setOnClickListener { onSelect(position) }
    }
}