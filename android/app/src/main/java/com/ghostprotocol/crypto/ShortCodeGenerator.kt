package com.ghostprotocol.crypto

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class ShortCode(
    val word1: String,
    val word2: String,
    val word3: String,
    val number: Int,
    val epochDay: Long
) {
    fun toDisplayString(): String = "$word1 — $word2 — $word3 — ${number.toString().padStart(4, '0')}"
    fun toCompactString(): String = "$word1-$word2-$word3-${number.toString().padStart(4, '0')}"
}

/**
 * Pure Kotlin generator for 24-Hour Rotating BIP-39 Short Verification Codes.
 * Zero Android dependencies.
 */
object ShortCodeGenerator {

    /**
     * Derives a deterministic 32-byte daily seed using HMAC-SHA256(key = ed25519PrivateKey, data = epochDay).
     */
    fun generateSeed(ed25519PrivateKey: ByteArray, epochDay: Long): ByteArray {
        require(ed25519PrivateKey.size == 32) { "Ed25519 private key seed must be 32 bytes" }
        val mac = Mac.getInstance("HmacSHA256")
        val keySpec = SecretKeySpec(ed25519PrivateKey, "HmacSHA256")
        mac.init(keySpec)

        val epochBytes = ByteArray(8)
        for (i in 0..7) {
            epochBytes[7 - i] = ((epochDay ushr (i * 8)) and 0xFF).toByte()
        }
        return mac.doFinal(epochBytes)
    }

    /**
     * Derives a ShortCode from a 32-byte daily seed using 11-bit chunks into the 2048-word BIP-39 list.
     */
    fun deriveCode(seed: ByteArray, wordlist: List<String>, epochDay: Long): ShortCode {
        require(seed.size >= 8) { "Seed must be at least 8 bytes" }
        require(wordlist.size == 2048) { "Wordlist must contain exactly 2048 words" }

        val word1Index = ((seed[0].toInt() and 0xFF) or ((seed[1].toInt() and 0xFF) shl 8)) and 0x7FF
        val word2Index = ((seed[2].toInt() and 0xFF) or ((seed[3].toInt() and 0xFF) shl 8)) and 0x7FF
        val word3Index = ((seed[4].toInt() and 0xFF) or ((seed[5].toInt() and 0xFF) shl 8)) and 0x7FF
        val rawNum = ((seed[6].toInt() and 0xFF) or ((seed[7].toInt() and 0xFF) shl 8))
        val number = (rawNum and 0xFFFF) % 10000

        val word1 = wordlist[word1Index].uppercase()
        val word2 = wordlist[word2Index].uppercase()
        val word3 = wordlist[word3Index].uppercase()

        return ShortCode(word1, word2, word3, number, epochDay)
    }

    /**
     * Validates input words against the BIP-39 wordlist and ensures number is within 0..9999.
     */
    fun validateInput(words: List<String>, number: Int, wordlist: List<String>): Boolean {
        if (words.size != 3) return false
        if (number !in 0..9999) return false
        val set = wordlist.toSet()
        return words.all { set.contains(it.lowercase()) }
    }

    /**
     * Parses a raw short code string (handles hyphens, em-dashes, and spaces).
     */
    fun parseInput(rawInput: String): Pair<List<String>, Int>? {
        val parts = rawInput.trim().split(Regex("[—\\-\\s]+")).filter { it.isNotEmpty() }
        if (parts.size != 4) return null
        val words = parts.take(3)
        val numStr = parts[3]
        if (numStr.length != 4 || !numStr.all { it.isDigit() }) return null
        val num = numStr.toIntOrNull() ?: return null
        return Pair(words, num)
    }

    /**
     * Parses and validates a raw short code string, returning a ShortCode if valid.
     */
    fun validateInput(rawInput: String, wordlist: List<String>, epochDay: Long = System.currentTimeMillis() / 86_400_000L): ShortCode? {
        val parsed = parseInput(rawInput) ?: return null
        if (!validateInput(parsed.first, parsed.second, wordlist)) return null
        return ShortCode(
            word1 = parsed.first[0].uppercase(),
            word2 = parsed.first[1].uppercase(),
            word3 = parsed.first[2].uppercase(),
            number = parsed.second,
            epochDay = epochDay
        )
    }

    /**
     * Produces a 4-byte hash hint suitable for BLE scan response broadcast.
     */
    fun codeToFingerprintHint(code: ShortCode): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(code.toCompactString().toByteArray(Charsets.UTF_8))
        return hash.copyOfRange(0, 4)
    }
}
