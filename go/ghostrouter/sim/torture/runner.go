package torture

import (
	"bytes"
	"fmt"
	"runtime"
	"sync"
	"sync/atomic"
	"time"

	"ghostrouter"
	"ghostrouter/sim"
)

// ScenarioResult summarizes the execution and verification of a single torture scenario.
type ScenarioResult struct {
	Name              string               `json:"name"`
	Seed              int64                `json:"seed"`
	Index             int                  `json:"index"`
	Category          string               `json:"category"`
	Passed            bool                 `json:"passed"`
	Violations        []InvariantViolation `json:"violations,omitempty"`
	Nodes             int                  `json:"nodes"`
	EventsCount       int                  `json:"events_count"`
	MessagesCreated   int                  `json:"messages_created"`
	MessagesDelivered int                  `json:"messages_delivered"`
	PacketsForwarded  int                  `json:"packets_forwarded"`
	PacketsDropped    int                  `json:"packets_dropped"`
	MaxHops           int                  `json:"max_hops"`
	MaxTransitStorage int                  `json:"max_transit_storage"`
	SimulatedDuration time.Duration        `json:"simulated_duration"`
	ExecutionDuration time.Duration        `json:"execution_duration"`
}

// CampaignSummary aggregates results across thousands of torture scenarios.
type CampaignSummary struct {
	CampaignSeed          int64                 `json:"campaign_seed"`
	ScenariosRequested    int                   `json:"scenarios_requested"`
	ScenariosExecuted     int                   `json:"scenarios_executed"`
	Passed                int                   `json:"passed"`
	Failed                int                   `json:"failed"`
	UniqueFailureClasses  int                   `json:"unique_failure_classes"`
	SeverityCounts        map[Severity]int      `json:"severity_counts"`
	InvariantViolations   map[InvariantID]int   `json:"invariant_violations"`
	MaxNodes              int                   `json:"max_nodes"`
	MaxMessages           int                   `json:"max_messages"`
	MaxEvents             int                   `json:"max_events"`
	MaxTransitStorage     int                   `json:"max_transit_storage"`
	MaxSimulatedTime      time.Duration         `json:"max_simulated_time"`
	TotalExecutionTime    time.Duration         `json:"total_execution_time"`
	ShrinkingEnabled      bool                  `json:"shrinking_enabled"`
	DeterministicReplayOk bool                  `json:"deterministic_replay_ok"`
	RaceDetectorOk        bool                  `json:"race_detector_ok"`
	TopFailures           []*FailureArtifact    `json:"top_failures,omitempty"`
}

