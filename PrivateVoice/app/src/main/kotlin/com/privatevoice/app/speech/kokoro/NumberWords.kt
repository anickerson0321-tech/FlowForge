package com.privatevoice.app.speech.kokoro

/** English number verbalization (cardinal, ordinal, year, digits), US style without "and". */
object NumberWords {
    private val ones = arrayOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
    )
    private val tens = arrayOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
    private val scales = arrayOf("", "thousand", "million", "billion", "trillion", "quadrillion")

    fun cardinal(n: Long): String {
        if (n < 0) return "minus " + cardinal(-n)
        if (n < 20) return ones[n.toInt()]
        if (n < 100) {
            val t = tens[(n / 10).toInt()]
            return if (n % 10 == 0L) t else "$t-${ones[(n % 10).toInt()]}"
        }
        if (n < 1000) {
            val rest = n % 100
            return ones[(n / 100).toInt()] + " hundred" + if (rest == 0L) "" else " " + cardinal(rest)
        }
        val parts = ArrayList<String>()
        var x = n
        var scale = 0
        while (x > 0 && scale < scales.size) {
            val chunk = x % 1000
            if (chunk != 0L) parts.add(0, cardinal(chunk) + if (scales[scale].isEmpty()) "" else " " + scales[scale])
            x /= 1000
            scale++
        }
        if (x > 0) return digits(n.toString())
        return parts.joinToString(" ")
    }

    fun ordinal(n: Long): String {
        val c = cardinal(n)
        val lastSep = maxOf(c.lastIndexOf(' '), c.lastIndexOf('-'))
        val head = c.substring(0, lastSep + 1)
        val last = c.substring(lastSep + 1)
        val ord = when (last) {
            "one" -> "first"; "two" -> "second"; "three" -> "third"; "five" -> "fifth"
            "eight" -> "eighth"; "nine" -> "ninth"; "twelve" -> "twelfth"
            else -> if (last.endsWith("y")) last.dropLast(1) + "ieth" else last + "th"
        }
        return head + ord
    }

    /** Reads a 4-digit year the way people say it ("nineteen ninety-nine", "twenty twenty-five", "two thousand five"). */
    fun year(n: Int): String {
        if (n < 1000 || n > 9999) return cardinal(n.toLong())
        val hi = n / 100
        val lo = n % 100
        return when {
            n % 1000 == 0 -> cardinal(n.toLong())
            hi % 10 == 0 && lo < 10 -> cardinal(n.toLong()) // 2005 -> two thousand five
            lo == 0 -> cardinal(hi.toLong()) + " hundred"
            lo < 10 -> cardinal(hi.toLong()) + " oh " + ones[lo]
            else -> cardinal(hi.toLong()) + " " + cardinal(lo.toLong())
        }
    }

    fun digits(s: String): String = s.filter { it.isDigit() }.map { ones[it - '0'] }.joinToString(" ")

    /** "2.5" -> "two point five", ".75" -> "point seven five". */
    fun decimal(s: String): String {
        val clean = s.replace(",", "")
        val intPart = clean.substringBefore('.')
        val frac = clean.substringAfter('.', "")
        val head = if (intPart.isEmpty()) "" else cardinal(intPart.toLongOrNull() ?: return digits(clean)) + " "
        return if (frac.isEmpty()) head.trim() else head + "point " + digits(frac)
    }
}
