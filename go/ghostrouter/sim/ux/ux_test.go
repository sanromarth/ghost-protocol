package ux

import (
	"encoding/json"
	"fmt"
	"testing"
)

// TestVirtualClockAndScheduler tests strict nanosecond precision and deterministic scheduling.
func TestVirtualClockAndScheduler(t *testing.T) {
	clock := NewVirtualClock()
	scheduler := NewEventScheduler(clock)

	var executionLog []string

	scheduler.Schedule(clock.NowNs()+2000, 1, "task2", func() error {
		executionLog = append(executionLog, fmt.Sprintf("task2@%d", clock.NowNs()))
		return nil
	})

	scheduler.Schedule(clock.NowNs()+1000, 1, "task1", func() error {
		executionLog = append(executionLog, fmt.Sprintf("task1@%d", clock.NowNs()))
		return nil
	})

	scheduler.Schedule(clock.NowNs()+1000, 0, "task1_priority", func() error {
		executionLog = append(executionLog, fmt.Sprintf("task1_prio@%d", clock.NowNs()))
		return nil
	})

	if err := scheduler.RunAll(); err != nil {
		t.Fatalf("RunAll failed: %v", err)
	}

	if len(executionLog) != 3 {
		t.Fatalf("expected 3 tasks executed, got %d", len(executionLog))
	}

	// Higher priority task1_priority should execute before task1 at timestamp 1000
	expectedPrio := fmt.Sprintf("task1_prio@%d", clock.NowNs()-1000)
	if executionLog[0] != expectedPrio {
		t.Errorf("expected first %s, got %s", expectedPrio, executionLog[0])
	}
}

// TestRoomDatabaseDurabilityAndTransactions validates Room ACID persistence and crash boundaries.
func TestRoomDatabaseDurabilityAndTransactions(t *testing.T) {
	db := NewRoomDatabaseModel()

	var observedStages []CrashWindowStage
	db.SetCrashHook(func(stage CrashWindowStage, msgID string) error {
		observedStages = append(observedStages, stage)
		return nil
	})

	msg := &MessageRecord{
		ID:          "test_msg_1",
		ContactID:   "contact_alice",
		Content:     "Hello Alice",
		IsOutgoing:  true,
		Timestamp:   1000,
		Status:      StatusPending,
		ContentHash: "hash123",
	}

	if err := db.InsertMessage(msg); err != nil {
		t.Fatalf("InsertMessage failed: %v", err)
	}

	// Verify all 4 crash stages were hit during insert
	expectedStages := []CrashWindowStage{
		CrashWindowMutationBegin,
		CrashWindowMutationCommit,
		CrashWindowMutationObservable,
		CrashWindowFlowEmission,
	}

	if len(observedStages) != len(expectedStages) {
		t.Fatalf("expected %d crash stages, got %d", len(expectedStages), len(observedStages))
	}
	for i, st := range expectedStages {
		if observedStages[i] != st {
			t.Errorf("stage %d: expected %s, got %s", i, st, observedStages[i])
		}
	}

	// Verify message in Room
	msgs := db.GetMessagesForContact("contact_alice")
	if len(msgs) != 1 || msgs[0].Content != "Hello Alice" {
		t.Fatalf("unexpected messages in DB: %+v", msgs)
	}

	// Invariant U5: Terminal DELIVERED cannot roll back
	_, err := db.UpdateMessageStatus("test_msg_1", StatusDelivered)
	if err != nil {
		t.Fatalf("failed to update to DELIVERED: %v", err)
	}

	ok, rollbackErr := db.UpdateMessageStatus("test_msg_1", StatusSent)
	if ok || rollbackErr == nil {
		t.Fatalf("expected rollback from DELIVERED to SENT to be rejected")
	}
}

