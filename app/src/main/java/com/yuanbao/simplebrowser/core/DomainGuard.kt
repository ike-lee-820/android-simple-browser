package com.yuanbao.simplebrowser.core

import android.net.Uri
import java.util.Locale

/**
 * 固定网页规则：
 * 开启时取当前网页的可注册主域，例如 exp.goy.com 会得到 goy.com。
 * 之后仅放行该主域本身以及它的任意层级子域：
 *   goy.com、xxx.goy.com、xy.xxx.goy.com 均放行；
 *   other.com、goy.com.evil.com、evilgoy.com 均拦截。
 *
 * 说明：主域提取采用常见公共后缀表做近似判断，不依赖 Public Suffix List 数据库，
 * 对 co.uk、com.cn 等两段公共后缀已覆盖。IP 与 localhost 按完整 host 处理。
 */
object DomainGuard {

    private val MULTI_PART_SUFFIXES = setOf(
        "co.uk", "org.uk", "ac.uk", "gov.uk", "me.uk", "net.uk", "sch.uk",
        "com.cn", "net.cn", "org.cn", "gov.cn", "edu.cn", "ac.cn",
        "com.hk", "org.hk", "net.hk", "edu.hk", "gov.hk",
        "com.tw", "org.tw", "net.tw", "edu.tw", "gov.tw", "idv.tw",
        "co.jp", "ne.jp", "or.jp", "ac.jp", "go.jp",
        "co.kr", "or.kr", "ne.kr", "go.kr", "ac.kr",
        "com.br", "net.br", "org.br", "com.au", "net.au", "org.au", "edu.au",
        "co.nz", "com.sg", "com.my", "com.tr", "com.mx", "com.ar",
        "co.in", "net.in", "org.in", "com.ru", "com.ua", "com.vn",
        "com.ph", "com.id", "co.th", "com.pt", "com.es", "com.pl",
        "com.za", "com.pk", "com.bd", "com.eg", "co.za"
    )

    private val IP_LIKE = Regex("^[0-9.:]+$")

    @Volatile
    var pinnedBase: String? = null
        private set

    fun pinFrom(url: String): String? {
        val host = Uri.parse(url).host ?: return null
        val base = registrableDomain(host)
        if (base.isEmpty()) return null
        pinnedBase = base
        return base
    }

    fun restore(base: String?) {
        pinnedBase = base?.takeIf { it.isNotEmpty() }
    }

    fun clear() {
        pinnedBase = null
    }

    fun isPinned(): Boolean = pinnedBase != null

    fun base(): String? = pinnedBase

    fun isAllowed(url: String): Boolean {
        if (url.startsWith("about:") || url.startsWith("data:") || url.startsWith("file:")) return true
        val base = pinnedBase ?: return true
        val host = Uri.parse(url).host
            ?.lowercase(Locale.ROOT)
            ?.trimEnd('.') ?: return false
        return host == base || host.endsWith(".$base")
    }

    fun registrableDomain(host: String): String {
        val h = host.lowercase(Locale.ROOT).trimEnd('.')
        if (h.isEmpty()) return ""
        if (IP_LIKE.matches(h) || !h.contains('.') || h == "localhost") return h
        val parts = h.split('.').filter { it.isNotEmpty() }
        if (parts.size <= 2) return parts.joinToString(".")
        val lastTwo = parts.takeLast(2).joinToString(".")
        return if (lastTwo in MULTI_PART_SUFFIXES) {
            parts.takeLast(3).joinToString(".")
        } else {
            lastTwo
        }
    }
}
