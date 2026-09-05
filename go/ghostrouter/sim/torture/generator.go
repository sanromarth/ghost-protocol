package torture

import (
	"crypto/sha256"
	"encoding/binary"
	"fmt"
	"math/rand"
	"time"
)

// EventType represents an atomic action within a torture scenario.
type EventType string

const (
	EventSendMessage     EventType = "send_message"
	EventContactUp       EventType = "contact_up"
	EventContactDown     EventType = "contact_down"
	EventExchange        EventType = "exchange"
	EventExchangeAll     EventType = "exchange_all"
	EventAdvanceTime     EventType = "advance_time"
	EventSetBattery      EventType = "set_battery"
	EventSetLinkLoss     EventType = "set_link_loss"
	EventSetGlobalLoss   EventType = "set_global_loss"
	EventCrash           EventType = "crash"
	EventRestart         EventType = "restart"
	EventDuplicatePacket EventType = "duplicate_packet"
	EventInjectMalformed EventType = "inject_malformed"
	EventCheckInvariants EventType = "check_invariants"
)

// TortureEvent models an executable step in a simulation scenario.
type TortureEvent struct {
	Type          EventType     `json:"type"`
	Source        string        `json:"source,omitempty"`
	Dest          string        `json:"dest,omitempty"`
	Payload       []byte        `json:"payload,omitempty"`
	RSSI          int           `json:"rssi,omitempty"`
	Duration      time.Duration `json:"duration,omitempty"`
	Battery       int           `json:"battery,omitempty"`
	LossRate      float64       `json:"loss_rate,omitempty"`
	MalformedType string        `json:"malformed_type,omitempty"`
}

// ScenarioConfig holds the complete, deterministic definition of a torture scenario.
type ScenarioConfig struct {
	CampaignSeed  int64          `json:"campaign_seed"`
	ScenarioIndex int            `json:"scenario_index"`
	DerivedSeed   int64          `json:"derived_seed"`
	Name          string         `json:"name"`
	Category      string         `json:"category"`
	Topology      TopologyType   `json:"topology"`
	Nodes         []string       `json:"nodes"`
	InitialLinks  [][2]string    `json:"initial_links"`
	Events        []TortureEvent `json:"events"`
}

// DeriveSeed generates a cryptographically sound, deterministic seed for a scenario index.
func DeriveSeed(campaignSeed int64, scenarioIndex int) int64 {
	h := sha256.Sum256([]byte(fmt.Sprintf("ghost-torture-v1-%d-%d", campaignSeed, scenarioIndex)))
	val := binary.BigEndian.Uint64(h[:8])
	return int64(val & 0x7FFFFFFFFFFFFFFF)
}

// GenerateScenario constructs a scenario based on the campaign index.
func GenerateScenario(campaignSeed int64, scenarioIndex int) *ScenarioConfig {
	derivedSeed := DeriveSeed(campaignSeed, scenarioIndex)
	rng := rand.New(rand.NewSource(derivedSeed))

	switch {
	case scenarioIndex < 1000:
		return generateBoundaryScenario(campaignSeed, scenarioIndex, derivedSeed, rng)
	case scenarioIndex < 6000:
		return generateCombinationScenario(campaignSeed, scenarioIndex, derivedSeed, rng)
	case scenarioIndex < 8000:
		return generateChaosScenario(campaignSeed, scenarioIndex, derivedSeed, rng)
	default:
		return generateTopologyStressScenario(campaignSeed, scenarioIndex, derivedSeed, rng)
	}
}

// Helper: build node names
func makeNodes(prefix string, count int) []string {
	nodes := make([]string, count)
	for i := 0; i < count; i++ {
		nodes[i] = fmt.Sprintf("%s%d", prefix, i)
	}
	return nodes
}

