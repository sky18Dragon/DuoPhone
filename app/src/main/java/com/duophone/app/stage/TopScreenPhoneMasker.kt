package com.duophone.app.stage

/**
 * Masks digits 4 through 7 (1-indexed local digits) of phone numbers shown on the top screen,
 * keeping prefix/suffix digits intact (e.g., 13812345678 -> 138****5678, +8613812345678 -> +86138****5678).
 */
object TopScreenPhoneMasker {
    fun mask(raw: String): String {
        if (raw.isEmpty()) return raw
        val startIndex = if (raw.startsWith("+86")) 3 else 0
        val out = StringBuilder(raw.length)
        if (startIndex > 0) {
            out.append(raw, 0, startIndex)
        }
        var digitIndex = 0
        for (i in startIndex until raw.length) {
            val ch = raw[i]
            if (ch in '0'..'9') {
                digitIndex++
                out.append(if (digitIndex in 4..7) '*' else ch)
            } else {
                out.append(ch)
            }
        }
        return out.toString()
    }
}
