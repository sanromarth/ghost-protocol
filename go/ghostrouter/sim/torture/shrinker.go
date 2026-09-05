package torture

import (
	"fmt"
)

// Shrinker performs delta-debugging and binary reduction on failing scenarios
// to produce minimal, human-readable reproduction test vectors.
type Shrinker struct {
	maxIterations int
}

// NewShrinker initializes an automated test case shrinker.
func NewShrinker() *Shrinker {
	return &Shrinker{
		maxIterations: 50,
	}
}

// Shrink reduces the node count, message workload, and event sequence of a failing scenario
// while verifying that the same invariant violation still reproduces.
func (s *Shrinker) Shrink(cfg *ScenarioConfig, targetViolation InvariantViolation) *ScenarioConfig {
	if cfg == nil || len(cfg.Events) <= 1 {
		return cfg
	}

	minimized := copyConfig(cfg)

	// Phase 1: Binary search on event prefix
	// Find the earliest event prefix that triggers the violation
	low := 1
	high := len(minimized.Events)
	bestPrefix := high

	for low <= high {
		mid := (low + high) / 2
		testCfg := copyConfig(minimized)
		testCfg.Events = testCfg.Events[:mid]

		_, violations := ExecuteScenario(testCfg)
		if hasMatchingViolation(violations, targetViolation.ID) {
			bestPrefix = mid
			high = mid - 1 // Try even shorter prefix
		} else {
			low = mid + 1 // Violation hasn't occurred yet
		}
	}

	minimized.Events = minimized.Events[:bestPrefix]

	// Phase 2: Eliminate non-essential intermediate events (1-by-1 delta reduction)
	if len(minimized.Events) > 2 {
		for i := len(minimized.Events) - 2; i >= 0; i-- {
			if len(minimized.Events) <= 2 {
				break
			}
			testEvents := make([]TortureEvent, 0, len(minimized.Events)-1)
			testEvents = append(testEvents, minimized.Events[:i]...)
			testEvents = append(testEvents, minimized.Events[i+1:]...)

			testCfg := copyConfig(minimized)
			testCfg.Events = testEvents

			_, violations := ExecuteScenario(testCfg)
			if hasMatchingViolation(violations, targetViolation.ID) {
				minimized.Events = testEvents
			}
		}
	}

	// Phase 3: Node count reduction
	// Try removing peripheral nodes that aren't necessary for the failure
	if len(minimized.Nodes) > 2 {
		for i := len(minimized.Nodes) - 1; i >= 0; i-- {
			if len(minimized.Nodes) <= 2 {
				break
			}
			candidateNode := minimized.Nodes[i]
			// Do not remove if node is referenced in remaining events
			isReferenced := false
			for _, ev := range minimized.Events {
				if ev.Source == candidateNode || ev.Dest == candidateNode {
					isReferenced = true
					break
				}
			}
			if isReferenced {
				continue
			}

			// Safe to prune unreferenced node
			testNodes := make([]string, 0, len(minimized.Nodes)-1)
			testNodes = append(testNodes, minimized.Nodes[:i]...)
			testNodes = append(testNodes, minimized.Nodes[i+1:]...)

			testCfg := copyConfig(minimized)
			testCfg.Nodes = testNodes

			_, violations := ExecuteScenario(testCfg)
			if hasMatchingViolation(violations, targetViolation.ID) {
				minimized.Nodes = testNodes
			}
		}
	}

	minimized.Name = fmt.Sprintf("%s_minimized", minimized.Name)
	return minimized
}

func hasMatchingViolation(violations []InvariantViolation, targetID InvariantID) bool {
	for _, v := range violations {
		if v.ID == targetID {
			return true
		}
	}
	return false
}

func copyConfig(src *ScenarioConfig) *ScenarioConfig {
	nodesCopy := make([]string, len(src.Nodes))
	copy(nodesCopy, src.Nodes)

	linksCopy := make([][2]string, len(src.InitialLinks))
	copy(linksCopy, src.InitialLinks)

	eventsCopy := make([]TortureEvent, len(src.Events))
	copy(eventsCopy, src.Events)

	return &ScenarioConfig{
		CampaignSeed:  src.CampaignSeed,
		ScenarioIndex: src.ScenarioIndex,
		DerivedSeed:   src.DerivedSeed,
		Name:          src.Name,
		Category:      src.Category,
		Topology:      src.Topology,
		Nodes:         nodesCopy,
		InitialLinks:  linksCopy,
		Events:        eventsCopy,
	}
}
