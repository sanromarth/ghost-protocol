package oem

import (
	"fmt"
	"sync"
	"time"
)

// SOURCE: Stage 4 Android OEM Hostile Runtime Engine Architecture
// CONTRACT: Invariants O1 through O24 & recovery metrics
// MODEL: Comprehensive metrics collection across campaign scenarios.

// ScenarioResult records the outcome of a single scenario run.
type ScenarioResult struct {
	ScenarioID       string               `json:"scenario_id"`
	ProfileType      OemProfileType       `json:"profile_type"`
	Seed             int64                `json:"seed"`
	Passed           bool                 `json:"passed"`
	Violations       []InvariantViolation `json:"violations,omitempty"`
	DurationNs       int64                `json:"duration_ns"`
	MessagesSent     int                  `json:"messages_sent"`
	MessagesDelivered int                 `json:"messages_delivered"`
	MessagesRelayed  int                  `json:"messages_relayed"`
	MessagesGated    int                  `json:"messages_gated"`
	ProcessKills     int                  `json:"process_kills"`
	ServiceRestarts  int                  `json:"service_restarts"`
	Gatt133Count     int                  `json:"gatt_133_count"`
	GattTimeouts     int                  `json:"gatt_timeouts"`
	LateCallbacks    int                  `json:"late_callbacks"`
	ExecutionWallTime time.Duration       `json:"execution_wall_time"`
}

// CampaignMetrics aggregates results across all scenarios.
type CampaignMetrics struct {
	mu sync.Mutex

	TotalScenarios   int
	PassedScenarios  int
	FailedScenarios  int
	TotalWallTime    time.Duration

	// Violations by severity
	P0Violations int
	P1Violations int
	P2Violations int
	P3Violations int
	P4Violations int

	// Violations by Invariant
	ViolationsByInvariant map[InvariantID]int

	// Totals
	TotalMessagesSent      int
	TotalMessagesDelivered int
	TotalMessagesRelayed   int
	TotalMessagesGated     int
	TotalProcessKills      int
	TotalServiceRestarts   int
	TotalGatt133           int
	TotalGattTimeouts      int
	TotalLateCallbacks     int

	// Results by profile
	ResultsByProfile map[OemProfileType]*ProfileAggregate
}

// ProfileAggregate tracks metrics per OEM profile.
type ProfileAggregate struct {
	TotalScenarios  int
	PassedScenarios int
	FailedScenarios int
	P0Count         int
	P1Count         int
	P2Count         int
	P3Count         int
	P4Count         int
}

// NewCampaignMetrics creates an initialized campaign metrics accumulator.
func NewCampaignMetrics() *CampaignMetrics {
	return &CampaignMetrics{
		ViolationsByInvariant: make(map[InvariantID]int),
		ResultsByProfile:      make(map[OemProfileType]*ProfileAggregate),
	}
}

// RecordScenario adds the result of a single scenario to the campaign metrics.
func (m *CampaignMetrics) RecordScenario(res *ScenarioResult) {
	m.mu.Lock()
	defer m.mu.Unlock()

	m.TotalScenarios++
	m.TotalMessagesSent += res.MessagesSent
	m.TotalMessagesDelivered += res.MessagesDelivered
	m.TotalMessagesRelayed += res.MessagesRelayed
	m.TotalMessagesGated += res.MessagesGated
	m.TotalProcessKills += res.ProcessKills
	m.TotalServiceRestarts += res.ServiceRestarts
	m.TotalGatt133 += res.Gatt133Count
	m.TotalGattTimeouts += res.GattTimeouts
	m.TotalLateCallbacks += res.LateCallbacks

	profAgg, exists := m.ResultsByProfile[res.ProfileType]
	if !exists {
		profAgg = &ProfileAggregate{}
		m.ResultsByProfile[res.ProfileType] = profAgg
	}
	profAgg.TotalScenarios++

	if res.Passed && len(res.Violations) == 0 {
		m.PassedScenarios++
		profAgg.PassedScenarios++
	} else {
		m.FailedScenarios++
		profAgg.FailedScenarios++
		for _, v := range res.Violations {
			m.ViolationsByInvariant[v.ID]++
			switch v.Severity {
			case SeverityP0:
				m.P0Violations++
				profAgg.P0Count++
			case SeverityP1:
				m.P1Violations++
				profAgg.P1Count++
			case SeverityP2:
				m.P2Violations++
				profAgg.P2Count++
			case SeverityP3:
				m.P3Violations++
				profAgg.P3Count++
			case SeverityP4:
				m.P4Violations++
				profAgg.P4Count++
			}
		}
	}
}

// SummaryString returns a human-readable summary of the metrics.
func (m *CampaignMetrics) SummaryString() string {
	m.mu.Lock()
	defer m.mu.Unlock()

	return fmt.Sprintf(
		"Total: %d | Pass: %d | Fail: %d | P0: %d | P1: %d | P2: %d | P3: %d | P4: %d | ProcessKills: %d | Restarts: %d | GATT133: %d",
		m.TotalScenarios, m.PassedScenarios, m.FailedScenarios,
		m.P0Violations, m.P1Violations, m.P2Violations, m.P3Violations, m.P4Violations,
		m.TotalProcessKills, m.TotalServiceRestarts, m.TotalGatt133,
	)
}
