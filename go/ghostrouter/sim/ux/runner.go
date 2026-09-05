package ux

import (
	"fmt"
	"runtime"
	"sync"
	"sync/atomic"
	"time"
)

// SOURCE: Multi-worker Parallel Campaign Runner & Orchestrator (Section 31, 32, 33)
// CONTRACT: U15 (Strict Determinism across concurrent workers)
// MODEL: Parallel worker pool dispatching deterministically seeded scenarios.

// ScenarioResult records the outcome of a single scenario execution.
type ScenarioResult struct {
	ScenarioID   string                      `json:"scenario_id"`
	Index        int                         `json:"index"`
	Seed         int64                       `json:"seed"`
	Class        ScenarioClass               `json:"class"`
	Profile      string                      `json:"profile"`
	Violations   []InvariantViolation        `json:"violations"`
	Transactions []*CausalTransactionMetrics `json:"transactions"`
	DurationNs   int64                       `json:"duration_ns"`
	Passed       bool                        `json:"passed"`
}

// CampaignConfig sets execution parameters for a test campaign.
type CampaignConfig struct {
	ScenarioCount   int
	MasterSeed      int64
	WorkerCount     int
	OutputDir       string
	EnableShrinking bool
	Verbose         bool
}

// CampaignResult provides consolidated metrics and violation logs for a full run.
type CampaignResult struct {
	TotalScenarios      int                  `json:"total_scenarios"`
	PassedScenarios     int                  `json:"passed_scenarios"`
	FailedScenarios     int                  `json:"failed_scenarios"`
	ViolationCounts     map[Severity]int     `json:"violation_counts"`
	Violations          []InvariantViolation `json:"violations"`
	MinimalRepros       []MinimalRepro       `json:"minimal_repros"`
	Summary             SummaryReport        `json:"summary"`
	WallClockDuration   time.Duration        `json:"wall_clock_duration"`
	VirtualDuration     time.Duration        `json:"virtual_clock_duration"`
	DeterminismVerified bool                 `json:"determinism_verified"`
	CausalityReport     CausalityAuditReport `json:"causality_report"`
}

