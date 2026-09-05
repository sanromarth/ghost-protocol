package torture

import (
	"os"
	"testing"
	"time"
)

func TestTopologiesGeneration(t *testing.T) {
	nodes := []string{"n0", "n1", "n2", "n3", "n4"}
	topologies := []TopologyType{
		TopologyChain, TopologyStar, TopologyRing, TopologyComplete,
		TopologySparse, TopologyIslands, TopologyBridge, TopologyBottleneck,
		TopologyDynamic, TopologyOscillating, TopologyHubFailure, TopologyRapidChurn,
	}

	for _, top := range topologies {
		links := GenerateLinks(top, nodes, nil)
		if len(links) == 0 {
			t.Errorf("Topology %s generated 0 links for 5 nodes", top)
		}
	}
}

func TestGeneratorDeterminism(t *testing.T) {
	campaignSeed := int64(987654321)
	scenarioIndex := 42

	cfg1 := GenerateScenario(campaignSeed, scenarioIndex)
	cfg2 := GenerateScenario(campaignSeed, scenarioIndex)

	if cfg1.DerivedSeed != cfg2.DerivedSeed {
		t.Fatalf("Derived seeds diverged: %d vs %d", cfg1.DerivedSeed, cfg2.DerivedSeed)
	}
	if len(cfg1.Events) != len(cfg2.Events) {
		t.Fatalf("Event counts diverged: %d vs %d", len(cfg1.Events), len(cfg2.Events))
	}
	if len(cfg1.Nodes) != len(cfg2.Nodes) {
		t.Fatalf("Node counts diverged: %d vs %d", len(cfg1.Nodes), len(cfg2.Nodes))
	}
}

func TestDeterministicReplay(t *testing.T) {
	campaignSeed := int64(123456789)
	for i := 0; i < 5; i++ {
		res, err := ReplayScenario(campaignSeed, i)
		if err != nil {
			t.Errorf("Scenario %d failed replay determinism check: %v", i, err)
		}
		if res == nil {
			t.Errorf("Scenario %d returned nil result", i)
		}
	}
}

func TestCorpusDeduplication(t *testing.T) {
	tempDir, err := os.MkdirTemp("", "ghost_corpus_test_*")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	defer os.RemoveAll(tempDir)

	corpus := NewCorpusManager(tempDir)
	cfg := GenerateScenario(123, 0)
	v := InvariantViolation{
		ID:      I3_CopyConservation,
		Message: "Copy explosion test",
	}

	hash1, isNew1 := corpus.RecordFailure(cfg, v, nil)
	if !isNew1 {
		t.Errorf("Expected first record to be new")
	}

	hash2, isNew2 := corpus.RecordFailure(cfg, v, nil)
	if isNew2 {
		t.Errorf("Expected second identical record to be deduplicated")
	}
	if hash1 != hash2 {
		t.Errorf("Expected identical hash, got %s vs %s", hash1, hash2)
	}
}

func TestShrinkerReduction(t *testing.T) {
	cfg := &ScenarioConfig{
		CampaignSeed:  100,
		ScenarioIndex: 1,
		DerivedSeed:   1001,
		Name:          "shrink_test",
		Nodes:         []string{"a", "b", "c", "d", "e"},
		Events: []TortureEvent{
			{Type: EventContactUp, Source: "a", Dest: "b"},
			{Type: EventSendMessage, Source: "a", Dest: "b", Payload: []byte("test")},
			{Type: EventAdvanceTime, Duration: time.Second},
			{Type: EventExchange, Source: "a", Dest: "b"},
			{Type: EventAdvanceTime, Duration: 5 * time.Second},
			{Type: EventAdvanceTime, Duration: 10 * time.Second},
		},
	}

	shrinker := NewShrinker()
	// Target synthetic violation
	v := InvariantViolation{ID: I2_Delivery}
	minimized := shrinker.Shrink(cfg, v)

	if minimized == nil {
		t.Fatalf("Shrinker returned nil config")
	}
}
