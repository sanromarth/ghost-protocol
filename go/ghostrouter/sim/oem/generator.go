package oem

import (
	"fmt"
	"math/rand"
	"sort"
	"time"
)

// SOURCE: Stage 4 Android OEM Hostile Runtime Engine Architecture
// CONTRACT: Invariants O1 through O24
// MODEL: Deterministic combinatorial scenario generator.

// ScenarioGenerator generates deterministic adversarial workloads across OEM profiles.
type ScenarioGenerator struct {
	baseSeed int64
}

// NewScenarioGenerator creates a scenario generator with the given base seed.
func NewScenarioGenerator(baseSeed int64) *ScenarioGenerator {
	return &ScenarioGenerator{
		baseSeed: baseSeed,
	}
}

// GenerateScenario generates a single deterministic scenario for a given scenario index and profile type.
func (g *ScenarioGenerator) GenerateScenario(index int, profileType OemProfileType) *OemScenario {
	profile := GetOemProfile(profileType)
	scenarioSeed := g.baseSeed ^ (int64(index)*1_000_003 + 0x5DEECE66D)
	rng := rand.New(rand.NewSource(scenarioSeed))

	duration := 60 * time.Second
	durationNs := duration.Nanoseconds()
	quiescenceWindowNs := 15 * time.Second.Nanoseconds()
	activeWindowNs := durationNs - quiescenceWindowNs

	events := make([]ScenarioEvent, 0, 128)
	scenarioId := fmt.Sprintf("oem_%s_%06d", profileType, index)

	numMessages := 5 + rng.Intn(15)
	msgCounter := 0

	type sentInfo struct {
		id       string
		timeNs   int64
		estDurNs int64
	}
	sentList := make([]sentInfo, 0, numMessages)

	// 1. Generate User Message Sends in active window
	for i := 0; i < numMessages; i++ {
		msgCounter++
		msgId := fmt.Sprintf("msg_%s_%03d", scenarioId, msgCounter)
		tNs := int64(1_000_000_000) + rng.Int63n(activeWindowNs-2_000_000_000)
		pLen := 50 + rng.Intn(300)
		targetPeer := fmt.Sprintf("peer_%02d", 1+rng.Intn(3))

		events = append(events, ScenarioEvent{
			TimestampNs: tNs,
			Type:        EventSendMessage,
			MessageID:   msgId,
			PeerID:      targetPeer,
			PayloadLen:  pLen,
		})

		estDur := profile.ExpectedGattDuration(pLen)
		sentList = append(sentList, sentInfo{
			id:       msgId,
			timeNs:   tNs,
			estDurNs: estDur,
		})
	}

	// 2. Generate Causal Delivery Receipts
	// A receipt can only arrive AFTER the message was sent + GATT transmission completed
	for _, sent := range sentList {
		// 80% of messages receive delivery receipts
		if rng.Float64() < 0.80 {
			receiptDelayNs := sent.estDurNs + int64((200+rng.Intn(800))*1_000_000)
			receiptTimeNs := sent.timeNs + receiptDelayNs
			if receiptTimeNs < activeWindowNs {
				events = append(events, ScenarioEvent{
					TimestampNs: receiptTimeNs,
					Type:        EventDeliveryReceipt,
					MessageID:   sent.id,
				})
			}
		}
	}

	// 3. Generate Third-Party Relay Packets
	numRelays := 5 + rng.Intn(10)
	for i := 0; i < numRelays; i++ {
		tNs := int64(1_500_000_000) + rng.Int63n(activeWindowNs-3_000_000_000)
		events = append(events, ScenarioEvent{
			TimestampNs: tNs,
			Type:        EventRelayPacket,
			MessageID:   fmt.Sprintf("relay_pkt_%03d", i+1),
		})
	}

	// 4. Generate MAC Rotations
	numRotations := 1 + rng.Intn(3)
	for i := 0; i < numRotations; i++ {
		tNs := int64(2_000_000_000) + rng.Int63n(activeWindowNs-4_000_000_000)
		newMac := fmt.Sprintf("FE:ED:%02X:%02X:%02X:%02X", rng.Intn(256), rng.Intn(256), rng.Intn(256), rng.Intn(256))
		events = append(events, ScenarioEvent{
			TimestampNs: tNs,
			Type:        EventRotateMac,
			ParamStr:    newMac,
		})
	}

	// 5. Inject Profile-Specific OEM Chaos in active window
	injectChaos(rng, profile, activeWindowNs, &events)

	// Sort events strictly monotonically by virtual timestamp
	sort.Slice(events, func(i, j int) bool {
		if events[i].TimestampNs != events[j].TimestampNs {
			return events[i].TimestampNs < events[j].TimestampNs
		}
		return events[i].Type < events[j].Type
	})

	return &OemScenario{
		ID:         scenarioId,
		Seed:       scenarioSeed,
		Profile:    profile,
		DurationNs: durationNs,
		Events:     events,
	}
}

