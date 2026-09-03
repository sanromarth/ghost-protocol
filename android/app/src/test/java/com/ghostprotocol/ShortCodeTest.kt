package com.ghostprotocol

import com.ghostprotocol.crypto.ShortCode
import com.ghostprotocol.crypto.ShortCodeGenerator
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ShortCodeTest {

    private fun loadWordlist(): List<String> {
        val wordlistFile = File("src/main/res/raw/bip39_english.txt")
        assertTrue("bip39_english.txt must exist", wordlistFile.exists())
        val words = wordlistFile.readLines().map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        assertEquals(2048, words.size)
        return words
    }

    @Test
    fun testShortCodeDerivationDeterminism() {
        val wordlist = loadWordlist()
        val privateKey = ByteArray(32) { it.toByte() }
        val epochDay = 20689L // fixed test epoch

        val seed1 = ShortCodeGenerator.generateSeed(privateKey, epochDay)
        val seed2 = ShortCodeGenerator.generateSeed(privateKey, epochDay)
        assertArrayEquals(seed1, seed2)

        val code1 = ShortCodeGenerator.deriveCode(seed1, wordlist, epochDay)
        val code2 = ShortCodeGenerator.deriveCode(seed2, wordlist, epochDay)

        assertEquals(code1.word1, code2.word1)
        assertEquals(code1.word2, code2.word2)
        assertEquals(code1.word3, code2.word3)
        assertEquals(code1.number, code2.number)
        assertEquals(code1.epochDay, code2.epochDay)

        // Verify words are all uppercase
        assertTrue(code1.word1.all { it.isUpperCase() })
        assertTrue(code1.word2.all { it.isUpperCase() })
        assertTrue(code1.word3.all { it.isUpperCase() })

        // Verify number is in range 0..9999
        assertTrue(code1.number in 0..9999)

        // Verify display string format (WORD1 — WORD2 — WORD3 — 0000)
        val display = code1.toDisplayString()
        assertTrue(display.matches(Regex("^[A-Z]+ — [A-Z]+ — [A-Z]+ — \\d{4}$")))

        // Verify compact string format (WORD1-WORD2-WORD3-0000)
        val compact = code1.toCompactString()
        assertTrue(compact.matches(Regex("^[A-Z]+-[A-Z]+-[A-Z]+-\\d{4}$")))
    }

    @Test
    fun testCodeRotationAcrossDays() {
        val wordlist = loadWordlist()
        val privateKey = ByteArray(32) { 0x42.toByte() }

        val seedDay1 = ShortCodeGenerator.generateSeed(privateKey, 20689L)
        val seedDay2 = ShortCodeGenerator.generateSeed(privateKey, 20690L)

        // HMAC-SHA256 seeds must differ across days
        assertFalse(seedDay1.contentEquals(seedDay2))

        val codeDay1 = ShortCodeGenerator.deriveCode(seedDay1, wordlist, 20689L)
        val codeDay2 = ShortCodeGenerator.deriveCode(seedDay2, wordlist, 20690L)

        // Daily codes must rotate and differ
        assertNotEquals(codeDay1.toCompactString(), codeDay2.toCompactString())
    }

    @Test
    fun testParseInputFormats() {
        // Hyphen-separated
        val parsed1 = ShortCodeGenerator.parseInput("LION-COBALT-ORBIT-8492")
        assertNotNull(parsed1)
        assertEquals(listOf("LION", "COBALT", "ORBIT"), parsed1!!.first)
        assertEquals(8492, parsed1.second)

        // Em-dash separated with spaces
        val parsed2 = ShortCodeGenerator.parseInput("LION — COBALT — ORBIT — 8492")
        assertNotNull(parsed2)
        assertEquals(listOf("LION", "COBALT", "ORBIT"), parsed2!!.first)
        assertEquals(8492, parsed2.second)

        // Lowercase mixed spaces
        val parsed3 = ShortCodeGenerator.parseInput("lion cobalt orbit 0123")
        assertNotNull(parsed3)
        assertEquals(listOf("lion", "cobalt", "orbit"), parsed3!!.first)
        assertEquals(123, parsed3.second)

        // Invalid: missing number
        assertNull(ShortCodeGenerator.parseInput("LION-COBALT-ORBIT"))

        // Invalid: number not 4 digits
        assertNull(ShortCodeGenerator.parseInput("LION-COBALT-ORBIT-12"))

        // Invalid: 5 words
        assertNull(ShortCodeGenerator.parseInput("LION-COBALT-ORBIT-EXTRA-8492"))
    }

    @Test
    fun testValidateInputVariations() {
        val wordlist = loadWordlist()

        // Valid hyphenated uppercase
        val res1 = ShortCodeGenerator.validateInput("ABANDON-ABILITY-ABLE-0123", wordlist)
        assertNotNull(res1)
        assertEquals("ABANDON", res1!!.word1)
        assertEquals(123, res1.number)

        // Valid em-dash lowercase with spaces
        val res2 = ShortCodeGenerator.validateInput("abandon — ability — able — 0123", wordlist)
        assertNotNull(res2)
        assertEquals("ABANDON", res2!!.word1)
        assertEquals(123, res2.number)

        // Invalid word not in BIP-39
        val resInvalid = ShortCodeGenerator.validateInput("FOOBAR-ABILITY-ABLE-0123", wordlist)
        assertNull(resInvalid)

        // Invalid digit count
        val resBadDigits = ShortCodeGenerator.validateInput("ABANDON-ABILITY-ABLE-12", wordlist)
        assertNull(resBadDigits)
    }

    @Test
    fun testCodeToFingerprintHint() {
        val code = ShortCode("LION", "COBALT", "ORBIT", 8492, 20689L)
        val hint1 = ShortCodeGenerator.codeToFingerprintHint(code)
        val hint2 = ShortCodeGenerator.codeToFingerprintHint(code)

        assertEquals(4, hint1.size)
        assertArrayEquals(hint1, hint2)

        // Different code must produce different hint
        val codeOther = ShortCode("LION", "COBALT", "ORBIT", 8493, 20689L)
        val hintOther = ShortCodeGenerator.codeToFingerprintHint(codeOther)
        assertFalse(hint1.contentEquals(hintOther))
    }
}
