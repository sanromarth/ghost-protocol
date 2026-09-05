package oem

// SOURCE: Stage 4 Android OEM Hostile Runtime Engine Architecture
// CONTRACT: Delta-debugging scenario minimizer for deterministic minimal reproduction.

// Shrinker reduces a failing scenario to its minimal reproducing event set.
type Shrinker struct {
	maxIterations int
}

// NewShrinker creates a scenario shrinker.
func NewShrinker(maxIterations int) *Shrinker {
	if maxIterations <= 0 {
		maxIterations = 100
	}
	return &Shrinker{
		maxIterations: maxIterations,
	}
}

// Shrink attempts to minimize scenario events while reproducing at least one violation.
func (s *Shrinker) Shrink(scenario *OemScenario, executeFn func(*OemScenario) ([]InvariantViolation, error)) *OemScenario {
	initialViolations, err := executeFn(scenario)
	if err != nil || len(initialViolations) == 0 {
		return scenario // Not failing or errored
	}

	targetViolationID := initialViolations[0].ID
	currentEvents := make([]ScenarioEvent, len(scenario.Events))
	copy(currentEvents, scenario.Events)

	changed := true
	iter := 0

	for changed && iter < s.maxIterations && len(currentEvents) > 1 {
		changed = false
		iter++

		// Try removing chunks (delta-debugging)
		chunkSizes := []int{len(currentEvents) / 2, len(currentEvents) / 4, 1}
		for _, chunkSize := range chunkSizes {
			if chunkSize <= 0 {
				continue
			}

			for i := 0; i < len(currentEvents); i += chunkSize {
				end := i + chunkSize
				if end > len(currentEvents) {
					end = len(currentEvents)
				}

				// Candidate with chunk removed
				candidateEvents := make([]ScenarioEvent, 0, len(currentEvents)-(end-i))
				candidateEvents = append(candidateEvents, currentEvents[:i]...)
				candidateEvents = append(candidateEvents, currentEvents[end:]...)

				candidateScenario := &OemScenario{
					ID:         scenario.ID + "_shrunk",
					Seed:       scenario.Seed,
					Profile:    scenario.Profile,
					DurationNs: scenario.DurationNs,
					Events:     candidateEvents,
				}

				vList, execErr := executeFn(candidateScenario)
				if execErr == nil && hasViolationID(vList, targetViolationID) {
					currentEvents = candidateEvents
					changed = true
					break
				}
			}
			if changed {
				break
			}
		}
	}

	return &OemScenario{
		ID:         scenario.ID + "_minimized",
		Seed:       scenario.Seed,
		Profile:    scenario.Profile,
		DurationNs: scenario.DurationNs,
		Events:     currentEvents,
	}
}

func hasViolationID(list []InvariantViolation, target InvariantID) bool {
	for _, v := range list {
		if v.ID == target {
			return true
		}
	}
	return false
}