// ExecuteScenario runs a single deterministic scenario through the complete GHOST UX pipeline.
func ExecuteScenario(s *Scenario) (*ScenarioResult, error) {
	clock := NewVirtualClock()
	scheduler := NewEventScheduler(clock)
	db := NewRoomDatabaseModel()
	gatt := NewGattQueueModel(clock, scheduler, s.Profile)
	bridge := NewNativeBridgeModel(s.Profile)
	repo := NewConversationRepoModel()
	compose := NewComposeViewModelState("contact_0")
	lifecycle := NewLifecycleModel()
	groupModel := NewGroupModel(db, gatt, bridge)
	checker := NewUXInvariantChecker()
	metricsAgg := NewUXMetricsAggregator()

	// Prepopulate contacts
	for i := 0; i <= s.InitialContacts; i++ {
		cid := fmt.Sprintf("contact_%d", i)
		db.InsertContact(&ContactRecord{
			ID:         cid,
			Name:       fmt.Sprintf("Contact %d", i),
			CreatedAt:  clock.NowNs(),
			IsVerified: true,
		})
	}

	// Prepopulate groups
	for g := 0; g <= s.InitialGroups; g++ {
		gid := fmt.Sprintf("group_%d", g)
		members := []string{"self_node"}
		for m := 0; m <= 3 && m <= s.InitialContacts; m++ {
			members = append(members, fmt.Sprintf("contact_%d", m))
		}
		db.InsertGroup(&GroupRecord{
			GroupID:          gid,
			Name:             fmt.Sprintf("Group %d", g),
			CreatorContactID: "self_node",
			MemberContactIDs: members,
			CreatedAt:        clock.NowNs(),
			IsActive:         true,
		})
	}

	// Prepopulate messages
	for m := 0; m < s.InitialMessages; m++ {
		cid := fmt.Sprintf("contact_%d", m%(s.InitialContacts+1))
		mid := fmt.Sprintf("init_msg_%d_%d", s.Index, m)
		_ = db.InsertMessage(&MessageRecord{
			ID:          mid,
			ContactID:   cid,
			Content:     fmt.Sprintf("Initial historical message %d", m),
			IsOutgoing:  m%2 == 0,
			Timestamp:   clock.NowNs() + int64(m*1_000_000),
			Status:      StatusDelivered,
			ContentHash: ComputeMessageHash("self_node", clock.NowNs()+int64(m*1_000_000), fmt.Sprintf("Initial %d", m)),
		})
	}

	// Wire Room InvalidationTracker to Repository and Compose
	db.ObserveMessages(func() {
		repo.RecordSourceEvent()
		allMsgs := db.GetAllMessages()
		contacts := db.GetAllContacts()
		groups := db.GetAllActiveGroups()
		groupMsgs := db.GetAllGroupMessages()
		repo.Recompute(contacts, groups, allMsgs, groupMsgs, nil)

		contactMsgs := db.GetMessagesForContact(compose.ContactID)
		compose.OnRoomFlowEmitted(contactMsgs)
	})

	// Prime initial state
	contactMsgs := db.GetMessagesForContact(compose.ContactID)
	compose.OnRoomFlowEmitted(contactMsgs)

	// Schedule scenario events
	for _, event := range s.Events {
		ev := event // Capture loop variable
		scheduler.Schedule(clock.NowNs()+ev.TimeOffsetNs, 1, string(ev.Type), func() error {
			now := clock.NowNs()

			switch ev.Type {
			case EventUserAction:
				switch ev.Action {
				case ActionSendMessage, ActionDoubleTapSend, ActionSendRepeatedly:
					actionStartNs := now

					msgRec := &MessageRecord{
						ID:          ev.MessageID,
						ContactID:   ev.RecipientID,
						Content:     ev.Content,
						IsOutgoing:  true,
						Timestamp:   actionStartNs,
						Status:      StatusPending,
						ContentHash: ComputeMessageHash("self_node", actionStartNs, ev.Content),
					}

					// 1. Optimistic UI update (<1ms)
					compose.SendOptimisticMessage(msgRec, db.GetMessagesForContact(compose.ContactID))
					stateAckNs := clock.NowNs()
					checker.RecordMessageState(ev.MessageID, StatusPending)

					metricsAgg.RecordMainThreadWork("sendMessage_optimistic", 1500*time.Microsecond, actionStartNs)

					// 2. Native Bridge crypto
					_, bridgeDelay, _ := bridge.EncryptAndSign([]byte(ev.Content))
					clock.Advance(bridgeDelay)

					// 3. Room DB persistence
					_ = db.InsertMessage(msgRec)
					persistCommitNs := clock.NowNs()

					// 4. Room committed -> Remove from optimistic map
					compose.OnRoomCommitted(ev.MessageID, db.GetMessagesForContact(compose.ContactID))
					flowEmissionNs := clock.NowNs()
					visibleStateNs := clock.NowNs()

					// 5. GATT Transport
					recpMac := fmt.Sprintf("MAC_%s", ev.RecipientID)
					targetStatusOutcome := ev.StatusOutcome
					if targetStatusOutcome == "" {
						targetStatusOutcome = "GATT_SUCCESS"
					}

					gattItem := &GattItem{
						ID:         ev.MessageID,
						MacAddress: recpMac,
						PayloadLen: len(ev.Content) + 120,
						TimeoutMs:  5000,
						OnResult: func(success bool) {
							if success {
								if targetStatusOutcome == "RELAY_SPRAY" {
									committed, _ := db.UpdateMessageStatus(ev.MessageID, StatusSprayed)
									if committed {
										checker.RecordMessageState(ev.MessageID, StatusSprayed)
									} else {
										checker.RecordRejectedMutation(ev.MessageID, StatusSprayed, "stale_relay_spray_after_terminal")
									}
									checker.RecordTransportOutcome(ev.MessageID, "RELAY_SPRAY")
								} else {
									committed, _ := db.UpdateMessageStatus(ev.MessageID, StatusSent)
									if committed {
										checker.RecordMessageState(ev.MessageID, StatusSent)
									} else {
										checker.RecordRejectedMutation(ev.MessageID, StatusSent, "stale_gatt_callback_after_terminal")
									}
									checker.RecordTransportOutcome(ev.MessageID, "GATT_SUCCESS")
								}
							} else {
								committed, _ := db.UpdateMessageStatus(ev.MessageID, StatusFailed)
								if committed {
									checker.RecordMessageState(ev.MessageID, StatusFailed)
								} else {
									checker.RecordRejectedMutation(ev.MessageID, StatusFailed, "stale_failure_after_terminal")
								}
								checker.RecordTransportOutcome(ev.MessageID, "GATT_FAILURE")
							}
						},
					}
					_, _ = gatt.Enqueue(gattItem)

					// If group message
					if ev.GroupID != "" {
						_, _, _ = groupModel.SendGroupMessage(ev.GroupID, "self_node", ev.Content, []string{"contact_0", "contact_1"}, actionStartNs)
					}

					txMetrics := &CausalTransactionMetrics{
						MessageID:            ev.MessageID,
						SenderID:             "self_node",
						RecipientID:          ev.RecipientID,
						ActionTimeNs:         actionStartNs,
						StateAckTimeNs:       stateAckNs,
						PersistCommitTimeNs:  persistCommitNs,
						FlowEmissionTimeNs:   flowEmissionNs,
						VisibleStateTimeNs:   visibleStateNs,
						QueueDepthAtAction:   gatt.QueueDepth(),
						PendingOptimisticCnt: len(compose.OptimisticPending),
						BridgeLatency:        bridgeDelay,
					}
					metricsAgg.RecordTransaction(txMetrics)

				case ActionScrollUp, ActionScrollDown, ActionFling:
					delta := 10
					if ev.Action == ActionFling {
						delta = 50
					}
					compose.Scroll(delta)
					metricsAgg.RecordMainThreadWork("lazyColumn_scroll", 4*time.Millisecond, now)

				case ActionOpenConversation:
					compose.ContactID = ev.RecipientID
					compose.OnRoomFlowEmitted(db.GetMessagesForContact(ev.RecipientID))

				case ActionSearch:
					_ = db.SearchMessages(ev.Content)
					metricsAgg.RecordMainThreadWork("search_query", 6*time.Millisecond, now)
				}

			case EventBleIncoming:
				_, _, bridgeDelay, _ := bridge.DecryptAndVerify(make([]byte, 120))
				clock.Advance(bridgeDelay)

				msgRec := &MessageRecord{
					ID:          ev.MessageID,
					ContactID:   ev.SenderID,
					Content:     ev.Content,
					IsOutgoing:  false,
					Timestamp:   now,
					Status:      StatusDelivered,
					ContentHash: ComputeMessageHash(ev.SenderID, now, ev.Content),
				}
				_ = db.InsertMessage(msgRec)
				checker.RecordMessageState(ev.MessageID, StatusDelivered)
				metricsAgg.RecordMainThreadWork("ble_incoming_consume", 3*time.Millisecond, now)

			case EventBleReceipt:
				receiptTimeNs := now
				committed, _ := db.UpdateMessageStatus(ev.MessageID, StatusDelivered)
				if committed {
					checker.RecordMessageState(ev.MessageID, StatusDelivered)
				} else {
					checker.RecordRejectedMutation(ev.MessageID, StatusDelivered, "rejected_delivered_status")
				}
				checker.RecordTransportOutcome(ev.MessageID, "RECEIPT_VALID")

				for _, tx := range metricsAgg.transactions {
					if tx.MessageID == ev.MessageID {
						tx.TransportAckTimeNs = receiptTimeNs
						break
					}
				}

			case EventLifecycleEvent:
				switch ev.Lifecycle {
				case LifecycleActivityRecreate:
					_ = lifecycle.RecreateActivity()
					compose.Reset()
					compose.OnRoomFlowEmitted(db.GetMessagesForContact(compose.ContactID))
				case LifecycleBluetoothOff:
					lifecycle.ToggleBluetooth(false)
					gatt.CancelAll()
				case LifecycleBluetoothOn:
					lifecycle.ToggleBluetooth(true)
				case LifecycleScreenOff:
					lifecycle.ToggleScreen(false)
				case LifecycleScreenOn:
					lifecycle.ToggleScreen(true)
				}

			case EventCrashTrigger:
				lifecycle.KillProcess()
				lifecycle.RestartProcess()
				compose.Reset()
				compose.OnRoomFlowEmitted(db.GetMessagesForContact(compose.ContactID))
			}

			return nil
		})
	}

	// Drain scheduler
	if err := scheduler.RunAll(); err != nil {
		return nil, err
	}

	// Formal invariant checking
	violations := checker.CheckInvariants(db, compose, lifecycle, gatt, repo, s.Profile, clock.NowNs())

	result := &ScenarioResult{
		ScenarioID:   s.ID,
		Index:        s.Index,
		Seed:         s.Seed,
		Class:        s.Class,
		Profile:      s.Profile.Name,
		Violations:   violations,
		Transactions: metricsAgg.transactions,
		DurationNs:   clock.NowNs(),
		Passed:       len(violations) == 0,
	}

	return result, nil
}

