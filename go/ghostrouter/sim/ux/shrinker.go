package ux

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
)

// SOURCE: Failure Shrinking & Delta Reduction Engine (Section 34)
// CONTRACT: U15 (Determinism)
// MODEL: Delta-debugging (ddmin) algorithm to reduce failing scenario to minimal reproducible event sequence.

// MinimalRepro stores the reduced reproducer details.
type MinimalRepro struct {
	OriginalScenarioID string             `json:"original_scenario_id"`
	OriginalSeed       int64              `json:"original_seed"`
	Violation          InvariantViolation `json:"violation"`
	ShrunkScenario     Scenario           `json:"shrunk_scenario"`
	EventCountOriginal int                `json:"event_count_original"`
	EventCountShrunk   int                `json:"event_count_shrunk"`
	Hash               string             `json:"hash"`
}

// ScenarioShrinker minimizes scenarios that triggered invariant violations.
type ScenarioShrinker struct {
	executor func(s *Scenario) []InvariantViolation
}

// NewScenarioShrinker creates a shrinker with a test execution callback.
func NewScenarioShrinker(executor func(s *Scenario) []InvariantViolation) *ScenarioShrinker {
	return &ScenarioShrinker{executor: executor}
}

// Shrink applies delta debugging to find the minimal event subsequence triggering targetViolation.
func (sh *ScenarioShrinker) Shrink(orig Scenario, targetViolation InvariantViolation) MinimalRepro {
	current := orig
	origEventCount := len(orig.Events)

	// Step 1: Delta debugging on event sequence
	currentEvents := make([]ScheduledScenarioEvent, len(orig.Events))
	copy(currentEvents, orig.Events)

	granularity := 2
	for len(currentEvents) >= 2 && granularity <= len(currentEvents) {
		chunkSize := len(currentEvents) / granularity
		reduced := false

		for i := 0; i < granularity; i++ {
			start := i * chunkSize
			end := start + chunkSize
			if i == granularity-1 {
				end = len(currentEvents)
			}

			// Try removing chunk [start:end]
			candidateEvents := make([]ScheduledScenarioEvent, 0, len(currentEvents)-(end-start))
			candidateEvents = append(candidateEvents, currentEvents[:start]...)
			candidateEvents = append(candidateEvents, currentEvents[end:]...)

			candidateScenario := current
			candidateScenario.Events = candidateEvents

			violations := sh.executor(&candidateScenario)
			if sh.containsViolation(violations, targetViolation.ID) {
				currentEvents = candidateEvents
				current = candidateScenario
				reduced = true
				break
			}
		}

		if reduced {
			granularity = 2
		} else {
			granularity *= 2
		}
	}

	// Step 2: Try removing single events one by one
	for i := len(currentEvents) - 1; i >= 0; i-- {
		if len(currentEvents) <= 1 {
			break
		}
		candidateEvents := make([]ScheduledScenarioEvent, 0, len(currentEvents)-1)
		candidateEvents = append(candidateEvents, currentEvents[:i]...)
		candidateEvents = append(candidateEvents, currentEvents[i+1:]...)

		candidateScenario := current
		candidateScenario.Events = candidateEvents

		violations := sh.executor(&candidateScenario)
		if sh.containsViolation(violations, targetViolation.ID) {
			currentEvents = candidateEvents
			current = candidateScenario
		}
	}

	// Step 3: Try shrinking initial message count
	initCounts := []int{0, 1, 10, 50, 100}
	for _, cnt := range initCounts {
		if cnt >= current.InitialMessages {
			continue
		}
		candidateScenario := current
		candidateScenario.InitialMessages = cnt
		violations := sh.executor(&candidateScenario)
		if sh.containsViolation(violations, targetViolation.ID) {
			current = candidateScenario
			break
		}
	}

	// Compute stable repro hash
	hasher := sha256.New()
	hasher.Write([]byte(fmt.Sprintf("%s:%s:%d:%d", targetViolation.ID, targetViolation.Severity, current.Seed, len(current.Events))))
	reproHash := hex.EncodeToString(hasher.Sum(nil))[:16]

	repro := MinimalRepro{
		OriginalScenarioID: orig.ID,
		OriginalSeed:       orig.Seed,
		Violation:          targetViolation,
		ShrunkScenario:     current,
		EventCountOriginal: origEventCount,
		EventCountShrunk:   len(current.Events),
		Hash:               reproHash,
	}

	return repro
}

func (sh *ScenarioShrinker) containsViolation(violations []InvariantViolation, targetID InvariantID) bool {
	for _, v := range violations {
		if v.ID == targetID {
			return true
		}
	}
	return false
}

// SaveRepro writes the shrunk reproducer to disk at testdata/ux/failures/<hash>/
func SaveRepro(outputDir string, repro MinimalRepro) (string, error) {
	targetDir := filepath.Join(outputDir, "testdata", "ux", "failures", repro.Hash)
	if err := os.MkdirAll(targetDir, 0755); err != nil {
		return "", err
	}

	jsonPath := filepath.Join(targetDir, "repro.json")
	data, err := json.MarshalIndent(repro, "", "  ")
	if err != nil {
		return "", err
	}

	if err := os.WriteFile(jsonPath, data, 0644); err != nil {
		return "", err
	}

	reportPath := filepath.Join(targetDir, "repro_report.txt")
	report := fmt.Sprintf("=== GHOST UX TORTURE ENGINE REPRODUCER ===\n"+
		"Hash: %s\n"+
		"Original Scenario: %s (Seed: %d)\n"+
		"Violation: [%s][%s] %s\n"+
		"Original Events: %d -> Shrunk Events: %d\n"+
		"Device Profile: %s\n",
		repro.Hash, repro.OriginalScenarioID, repro.OriginalSeed,
		repro.Violation.Severity, repro.Violation.ID, repro.Violation.Message,
		repro.EventCountOriginal, repro.EventCountShrunk,
		repro.ShrunkScenario.Profile.Name,
	)

	_ = os.WriteFile(reportPath, []byte(report), 0644)
	return targetDir, nil
}
