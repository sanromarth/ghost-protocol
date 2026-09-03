package com.ghostprotocol.security

import android.content.Context
import android.util.Log
import com.ghostprotocol.IdentityManager
import com.ghostprotocol.R
import com.ghostprotocol.crypto.ShortCode
import com.ghostprotocol.crypto.ShortCodeGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ShortCodeManager(
    private val context: Context,
    private val postureProvider: () -> SecurityPosture
) {
    private val _currentCode = MutableStateFlow<ShortCode?>(null)
    val currentCode: StateFlow<ShortCode?> = _currentCode.asStateFlow()

    private val _timeRemaining = MutableStateFlow("")
    val timeRemaining: StateFlow<String> = _timeRemaining.asStateFlow()

    val wordlist: List<String>
    private var countdownJob: Job? = null

    init {
        wordlist = try {
            context.resources.openRawResource(R.raw.bip39_english).bufferedReader().useLines { lines ->
                lines.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "GHOST_SHORTCODE: Failed to load BIP-39 wordlist from res/raw: ${e.message}", e)
            emptyList()
        }

        if (wordlist.size != 2048) {
            Log.e(TAG, "GHOST_SHORTCODE: Wordlist count is ${wordlist.size}, expected 2048. Short codes disabled.")
        } else {
            Log.d(TAG, "GHOST_SHORTCODE: Loaded ${wordlist.size} BIP-39 words successfully.")
        }

        refreshCode()
    }

    /**
     * Regenerates the daily short code based on today's UTC epoch day and Ed25519 private seed.
     * Silently cleared when in STEALTH posture.
     */
    fun refreshCode() {
        val posture = postureProvider()
        if (posture == SecurityPosture.STEALTH || wordlist.size != 2048) {
            _currentCode.value = null
            _timeRemaining.value = "Disabled in STEALTH mode"
            return
        }

        try {
            val epochDay = System.currentTimeMillis() / 86_400_000L
            val privateKeySeed = IdentityManager.getEd25519Seed()
            val seed = ShortCodeGenerator.generateSeed(privateKeySeed, epochDay)
            val code = ShortCodeGenerator.deriveCode(seed, wordlist, epochDay)
            _currentCode.value = code
            updateRemainingTimeString()
            Log.d(TAG, "GHOST_SHORTCODE: Refreshed short code for epochDay=$epochDay: ${code.toCompactString()}")
        } catch (e: Exception) {
            Log.e(TAG, "GHOST_SHORTCODE: Error deriving short code: ${e.message}", e)
            _currentCode.value = null
        }
    }

    /**
     * Starts background countdown timer updating timeRemaining every minute and auto-rotating at 00:00 UTC.
     */
    fun startCountdown(scope: CoroutineScope) {
        countdownJob?.cancel()
        countdownJob = scope.launch {
            while (isActive) {
                val posture = postureProvider()
                if (posture == SecurityPosture.STEALTH) {
                    _currentCode.value = null
                    _timeRemaining.value = "Disabled in STEALTH mode"
                } else {
                    val currentEpochDay = System.currentTimeMillis() / 86_400_000L
                    if (_currentCode.value == null || _currentCode.value?.epochDay != currentEpochDay) {
                        refreshCode()
                    }
                    updateRemainingTimeString()
                }
                delay(60_000L)
            }
        }
    }

    private fun updateRemainingTimeString() {
        val now = System.currentTimeMillis()
        val millisInDay = 86_400_000L
        val millisPassed = now % millisInDay
        val remainingMillis = millisInDay - millisPassed
        val hours = (remainingMillis / (3600 * 1000L)).toInt()
        val minutes = ((remainingMillis % (3600 * 1000L)) / (60 * 1000L)).toInt()
        _timeRemaining.value = "Valid for: ${hours}h ${minutes}m remaining"
    }

    /**
     * Returns the 4-byte hash hint of the current short code for BLE scan response.
     */
    fun getCurrentFingerprintHint(): ByteArray? {
        val code = _currentCode.value ?: return null
        return ShortCodeGenerator.codeToFingerprintHint(code)
    }

    /**
     * Resolves and validates a raw user input string into a ShortCode.
     * Supports formats like "LION-COBALT-ORBIT-8492" and "LION — COBALT — ORBIT — 8492".
     */
    fun resolveInput(input: String): ShortCode? {
        if (wordlist.size != 2048) return null
        val cleaned = input.replace("—", "-").replace("–", "-").trim()
        val tokens = cleaned.split(Regex("[\\s-]+")).filter { it.isNotBlank() }
        if (tokens.size != 4) return null

        val w1 = tokens[0].lowercase()
        val w2 = tokens[1].lowercase()
        val w3 = tokens[2].lowercase()
        val numStr = tokens[3]

        if (numStr.length != 4 || !numStr.all { it.isDigit() }) return null
        val number = numStr.toIntOrNull() ?: return null

        if (!ShortCodeGenerator.validateInput(listOf(w1, w2, w3), number, wordlist)) {
            return null
        }

        val epochDay = System.currentTimeMillis() / 86_400_000L
        return ShortCode(w1.uppercase(), w2.uppercase(), w3.uppercase(), number, epochDay)
    }

    fun cleanup() {
        countdownJob?.cancel()
        countdownJob = null
    }

    companion object {
        private const val TAG = "ShortCodeManager"
    }
}
