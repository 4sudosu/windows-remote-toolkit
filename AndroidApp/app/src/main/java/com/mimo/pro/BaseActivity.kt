package com.mimo.pro

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Every screen extends this so the user-selected theme is applied before the
 * layout inflates, and so navigation uses smooth cross-fade transitions.
 */
abstract class BaseActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(R.anim.push_in, R.anim.push_out)
    }

    override fun onContentChanged() {
        super.onContentChanged()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.push_in, R.anim.push_out)
    }
}