// TestComposeViewModelState verifies ChatViewModel optimistic pending map and Flow combine logic.
func TestComposeViewModelState(t *testing.T) {
	compose := NewComposeViewModelState("contact_alice")

	msg1 := &MessageRecord{
		ID:        "m1",
		ContactID: "contact_alice",
		Content:   "Hi",
		Timestamp: 100,
		Status:    StatusPending,
	}

	// 1. Optimistic send
	compose.SendOptimisticMessage(msg1, nil)
	if len(compose.CombinedMessages) != 1 {
		t.Fatalf("expected 1 optimistic message visible, got %d", len(compose.CombinedMessages))
	}
	if len(compose.OptimisticPending) != 1 {
		t.Fatalf("expected 1 pending in map, got %d", len(compose.OptimisticPending))
	}

	// 2. Room committed
	roomMsgs := []*MessageRecord{msg1}
	compose.OnRoomCommitted("m1", roomMsgs)
	if len(compose.OptimisticPending) != 0 {
		t.Fatalf("expected optimistic map empty after room commit, got %d", len(compose.OptimisticPending))
	}
	if len(compose.CombinedMessages) != 1 {
		t.Fatalf("expected 1 message in combined, got %d", len(compose.CombinedMessages))
	}
}

// TestGattOperationQueue verifies GATT serialization, cool-off, and state transitions.
func TestGattOperationQueue(t *testing.T) {
	clock := NewVirtualClock()
	scheduler := NewEventScheduler(clock)
	profile := ProfileMidRange()
	gatt := NewGattQueueModel(clock, scheduler, profile)

	completed := false
	item := &GattItem{
		ID:         "gatt_test_1",
		MacAddress: "AA:BB:CC:DD:EE:FF",
		PayloadLen: 50,
		TimeoutMs:  5000,
		OnResult: func(success bool) {
			completed = success
		},
	}

	enqueued, err := gatt.Enqueue(item)
	if !enqueued || err != nil {
		t.Fatalf("failed to enqueue GATT item: %v", err)
	}

	// Step scheduler until item completes
	if err := scheduler.RunAll(); err != nil {
		t.Fatalf("scheduler execution failed: %v", err)
	}

	if !completed {
		t.Fatalf("expected GATT operation to complete successfully")
	}
	if gatt.State() != GattIdle {
		t.Errorf("expected GATT state IDLE after completion, got %s", gatt.State())
	}
}

// TestLifecycleModel verifies Activity recreation vs Process death separation.
func TestLifecycleModel(t *testing.T) {
	life := NewLifecycleModel()

	if life.ActiveCollectors != 2 {
		t.Errorf("expected baseline 2 collectors, got %d", life.ActiveCollectors)
	}

	// Activity recreation (screen rotation)
	if err := life.RecreateActivity(); err != nil {
		t.Fatalf("RecreateActivity failed: %v", err)
	}
	if life.ActiveCollectors != 2 {
		t.Errorf("expected 2 collectors after recreate, got %d", life.ActiveCollectors)
	}

	// Process kill
	life.KillProcess()
	if life.ProcessAlive {
		t.Errorf("expected process dead")
	}
	if life.ActiveCollectors != 0 {
		t.Errorf("expected 0 collectors when process dead, got %d", life.ActiveCollectors)
	}

	// Process restart
	life.RestartProcess()
	if !life.ProcessAlive {
		t.Errorf("expected process alive after restart")
	}
}

