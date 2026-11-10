package com.github.gezimos.inkos.data

import android.os.UserHandle
import com.github.gezimos.inkos.helper.PinyinHelper
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

private val DIACRITICS = Regex("\\p{InCombiningDiacriticalMarks}+")
private val PUNCTUATION = Regex("[-_+,. ]")

/** Lightweight normalization identical to SearchHelper.normalizeString — lives here to avoid
 *  a circular dependency and to keep AppListItem self-contained. */
internal fun normalizeLabel(input: String): String {
    return Normalizer.normalize(input.uppercase(Locale.getDefault()), Normalizer.Form.NFD)
        .replace(DIACRITICS, "")
        .replace(PUNCTUATION, "")
}

val collator: Collator = Collator.getInstance().apply {
    strength = Collator.PRIMARY
    decomposition = Collator.CANONICAL_DECOMPOSITION
}

data class AppListItem(
    val activityLabel: String,
    val activityPackage: String,
    val activityClass: String,
    val user: UserHandle,
    var customLabel: String, // TODO make immutable by refining data flow
    val shortcutId: String? = null, // Non-null for app shortcuts, null for regular apps
) : Comparable<AppListItem> {
    val label get() = customLabel.ifEmpty { activityLabel }

    /** Returns true if this item represents an app shortcut */
    val isShortcut: Boolean get() = shortcutId != null

    /** Pre-computed normalized label for search/filter — avoids repeated Unicode normalization. */
    val normalizedLabel: String by lazy { normalizeLabel(label) }

    /** Normalized original app name (only differs from normalizedLabel when an alias is set). */
    val normalizedOriginalLabel: String? by lazy {
        if (customLabel.isNotEmpty() && customLabel != activityLabel) normalizeLabel(activityLabel) else null
    }

    /** True when the label contains CJK characters — pinyin lookup only matters for those. */
    val hasCjk: Boolean by lazy {
        label.any { c ->
            c.code in 0x3400..0x4DBF || c.code in 0x4E00..0x9FFF ||
                c.code in 0xF900..0xFAFF || c.code in 0xFF21..0xFF5A
        }
    }

    /** Pinyin for CJK labels: 微信 -> initials "WX", full "WEIXIN". Empty for plain Latin labels. */
    private val pinyin: PinyinHelper.Entry by lazy { PinyinHelper.of(label) }
    val pinyinInitials: String get() = pinyin.initials
    val pinyinFull: String get() = pinyin.full

    /** First character for the A-Z sidebar: Latin labels use their own first letter,
     *  CJK labels fall back to the pinyin initial (微信 -> W). */
    val normalizedFirstChar: Char? by lazy {
        normalizedLabel.trimStart().firstOrNull()?.takeIf { it in 'A'..'Z' }
            ?: pinyin.firstLetter
    }

    /** Speed up sort and search — lazy so getCollationKey() runs once, not per compareTo() call. */
    private val collationKey by lazy { collator.getCollationKey(label) }

    override fun compareTo(other: AppListItem): Int =
        collationKey.compareTo(other.collationKey)
}