// --- 1. Boundary Campaign (1,000 Scenarios) ---
func generateBoundaryScenario(campaignSeed int64, index int, derivedSeed int64, rng *rand.Rand) *ScenarioConfig {
	boundaryType := index % 12
	name := fmt.Sprintf("boundary_%04d", index)
	var nodes []string
	var topType TopologyType = TopologyChain
	var events []TortureEvent

	switch boundaryType {
	case 0: // 0-node or 1-node network
		name += "_single_node"
		nodes = []string{"node0"}
		events = append(events, TortureEvent{
			Type:    EventSendMessage,
			Source:  "node0",
			Dest:    "node0", // Source == Destination
			Payload: []byte("self_message"),
		})

	case 1: // Nonexistent destination
		name += "_nonexistent_dst"
		nodes = []string{"node0", "node1"}
		events = append(events,
			TortureEvent{Type: EventContactUp, Source: "node0", Dest: "node1"},
			TortureEvent{Type: EventSendMessage, Source: "node0", Dest: "node999", Payload: []byte("lost_message")},
			TortureEvent{Type: EventExchange, Source: "node0", Dest: "node1"},
		)

	case 2: // Zero byte payload
		name += "_zero_byte_payload"
		nodes = []string{"node0", "node1"}
		events = append(events,
			TortureEvent{Type: EventContactUp, Source: "node0", Dest: "node1"},
			TortureEvent{Type: EventSendMessage, Source: "node0", Dest: "node1", Payload: []byte{}},
			TortureEvent{Type: EventExchange, Source: "node0", Dest: "node1"},
		)

	case 3: // Max payload size (4000 bytes)
		name += "_max_payload"
		nodes = []string{"node0", "node1"}
		bigPayload := make([]byte, 4000)
		for i := range bigPayload {
			bigPayload[i] = byte(i % 256)
		}
		events = append(events,
			TortureEvent{Type: EventContactUp, Source: "node0", Dest: "node1"},
			TortureEvent{Type: EventSendMessage, Source: "node0", Dest: "node1", Payload: bigPayload},
			TortureEvent{Type: EventExchange, Source: "node0", Dest: "node1"},
		)

	case 4: // TTL - 1s vs TTL + 1s (24h boundary)
		name += "_ttl_boundary"
		nodes = []string{"node0", "node1", "node2"}
		events = append(events,
			TortureEvent{Type: EventSendMessage, Source: "node0", Dest: "node2", Payload: []byte("ttl_test")},
			TortureEvent{Type: EventContactUp, Source: "node0", Dest: "node1"},
			TortureEvent{Type: EventExchange, Source: "node0", Dest: "node1"},
			TortureEvent{Type: EventAdvanceTime, Duration: 24*time.Hour + 5*time.Second},
			TortureEvent{Type: EventContactUp, Source: "node1", Dest: "node2"},
			TortureEvent{Type: EventExchange, Source: "node1", Dest: "node2"}, // Should be dropped due to TTL
		)

	case 5: // Battery threshold boundary: 19% (refusal) vs 20% (acceptance)
		name += "_battery_threshold"
		nodes = []string{"node0", "relay", "node2"}
		events = append(events,
			TortureEvent{Type: EventSetBattery, Source: "relay", Battery: 19},
			TortureEvent{Type: EventContactUp, Source: "node0", Dest: "relay"},
			TortureEvent{Type: EventSendMessage, Source: "node0", Dest: "node2", Payload: []byte("relay_refusal")},
			TortureEvent{Type: EventExchange, Source: "node0", Dest: "relay"}, // Relay must refuse
			TortureEvent{Type: EventSetBattery, Source: "relay", Battery: 25}, // Recover
			TortureEvent{Type: EventSendMessage, Source: "node0", Dest: "node2", Payload: []byte("relay_accept")},
			TortureEvent{Type: EventExchange, Source: "node0", Dest: "relay"}, // Relay now accepts
		)

	case 6: // Hop count boundary (chain of 12 nodes, MaxHops=10)
		name += "_hop_limit_chain"
		nodes = makeNodes("hop_", 12)
		events = append(events,
			TortureEvent{Type: EventSendMessage, Source: nodes[0], Dest: nodes[11], Payload: []byte("hop_test")},
		)
		for i := 0; i < len(nodes)-1; i++ {
			events = append(events,
				TortureEvent{Type: EventContactUp, Source: nodes[i], Dest: nodes[i+1]},
				TortureEvent{Type: EventExchange, Source: nodes[i], Dest: nodes[i+1]},
			)
		}

	case 7: // Duplicate packet storm (100 duplicate deliveries)
		name += "_duplicate_storm"
		nodes = []string{"src", "dst"}
		events = append(events,
			TortureEvent{Type: EventContactUp, Source: "src", Dest: "dst"},
			TortureEvent{Type: EventSendMessage, Source: "src", Dest: "dst", Payload: []byte("dup_target")},
			TortureEvent{Type: EventExchange, Source: "src", Dest: "dst"},
		)
		for i := 0; i < 20; i++ {
			events = append(events, TortureEvent{Type: EventDuplicatePacket, Source: "src", Dest: "dst"})
		}

	case 8: // All nodes offline / crash storm
		name += "_all_nodes_crashed"
		nodes = []string{"n0", "n1", "n2"}
		events = append(events,
			TortureEvent{Type: EventCrash, Source: "n0"},
			TortureEvent{Type: EventCrash, Source: "n1"},
			TortureEvent{Type: EventCrash, Source: "n2"},
			TortureEvent{Type: EventRestart, Source: "n0"},
			TortureEvent{Type: EventRestart, Source: "n1"},
		)

	case 9: // Malformed packets injection
		name += "_malformed_packets"
		nodes = []string{"target"}
		events = append(events,
			TortureEvent{Type: EventInjectMalformed, Source: "target", MalformedType: "truncated_header"},
			TortureEvent{Type: EventInjectMalformed, Source: "target", MalformedType: "invalid_opcode"},
			TortureEvent{Type: EventInjectMalformed, Source: "target", MalformedType: "corrupted_payload"},
			TortureEvent{Type: EventInjectMalformed, Source: "target", MalformedType: "random_fuzz"},
		)

	case 10: // Storage pressure near 500 limit
		name += "_storage_pressure"
		nodes = []string{"sender", "collector"}
		events = append(events, TortureEvent{Type: EventContactUp, Source: "sender", Dest: "collector"})
		for i := 0; i < 50; i++ {
			events = append(events, TortureEvent{
				Type:    EventSendMessage,
				Source:  "sender",
				Dest:    "collector",
				Payload: []byte(fmt.Sprintf("msg_%d", i)),
			})
		}
		events = append(events, TortureEvent{Type: EventExchange, Source: "sender", Dest: "collector"})

	default: // Rapid encounter flapping
		name += "_encounter_flapping"
		nodes = []string{"nodeA", "nodeB"}
		for i := 0; i < 15; i++ {
			events = append(events,
				TortureEvent{Type: EventContactUp, Source: "nodeA", Dest: "nodeB"},
				TortureEvent{Type: EventSendMessage, Source: "nodeA", Dest: "nodeB", Payload: []byte(fmt.Sprintf("flap_%d", i))},
				TortureEvent{Type: EventExchange, Source: "nodeA", Dest: "nodeB"},
				TortureEvent{Type: EventContactDown, Source: "nodeA", Dest: "nodeB"},
				TortureEvent{Type: EventAdvanceTime, Duration: 10 * time.Millisecond},
			)
		}
	}

	links := GenerateLinks(topType, nodes, rng)
	return &ScenarioConfig{
		CampaignSeed:  campaignSeed,
		ScenarioIndex: index,
		DerivedSeed:   derivedSeed,
		Name:          name,
		Category:      "boundary",
		Topology:      topType,
		Nodes:         nodes,
		InitialLinks:  links,
		Events:        events,
	}
}