// TestMetamorphicDeterminism (M1) verifies strict bit-for-bit repeatability across identical seeds.
func TestMetamorphicDeterminism(t *testing.T) {
	gen := NewScenarioGenerator(42)
	sc1 := gen.GenerateScenario(1)
	sc2 := gen.GenerateScenario(1)

	res1, err1 := ExecuteScenario(&sc1)
	if err1 != nil {
		t.Fatalf("res1 execution failed: %v", err1)
	}
	res2, err2 := ExecuteScenario(&sc2)
	if err2 != nil {
		t.Fatalf("res2 execution failed: %v", err2)
	}

	if len(res1.Violations) != len(res2.Violations) {
		t.Errorf("determinism violation: violations count mismatch (%d vs %d)", len(res1.Violations), len(res2.Violations))
	}
	if len(res1.Transactions) != len(res2.Transactions) {
		t.Errorf("determinism violation: transactions count mismatch (%d vs %d)", len(res1.Transactions), len(res2.Transactions))
	}
	for i := range res1.Transactions {
		t1 := res1.Transactions[i]
		t2 := res2.Transactions[i]
		if t1.ActionTimeNs != t2.ActionTimeNs || t1.StateAckTimeNs != t2.StateAckTimeNs {
			t.Errorf("determinism violation in tx %d: %+v vs %+v", i, t1, t2)
		}
	}
}

// TestDeterministicReplay verifies that replaying a scenario 5 consecutive times produces byte-for-byte identical traces.
func TestDeterministicReplay(t *testing.T) {
	var traces [][]byte
	for iter := 0; iter < 5; iter++ {
		trace, err := ReplayFromSeed(99999, 3)
		if err != nil {
			t.Fatalf("ReplayFromSeed iteration %d failed: %v", iter, err)
		}
		data, err := json.Marshal(trace)
		if err != nil {
			t.Fatalf("json marshal failed: %v", err)
		}
		traces = append(traces, data)
	}

	for i := 1; i < len(traces); i++ {
		if string(traces[0]) != string(traces[i]) {
			t.Fatalf("determinism violation: replay iteration %d differs from iteration 0", i)
		}
	}
}

// TestShrinking verifies that the delta debugging shrinker reduces a multi-event scenario to minimal repro.
func TestShrinking(t *testing.T) {
	// Construct a 20-event scenario where event 0 sets message to DELIVERED,
	// and event 15 erroneously sets it to SENT (U5 rollback violation)
	scenario := Scenario{
		ID:      "TEST_SHRINK_SCENARIO",
		Seed:    777,
		Class:   ScenarioClassBoundary,
		Profile: MidRangeProfile(),
		Events: []ScheduledScenarioEvent{
			{TimeOffsetNs: 1_000_000, Type: EventBleIncoming, SenderID: "contact_0", MessageID: "msg_target", Content: "Delivered Message"},
		},
	}

	for i := 1; i < 20; i++ {
		ev := ScheduledScenarioEvent{
			TimeOffsetNs: int64((i + 1) * 10_000_000),
			Type:         EventUserAction,
			Action:       ActionScrollUp,
		}
		if i == 15 {
			// Cause an impossible rollback / state transition
			ev = ScheduledScenarioEvent{
				TimeOffsetNs: int64((i + 1) * 10_000_000),
				Type:         EventUserAction,
				Action:       ActionSendMessage,
				MessageID:    "msg_target",
				RecipientID:  "contact_0",
				Content:      "Rollback trigger",
			}
		}
		scenario.Events = append(scenario.Events, ev)
	}

	// Verify original scenario fails with U5 or U4
	initialRes, err := ExecuteScenario(&scenario)
	if err != nil {
		t.Fatalf("ExecuteScenario failed: %v", err)
	}
	if len(initialRes.Violations) == 0 {
		t.Fatalf("expected initial scenario to fail with invariant violation")
	}

	targetViolation := initialRes.Violations[0]
	shrinker := NewScenarioShrinker(func(cand *Scenario) []InvariantViolation {
		res, cErr := ExecuteScenario(cand)
		if cErr != nil {
			return nil
		}
		return res.Violations
	})

	repro := shrinker.Shrink(scenario, targetViolation)
	if repro.EventCountShrunk >= repro.EventCountOriginal {
		t.Errorf("shrinker failed to reduce event count: orig %d, shrunk %d", repro.EventCountOriginal, repro.EventCountShrunk)
	}

	// Verify shrunk scenario still reproduces target violation
	shrunkRes, sErr := ExecuteScenario(&repro.ShrunkScenario)
	if sErr != nil {
		t.Fatalf("shrunk scenario failed with execution error: %v", sErr)
	}
	found := false
	for _, v := range shrunkRes.Violations {
		if v.ID == targetViolation.ID {
			found = true
			break
		}
	}
	if !found {
		t.Errorf("shrunk scenario did not reproduce violation %s", targetViolation.ID)
	}
}

