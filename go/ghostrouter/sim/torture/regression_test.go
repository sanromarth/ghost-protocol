package torture

import (
	"bytes"
	"testing"
	"time"

	"ghostrouter"
	"ghostrouter/sim"
)

// TestRegression_RebootDuplicateDelivery verifies that when multiple carriers hold copies of
// a message, a destination node that reboots does not deliver a duplicate if a second carrier
// delivers after the reboot.
func TestRegression_RebootDuplicateDelivery(t *testing.T) {
	engine, err := sim.NewSimEngine(9999, false)
	if err != nil {
		t.Fatalf("failed to create engine: %v", err)
	}
	defer engine.Close()

	if _, err := engine.AddNode("src"); err != nil {
		t.Fatalf("AddNode src failed: %v", err)
	}
	c1, err := engine.AddNode("c1")
	if err != nil {
		t.Fatalf("AddNode c1 failed: %v", err)
	}
	c2, err := engine.AddNode("c2")
	if err != nil {
		t.Fatalf("AddNode c2 failed: %v", err)
	}
	dst, err := engine.AddNode("dst")
	if err != nil {
		t.Fatalf("AddNode dst failed: %v", err)
	}

	// Connect src to both carriers
	engine.Connect("src", "c1", -65)
	engine.Connect("src", "c2", -65)

	payload := []byte("critical_duplicate_reboot_test")
	msgID, err := engine.SendMessage("src", "dst", payload)
	if err != nil {
		t.Fatalf("SendMessage failed: %v", err)
	}

	// Spray to c1 (4 -> 2, 2)
	engine.Exchange("src", "c1")
	// Spray to c2 (2 -> 1, 1)
	engine.Exchange("src", "c2")

	// Verify both c1 and c2 have pending copies
	m1, _ := c1.Router.GetStore().GetMessage(msgID)
	m2, _ := c2.Router.GetStore().GetMessage(msgID)
	if m1 == nil || m2 == nil {
		t.Fatalf("Carriers did not receive spray copies")
	}

	// Disconnect src
	engine.Disconnect("src", "c1")
	engine.Disconnect("src", "c2")

	// Phase 1: c1 encounters dst and delivers
	engine.Connect("c1", "dst", -65)
	d1, _, _, err := engine.Exchange("c1", "dst")
	if err != nil {
		t.Fatalf("Exchange c1-dst failed: %v", err)
	}
	if d1 != 1 || dst.DeliveredCount() != 1 {
		t.Fatalf("Expected 1 delivery from c1, got %d", d1)
	}

	// Phase 2: dst crashes and restarts
	dst.Crash()
	engine.Advance(10 * time.Second)
	if err := dst.Restart(); err != nil {
		t.Fatalf("dst restart failed: %v", err)
	}

	// Phase 3: c2 (which still holds its sprayed copy) encounters dst
	engine.Connect("c2", "dst", -65)
	d2, _, _, err := engine.Exchange("c2", "dst")
	if err != nil {
		t.Fatalf("Exchange c2-dst failed: %v", err)
	}

	// Invariant I6: Destination must NOT deliver a duplicate logical message after reboot
	if d2 > 0 || dst.DeliveredCount() > 1 {
		t.Errorf("INVARIANT VIOLATION (I6 Dedup): dst delivered duplicate message after reboot (total deliveries: %d, exchange2: %d)", dst.DeliveredCount(), d2)
	}
}

// TestRegression_BatteryDrainStoredTransit verifies the behavior when a node that previously
// accepted and stored transit relay messages experiences battery depletion below the 20% critical threshold.
func TestRegression_BatteryDrainStoredTransit(t *testing.T) {
	engine, err := sim.NewSimEngine(8888, false)
	if err != nil {
		t.Fatalf("failed to create engine: %v", err)
	}
	defer engine.Close()

	_, _ = engine.AddNode("src")
	relay, _ := engine.AddNode("relay")
	_, _ = engine.AddNode("dst")

	engine.Connect("src", "relay", -65)

	payload := []byte("transit_message")
	msgID, err := engine.SendMessage("src", "dst", payload)
	if err != nil {
		t.Fatalf("SendMessage failed: %v", err)
	}

	// Relay receives message while battery is 100%
	engine.Exchange("src", "relay")
	m, _ := relay.Router.GetStore().GetMessage(msgID)
	if m == nil {
		t.Fatalf("Relay failed to store transit message")
	}

	// Battery drops to 10% (CRITICAL threshold, willingness = 0.0)
	relay.SetBattery(10)

	// Invariant I7: Check whether low-battery relay retains or rejects new transit
	isTransit := !bytes.Equal(m.Src, relay.ID) && !bytes.Equal(m.Dst, relay.ID)
	if isTransit && relay.BatteryPercent < 20 {
		t.Logf("DISCOVERED BEHAVIOR (I7 Relay Gating): Relay at %d%% battery retains pre-existing transit message in BoltDB", relay.BatteryPercent)
	}

	// When relay at 10% receives a NEW message, it must reject it
	payload2 := []byte("new_transit_message_while_critical")
	_, _ = engine.SendMessage("src", "dst", payload2)
	_, fwd2, _, _ := engine.Exchange("src", "relay")
	if fwd2 > 0 {
		t.Errorf("INVARIANT VIOLATION: Low battery relay accepted new transit message while critical!")
	}
}

