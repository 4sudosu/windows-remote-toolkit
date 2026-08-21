package com.runtimebroker.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Every screen extends this so the user-selected theme is applied before the
 * layout inflates, and so navigation uses smooth cross-fade transitions.
 */
abstract class BaseActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(ThemeManager.current(this).styleRes)
        super.onCreate(savedInstanceState)
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
    }

    /** Re-inflates this activity with the freshly selected theme. */
    protected fun applyThemeNow() {
        runOnUiThread {
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
            recreate()
        }
    }
}