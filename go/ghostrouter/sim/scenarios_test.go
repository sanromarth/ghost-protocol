package sim

import (
	"bytes"
	"testing"
)

func TestScenario01_Direct(t *testing.T) {
	res, err := RunScenario01_Direct(42)
	if err != nil {
		t.Fatalf("Scenario 01 failed: %v", err)
	}
	if res.MessagesDelivered != 1 {
		t.Errorf("expected 1 delivered message, got %d", res.MessagesDelivered)
	}
	if !res.InvariantsPassed {
		t.Errorf("invariants failed: %v", res.InvariantErrors)
	}
}

func TestScenario02_OneRelay(t *testing.T) {
	res, err := RunScenario02_OneRelay(42)
	if err != nil {
		t.Fatalf("Scenario 02 failed: %v", err)
	}
	if res.MessagesDelivered != 1 {
		t.Errorf("expected 1 delivered message, got %d", res.MessagesDelivered)
	}
	if res.PacketsForwarded < 1 {
		t.Errorf("expected at least 1 packet forwarded, got %d", res.PacketsForwarded)
	}
	if !res.InvariantsPassed {
		t.Errorf("invariants failed: %v", res.InvariantErrors)
	}
}

func TestScenario03_PartitionReconnect(t *testing.T) {
	res, err := RunScenario03_PartitionReconnect(42)
	if err != nil {
		t.Fatalf("Scenario 03 failed: %v", err)
	}
	if res.MessagesDelivered != 1 {
		t.Errorf("expected 1 delivered message, got %d", res.MessagesDelivered)
	}
	if !res.InvariantsPassed {
		t.Errorf("invariants failed: %v", res.InvariantErrors)
	}
}

func TestScenario04_FourCopySpray(t *testing.T) {
	res, err := RunScenario04_FourCopySpray(42)
	if err != nil {
		t.Fatalf("Scenario 04 failed: %v", err)
	}
	if !res.InvariantsPassed {
		t.Errorf("invariants failed: %v", res.InvariantErrors)
	}
}

func TestScenario05_DuplicateFlood(t *testing.T) {
	res, err := RunScenario05_DuplicateFlood(42)
	if err != nil {
		t.Fatalf("Scenario 05 failed: %v", err)
	}
	if res.MessagesDelivered != 1 {
		t.Errorf("expected 1 delivered message, got %d", res.MessagesDelivered)
	}
	if res.DuplicatesDropped < 1 {
		t.Errorf("expected at least 1 duplicate drop, got %d", res.DuplicatesDropped)
	}
	if !res.InvariantsPassed {
		t.Errorf("invariants failed: %v", res.InvariantErrors)
	}
}

func TestScenario06_TTLExpiration(t *testing.T) {
	res, err := RunScenario06_TTLExpiration(42)
	if err != nil {
		t.Fatalf("Scenario 06 failed: %v", err)
	}
	if res.MessagesDelivered != 0 {
		t.Errorf("expected 0 delivered messages for expired TTL, got %d", res.MessagesDelivered)
	}
	if !res.InvariantsPassed {
		t.Errorf("invariants failed: %v", res.InvariantErrors)
	}
}

func TestScenario07_HopLimit(t *testing.T) {
	res, err := RunScenario07_HopLimit(42)
	if err != nil {
		t.Fatalf("Scenario 07 failed: %v", err)
	}
	if res.MessagesDelivered != 0 {
		t.Errorf("expected 0 delivered messages beyond MaxHops=10, got %d", res.MessagesDelivered)
	}
	if !res.InvariantsPassed {
		t.Errorf("invariants failed: %v", res.InvariantErrors)
	}
}

func TestScenario08_LowBattery(t *testing.T) {
	res, err := RunScenario08_LowBattery(42)
	if err != nil {
		t.Fatalf("Scenario 08 failed: %v", err)
	}
	if res.MessagesDelivered != 1 {
		t.Errorf("expected 1 delivered message after recharge, got %d", res.MessagesDelivered)
	}
	if !res.InvariantsPassed {
		t.Errorf("invariants failed: %v", res.InvariantErrors)
	}
}

func TestScenario09_CrashDuringTransit(t *testing.T) {
	res, err := RunScenario09_CrashDuringTransit(42)
	if err != nil {
		t.Fatalf("Scenario 09 failed: %v", err)
	}
	if res.MessagesDelivered != 1 {
		t.Errorf("expected 1 delivered message after reboot, got %d", res.MessagesDelivered)
	}
	if !res.InvariantsPassed {
		t.Errorf("invariants failed: %v", res.InvariantErrors)
	}
}

func TestScenario10_RepeatedEncounters(t *testing.T) {
	res, err := RunScenario10_RepeatedEncounters(42)
	if err != nil {
		t.Fatalf("Scenario 10 failed: %v", err)
	}
	if res.MessagesDelivered != 1 {
		t.Errorf("expected exactly 1 delivered message, got %d", res.MessagesDelivered)
	}
	if !res.InvariantsPassed {
		t.Errorf("invariants failed: %v", res.InvariantErrors)
	}
}

func TestScenario11_HighPacketLoss(t *testing.T) {
	res, err := RunScenario11_HighPacketLoss(42)
	if err != nil {
		t.Fatalf("Scenario 11 failed: %v", err)
	}
	if !res.InvariantsPassed {
		t.Errorf("invariants failed: %v", res.InvariantErrors)
	}
}

func TestScenario12_100NodeMesh(t *testing.T) {
	res, err := RunScenario12_100NodeMesh(42)
	if err != nil {
		t.Fatalf("Scenario 12 failed: %v", err)
	}
	if !res.InvariantsPassed {
		t.Errorf("invariants failed: %v", res.InvariantErrors)
	}
	if res.MessagesDelivered == 0 {
		t.Errorf("expected deliveries in 100-node mesh, got 0")
	}
}

// TestDeterministicReplay verifies Section 21:
// Given identical seed and scenario, two separate runs produce identical results.
func TestDeterministicReplay(t *testing.T) {
	const testSeed = 998877

	res1, err1 := RunScenario03_PartitionReconnect(testSeed)
	if err1 != nil {
		t.Fatalf("Run 1 failed: %v", err1)
	}

	res2, err2 := RunScenario03_PartitionReconnect(testSeed)
	if err2 != nil {
		t.Fatalf("Run 2 failed: %v", err2)
	}

	json1, _ := res1.ToJSON()
	json2, _ := res2.ToJSON()

	if !bytes.Equal(json1, json2) {
		t.Fatalf("Deterministic replay failed!\nRun 1:\n%s\nRun 2:\n%s", string(json1), string(json2))
	}
}