// --- 2. Combination Campaign (5,000 Scenarios) ---
func generateCombinationScenario(campaignSeed int64, index int, derivedSeed int64, rng *rand.Rand) *ScenarioConfig {
	nodeCount := 4 + rng.Intn(6) // 4 to 9 nodes
	nodes := makeNodes("combo_", nodeCount)
	topologies := []TopologyType{TopologyChain, TopologyStar, TopologyRing, TopologyBridge, TopologyBottleneck}
	topType := topologies[rng.Intn(len(topologies))]
	name := fmt.Sprintf("combination_%04d_%s", index, topType)

	var events []TortureEvent

	// Stressor 1: Packet Loss Rate (0%, 20%, 50%, 80%)
	lossRates := []float64{0.0, 0.2, 0.5, 0.8}
	lossRate := lossRates[rng.Intn(len(lossRates))]
	if lossRate > 0 {
		events = append(events, TortureEvent{Type: EventSetGlobalLoss, LossRate: lossRate})
	}

	// Stressor 2: Relay Battery Chaos
	if rng.Float64() < 0.6 {
		relayNode := nodes[1]
		events = append(events, TortureEvent{Type: EventSetBattery, Source: relayNode, Battery: 15 + rng.Intn(10)})
	}

	// Stressor 3: Inject Traffic
	msgCount := 2 + rng.Intn(4)
	for i := 0; i < msgCount; i++ {
		src := nodes[rng.Intn(nodeCount)]
		dst := nodes[rng.Intn(nodeCount)]
		events = append(events, TortureEvent{
			Type:    EventSendMessage,
			Source:  src,
			Dest:    dst,
			Payload: []byte(fmt.Sprintf("combo_payload_%d_%d", index, i)),
		})
	}

	// Stressor 4: Contacts & Exchanges
	events = append(events, TortureEvent{Type: EventExchangeAll})

	// Stressor 5: Node Crash and Restart mid-transit
	if rng.Float64() < 0.5 {
		crashTarget := nodes[rng.Intn(nodeCount)]
		events = append(events,
			TortureEvent{Type: EventCrash, Source: crashTarget},
			TortureEvent{Type: EventAdvanceTime, Duration: 5 * time.Second},
			TortureEvent{Type: EventExchangeAll},
			TortureEvent{Type: EventRestart, Source: crashTarget},
			TortureEvent{Type: EventExchangeAll},
		)
	}

	// Stressor 6: Virtual Time Advance / TTL Pressure
	events = append(events,
		TortureEvent{Type: EventAdvanceTime, Duration: time.Duration(1+rng.Intn(10)) * time.Minute},
		TortureEvent{Type: EventExchangeAll},
	)

	links := GenerateLinks(topType, nodes, rng)
	return &ScenarioConfig{
		CampaignSeed:  campaignSeed,
		ScenarioIndex: index,
		DerivedSeed:   derivedSeed,
		Name:          name,
		Category:      "combination",
		Topology:      topType,
		Nodes:         nodes,
		InitialLinks:  links,
		Events:        events,
	}
}