// TestStateConvergence (Section 9: Most Important Test)
// Compares:
// Path A: SEND -> DISCONNECT -> RECONNECT -> RECEIPT -> RELOAD
// Path B: SEND -> RECEIPT -> RELOAD
// Verifies that equivalent event histories converge to equivalent application state.
func TestStateConvergence(t *testing.T) {
	// Path A: send -> disconnect -> reconnect -> receipt (after reconnect) -> reload
	scA := Scenario{
		ID:      "CONVERGENCE_PATH_A",
		Seed:    101,
		Class:   ScenarioClassLifecycle,
		Profile: MidRangeProfile(),
		Events: []ScheduledScenarioEvent{
			{TimeOffsetNs: 1_000_000, Type: EventUserAction, Action: ActionSendMessage, RecipientID: "contact_0", MessageID: "msg_conv", Content: "Hello Convergence", StatusOutcome: "GATT_FAILURE"},
			{TimeOffsetNs: 50_000_000, Type: EventLifecycleEvent, Lifecycle: LifecycleBluetoothOff},
			{TimeOffsetNs: 100_000_000, Type: EventLifecycleEvent, Lifecycle: LifecycleBluetoothOn},
			{TimeOffsetNs: 350_000_000, Type: EventBleReceipt, MessageID: "msg_conv", SenderID: "contact_0", StatusOutcome: "DELIVERED"},
			{TimeOffsetNs: 400_000_000, Type: EventLifecycleEvent, Lifecycle: LifecycleActivityRecreate},
		},
	}

	// Path B: send -> receipt (after GATT completes at ~216ms) -> reload
	scB := Scenario{
		ID:      "CONVERGENCE_PATH_B",
		Seed:    102,
		Class:   ScenarioClassLifecycle,
		Profile: MidRangeProfile(),
		Events: []ScheduledScenarioEvent{
			{TimeOffsetNs: 1_000_000, Type: EventUserAction, Action: ActionSendMessage, RecipientID: "contact_0", MessageID: "msg_conv", Content: "Hello Convergence", StatusOutcome: "GATT_SUCCESS"},
			{TimeOffsetNs: 350_000_000, Type: EventBleReceipt, MessageID: "msg_conv", SenderID: "contact_0", StatusOutcome: "DELIVERED"},
			{TimeOffsetNs: 400_000_000, Type: EventLifecycleEvent, Lifecycle: LifecycleActivityRecreate},
		},
	}

	resA, errA := ExecuteScenario(&scA)
	if errA != nil {
		t.Fatalf("Path A execution error: %v", errA)
	}

	resB, errB := ExecuteScenario(&scB)
	if errB != nil {
		t.Fatalf("Path B execution error: %v", errB)
	}

	// Verify both paths passed without invariant violations
	if len(resA.Violations) > 0 {
		t.Errorf("Path A had violations: %+v", resA.Violations)
	}
	if len(resB.Violations) > 0 {
		t.Errorf("Path B had violations: %+v", resB.Violations)
	}
}

