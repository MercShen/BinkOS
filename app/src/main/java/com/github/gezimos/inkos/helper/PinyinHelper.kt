package com.github.gezimos.inkos.helper

import android.icu.text.Transliterator
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 中文标签的拼音辅助。
 *
 * 用 ICU 的 Han-Latin 转写把汉字变成带声调的拉丁拼音（如「微信」→「wēi xìn」），
 * 去掉声调符号后生成：
 *  - [Entry.initials]：每个音节的首字母，如「微信」→ WX、「微信读书」→ WXDS
 *  - [Entry.full]：全拼，如「微信」→ WEIXIN
 *
 * 结果都是大写，方便和 SearchHelper.normalizeString() 出来的查询串直接比较。
 * 转写结果按标签缓存，避免每次输入都重新跑 ICU。
 */
object PinyinHelper {

    data class Entry(val initials: String, val full: String) {
        val isEmpty: Boolean get() = initials.isEmpty() && full.isEmpty()
        val firstLetter: Char? get() = initials.firstOrNull()?.takeIf { it in 'A'..'Z' }
    }

    private val EMPTY = Entry("", "")
    private val toneMarks = Regex("\\p{InCombiningDiacriticalMarks}+")
    private val cache = ConcurrentHashMap<String, Entry>(256)

    private val transliterator: Transliterator? by lazy {
        try {
            Transliterator.getInstance("Han-Latin")
        } catch (_: Throwable) {
            null
        }
    }

    fun of(text: String): Entry {
        if (text.isBlank()) return EMPTY
        cache[text]?.let { return it }
        val entry = try {
            build(text)
        } catch (_: Throwable) {
            EMPTY
        }
        if (cache.size > 1024) cache.clear()
        cache[text] = entry
        return entry
    }

    private fun build(text: String): Entry {
        val t = transliterator ?: return EMPTY

        // 先把 camelCase 拆开，让拉丁词也能取到多个首字母（WeChat -> W C）
        val spaced = buildString {
            for (i in text.indices) {
                val c = text[i]
                if (i > 0 && c.isUpperCase() && text[i - 1].isLowerCase()) append(' ')
                append(c)
            }
        }

        val latin = t.transliterate(spaced)
        val plain = Normalizer.normalize(latin, Normalizer.Form.NFD)
            .replace(toneMarks, "")
            .lowercase(Locale.ROOT)

        val initials = StringBuilder()
        val full = StringBuilder()
        var inWord = false
        for (ch in plain) {
            val alnum = (ch in 'a'..'z') || (ch in '0'..'9')
            if (alnum) {
                if (!inWord) {
                    initials.append(ch)
                    inWord = true
                }
                full.append(ch)
            } else {
                inWord = false
            }
        }
        return Entry(
            initials.toString().uppercase(Locale.ROOT),
            full.toString().uppercase(Locale.ROOT)
        )
    }
}