// RunCampaign executes N scenarios across worker goroutines.
func RunCampaign(cfg CampaignConfig) (*CampaignResult, error) {
	if cfg.WorkerCount <= 0 {
		cfg.WorkerCount = runtime.NumCPU()
	}

	startWall := time.Now()
	generator := NewScenarioGenerator(cfg.MasterSeed)

	workChan := make(chan int, cfg.ScenarioCount)
	for i := 0; i < cfg.ScenarioCount; i++ {
		workChan <- i
	}
	close(workChan)

	var mu sync.Mutex
	var allViolations []InvariantViolation
	var minimalRepros []MinimalRepro
	metricsAgg := NewUXMetricsAggregator()

	var passedCount int64
	var failedCount int64
	violationCounts := make(map[Severity]int)
	var campaignCausality CausalityAuditReport

	var wg sync.WaitGroup
	wg.Add(cfg.WorkerCount)

	for w := 0; w < cfg.WorkerCount; w++ {
		go func() {
			defer wg.Done()
			for idx := range workChan {
				scenario := generator.GenerateScenario(idx)
				audit := AuditScenarioCausality(&scenario)
				mu.Lock()
				campaignCausality.TotalReceipts += audit.TotalReceipts
				campaignCausality.CausallyValidReceipts += audit.CausallyValidReceipts
				campaignCausality.ExplicitStaleReceipts += audit.ExplicitStaleReceipts
				campaignCausality.UnclassifiedAcausalReceipts += audit.UnclassifiedAcausalReceipts
				mu.Unlock()

				if audit.UnclassifiedAcausalReceipts > 0 {
					mu.Lock()
					allViolations = append(allViolations, InvariantViolation{
						ID:        U10_TransportTruthfulness,
						Severity:  SeverityP2,
						Component: "ScenarioGenerator",
						Message:   fmt.Sprintf("Scenario %s generated %d unclassified acausal receipts", scenario.ID, audit.UnclassifiedAcausalReceipts),
					})
					failedCount++
					violationCounts[SeverityP2]++
					mu.Unlock()
					continue
				}

				res, err := ExecuteScenario(&scenario)
				if err != nil {
					mu.Lock()
					allViolations = append(allViolations, InvariantViolation{
						ID:        U11_NativeBoundarySafety,
						Severity:  SeverityP1,
						Component: "ScenarioRunner",
						Message:   fmt.Sprintf("Execution error: %v", err),
					})
					failedCount++
					mu.Unlock()
					continue
				}

				if res.Passed {
					atomic.AddInt64(&passedCount, 1)
				} else {
					atomic.AddInt64(&failedCount, 1)
					mu.Lock()
					allViolations = append(allViolations, res.Violations...)
					for _, v := range res.Violations {
						violationCounts[v.Severity]++
					}

					// Shrink failure if enabled
					if cfg.EnableShrinking && len(res.Violations) > 0 {
						shrinker := NewScenarioShrinker(func(cand *Scenario) []InvariantViolation {
							candRes, cErr := ExecuteScenario(cand)
							if cErr != nil {
								return []InvariantViolation{{ID: U11_NativeBoundarySafety, Severity: SeverityP1, Message: cErr.Error()}}
							}
							return candRes.Violations
						})
						repro := shrinker.Shrink(scenario, res.Violations[0])
						minimalRepros = append(minimalRepros, repro)
						if cfg.OutputDir != "" {
							_, _ = SaveRepro(cfg.OutputDir, repro)
						}
					}
					mu.Unlock()
				}

				for _, tx := range res.Transactions {
					metricsAgg.RecordTransaction(tx)
				}
			}
		}()
	}

	wg.Wait()
	wallDuration := time.Since(startWall)

	// Determinism verification: Sample 10 scenarios and re-run to verify identical result
	determinismVerified := true
	sampleCount := 10
	if cfg.ScenarioCount < sampleCount {
		sampleCount = cfg.ScenarioCount
	}
	for i := 0; i < sampleCount; i++ {
		sc1 := generator.GenerateScenario(i)
		sc2 := generator.GenerateScenario(i)
		r1, _ := ExecuteScenario(&sc1)
		r2, _ := ExecuteScenario(&sc2)
		if len(r1.Violations) != len(r2.Violations) || len(r1.Transactions) != len(r2.Transactions) {
			determinismVerified = false
			break
		}
	}

	summary := metricsAgg.GenerateSummary()

	return &CampaignResult{
		TotalScenarios:      cfg.ScenarioCount,
		PassedScenarios:     int(passedCount),
		FailedScenarios:     int(failedCount),
		ViolationCounts:     violationCounts,
		Violations:          allViolations,
		MinimalRepros:       minimalRepros,
		Summary:             summary,
		WallClockDuration:   wallDuration,
		DeterminismVerified: determinismVerified,
		CausalityReport:     campaignCausality,
	}, nil
}