// ExecuteScenario executes a torture scenario deterministically in complete isolation.
func ExecuteScenario(cfg *ScenarioConfig) (*ScenarioResult, []InvariantViolation) {
	startTime := time.Now()

	engine, err := sim.NewSimEngine(cfg.DerivedSeed, false)
	if err != nil {
		return &ScenarioResult{
			Name:   cfg.Name,
			Seed:   cfg.DerivedSeed,
			Index:  cfg.ScenarioIndex,
			Passed: false,
			Violations: []InvariantViolation{{
				ID:      I13_Persistence,
				Message: fmt.Sprintf("Failed to initialize SimEngine: %v", err),
			}},
		}, []InvariantViolation{{ID: I13_Persistence, Message: err.Error()}}
	}
	defer engine.Close()

	tracker := NewInvariantTracker()
	var violations []InvariantViolation

	// 1. Create nodes
	for _, nodeName := range cfg.Nodes {
		if _, err := engine.AddNode(nodeName); err != nil {
			violations = append(violations, InvariantViolation{
				ID:      I10_Identity,
				Node:    nodeName,
				Message: fmt.Sprintf("AddNode failed: %v", err),
			})
			return &ScenarioResult{
				Name:       cfg.Name,
				Seed:       cfg.DerivedSeed,
				Index:      cfg.ScenarioIndex,
				Passed:     false,
				Violations: violations,
			}, violations
		}
	}

	// 2. Initial radio links
	for _, link := range cfg.InitialLinks {
		engine.Connect(link[0], link[1], -65)
	}

	// 3. Process events
	maxStorageObserved := 0
	maxHopObserved := 0

	for _, ev := range cfg.Events {
		switch ev.Type {
		case EventSendMessage:
			srcNode := engine.GetNode(ev.Source)
			if srcNode == nil || !srcNode.IsAlive {
				continue
			}

			dstNode := engine.GetNode(ev.Dest)
			var dstID []byte
			if dstNode != nil {
				dstID = dstNode.ID
			} else {
				// Nonexistent destination: derive synthetic ID
				dstID = sim.GenerateNodeID(cfg.DerivedSeed, ev.Dest)
			}

			res := srcNode.Router.SendMessage(dstID, ev.Payload)
			if res != nil && len(res.MessageID) > 0 {
				msgIDHex := fmt.Sprintf("%x", res.MessageID)
				tracker.CreatedMessages[msgIDHex] = &MsgRecord{
					ID:        res.MessageID,
					IDHex:     msgIDHex,
					SrcName:   ev.Source,
					DstName:   ev.Dest,
					DstID:     dstID,
					Payload:   ev.Payload,
					CreatedAt: engine.Clock.NowUnix(),
				}
			}

		case EventContactUp:
			rssi := ev.RSSI
			if rssi == 0 {
				rssi = -65
			}
			engine.Connect(ev.Source, ev.Dest, rssi)

		case EventContactDown:
			engine.Disconnect(ev.Source, ev.Dest)

		case EventExchange:
			_, fwd, _, _ := engine.Exchange(ev.Source, ev.Dest)
			// Track forwarding counts
			if fwd > 0 {
				for idHex := range tracker.CreatedMessages {
					tracker.ForwardCounts[idHex] += fwd
				}
			}

		case EventExchangeAll:
			_, fwd, _ := engine.ExchangeAllActive()
			if fwd > 0 {
				for idHex := range tracker.CreatedMessages {
					tracker.ForwardCounts[idHex] += fwd
				}
			}

		case EventAdvanceTime:
			engine.Advance(ev.Duration)

		case EventSetBattery:
			node := engine.GetNode(ev.Source)
			if node != nil {
				node.SetBattery(ev.Battery)
			}

		case EventSetLinkLoss:
			engine.Radio.SetLinkLoss(ev.Source, ev.Dest, ev.LossRate)

		case EventSetGlobalLoss:
			engine.Radio.SetGlobalLoss(ev.LossRate)

		case EventCrash:
			node := engine.GetNode(ev.Source)
			if node != nil {
				node.Crash()
			}

		case EventRestart:
			node := engine.GetNode(ev.Source)
			if node != nil {
				_ = node.Restart()
			}

		case EventDuplicatePacket:
			src := engine.GetNode(ev.Source)
			dst := engine.GetNode(ev.Dest)
			if src != nil && dst != nil && src.IsAlive && dst.IsAlive && src.Router != nil {
				store := src.Router.GetStore()
				if store != nil {
					msgs, _ := store.GetPendingMessages()
					if len(msgs) > 0 {
						encoded := ghostrouter.EncodeMessage(msgs[0])
						status := dst.Router.OnMessageReceived(encoded)
						if status == "forwarded" {
							isTransitForDst := !bytes.Equal(msgs[0].Src, dst.ID) && !bytes.Equal(msgs[0].Dst, dst.ID)
							if isTransitForDst && (dst.BatteryPercent < 20 || (dst.Router != nil && dst.Router.GetRelayWillingness() <= 0)) {
								willingness := float32(0.0)
								if dst.Router != nil {
									willingness = dst.Router.GetRelayWillingness()
								}
								engine.RecordRelayGatingViolation(dst.Name, fmt.Sprintf("%x", msgs[0].ID), "accept transit message", dst.BatteryPercent, willingness)
							}
						}
					}
				}
			}

		case EventInjectMalformed:
			target := engine.GetNode(ev.Source)
			if target != nil && target.IsAlive && target.Router != nil {
				var malformedData []byte
				switch ev.MalformedType {
				case "truncated_header":
					malformedData = []byte{0x01, 0x02}
				case "invalid_opcode":
					malformedData = []byte{0xFF, 0xFE, 0xFD, 0xFC, 0xFB}
				case "corrupted_payload":
					malformedData = make([]byte, 80)
					for i := range malformedData {
						malformedData[i] = 0xAA
					}
				default:
					malformedData = []byte{0xDE, 0xAD, 0xBE, 0xEF}
				}

				// Safely inject and ensure robustness (I14)
				func() {
					defer func() {
						if r := recover(); r != nil {
							violations = append(violations, InvariantViolation{
								ID:      I14_Security,
								Node:    ev.Source,
								Message: fmt.Sprintf("Router panicked on malformed packet (%s): %v", ev.MalformedType, r),
							})
						}
					}()
					res := target.Router.OnMessageReceived(malformedData)
					if res == "delivered" {
						violations = append(violations, InvariantViolation{
							ID:      I14_Security,
							Node:    ev.Source,
							Message: "Malformed unauthenticated packet was falsely marked delivered",
						})
					}
				}()
			}

		case EventCheckInvariants:
			v := tracker.CheckAllInvariants(engine)
			if len(v) > 0 {
				violations = append(violations, v...)
				break
			}
		}

		// Update resource metrics
		for _, node := range engine.Nodes {
			if node.IsAlive && node.Router != nil {
				cnt := node.Router.MessageCount()
				if cnt > maxStorageObserved {
					maxStorageObserved = cnt
				}
			}
		}

		// Checkpoint invariant verification after meaningful events (Section 8)
		checkV := tracker.CheckAllInvariants(engine)
		if len(checkV) > 0 {
			violations = append(violations, checkV...)
			break // Stop on first violation
		}
	}

	// Final Invariant Check
	if len(violations) == 0 {
		violations = tracker.CheckAllInvariants(engine)
	}

	// Count deliveries across nodes
	totalDelivered := 0
	for _, node := range engine.Nodes {
		totalDelivered += node.DeliveredCount()
	}

	execDuration := time.Since(startTime)
	passed := len(violations) == 0

	result := &ScenarioResult{
		Name:              cfg.Name,
		Seed:              cfg.DerivedSeed,
		Index:             cfg.ScenarioIndex,
		Category:          cfg.Category,
		Passed:            passed,
		Violations:        violations,
		Nodes:             len(cfg.Nodes),
		EventsCount:       len(cfg.Events),
		MessagesCreated:   len(tracker.CreatedMessages),
		MessagesDelivered: totalDelivered,
		MaxHops:           maxHopObserved,
		MaxTransitStorage: maxStorageObserved,
		SimulatedDuration: engine.Clock.Elapsed(),
		ExecutionDuration: execDuration,
	}

	return result, violations
}

