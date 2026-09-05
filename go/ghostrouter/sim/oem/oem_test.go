package oem

import (
	"testing"
	"time"
)

func TestActivityDecoupling(t *testing.T) {
	clock := NewVirtualClock()
	scheduler := NewEventScheduler(clock)
	profile := StockProfile()

	activity := NewActivityModel(clock, scheduler)
	service := NewServiceModel(clock, scheduler, profile)

	activity.OnCreate()
	activity.OnResume()
	if err := service.Start(); err != nil {
		t.Fatalf("Failed to start service: %v", err)
	}

	if !service.IsRunning() {
		t.Fatalf("Service expected running")
	}

	// Destroy Activity (e.g. user exits UI)
	activity.OnPause()
	activity.OnStop()
	activity.OnDestroy()

	if activity.State() != ActivityStateDestroyed {
		t.Fatalf("Activity expected destroyed, got %s", activity.State())
	}

	// Service MUST remain running (Invariant O2)
	if !service.IsRunning() {
		t.Fatalf("O2 violation: Service stopped when Activity was destroyed")
	}
}

func TestGattQueueSerialization(t *testing.T) {
	clock := NewVirtualClock()
	scheduler := NewEventScheduler(clock)
	profile := StockProfile()

	gatt := NewGattQueueModel(clock, scheduler, profile)

	var completed1, completed2 bool

	item1 := &GattQueueItem{
		ID:         "item_1",
		MacAddress: "AA:BB:CC:DD:01:02",
		Payload:    []byte("hello 1"),
		TimeoutMs:  5000,
		OnResult: func(ok bool) {
			completed1 = ok
		},
	}
	item2 := &GattQueueItem{
		ID:         "item_2",
		MacAddress: "AA:BB:CC:DD:01:03",
		Payload:    []byte("hello 2"),
		TimeoutMs:  5000,
		OnResult: func(ok bool) {
			completed2 = ok
		},
	}

	if err := gatt.Enqueue(item1); err != nil {
		t.Fatalf("Enqueue 1 failed: %v", err)
	}
	if err := gatt.Enqueue(item2); err != nil {
		t.Fatalf("Enqueue 2 failed: %v", err)
	}

	// Invariant O4: At most 1 active GATT
	if err := gatt.CheckSerializationInvariant(); err != nil {
		t.Fatalf("Serialization check failed: %v", err)
	}

	// Drain all tasks
	if err := scheduler.RunAll(); err != nil {
		t.Fatalf("Scheduler error: %v", err)
	}

	if !completed1 || !completed2 {
		t.Fatalf("Expected both items completed successfully, got c1=%t, c2=%t", completed1, completed2)
	}

	if err := gatt.CheckSerializationInvariant(); err != nil {
		t.Fatalf("O4 violation: %v", err)
	}
}

func TestClosedGattSafety(t *testing.T) {
	clock := NewVirtualClock()
	scheduler := NewEventScheduler(clock)
	profile := StockProfile()

	gatt := NewGattQueueModel(clock, scheduler, profile)

	item := &GattQueueItem{
		ID:         "item_abort",
		MacAddress: "AA:BB:CC:DD:01:02",
		Payload:    []byte("test"),
		TimeoutMs:  5000,
	}

	if err := gatt.Enqueue(item); err != nil {
		t.Fatalf("Enqueue failed: %v", err)
	}

	// Abruptly cancel all (simulating BleManager.stop())
	gatt.CancelAll()

	if gatt.State() != GattStateIdle {
		t.Fatalf("Expected state IDLE, got %s", gatt.State())
	}

	// Inject late hostile callback from stale connection (Invariant O5)
	if err := gatt.ReceiveHostileLateCallback(1); err != nil {
		t.Fatalf("O5 violation: Late callback returned error: %v", err)
	}

	if gatt.lateCallbacksIgnored != 1 {
		t.Fatalf("Expected 1 late callback safely ignored, got %d", gatt.lateCallbacksIgnored)
	}
}

func TestBluetoothOffBoundedAbort(t *testing.T) {
	clock := NewVirtualClock()
	scheduler := NewEventScheduler(clock)
	profile := StockProfile()

	bt := NewBluetoothModel(clock, scheduler, profile)
	if !bt.IsOn() {
		t.Fatalf("Expected BT initially ON")
	}

	bt.TurnOff()
	if bt.State() != BtStateTurningOff {
		t.Fatalf("Expected state TURNING_OFF, got %s", bt.State())
	}

	// Advance virtual clock past shutdown completion (100ms)
	clock.Advance(150 * time.Millisecond)
	if err := scheduler.RunAll(); err != nil {
		t.Fatalf("Scheduler error: %v", err)
	}

	if bt.State() != BtStateOff {
		t.Fatalf("Expected state OFF, got %s", bt.State())
	}

	if err := bt.CheckBoundedAbort(); err != nil {
		t.Fatalf("O7 violation: %v", err)
	}
}

