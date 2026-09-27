package com.runtimebroker.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/**
 * Share-to-Gemini-app helper — no API key needed.
 * Uses the installed Gemini app. Screenshots + prompt are pre-filled via
 * ACTION_SEND(_MULTIPLE); the answer appears in the Gemini app.
 * The prompt is ALSO copied to the clipboard because Gemini's share receiver
 * drops EXTRA_TEXT on some builds.
 */
object GeminiShare {

    /** Packages that can RECEIVE an image+prompt share as Gemini (not Lens etc). */
    private val GEMINI_SHARE_PACKAGES = arrayOf(
        "com.google.android.apps.bard", // Gemini app (current)
        "com.google.android.apps.gemini" // future-proof alias
    )

    /** Known Gemini / Google app packages that can be opened on home. */
    private val GEMINI_PACKAGES = arrayOf(
        "com.google.android.apps.bard",
        "com.google.android.apps.gemini",
        "com.google.android.googlequicksearchbox" // Google app fallback (home only)
    )

    fun installedGeminiPackage(ctx: Context): String? {
        val pm = ctx.packageManager
        for (pkg in GEMINI_PACKAGES) {
            try {
                pm.getPackageInfo(pkg, 0)
                return pkg
            } catch (e: PackageManager.NameNotFoundException) { /* next */ }
        }
        return null
    }

    fun isGeminiInstalled(ctx: Context): Boolean = installedGeminiPackage(ctx) != null

    /** Return a package-targeted copy of [base] if something in that package handles it. */
    private fun targetedIfResolves(ctx: Context, base: Intent, pkg: String): Intent? {
        return try {
            val targeted = Intent(base).setPackage(pkg)
            val res = ctx.packageManager.queryIntentActivities(
                targeted, PackageManager.MATCH_DEFAULT_ONLY
            )
            if (res.isNotEmpty()) targeted else null
        } catch (e: Exception) {
            null
        }
    }

