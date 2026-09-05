package com.ghostprotocol

import com.ghostprotocol.power.PowerMode
import com.ghostprotocol.power.PowerPolicyEngine
import com.ghostprotocol.security.SecurityPosture
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for PowerPolicyEngine verifying low-battery relay protection,
 * posture overrides, and battery threshold hysteresis.
 */
class PowerPolicyTest {

    @Test
    fun testLowBatteryRelayProtectionAcrossAllPostures() {
        val engine = PowerPolicyEngine()

        // 1. Stealth mode: 15% battery -> relay willingness = 0.0
        val stealth = engine.updateInputs(
            batteryPercent = 15,
            isCharging = false,
            screenOn = true,
            peerCount = 5,
            queueSize = 2,
            timeSinceLastEncounterMs = 10_000L,
            isMoving = true,
            securityPosture = SecurityPosture.STEALTH
        )
        assertEquals("Relay willingness must be 0.0 at <20% in STEALTH", 0.0f, stealth.relayWillingness, 0.001f)

        // 2. Protest mode: 15% battery -> relay willingness must STILL be 0.0
        val protest = engine.updateInputs(
            batteryPercent = 15,
            isCharging = false,
            screenOn = true,
            peerCount = 5,
            queueSize = 2,
            timeSinceLastEncounterMs = 10_000L,
            isMoving = true,
            securityPosture = SecurityPosture.PROTEST
        )
        assertEquals("Relay willingness must be 0.0 at <20% in PROTEST", 0.0f, protest.relayWillingness, 0.001f)

        // 3. Emergency mode: 15% battery -> relay willingness must STILL be 0.0
        val emergency = engine.updateInputs(
            batteryPercent = 15,
            isCharging = false,
            screenOn = true,
            peerCount = 5,
            queueSize = 2,
            timeSinceLastEncounterMs = 10_000L,
            isMoving = true,
            securityPosture = SecurityPosture.EMERGENCY
        )
        assertEquals("Relay willingness must be 0.0 at <20% in EMERGENCY", 0.0f, emergency.relayWillingness, 0.001f)
    }

    @Test
    fun testChargingOverridesLowBatteryRelayCutoff() {
        val engine = PowerPolicyEngine()

        // 10% battery, but charging -> relay allowed, mode is ACTIVE
        val chargingPolicy = engine.updateInputs(
            batteryPercent = 10,
            isCharging = true,
            screenOn = false,
            peerCount = 0,
            queueSize = 0,
            timeSinceLastEncounterMs = 100_000L,
            isMoving = false,
            securityPosture = SecurityPosture.STEALTH
        )
        assertTrue("Relay willingness must be > 0.0 when charging even at 10%", chargingPolicy.relayWillingness > 0.0f)
        assertEquals(PowerMode.ACTIVE, chargingPolicy.mode)
    }

    @Test
    fun testBatteryHysteresisFalling() {
        val engine = PowerPolicyEngine()

        // 22% -> ECO
        var p = engine.updateInputs(22, false, true, 2, 0, 1000L, true)
        assertEquals(PowerMode.ECO, p.mode)
        assertFalse(engine.isCriticalBattery())

        // 21% -> ECO
        p = engine.updateInputs(21, false, true, 2, 0, 1000L, true)
        assertEquals(PowerMode.ECO, p.mode)
        assertFalse(engine.isCriticalBattery())

        // 20% -> ECO (threshold is strictly < 20)
        p = engine.updateInputs(20, false, true, 2, 0, 1000L, true)
        assertEquals(PowerMode.ECO, p.mode)
        assertFalse(engine.isCriticalBattery())

        // 19% -> Enters CRITICAL
        p = engine.updateInputs(19, false, true, 2, 0, 1000L, true)
        assertEquals(PowerMode.CRITICAL, p.mode)
        assertTrue(engine.isCriticalBattery())
        assertEquals(0.0f, p.relayWillingness, 0.001f)
    }

    @Test
    fun testBatteryHysteresisRising() {
        val engine = PowerPolicyEngine()

        // Start at 19% -> CRITICAL
        var p = engine.updateInputs(19, false, true, 2, 0, 1000L, true)
        assertEquals(PowerMode.CRITICAL, p.mode)
        assertTrue(engine.isCriticalBattery())

        // Rise to 20% -> Still CRITICAL (inside hysteresis deadband [20..21])
        p = engine.updateInputs(20, false, true, 2, 0, 1000L, true)
        assertEquals("Must remain CRITICAL at 20% to prevent thrashing", PowerMode.CRITICAL, p.mode)
        assertTrue(engine.isCriticalBattery())

        // Rise to 21% -> Still CRITICAL (inside hysteresis deadband)
        p = engine.updateInputs(21, false, true, 2, 0, 1000L, true)
        assertEquals("Must remain CRITICAL at 21% to prevent thrashing", PowerMode.CRITICAL, p.mode)
        assertTrue(engine.isCriticalBattery())

        // Rise to 22% -> Exits CRITICAL to ECO
        p = engine.updateInputs(22, false, true, 2, 0, 1000L, true)
        assertEquals("Must exit CRITICAL at >=22%", PowerMode.ECO, p.mode)
        assertFalse(engine.isCriticalBattery())
        assertTrue("Relay willingness restored", p.relayWillingness > 0.0f)
    }

    @Test
    fun testDeepSleepCondition() {
        val engine = PowerPolicyEngine()

        val deepSleep = engine.updateInputs(
            batteryPercent = 80,
            isCharging = false,
            screenOn = false,
            peerCount = 0,
            queueSize = 0,
            timeSinceLastEncounterMs = 45 * 60 * 1000L, // 45 mins idle
            isMoving = false
        )
        assertEquals(PowerMode.DEEP_SLEEP, deepSleep.mode)
        assertEquals(0.0f, deepSleep.relayWillingness, 0.001f)
    }
}