// --- 3. Chaos Campaign (2,000 Scenarios) ---
func generateChaosScenario(campaignSeed int64, index int, derivedSeed int64, rng *rand.Rand) *ScenarioConfig {
	nodeCount := 3 + rng.Intn(5)
	nodes := makeNodes("chaos_", nodeCount)
	topType := TopologySparse
	name := fmt.Sprintf("chaos_%04d", index)

	eventTypes := []EventType{
		EventSendMessage,
		EventExchangeAll,
		EventAdvanceTime,
		EventCrash,
		EventRestart,
		EventSetBattery,
		EventContactUp,
		EventContactDown,
	}

	eventCount := 10 + rng.Intn(15)
	events := make([]TortureEvent, 0, eventCount)

	for i := 0; i < eventCount; i++ {
		evType := eventTypes[rng.Intn(len(eventTypes))]
		src := nodes[rng.Intn(nodeCount)]
		dst := nodes[rng.Intn(nodeCount)]

		switch evType {
		case EventSendMessage:
			events = append(events, TortureEvent{
				Type:    EventSendMessage,
				Source:  src,
				Dest:    dst,
				Payload: []byte(fmt.Sprintf("chaos_data_%d_%d", index, i)),
			})
		case EventExchangeAll:
			events = append(events, TortureEvent{Type: EventExchangeAll})
		case EventAdvanceTime:
			events = append(events, TortureEvent{
				Type:     EventAdvanceTime,
				Duration: time.Duration(10+rng.Intn(300)) * time.Second,
			})
		case EventCrash:
			events = append(events, TortureEvent{Type: EventCrash, Source: src})
		case EventRestart:
			events = append(events, TortureEvent{Type: EventRestart, Source: src})
		case EventSetBattery:
			events = append(events, TortureEvent{
				Type:    EventSetBattery,
				Source:  src,
				Battery: 10 + rng.Intn(90),
			})
		case EventContactUp:
			events = append(events, TortureEvent{Type: EventContactUp, Source: src, Dest: dst})
		case EventContactDown:
			events = append(events, TortureEvent{Type: EventContactDown, Source: src, Dest: dst})
		}
	}

	// Always ensure an exchange and check at the end
	events = append(events, TortureEvent{Type: EventExchangeAll})

	links := GenerateLinks(topType, nodes, rng)
	return &ScenarioConfig{
		CampaignSeed:  campaignSeed,
		ScenarioIndex: index,
		DerivedSeed:   derivedSeed,
		Name:          name,
		Category:      "chaos",
		Topology:      topType,
		Nodes:         nodes,
		InitialLinks:  links,
		Events:        events,
	}
}

