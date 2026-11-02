package com.glasslauncher.tv.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Opens a title inside a player app: Pear, Nuvio Legacy, the official Nuvio TV app, Nuvio Arc (fork),
 * Nuvio Re-Shaped or Stremio. "auto" (default) uses the first one installed, in that order.
 */
object NuvioLaunch {
    const val LEGACY_PKG = "space.nuvio.nativelegacy"
    const val OFFICIAL_PKG = "com.nuvio.tv"
    const val ARC_PKG = "com.nuvio.tv.test"
    const val RESHAPED_PKG = "com.nuvioreshaped.tv"
    const val STREMIO_PKG = "com.stremio.one"
    /** Pear (github.com/joseph81301/Pear); a development build has the ".debug" suffix. */
    const val PEAR_PKG = "app.pear.tv"
    const val PEAR_DEBUG_PKG = "app.pear.tv.debug"

    /** Setting value → package, in the "auto" priority order. */
    val TARGETS = linkedMapOf(
        "pear" to PEAR_PKG, "legacy" to LEGACY_PKG, "official" to OFFICIAL_PKG, "arc" to ARC_PKG, "reshaped" to RESHAPED_PKG, "stremio" to STREMIO_PKG,
    )
    val NAMES = mapOf(
        "auto" to "Auto", "pear" to "Pear", "legacy" to "Nuvio Legacy", "official" to "Nuvio (official)",
        "arc" to "Nuvio Arc", "reshaped" to "Nuvio Re-Shaped", "stremio" to "Stremio",
    )

    fun installed(ctx: Context, pkg: String): Boolean {
        if (pkg == PEAR_PKG) return pearPackage(ctx) != null
        return pkgInstalled(ctx, pkg)
    }

    private fun pkgInstalled(ctx: Context, pkg: String) = runCatching { ctx.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    /**
     * The Pear build that opens title links: the release, else the development build. A Pear from before
     * pear://title links has no screen for them, so it counts as not installed and Auto moves on to Nuvio.
     */
    private fun pearPackage(ctx: Context): String? = listOf(PEAR_PKG, PEAR_DEBUG_PKG).firstOrNull { pkg ->
        runCatching {
            val probe = Intent(Intent.ACTION_VIEW, Uri.parse("pear://title?id=tt0111161&type=movie")).setPackage(pkg)
            ctx.packageManager.queryIntentActivities(probe, 0).isNotEmpty()
        }.getOrDefault(false)
    }

    /** The package to send the link to. */
    private fun actualPackage(ctx: Context, pkg: String) = if (pkg == PEAR_PKG) pearPackage(ctx) ?: PEAR_PKG else pkg

    /** The target actually used: the chosen one, or for "auto" the first installed (Legacy if none). */
    fun resolve(ctx: Context, s: Settings): String {
        val t = s.nuvioTarget
        if (t in TARGETS) return t
        return TARGETS.entries.firstOrNull { installed(ctx, it.value) }?.key ?: "legacy"
    }

    fun packageName(ctx: Context, s: Settings) = actualPackage(ctx, TARGETS.getValue(resolve(ctx, s)))

    fun intent(ctx: Context, s: Settings, type: String, id: String): Intent {
        // No CLEAR_TASK: a running Nuvio is brought forward and opens the title at once, without
        // restarting (no splash). If Nuvio is not running it starts normally.
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        val target = resolve(ctx, s)
        val pkg = actualPackage(ctx, TARGETS.getValue(target))
        val t = if (type.equals("movie", true)) "movie" else "series"
        val uri = if (target == "pear") {
            // Pear's title link, the same one its Apple apps take: pear://title?id=tt0111161&type=movie (its page).
            Uri.Builder().scheme("pear").authority("title")
                .appendQueryParameter("id", id.trim()).appendQueryParameter("type", t).build()
        } else if (target == "stremio") {
            // Stremio's own link: stremio:///detail/movie/tt0111161 (its page for the title, with your add-ons' streams).
            Uri.parse("stremio:///detail/$t/${id.trim()}/${if (t == "movie") id.trim() else ""}".trimEnd('/'))
        } else if (target == "legacy") {
            Uri.Builder().scheme("nuvio-legacy").authority("meta").appendPath(t).appendPath(id.trim()).build()
        } else {
            // Official Nuvio TV, Nuvio Arc and Re-Shaped all read nuvio://meta?type=…&id=…
            Uri.Builder().scheme("nuvio").authority("meta")
                .appendQueryParameter("type", t).appendQueryParameter("id", id.trim()).build()
        }
        return Intent(Intent.ACTION_VIEW, uri).setPackage(pkg).addFlags(flags)
    }

    fun open(ctx: Context, s: Settings, http: Http, item: HeroItem) {
        try {
            ctx.startActivity(intent(ctx, s, item.type, item.id))
        } catch (e: Exception) {
            Glog.w("Open ${item.type} ${item.id} in ${packageName(ctx, s)}", e)
            Toast.makeText(ctx, "${NAMES[resolve(ctx, s)]} is not installed or can't open this title", Toast.LENGTH_LONG).show()
            return
        }
        if (s.haOnLaunch && s.haWebhook.startsWith("http")) {
            thread(name = "ha-hook") {
                runCatching {
                    http.post(s.haWebhook, JSONObject()
                        .put("event", "launch").put("title", item.name).put("type", item.type)
                        .put("id", item.id).put("app", packageName(ctx, s)))
                }
            }
        }
    }

    fun testWebhook(s: Settings, http: Http): String = try {
        if (!s.haWebhook.startsWith("http")) "Set the webhook URL first"
        else "Home Assistant answered HTTP " + http.post(s.haWebhook, JSONObject().put("event", "test").put("source", "glassio"))
    } catch (e: Exception) { "Failed: ${e.message}" }
}