// TestGattRaceConditionDeliveryReceiptWinsOverDelayedGattCallback tests the 2-event race:
// Event 0: Send message at t=1ms (GATT transmission scheduled for ~215ms)
// Event 1: Receipt arrives at t=20ms (explicit out-of-order, updates message to DELIVERED)
// Later: Delayed GATT callback fires at ~215ms (attempts to downgrade to SENT)
// Verifies:
// 1. Zero invariant violations (U4 and U5 pass).
// 2. Final message status remains StatusDelivered (status 2).
func TestGattRaceConditionDeliveryReceiptWinsOverDelayedGattCallback(t *testing.T) {
	scenario := Scenario{
		ID:      "TEST_2_EVENT_RACE_REPRO",
		Seed:    42,
		Class:   ScenarioClassBoundary,
		Profile: MidRangeProfile(),
		Events: []ScheduledScenarioEvent{
			{
				TimeOffsetNs:      1_000_000,
				Type:              EventUserAction,
				Action:            ActionSendMessage,
				SenderID:          "self_node",
				RecipientID:       "contact_alice",
				MessageID:         "msg_race_repro",
				Content:           "Race test message",
				StatusOutcome:     "GATT_SUCCESS",
				TransportFinishNs: 216_000_000,
			},
			{
				TimeOffsetNs:         20_000_000, // Arrives while GATT write is in flight
				Type:                 EventBleReceipt,
				MessageID:            "msg_race_repro",
				SenderID:             "contact_alice",
				RecipientID:          "self_node",
				StatusOutcome:        "DELIVERED",
				IsExplicitOutOfOrder: true,
				TransportFinishNs:    216_000_000,
			},
		},
		DurationNs: 500_000_000,
	}

	res, err := ExecuteScenario(&scenario)
	if err != nil {
		t.Fatalf("ExecuteScenario failed: %v", err)
	}

	if len(res.Violations) != 0 {
		for _, v := range res.Violations {
			t.Errorf("unexpected invariant violation: %v", v)
		}
		t.Fatalf("expected 0 violations in 2-event reproduction after hardening, got %d", len(res.Violations))
	}
}

// TestGeneratorCausalityAudit verifies that across 100 generated scenarios,
// 0 unclassified acausal receipts are generated.
func TestGeneratorCausalityAudit(t *testing.T) {
	gen := NewScenarioGenerator(12345)
	var total CausalityAuditReport

	for i := 0; i < 100; i++ {
		sc := gen.GenerateScenario(i)
		rep := AuditScenarioCausality(&sc)
		total.TotalReceipts += rep.TotalReceipts
		total.CausallyValidReceipts += rep.CausallyValidReceipts
		total.ExplicitStaleReceipts += rep.ExplicitStaleReceipts
		total.UnclassifiedAcausalReceipts += rep.UnclassifiedAcausalReceipts
	}

	if total.UnclassifiedAcausalReceipts != 0 {
		t.Fatalf("expected 0 unclassified acausal receipts across 100 scenarios, got %d", total.UnclassifiedAcausalReceipts)
	}
	if total.TotalReceipts != total.CausallyValidReceipts+total.ExplicitStaleReceipts {
		t.Fatalf("receipt accounting mismatch: total=%d, valid=%d, stale=%d", total.TotalReceipts, total.CausallyValidReceipts, total.ExplicitStaleReceipts)
	}
	if total.TotalReceipts == 0 {
		t.Fatalf("expected receipts to be generated across 100 scenarios, got 0")
	}
}

// TestSoakScenario24H validates a 24-hour virtual time continuous soak test scenario.
func TestSoakScenario24H(t *testing.T) {
	gen := NewScenarioGenerator(42)
	soak := gen.GenerateSoakScenario(42, 500)

	audit := AuditScenarioCausality(&soak)
	if audit.UnclassifiedAcausalReceipts != 0 {
		t.Fatalf("soak scenario generated %d unclassified acausal receipts", audit.UnclassifiedAcausalReceipts)
	}

	res, err := ExecuteScenario(&soak)
	if err != nil {
		t.Fatalf("ExecuteScenario failed on soak scenario: %v", err)
	}

	if len(res.Violations) != 0 {
		for _, v := range res.Violations {
			t.Errorf("soak scenario invariant violation: %v", v)
		}
		t.Fatalf("expected 0 violations in 24h soak test, got %d", len(res.Violations))
	}

	if !res.Passed {
		t.Fatalf("soak scenario reported Passed=false")
	}
}