// injectChaos injects adversarial events based on profile hostility parameters.
func injectChaos(rng *rand.Rand, profile OemProfile, activeWindowNs int64, events *[]ScenarioEvent) {
	// Process Kills (LMKD)
	if rng.Float64() < profile.LmkdKillRate {
		tNs := int64(5_000_000_000) + rng.Int63n(activeWindowNs-10_000_000_000)
		*events = append(*events, ScenarioEvent{
			TimestampNs: tNs,
			Type:        EventKillProcess,
		})
	}

	// Activity backgrounding / recreation
	if rng.Float64() < 0.35 {
		tNs := int64(3_000_000_000) + rng.Int63n(activeWindowNs-8_000_000_000)
		*events = append(*events, ScenarioEvent{
			TimestampNs: tNs,
			Type:        EventRecreateActivity,
		})
	}

	// Background service task swipe
	if rng.Float64() < profile.BackgroundServiceKillRate {
		tNs := int64(6_000_000_000) + rng.Int63n(activeWindowNs-12_000_000_000)
		*events = append(*events, ScenarioEvent{
			TimestampNs: tNs,
			Type:        EventTaskRemoved,
		})
	}

	// Bluetooth Adapter toggling
	if rng.Float64() < profile.GattFailureRate {
		tOffNs := int64(8_000_000_000) + rng.Int63n(activeWindowNs-16_000_000_000)
		tOnNs := tOffNs + int64((1000+rng.Intn(2000))*1_000_000)
		*events = append(*events, ScenarioEvent{
			TimestampNs: tOffNs,
			Type:        EventToggleBluetoothOff,
		})
		*events = append(*events, ScenarioEvent{
			TimestampNs: tOnNs,
			Type:        EventToggleBluetoothOn,
		})
	}

	// GATT 133 / Watchdog Timeouts
	if rng.Float64() < profile.Gatt133Rate {
		tNs := int64(4_000_000_000) + rng.Int63n(activeWindowNs-6_000_000_000)
		*events = append(*events, ScenarioEvent{
			TimestampNs: tNs,
			Type:        EventInjectGatt133,
		})
	}

	// Late Callbacks
	if rng.Float64() < profile.LateCallbackRate {
		tNs := int64(7_000_000_000) + rng.Int63n(activeWindowNs-10_000_000_000)
		*events = append(*events, ScenarioEvent{
			TimestampNs: tNs,
			Type:        EventInjectLateCallback,
		})
	}

	// Duplicate Callbacks
	if rng.Float64() < profile.DuplicateCallbackRate {
		tNs := int64(9_000_000_000) + rng.Int63n(activeWindowNs-12_000_000_000)
		*events = append(*events, ScenarioEvent{
			TimestampNs: tNs,
			Type:        EventInjectDuplicateCallback,
		})
	}

	// Battery Drain & Charging
	if profile.BatteryDrainMultiplier > 1.5 {
		tDrainNs := int64(10_000_000_000) + rng.Int63n(activeWindowNs-18_000_000_000)
		*events = append(*events, ScenarioEvent{
			TimestampNs: tDrainNs,
			Type:        EventDrainBattery,
			ParamInt:    65, // Drops 80% -> 15% (CRITICAL, relay gating triggered)
		})

		// Reconnect charger later
		tChargeNs := tDrainNs + int64(5000*1_000_000)
		*events = append(*events, ScenarioEvent{
			TimestampNs: tChargeNs,
			Type:        EventConnectCharger,
		})
	}

	// Storage pressure / disk full
	if rng.Float64() < profile.RoomIoErrorRate {
		tFillNs := int64(12_000_000_000) + rng.Int63n(activeWindowNs-20_000_000_000)
		tRestoreNs := tFillNs + int64(2000*1_000_000)
		*events = append(*events, ScenarioEvent{
			TimestampNs: tFillNs,
			Type:        EventFillDisk,
		})
		*events = append(*events, ScenarioEvent{
			TimestampNs: tRestoreNs,
			Type:        EventRestoreDisk,
		})
	}
}
