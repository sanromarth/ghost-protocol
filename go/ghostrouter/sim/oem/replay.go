package oem

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
)

// SOURCE: Stage 4 Android OEM Hostile Runtime Engine Architecture
// CONTRACT: O21 (Exact Deterministic Replay) & Physical-to-Simulation Trace Bridge
// MODEL: Deterministic 5x replay verification and physical OEM trace translation.

// VerifyDeterministicReplay runs a scenario count times (default 5) and verifies exact identical outputs.
func VerifyDeterministicReplay(scenario *OemScenario, iterations int) error {
	if iterations <= 0 {
		iterations = 5
	}

	var firstHash string

	for i := 0; i < iterations; i++ {
		res, err := ExecuteOemScenario(scenario)
		if err != nil {
			return fmt.Errorf("iteration %d failed with error: %w", i+1, err)
		}

		hash := computeResultHash(res)
		if i == 0 {
			firstHash = hash
		} else if hash != firstHash {
			return fmt.Errorf("O21 violation: deterministic replay mismatch on iteration %d: expected hash %s, got %s",
				i+1, firstHash, hash)
		}
	}

	return nil
}

// computeResultHash computes a deterministic SHA-256 fingerprint of a scenario execution result.
func computeResultHash(res *ScenarioResult) string {
	h := sha256.New()
	// Include all deterministic outputs (excluding non-deterministic wall clock time)
	fmt.Fprintf(h, "id:%s|passed:%t|msgsSent:%d|msgsDelivered:%d|relayed:%d|gated:%d|kills:%d|restarts:%d|gatt133:%d|gattTO:%d|lateCb:%d|violations:%d\n",
		res.ScenarioID, res.Passed, res.MessagesSent, res.MessagesDelivered,
		res.MessagesRelayed, res.MessagesGated, res.ProcessKills, res.ServiceRestarts,
		res.Gatt133Count, res.GattTimeouts, res.LateCallbacks, len(res.Violations))

	for _, v := range res.Violations {
		fmt.Fprintf(h, "v:%s:%s:%s:%d\n", v.ID, v.Severity, v.Component, v.Timestamp)
	}

	return hex.EncodeToString(h.Sum(nil))
}

// ImportPhysicalTrace reads a PhysicalObservationTrace JSON file recorded from an Android device.
func ImportPhysicalTrace(filePath string) (*PhysicalObservationTrace, error) {
	data, err := os.ReadFile(filePath)
	if err != nil {
		return nil, fmt.Errorf("failed to read physical trace file %s: %w", filePath, err)
	}

	var trace PhysicalObservationTrace
	if err := json.Unmarshal(data, &trace); err != nil {
		return nil, fmt.Errorf("failed to parse physical trace JSON: %w", err)
	}

	return &trace, nil
}

// ConvertPhysicalTraceToScenario transforms a physical trace into a replayable OemScenario.
func ConvertPhysicalTraceToScenario(trace *PhysicalObservationTrace, profile OemProfile) *OemScenario {
	events := make([]ScenarioEvent, 0, len(trace.Events))

	for _, pe := range trace.Events {
		var sType ScenarioEventType
		switch pe.EventType {
		case "BLUETOOTH_STATE_OFF":
			sType = EventToggleBluetoothOff
		case "BLUETOOTH_STATE_ON":
			sType = EventToggleBluetoothOn
		case "PROCESS_KILLED_LMKD":
			sType = EventKillProcess
		case "SERVICE_TASK_REMOVED":
			sType = EventTaskRemoved
		case "GATT_STATUS_133":
			sType = EventInjectGatt133
		case "GATT_TIMEOUT":
			sType = EventInjectGattTimeout
		case "BATTERY_CRITICAL":
			sType = EventDrainBattery
		default:
			continue
		}

		events = append(events, ScenarioEvent{
			TimestampNs: pe.TimestampNs,
			Type:        sType,
			MessageID:   pe.Details["message_id"],
		})
	}

	return &OemScenario{
		ID:         fmt.Sprintf("physical_%s_%s", trace.Manufacturer, trace.DeviceModel),
		Seed:       12345,
		Profile:    profile,
		DurationNs: 60 * 1_000_000_000,
		Events:     events,
	}
}
