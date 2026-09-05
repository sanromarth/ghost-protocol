package ux

import (
	"encoding/json"
	"fmt"
	"os"
)

// SOURCE: Replay CLI & Trace Exporter (Section 35)
// CONTRACT: U15 (100% Deterministic Bit-for-Bit Replay)
// MODEL: Standalone scenario replay engine with structured JSON trace export.

// TraceStep records a single discrete step in the replayed execution.
type TraceStep struct {
	StepNumber  int    `json:"step_number"`
	TimestampNs int64  `json:"timestamp_ns"`
	EventType   string `json:"event_type"`
	Description string `json:"description"`
	GattState   string `json:"gatt_state,omitempty"`
	QueueDepth  int    `json:"queue_depth"`
}

// ReplayTrace contains the complete forensic audit log of a scenario run.
type ReplayTrace struct {
	ScenarioID string               `json:"scenario_id"`
	Seed       int64                `json:"seed"`
	Profile    string               `json:"profile"`
	Steps      []TraceStep          `json:"steps"`
	Violations []InvariantViolation `json:"violations"`
	Passed     bool                 `json:"passed"`
}

// ReplayFromSeed re-generates and executes a scenario by seed and index, returning complete forensic trace.
func ReplayFromSeed(masterSeed int64, scenarioIndex int) (*ReplayTrace, error) {
	gen := NewScenarioGenerator(masterSeed)
	scenario := gen.GenerateScenario(scenarioIndex)
	return ReplayScenario(&scenario)
}

// ReplayFromFile loads a scenario JSON file and executes it forensically.
func ReplayFromFile(filePath string) (*ReplayTrace, error) {
	data, err := os.ReadFile(filePath)
	if err != nil {
		return nil, fmt.Errorf("read scenario file error: %w", err)
	}

	var scenario Scenario
	if err := json.Unmarshal(data, &scenario); err != nil {
		// Attempt parsing as MinimalRepro
		var repro MinimalRepro
		if rErr := json.Unmarshal(data, &repro); rErr == nil && repro.ShrunkScenario.ID != "" {
			scenario = repro.ShrunkScenario
		} else {
			return nil, fmt.Errorf("unmarshal scenario error: %w", err)
		}
	}

	return ReplayScenario(&scenario)
}

// ReplayScenario executes a scenario recording every step into a ReplayTrace.
func ReplayScenario(s *Scenario) (*ReplayTrace, error) {
	trace := &ReplayTrace{
		ScenarioID: s.ID,
		Seed:       s.Seed,
		Profile:    s.Profile.Name,
		Steps:      make([]TraceStep, 0, len(s.Events)+10),
	}

	stepCount := 0
	recordStep := func(ts int64, evType, desc, gattState string, qDepth int) {
		stepCount++
		trace.Steps = append(trace.Steps, TraceStep{
			StepNumber:  stepCount,
			TimestampNs: ts,
			EventType:   evType,
			Description: desc,
			GattState:   gattState,
			QueueDepth:  qDepth,
		})
	}

	recordStep(0, "INIT", fmt.Sprintf("Initializing scenario %s with %s profile", s.ID, s.Profile.Name), "IDLE", 0)

	res, err := ExecuteScenario(s)
	if err != nil {
		return nil, err
	}

	trace.Violations = res.Violations
	trace.Passed = res.Passed

	for idx, ev := range s.Events {
		desc := fmt.Sprintf("[%s] action=%s msg=%s rcpt=%s grp=%s", ev.Type, ev.Action, ev.MessageID, ev.RecipientID, ev.GroupID)
		recordStep(ev.TimeOffsetNs, string(ev.Type), desc, "", idx)
	}

	recordStep(res.DurationNs, "COMPLETE", fmt.Sprintf("Finished scenario execution. Passed: %t, Violations: %d", res.Passed, len(res.Violations)), "", 0)

	return trace, nil
}

// ExportTraceJSON writes the trace to a file.
func ExportTraceJSON(trace *ReplayTrace, outPath string) error {
	data, err := json.MarshalIndent(trace, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(outPath, data, 0644)
}
