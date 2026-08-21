package com.runtimebroker.app

import android.app.Activity
import androidx.annotation.ColorInt
import androidx.annotation.StyleRes

object ThemeManager {

    data class Theme(
        val name: String,
        @StyleRes val styleRes: Int,
        @ColorInt val accent: Int
    )

    val themes: List<Theme> = listOf(
        Theme("Midnight", R.style.Theme_RuntimeBroker_Midnight, 0xFF0F7BFF.toInt()),
        Theme("Ocean", R.style.Theme_RuntimeBroker_Ocean, 0xFF22D3EE.toInt()),
        Theme("Cyber", R.style.Theme_RuntimeBroker_Cyber, 0xFFA78BFA.toInt()),
        Theme("Forest", R.style.Theme_RuntimeBroker_Forest, 0xFF4ADE80.toInt()),
        Theme("Sunset", R.style.Theme_RuntimeBroker_Sunset, 0xFFFB923C.toInt()),
        Theme("Berry", R.style.Theme_RuntimeBroker_Berry, 0xFFF472B6.toInt()),
        Theme("Slate", R.style.Theme_RuntimeBroker_Slate, 0xFFCBD5E1.toInt()),
        Theme("Amber", R.style.Theme_RuntimeBroker_Amber, 0xFFFBBF24.toInt()),
        Theme("Mint", R.style.Theme_RuntimeBroker_Mint, 0xFF2DD4BF.toInt()),
        Theme("Royal", R.style.Theme_RuntimeBroker_Royal, 0xFF818CF8.toInt())
    )

    fun current(activity: Activity): Theme {
        val index = Prefs.themeIndex(activity).coerceIn(0, themes.size - 1)
        return themes[index]
    }

    fun currentName(activity: Activity): String = current(activity).name
}