// TestCrashConsistency_DeliveryWindows tests crash windows during inbound message delivery:
// - Window 1: Crash after persistence, before callback invocation
// - Window 2: Crash after callback invocation
// Verifies that in all crash scenarios, rebooting and receiving duplicate packets never produces duplicate delivery.
func TestCrashConsistency_DeliveryWindows(t *testing.T) {
	// Window 1: Crash after persistence, before callback
	t.Run("CrashAfterPersistenceBeforeCallback", func(t *testing.T) {
		engine, err := sim.NewSimEngine(7771, false)
		if err != nil {
			t.Fatalf("failed to create engine: %v", err)
		}
		defer engine.Close()

		src, _ := engine.AddNode("src")
		dst, _ := engine.AddNode("dst")

		payload := []byte("crash_window_payload_1")
		msgID, err := engine.SendMessage("src", "dst", payload)
		if err != nil {
			t.Fatalf("SendMessage failed: %v", err)
		}

		m, _ := src.Router.GetStore().GetMessage(msgID)
		rawMsg := ghostrouter.EncodeMessage(m)

		// Set test hook on dst router to crash right after durable BoltDB persistence
		crashedAfterPersist := false
		dst.Router.SetAfterPersistDeliverHook(func(id []byte) {
			crashedAfterPersist = true
			dst.Crash()
		})

		// Deliver packet - triggers hook and crashes
		dst.Router.OnMessageReceived(rawMsg)

		if !crashedAfterPersist {
			t.Fatalf("Expected crash hook to trigger")
		}

		// Destination restarts
		if err := dst.Restart(); err != nil {
			t.Fatalf("Restart failed: %v", err)
		}

		// Carrier re-attempts delivery
		status := dst.Router.OnMessageReceived(rawMsg)
		if status != "dropped: duplicate" {
			t.Errorf("Expected dropped: duplicate after restart, got %s", status)
		}

		// Verify application delivery count <= 1 (I6 Dedup invariant)
		if dst.DeliveredCount() > 1 {
			t.Errorf("INVARIANT VIOLATION (I6 Dedup): dst delivered duplicate (count=%d)", dst.DeliveredCount())
		}
	})

	// Window 2: Crash after callback
	t.Run("CrashAfterCallback", func(t *testing.T) {
		engine, err := sim.NewSimEngine(7772, false)
		if err != nil {
			t.Fatalf("failed to create engine: %v", err)
		}
		defer engine.Close()

		src, _ := engine.AddNode("src")
		dst, _ := engine.AddNode("dst")

		payload := []byte("crash_window_payload_2")
		msgID, err := engine.SendMessage("src", "dst", payload)
		if err != nil {
			t.Fatalf("SendMessage failed: %v", err)
		}

		m, _ := src.Router.GetStore().GetMessage(msgID)
		rawMsg := ghostrouter.EncodeMessage(m)

		// Deliver normally
		status := dst.Router.OnMessageReceived(rawMsg)
		if status != "delivered" || dst.DeliveredCount() != 1 {
			t.Fatalf("Initial delivery failed: status=%s, count=%d", status, dst.DeliveredCount())
		}

		// Node crashes and restarts
		dst.Crash()
		if err := dst.Restart(); err != nil {
			t.Fatalf("Restart failed: %v", err)
		}

		// Duplicate delivery attempt
		status2 := dst.Router.OnMessageReceived(rawMsg)
		if status2 != "dropped: duplicate" {
			t.Errorf("Expected dropped: duplicate after reboot, got %s", status2)
		}

		if dst.DeliveredCount() != 1 {
			t.Errorf("INVARIANT VIOLATION (I6 Dedup): expected exactly 1 delivery, got %d", dst.DeliveredCount())
		}
	})
}