    private fun addNewTaskIfNeeded(ctx: Context, intent: Intent) {
        if (ctx !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Write raw PNG bytes to cache/screenshots and return a FileProvider Uri. */
    fun cacheImageUri(ctx: Context, bytes: ByteArray, nameHint: String = "rb_ai"): Uri {
        val dir = File(ctx.cacheDir, "screenshots").apply { mkdirs() }
        val safe = nameHint.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val file = File(dir, "${safe}_${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { it.write(bytes) }
        return FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
    }

    /** Copy any content Uri (e.g. MediaStore) into cache so Gemini can read it. */
    fun copyToCacheUri(ctx: Context, src: Uri, nameHint: String = "rb_ai"): Uri? {
        return try {
            val dir = File(ctx.cacheDir, "screenshots").apply { mkdirs() }
            val safe = nameHint.replace(Regex("[^A-Za-z0-9_-]"), "_")
            val file = File(dir, "${safe}_${System.currentTimeMillis()}.png")
            val ins = ctx.contentResolver.openInputStream(src) ?: return null
            ins.use { input ->
                FileOutputStream(file).use { out -> input.copyTo(out) }
            }
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        } catch (e: Exception) {
            null
        }
    }

    /** Share one screenshot + prompt — opens Gemini directly, no chooser. */
    fun shareSingle(ctx: Context, imageUri: Uri, prompt: String): Boolean {
        return try {
            copyPromptToClipboard(ctx, prompt)
            val base = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, imageUri)
                putExtra(Intent.EXTRA_TEXT, prompt)
                putExtra(Intent.EXTRA_SUBJECT, prompt)
                clipData = ClipData.newUri(ctx.contentResolver, "screenshot", imageUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            // 1. Explicit Gemini entry point — guaranteed direct open, no chooser.
            for (pkg in GEMINI_SHARE_PACKAGES) {
                if (startExplicitShare(ctx, base, pkg)) return true
            }
            // 2. Package-targeted share, only when it resolves.
            for (pkg in GEMINI_SHARE_PACKAGES) {
                val targeted = targetedIfResolves(ctx, base, pkg)
                if (targeted != null) {
                    addNewTaskIfNeeded(ctx, targeted)
                    ctx.startActivity(targeted)
                    toastPromptCopied(ctx)
                    return true
                }
            }
            // 3. Last resort: chooser.
            val chooser = Intent.createChooser(base, ctx.getString(R.string.share_title))
            addNewTaskIfNeeded(ctx, chooser)
            ctx.startActivity(chooser)
            Toast.makeText(ctx, R.string.gemini_not_found, Toast.LENGTH_LONG).show()
            true
        } catch (e: Exception) {
            Toast.makeText(
                ctx,
                ctx.getString(R.string.gemini_open_failed, e.message ?: "?"),
                Toast.LENGTH_SHORT
            ).show()
            false
        }
    }

    /** Share multiple screenshots + one prompt — opens Gemini directly, no chooser. */
    fun shareMultiple(ctx: Context, imageUris: ArrayList<Uri>, prompt: String): Boolean {
        return try {
            copyPromptToClipboard(ctx, prompt)
            val base = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/png"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, imageUris)
                putExtra(Intent.EXTRA_TEXT, prompt)
                putExtra(Intent.EXTRA_SUBJECT, prompt)
                val clip = ClipData.newUri(ctx.contentResolver, "screenshot", imageUris[0])
                for (i in 1 until imageUris.size) {
                    clip.addItem(ClipData.Item(imageUris[i]))
                }
                setClipData(clip)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            for (pkg in GEMINI_SHARE_PACKAGES) {
                if (startExplicitShare(ctx, base, pkg)) return true
            }
            for (pkg in GEMINI_SHARE_PACKAGES) {
                val targeted = targetedIfResolves(ctx, base, pkg)
                if (targeted != null) {
                    addNewTaskIfNeeded(ctx, targeted)
                    ctx.startActivity(targeted)
                    toastPromptCopied(ctx)
                    return true
                }
            }
            val chooser = Intent.createChooser(base, ctx.getString(R.string.share_title))
            addNewTaskIfNeeded(ctx, chooser)
            ctx.startActivity(chooser)
            Toast.makeText(ctx, R.string.gemini_not_found, Toast.LENGTH_LONG).show()
            true
        } catch (e: Exception) {
            Toast.makeText(
                ctx,
                ctx.getString(R.string.gemini_open_failed, e.message ?: "?"),
                Toast.LENGTH_SHORT
            ).show()
            false
        }
    }

    /**
     * Fire the share straight at Gemini's entry-point activity.
     * True if it launched (direct open, no chooser).
     */
    private fun startExplicitShare(ctx: Context, base: Intent, pkg: String): Boolean {
        val entryPoints = arrayOf("$pkg.shellapp.BardEntryPointActivity")
        for (entry in entryPoints) {
            try {
                val explicit = Intent(base).setClassName(pkg, entry)
                addNewTaskIfNeeded(ctx, explicit)
                ctx.startActivity(explicit)
                toastPromptCopied(ctx)
                return true
            } catch (e: android.content.ActivityNotFoundException) { /* try next */ }
        }
        return false
    }

    private fun copyPromptToClipboard(ctx: Context, prompt: String) {
        try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Gemini prompt", prompt))
        } catch (e: Exception) { /* clipboard optional */ }
    }

    private fun toastPromptCopied(ctx: Context) {
        Toast.makeText(ctx, R.string.gemini_prompt_copied, Toast.LENGTH_LONG).show()
    }

    /** Open the Gemini app home (no image) — used by the Settings Gemini button. */
    fun openGemini(ctx: Context): Boolean {
        return try {
            // 1. Normal launcher intent for any known Gemini/Google package.
            for (pkg in GEMINI_PACKAGES) {
                val launch = try {
                    ctx.packageManager.getLaunchIntentForPackage(pkg)
                } catch (e: Exception) {
                    null
                }
                if (launch != null) {
                    addNewTaskIfNeeded(ctx, launch)
                    ctx.startActivity(launch)
                    return true
                }
            }
            // 2. Explicit Gemini entry point (handles shares, has no LAUNCHER on some builds).
            val explicit = Intent(Intent.ACTION_MAIN).apply {
                setClassName(
                    "com.google.android.apps.bard",
                    "com.google.android.apps.bard.shellapp.BardEntryPointActivity"
                )
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val res = ctx.packageManager.queryIntentActivities(
                explicit, PackageManager.MATCH_DEFAULT_ONLY
            )
            if (res.isNotEmpty()) {
                addNewTaskIfNeeded(ctx, explicit)
                ctx.startActivity(explicit)
                return true
            }
            // 3. Browser fallback.
            val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://gemini.google.com"))
            addNewTaskIfNeeded(ctx, web)
            ctx.startActivity(web)
            Toast.makeText(ctx, R.string.gemini_web_fallback, Toast.LENGTH_LONG).show()
            true
        } catch (e: Exception) {
            Toast.makeText(
                ctx,
                ctx.getString(R.string.gemini_open_failed, e.message ?: "?"),
                Toast.LENGTH_SHORT
            ).show()
            false
        }
    }
}