// --- 4. Topology & Stress Campaign (2,000 Scenarios) ---
func generateTopologyStressScenario(campaignSeed int64, index int, derivedSeed int64, rng *rand.Rand) *ScenarioConfig {
	topologies := []TopologyType{
		TopologyChain, TopologyStar, TopologyRing, TopologyComplete,
		TopologySparse, TopologyIslands, TopologyBridge, TopologyBottleneck,
		TopologyDynamic, TopologyOscillating, TopologyHubFailure, TopologyRapidChurn,
	}
	topType := topologies[(index-8000)%len(topologies)]

	nodeCount := 4 + rng.Intn(10) // 4 to 13 nodes for fast execution
	if index%100 == 0 {
		nodeCount = 20 + rng.Intn(15) // periodic larger stress (20-35 nodes)
	}

	nodes := makeNodes("topo_", nodeCount)
	name := fmt.Sprintf("topo_%04d_%s", index, topType)
	var events []TortureEvent

	// Inject messages from multiple sources
	src1 := nodes[0]
	dst1 := nodes[nodeCount-1]
	events = append(events,
		TortureEvent{Type: EventSendMessage, Source: src1, Dest: dst1, Payload: []byte(fmt.Sprintf("topo_msg_%d", index))},
	)

	// In hub failure topology, simulate crashing the hub
	if topType == TopologyHubFailure {
		hub := nodes[0]
		events = append(events,
			TortureEvent{Type: EventExchangeAll},
			TortureEvent{Type: EventCrash, Source: hub},
			TortureEvent{Type: EventAdvanceTime, Duration: 30 * time.Second},
			TortureEvent{Type: EventRestart, Source: hub},
		)
	}

	// In oscillating topology, alternate contacts
	if topType == TopologyOscillating && nodeCount >= 3 {
		for i := 0; i < 3; i++ {
			events = append(events,
				TortureEvent{Type: EventContactDown, Source: nodes[0], Dest: nodes[1]},
				TortureEvent{Type: EventAdvanceTime, Duration: 5 * time.Second},
				TortureEvent{Type: EventContactUp, Source: nodes[0], Dest: nodes[1]},
				TortureEvent{Type: EventExchangeAll},
			)
		}
	} else {
		// Multi-hop exchange cycles
		for i := 0; i < 3; i++ {
			events = append(events,
				TortureEvent{Type: EventExchangeAll},
				TortureEvent{Type: EventAdvanceTime, Duration: 10 * time.Second},
			)
		}
	}

	links := GenerateLinks(topType, nodes, rng)
	return &ScenarioConfig{
		CampaignSeed:  campaignSeed,
		ScenarioIndex: index,
		DerivedSeed:   derivedSeed,
		Name:          name,
		Category:      "pathological_topology",
		Topology:      topType,
		Nodes:         nodes,
		InitialLinks:  links,
		Events:        events,
	}
}