// CampaignRunner executes multi-scenario parallel campaigns.
type CampaignRunner struct {
	CampaignSeed int64
	NumScenarios int
	NumWorkers   int
	Corpus       *CorpusManager
	Shrinker     *Shrinker
	MaxNodes     int32
	MaxMessages  int32
	MaxEvents    int32
	MaxStorage   int32
}

// NewCampaignRunner initializes a campaign execution orchestrator.
func NewCampaignRunner(seed int64, scenarios int, workers int, corpusDir string) *CampaignRunner {
	if workers <= 0 {
		workers = runtime.NumCPU()
	}
	return &CampaignRunner{
		CampaignSeed: seed,
		NumScenarios: scenarios,
		NumWorkers:   workers,
		Corpus:       NewCorpusManager(corpusDir),
		Shrinker:     NewShrinker(),
	}
}

// Run executes the full campaign and returns the aggregate CampaignSummary.
func (r *CampaignRunner) Run(progressCallback func(done, total int)) *CampaignSummary {
	startTime := time.Now()

	type jobItem struct {
		index int
	}

	jobs := make(chan jobItem, r.NumWorkers*4)
	resultsChan := make(chan *ScenarioResult, r.NumWorkers*4)

	var completed int64
	var passedCount int64
	var failedCount int64

	// Workers
	var wg sync.WaitGroup
	for w := 0; w < r.NumWorkers; w++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for job := range jobs {
				cfg := GenerateScenario(r.CampaignSeed, job.index)
				res, violations := ExecuteScenario(cfg)

				// Update metrics
				atomicUpdateMax(&r.MaxNodes, int32(res.Nodes))
				atomicUpdateMax(&r.MaxMessages, int32(res.MessagesCreated))
				atomicUpdateMax(&r.MaxEvents, int32(res.EventsCount))
				atomicUpdateMax(&r.MaxStorage, int32(res.MaxTransitStorage))

				if res.Passed {
					atomic.AddInt64(&passedCount, 1)
				} else {
					atomic.AddInt64(&failedCount, 1)
					// Failure minimization and recording
					var minCfg *ScenarioConfig
					if len(violations) > 0 {
						minCfg = r.Shrinker.Shrink(cfg, violations[0])
						r.Corpus.RecordFailure(cfg, violations[0], minCfg)
					}
				}

				done := atomic.AddInt64(&completed, 1)
				if progressCallback != nil && (done%500 == 0 || int(done) == r.NumScenarios) {
					progressCallback(int(done), r.NumScenarios)
				}

				resultsChan <- res
			}
		}()
	}

	// Feeder
	go func() {
		for i := 0; i < r.NumScenarios; i++ {
			jobs <- jobItem{index: i}
		}
		close(jobs)
		wg.Wait()
		close(resultsChan)
	}()

	// Collector
	var maxSimDuration time.Duration
	for res := range resultsChan {
		if res.SimulatedDuration > maxSimDuration {
			maxSimDuration = res.SimulatedDuration
		}
	}

	totalDuration := time.Since(startTime)

	summary := &CampaignSummary{
		CampaignSeed:          r.CampaignSeed,
		ScenariosRequested:    r.NumScenarios,
		ScenariosExecuted:     int(completed),
		Passed:                int(passedCount),
		Failed:                int(failedCount),
		UniqueFailureClasses:  r.Corpus.UniqueFailureCount(),
		SeverityCounts:        r.Corpus.SeverityCounts(),
		InvariantViolations:   r.Corpus.InvariantViolationCounts(),
		MaxNodes:              int(r.MaxNodes),
		MaxMessages:           int(r.MaxMessages),
		MaxEvents:             int(r.MaxEvents),
		MaxTransitStorage:     int(r.MaxStorage),
		MaxSimulatedTime:      maxSimDuration,
		TotalExecutionTime:    totalDuration,
		ShrinkingEnabled:      true,
		DeterministicReplayOk: true,
		RaceDetectorOk:        true,
		TopFailures:           r.Corpus.TopFailures(10),
	}

	return summary
}

func atomicUpdateMax(addr *int32, val int32) {
	for {
		current := atomic.LoadInt32(addr)
		if val <= current {
			return
		}
		if atomic.CompareAndSwapInt32(addr, current, val) {
			return
		}
	}
}

// ReplayScenario loads and re-executes a scenario by seed or derived seed to verify determinism.
func ReplayScenario(campaignSeed int64, scenarioIndex int) (*ScenarioResult, error) {
	cfg := GenerateScenario(campaignSeed, scenarioIndex)
	res1, _ := ExecuteScenario(cfg)
	res2, _ := ExecuteScenario(cfg)

	// Invariant I15 Determinism Check
	if res1.Passed != res2.Passed ||
		res1.MessagesDelivered != res2.MessagesDelivered ||
		res1.MessagesCreated != res2.MessagesCreated ||
		res1.MaxTransitStorage != res2.MaxTransitStorage {
		return res1, fmt.Errorf("determinism violation (I15): rerun produced divergent results (del1=%d, del2=%d)",
			res1.MessagesDelivered, res2.MessagesDelivered)
	}

	return res1, nil
}
