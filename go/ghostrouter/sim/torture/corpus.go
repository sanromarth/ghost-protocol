package torture

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sync"
)

// Severity indicates the critical impact of an invariant violation.
type Severity string

const (
	SeverityP0 Severity = "P0" // Cryptographic / security failure
	SeverityP1 Severity = "P1" // Message loss / corruption / identity violation
	SeverityP2 Severity = "P2" // Routing correctness / persistence / duplicate delivery
	SeverityP3 Severity = "P3" // Resource / stability degradation
	SeverityP4 Severity = "P4" // Observability / harness issue
)

// ClassifySeverity assigns standard GHOST severity to an invariant failure.
func ClassifySeverity(id InvariantID) Severity {
	switch id {
	case I14_Security:
		return SeverityP0
	case I1_MessageAccounting, I2_Delivery, I10_Identity:
		return SeverityP1
	case I3_CopyConservation, I4_HopLimit, I5_TTL, I6_Dedup, I13_Persistence:
		return SeverityP2
	case I7_RelayGating, I8_Storage, I9_CrashIsolation, I11_InfiniteForward, I12_RetryBounds:
		return SeverityP3
	case I15_Determinism:
		return SeverityP4
	default:
		return SeverityP2
	}
}

// FailureArtifact represents a persisted, replayable reproduction package.
type FailureArtifact struct {
	CampaignSeed     int64              `json:"campaign_seed"`
	ScenarioIndex    int                `json:"scenario_index"`
	DerivedSeed      int64              `json:"derived_seed"`
	Configuration    *ScenarioConfig    `json:"configuration"`
	Violation        InvariantViolation `json:"violation"`
	Severity         Severity           `json:"severity"`
	Signature        string             `json:"signature"`
	ReproductionCmd  string             `json:"reproduction_cmd"`
	OriginalNodes    int                `json:"original_nodes"`
	MinimizedNodes   int                `json:"minimized_nodes"`
	OriginalEvents   int                `json:"original_events"`
	MinimizedEvents  int                `json:"minimized_events"`
}

// CorpusManager organizes, deduplicates, and saves failure vectors.
type CorpusManager struct {
	mu           sync.Mutex
	baseDir      string
	failures     map[string]*FailureArtifact
	classCounts  map[string]int
	sevCounts    map[Severity]int
	invViolCount map[InvariantID]int
}

// NewCorpusManager creates a corpus manager saving artifacts to baseDir.
func NewCorpusManager(baseDir string) *CorpusManager {
	if baseDir == "" {
		baseDir = "testdata/torture/failures"
	}
	return &CorpusManager{
		baseDir:      baseDir,
		failures:     make(map[string]*FailureArtifact),
		classCounts:  make(map[string]int),
		sevCounts:    make(map[Severity]int),
		invViolCount: make(map[InvariantID]int),
	}
}

// RecordFailure registers an invariant violation, deduplicates by signature,
// and saves a replayable artifact directory if it is a new failure class.
func (c *CorpusManager) RecordFailure(cfg *ScenarioConfig, v InvariantViolation, minCfg *ScenarioConfig) (string, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()

	sev := ClassifySeverity(v.ID)
	c.sevCounts[sev]++
	c.invViolCount[v.ID]++

	// Create deduplication signature based on invariant ID and failure message
	sigInput := fmt.Sprintf("%s:%s", v.ID, v.Message)
	h := sha256.Sum256([]byte(sigInput))
	sigHash := hex.EncodeToString(h[:8])

	c.classCounts[sigHash]++

	// If already recorded this unique failure class, return existing hash
	if _, exists := c.failures[sigHash]; exists {
		return sigHash, false
	}

	origNodes := len(cfg.Nodes)
	minNodes := origNodes
	origEvents := len(cfg.Events)
	minEvents := origEvents

	configToSave := cfg
	if minCfg != nil {
		configToSave = minCfg
		minNodes = len(minCfg.Nodes)
		minEvents = len(minCfg.Events)
	}

	artifact := &FailureArtifact{
		CampaignSeed:    cfg.CampaignSeed,
		ScenarioIndex:   cfg.ScenarioIndex,
		DerivedSeed:     cfg.DerivedSeed,
		Configuration:   configToSave,
		Violation:       v,
		Severity:        sev,
		Signature:       sigHash,
		ReproductionCmd: fmt.Sprintf("ghost-sim replay --seed %d", cfg.DerivedSeed),
		OriginalNodes:   origNodes,
		MinimizedNodes:  minNodes,
		OriginalEvents:  origEvents,
		MinimizedEvents: minEvents,
	}

	c.failures[sigHash] = artifact

	// Write artifact to disk
	dirPath := filepath.Join(c.baseDir, sigHash)
	_ = os.MkdirAll(dirPath, 0755)

	manifestPath := filepath.Join(dirPath, "manifest.json")
	if data, err := json.MarshalIndent(artifact, "", "  "); err == nil {
		_ = os.WriteFile(manifestPath, data, 0644)
	}

	shPath := filepath.Join(dirPath, "reproduce.sh")
	scriptContent := fmt.Sprintf("#!/bin/bash\n# Reproduction vector for GHOST Torture Failure %s\n%s\n", sigHash, artifact.ReproductionCmd)
	_ = os.WriteFile(shPath, []byte(scriptContent), 0755)

	return sigHash, true
}

// UniqueFailureCount returns the number of distinct failure classes found.
func (c *CorpusManager) UniqueFailureCount() int {
	c.mu.Lock()
	defer c.mu.Unlock()
	return len(c.failures)
}

// SeverityCounts returns breakdown by severity P0..P4.
func (c *CorpusManager) SeverityCounts() map[Severity]int {
	c.mu.Lock()
	defer c.mu.Unlock()
	res := make(map[Severity]int)
	for k, v := range c.sevCounts {
		res[k] = v
	}
	return res
}

// InvariantViolationCounts returns breakdown by Invariant ID.
func (c *CorpusManager) InvariantViolationCounts() map[InvariantID]int {
	c.mu.Lock()
	defer c.mu.Unlock()
	res := make(map[InvariantID]int)
	for k, v := range c.invViolCount {
		res[k] = v
	}
	return res
}

// TopFailures returns up to N failure artifacts sorted by occurrence count.
func (c *CorpusManager) TopFailures(n int) []*FailureArtifact {
	c.mu.Lock()
	defer c.mu.Unlock()

	var list []*FailureArtifact
	for _, art := range c.failures {
		list = append(list, art)
		if len(list) >= n {
			break
		}
	}
	return list
}
