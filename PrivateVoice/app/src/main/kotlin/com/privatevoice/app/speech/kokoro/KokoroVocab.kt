package com.privatevoice.app.speech.kokoro

/**
 * Kokoro v1.0 phoneme vocabulary (character -> token id), as used by the
 * official kokoro / kokoro-onnx packages. Token 0 ("$") pads both ends.
 */
object KokoroVocab {
    const val PAD = 0L
    /** Maximum phoneme tokens per inference call (512 incl. padding). */
    const val MAX_PHONEMES = 510

    private val map: Map<Int, Int> = mapOf(
        0x003B to 1,
        0x003A to 2,
        0x002C to 3,
        0x002E to 4,
        0x0021 to 5,
        0x003F to 6,
        0x2014 to 9,
        0x2026 to 10,
        0x0022 to 11,
        0x0028 to 12,
        0x0029 to 13,
        0x201C to 14,
        0x201D to 15,
        0x0020 to 16,
        0x0303 to 17,
        0x02A3 to 18,
        0x02A5 to 19,
        0x02A6 to 20,
        0x02A8 to 21,
        0x1D5D to 22,
        0xAB67 to 23,
        0x0041 to 24,
        0x0049 to 25,
        0x004F to 31,
        0x0051 to 33,
        0x0053 to 35,
        0x0054 to 36,
        0x0057 to 39,
        0x0059 to 41,
        0x1D4A to 42,
        0x0061 to 43,
        0x0062 to 44,
        0x0063 to 45,
        0x0064 to 46,
        0x0065 to 47,
        0x0066 to 48,
        0x0068 to 50,
        0x0069 to 51,
        0x006A to 52,
        0x006B to 53,
        0x006C to 54,
        0x006D to 55,
        0x006E to 56,
        0x006F to 57,
        0x0070 to 58,
        0x0071 to 59,
        0x0072 to 60,
        0x0073 to 61,
        0x0074 to 62,
        0x0075 to 63,
        0x0076 to 64,
        0x0077 to 65,
        0x0078 to 66,
        0x0079 to 67,
        0x007A to 68,
        0x0251 to 69,
        0x0250 to 70,
        0x0252 to 71,
        0x00E6 to 72,
        0x03B2 to 75,
        0x0254 to 76,
        0x0255 to 77,
        0x00E7 to 78,
        0x0256 to 80,
        0x00F0 to 81,
        0x02A4 to 82,
        0x0259 to 83,
        0x025A to 85,
        0x025B to 86,
        0x025C to 87,
        0x025F to 90,
        0x0261 to 92,
        0x0265 to 99,
        0x0268 to 101,
        0x026A to 102,
        0x029D to 103,
        0x026F to 110,
        0x0270 to 111,
        0x014B to 112,
        0x0273 to 113,
        0x0272 to 114,
        0x0274 to 115,
        0x00F8 to 116,
        0x0278 to 118,
        0x03B8 to 119,
        0x0153 to 120,
        0x0279 to 123,
        0x027E to 125,
        0x027B to 126,
        0x0281 to 128,
        0x027D to 129,
        0x0282 to 130,
        0x0283 to 131,
        0x0288 to 132,
        0x02A7 to 133,
        0x028A to 135,
        0x028B to 136,
        0x028C to 138,
        0x0263 to 139,
        0x0264 to 140,
        0x03C7 to 142,
        0x028E to 143,
        0x0292 to 147,
        0x0294 to 148,
        0x02C8 to 156,
        0x02CC to 157,
        0x02D0 to 158,
        0x02B0 to 162,
        0x02B2 to 164,
        0x2193 to 169,
        0x2192 to 171,
        0x2197 to 172,
        0x2198 to 173,
        0x1D7B to 177,
    )

    fun idOf(codePoint: Int): Int? = map[codePoint]

    fun contains(c: Char): Boolean = map.containsKey(c.code)

    /** Converts a phoneme string to padded input ids, dropping characters outside the vocabulary. */
    fun encode(phonemes: String): LongArray {
        val ids = ArrayList<Long>(phonemes.length + 2)
        ids.add(PAD)
        var i = 0
        while (i < phonemes.length) {
            val cp = phonemes.codePointAt(i)
            map[cp]?.let { ids.add(it.toLong()) }
            i += Character.charCount(cp)
        }
        ids.add(PAD)
        return ids.toLongArray()
    }
}