// TestMetamorphic_DedupCopies verifies the metamorphic property:
// If an execution delivers message M once, adding additional duplicate copies of M
// to the network must not increase application delivery count.
func TestMetamorphic_DedupCopies(t *testing.T) {
	engine, err := sim.NewSimEngine(7773, false)
	if err != nil {
		t.Fatalf("failed to create engine: %v", err)
	}
	defer engine.Close()

	src, _ := engine.AddNode("src")
	dst, _ := engine.AddNode("dst")

	payload := []byte("metamorphic_dup_test")
	msgID, err := engine.SendMessage("src", "dst", payload)
	if err != nil {
		t.Fatalf("SendMessage failed: %v", err)
	}

	m, _ := src.Router.GetStore().GetMessage(msgID)
	rawMsg := ghostrouter.EncodeMessage(m)

	// Baseline delivery
	status := dst.Router.OnMessageReceived(rawMsg)
	if status != "delivered" || dst.DeliveredCount() != 1 {
		t.Fatalf("Initial delivery failed: %s", status)
	}

	// Metamorphic perturbation: inject 20 duplicate copies from various angles
	for i := 0; i < 20; i++ {
		res := dst.Router.OnMessageReceived(rawMsg)
		if res != "dropped: duplicate" {
			t.Errorf("Duplicate injection %d returned %s, expected dropped: duplicate", i, res)
		}
	}

	if dst.DeliveredCount() != 1 {
		t.Fatalf("Metamorphic invariant violation: delivery count increased to %d", dst.DeliveredCount())
	}
}

// TestMetamorphic_Restart verifies the metamorphic restart property:
// Inserting a reboot at any point after durable delivery state exists
// must not change the final application delivery count.
func TestMetamorphic_Restart(t *testing.T) {
	engine, err := sim.NewSimEngine(7774, false)
	if err != nil {
		t.Fatalf("failed to create engine: %v", err)
	}
	defer engine.Close()

	src, _ := engine.AddNode("src")
	dst, _ := engine.AddNode("dst")

	payload := []byte("metamorphic_restart_test")
	msgID, err := engine.SendMessage("src", "dst", payload)
	if err != nil {
		t.Fatalf("SendMessage failed: %v", err)
	}

	m, _ := src.Router.GetStore().GetMessage(msgID)
	rawMsg := ghostrouter.EncodeMessage(m)

	dst.Router.OnMessageReceived(rawMsg)
	if dst.DeliveredCount() != 1 {
		t.Fatalf("Initial delivery failed")
	}

	// Multiple sequential reboots and duplicate arrivals
	for r := 0; r < 5; r++ {
		dst.Crash()
		if err := dst.Restart(); err != nil {
			t.Fatalf("Restart %d failed: %v", r, err)
		}
		dst.Router.OnMessageReceived(rawMsg)
	}

	if dst.DeliveredCount() != 1 {
		t.Fatalf("Restart metamorphic property violated: final deliveries=%d (expected 1)", dst.DeliveredCount())
	}
}

// TestMetamorphic_BatteryRecovery verifies that a node recovering from critical battery
// resumes relay behavior according to policy without producing duplicate deliveries.
func TestMetamorphic_BatteryRecovery(t *testing.T) {
	engine, err := sim.NewSimEngine(7775, false)
	if err != nil {
		t.Fatalf("failed to create engine: %v", err)
	}
	defer engine.Close()

	_, _ = engine.AddNode("src")
	relay, _ := engine.AddNode("relay")
	dst, _ := engine.AddNode("dst")

	engine.Connect("src", "relay", -65)
	engine.Connect("relay", "dst", -65)

	// Relay at 10% (critical, willingness=0.0)
	relay.SetBattery(10)

	payload := []byte("battery_recovery_test")
	_, err = engine.SendMessage("src", "dst", payload)
	if err != nil {
		t.Fatalf("SendMessage failed: %v", err)
	}

	// Exchange while critical: relay must not accept
	_, fwd1, _, _ := engine.Exchange("src", "relay")
	if fwd1 > 0 {
		t.Fatalf("Critical relay accepted message: fwd=%d", fwd1)
	}

	// Re-charge relay to 80% (willingness=1.0)
	relay.SetBattery(80)

	// Exchange after recovery: relay should now accept
	_, fwd2, _, _ := engine.Exchange("src", "relay")
	if fwd2 == 0 {
		t.Fatalf("Re-charged relay did not accept message: fwd=%d", fwd2)
	}

	// Relay encounters destination: delivers
	d, _, _, _ := engine.Exchange("relay", "dst")
	if d != 1 || dst.DeliveredCount() != 1 {
		t.Fatalf("Delivery after recovery failed: d=%d, deliveredCount=%d", d, dst.DeliveredCount())
	}

	// Repeated encounters must not duplicate
	d2, _, _, _ := engine.Exchange("relay", "dst")
	if d2 > 0 || dst.DeliveredCount() != 1 {
		t.Fatalf("Duplicate delivery occurred after recovery: d2=%d, count=%d", d2, dst.DeliveredCount())
	}
}

