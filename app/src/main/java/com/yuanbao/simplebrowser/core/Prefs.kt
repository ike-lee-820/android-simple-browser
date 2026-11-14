package com.yuanbao.simplebrowser.core

import android.content.Context
import com.yuanbao.simplebrowser.R
import java.net.URLEncoder

object HomePages {
    data class Item(val key: String, val label: Int, val url: String?)

    val ALL = listOf(
        Item("blank", R.string.home_blank, "about:blank"),
        Item("google", R.string.home_google, "https://www.google.com/"),
        Item("goy", R.string.home_goy, "https://goy.com/"),
        Item("custom", R.string.home_custom, null)
    )

    fun byKey(key: String): Item = ALL.firstOrNull { it.key == key } ?: ALL[0]
}

object SearchEngines {
    data class Item(val key: String, val label: Int, val template: String?)

    val ALL = listOf(
        Item("google", R.string.search_google, "https://www.google.com/search?q=%s"),
        Item("bing", R.string.search_bing, "https://www.bing.com/search?q=%s"),
        Item("baidu", R.string.search_baidu, "https://www.baidu.com/s?wd=%s"),
        Item("duck", R.string.search_duck, "https://duckduckgo.com/?q=%s"),
        Item("custom", R.string.search_custom, null)
    )

    fun byKey(key: String): Item = ALL.firstOrNull { it.key == key } ?: ALL[0]

    fun build(template: String, query: String): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return if (template.contains("%s")) template.replace("%s", encoded) else template + encoded
    }
}

object UaPresets {
    data class Item(val key: String, val label: Int, val ua: String?)

    private const val CHROME_ANDROID =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    private const val SAFARI_IPHONE =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1"
    private const val CHROME_WINDOWS =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    val ALL = listOf(
        Item("default", R.string.ua_default, null),
        Item("android", R.string.ua_android, CHROME_ANDROID),
        Item("iphone", R.string.ua_iphone, SAFARI_IPHONE),
        Item("desktop", R.string.ua_desktop, CHROME_WINDOWS),
        Item("custom", R.string.ua_custom, null)
    )

    fun byKey(key: String): Item = ALL.firstOrNull { it.key == key } ?: ALL[0]

    fun desktopUa(): String = CHROME_WINDOWS
}

class Prefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("simple_browser_prefs", Context.MODE_PRIVATE)

    var homeKey: String
        get() = sp.getString("home_key", "blank") ?: "blank"
        set(value) = sp.edit().putString("home_key", value).apply()

    var homeCustom: String
        get() = sp.getString("home_custom", "") ?: ""
        set(value) = sp.edit().putString("home_custom", value).apply()

    var searchKey: String
        get() = sp.getString("search_key", "google") ?: "google"
        set(value) = sp.edit().putString("search_key", value).apply()

    var searchCustom: String
        get() = sp.getString("search_custom", "") ?: ""
        set(value) = sp.edit().putString("search_custom", value).apply()

    var uaKey: String
        get() = sp.getString("ua_key", "default") ?: "default"
        set(value) = sp.edit().putString("ua_key", value).apply()

    var uaCustom: String
        get() = sp.getString("ua_custom", "") ?: ""
        set(value) = sp.edit().putString("ua_custom", value).apply()

    var pinnedBase: String?
        get() = sp.getString("pinned_base", null)
        set(value) = sp.edit().putString("pinned_base", value).apply()

    var maxTasks: Int
        get() = sp.getInt("max_tasks", 8)
        set(value) = sp.edit().putInt("max_tasks", value).apply()

    var partsPerTask: Int
        get() = sp.getInt("parts_per_task", 32)
        set(value) = sp.edit().putInt("parts_per_task", value).apply()

    var javascript: Boolean
        get() = sp.getBoolean("javascript", true)
        set(value) = sp.edit().putBoolean("javascript", value).apply()

    var desktop: Boolean
        get() = sp.getBoolean("desktop", false)
        set(value) = sp.edit().putBoolean("desktop", value).apply()

    fun homeUrl(): String {
        val item = HomePages.byKey(homeKey)
        if (item.key == "custom") {
            val raw = homeCustom.trim()
            return if (raw.isEmpty()) "about:blank" else normalize(raw)
        }
        return item.url ?: "about:blank"
    }

    fun searchTemplate(): String {
        val item = SearchEngines.byKey(searchKey)
        if (item.key == "custom") {
            val raw = searchCustom.trim()
            if (raw.isNotEmpty()) return raw
        }
        return SearchEngines.ALL[0].template ?: "https://www.google.com/search?q=%s"
    }

    fun resolveUa(systemUa: String?): String? {
        val item = UaPresets.byKey(uaKey)
        return when {
            item.key == "custom" -> uaCustom.trim().takeIf { it.isNotEmpty() } ?: systemUa
            desktop -> UaPresets.desktopUa()
            item.ua != null -> item.ua
            else -> systemUa
        }
    }

    private fun normalize(raw: String): String = when {
        raw.startsWith("http://") || raw.startsWith("https://") ||
            raw.startsWith("about:") || raw.startsWith("file:") -> raw
        else -> "https://$raw"
    }
}