func TestBatteryRelayGating(t *testing.T) {
	clock := NewVirtualClock()
	scheduler := NewEventScheduler(clock)
	profile := StockProfile()

	power := NewPowerModel(clock, scheduler, profile)
	if !power.CanRelayThirdParty() {
		t.Fatalf("Expected relay allowed at 80%% battery")
	}

	// Drain battery to 15% (CRITICAL)
	power.DrainBattery(65)
	if power.BatteryPercent() != 15 {
		t.Fatalf("Expected 15%% battery, got %d", power.BatteryPercent())
	}
	if power.Mode() != PowerModeCritical {
		t.Fatalf("Expected CRITICAL mode, got %s", power.Mode())
	}

	// Invariant O12: Relay MUST be gated
	if power.CanRelayThirdParty() {
		t.Fatalf("O12 violation: Third-party relay permitted at 15%% battery")
	}
	if err := power.CheckBatteryRelayGating(); err != nil {
		t.Fatalf("O12 violation: %v", err)
	}

	// Plug into charger -> relay permitted again
	power.SetCharging(true)
	if !power.CanRelayThirdParty() {
		t.Fatalf("Expected relay permitted while charging")
	}
}

func TestTerminalDeliveryInvariance(t *testing.T) {
	clock := NewVirtualClock()
	scheduler := NewEventScheduler(clock)
	profile := StockProfile()

	storage := NewStorageModel(clock, scheduler, profile)

	msg := &StoredMessage{
		ID:        "msg_terminal_01",
		ContactID: "peer_1",
		Content:   "critical payload",
		Timestamp: clock.NowNs(),
		Status:    MsgStatusPending,
	}

	if err := storage.Insert(msg); err != nil {
		t.Fatalf("Insert failed: %v", err)
	}

	// Update to SENT (1)
	upd, err := storage.UpdateStatus(msg.ID, MsgStatusSent)
	if err != nil || !upd {
		t.Fatalf("Update to SENT failed: %v", err)
	}

	// Update to DELIVERED (2)
	upd, err = storage.UpdateStatus(msg.ID, MsgStatusDelivered)
	if err != nil || !upd {
		t.Fatalf("Update to DELIVERED failed: %v", err)
	}

	// ATTEMPT DOWNGRADE: Try updating back to SENT (1)
	upd, err = storage.UpdateStatus(msg.ID, MsgStatusSent)
	if err != nil {
		t.Fatalf("Update threw unexpected error: %v", err)
	}
	if upd {
		t.Fatalf("O6 violation: UpdateStatus returned true on downgrade from DELIVERED to SENT")
	}

	// Verify database row still has status DELIVERED (2)
	m, _ := storage.GetMessage(msg.ID)
	if m.Status != MsgStatusDelivered {
		t.Fatalf("O6 violation: Database status downgraded to %d", m.Status)
	}

	if err := storage.CheckTerminalDeliveryInvariance(); err != nil {
		t.Fatalf("O6 violation: %v", err)
	}
}

func TestDurableMessageSurvival(t *testing.T) {
	clock := NewVirtualClock()
	scheduler := NewEventScheduler(clock)
	profile := MemoryPressureProfile()

	storage := NewStorageModel(clock, scheduler, profile)
	process := NewProcessModel(clock, scheduler, profile)

	msgIds := []string{"msg_01", "msg_02", "msg_03"}
	for _, id := range msgIds {
		m := &StoredMessage{
			ID:        id,
			ContactID: "peer_a",
			Content:   "persisted data",
			Timestamp: clock.NowNs(),
			Status:    MsgStatusDelivered,
		}
		if err := storage.Insert(m); err != nil {
			t.Fatalf("Insert failed: %v", err)
		}
	}

	// Simulate LMKD killing process
	process.KillProcess("LMKD_KILL")
	if process.IsAlive() {
		t.Fatalf("Process expected dead")
	}

	// Resurrect process
	process.SpawnProcess()
	if !process.IsAlive() {
		t.Fatalf("Process expected resurrected")
	}

	// Invariant O1/O18: All committed messages must survive process resurrection
	if err := storage.CheckCommittedMessageSurvival(msgIds); err != nil {
		t.Fatalf("O1/O18 violation: %v", err)
	}
}

func TestDeterministicReplay(t *testing.T) {
	generator := NewScenarioGenerator(42)
	scenario := generator.GenerateScenario(1, ProfileOemMaximumHostility)

	// Invariant O21: Exactly identical result across 5 runs
	if err := VerifyDeterministicReplay(scenario, 5); err != nil {
		t.Fatalf("O21 violation: %v", err)
	}
}

func TestCampaignSmall(t *testing.T) {
	cfg := CampaignConfig{
		TotalScenarios: 100,
		BaseSeed:       999,
		NumWorkers:     4,
	}

	metrics, results, err := RunOemCampaign(cfg)
	if err != nil {
		t.Fatalf("Campaign execution failed: %v", err)
	}

	if metrics.FailedScenarios > 0 {
		for _, r := range results {
			if !r.Passed {
				t.Logf("Failed scenario %s: violations=%v", r.ScenarioID, r.Violations)
			}
		}
		t.Fatalf("Expected 0 failures in 100 scenarios, got %d", metrics.FailedScenarios)
	}

	if len(results) != 100 {
		t.Fatalf("Expected 100 results, got %d", len(results))
	}
}
