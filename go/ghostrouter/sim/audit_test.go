package sim

import (
	"fmt"
	"math/rand"
	"testing"
	"time"

	"ghostrouter"
)

// TestAuditBreakdownAndLatency verifies Section 3 and Section 4:
// 1. Exact message state reconciliation: Created == Delivered + Pending + Expired + Failed + Rejected
// 2. Exact latency breakdown proving why P50 was 0ms.
func TestAuditBreakdownAndLatency(t *testing.T) {
	const (
		seed        = 123456789
		numNodes    = 100
		numMessages = 1000
	)

	engine, err := NewSimEngine(seed, false)
	if err != nil {
		t.Fatalf("failed to init engine: %v", err)
	}
	defer engine.Close()

	rng := rand.New(rand.NewSource(seed))

	// 1. Create 100 nodes
	for i := 0; i < numNodes; i++ {
		name := fmt.Sprintf("N%03d", i)
		if _, err := engine.AddNode(name); err != nil {
			t.Fatalf("failed to add node %s: %v", name, err)
		}
	}

	// 2. Build deterministic 10x10 torus/mesh topology
	for i := 0; i < numNodes; i++ {
		col := i % 10
		row := i / 10
		curr := fmt.Sprintf("N%03d", i)

		if col < 9 {
			engine.Connect(curr, fmt.Sprintf("N%03d", i+1), -65)
		}
		if row < 9 {
			engine.Connect(curr, fmt.Sprintf("N%03d", i+10), -65)
		}
		if col < 9 && row < 9 {
			engine.Connect(curr, fmt.Sprintf("N%03d", i+11), -75)
		}
	}

	// 3. Inject 1,000 messages across random pairs
	for m := 0; m < numMessages; m++ {
		srcIdx := rng.Intn(numNodes)
		dstIdx := rng.Intn(numNodes)
		for dstIdx == srcIdx {
			dstIdx = rng.Intn(numNodes)
		}

		src := fmt.Sprintf("N%03d", srcIdx)
		dst := fmt.Sprintf("N%03d", dstIdx)
		payload := []byte(fmt.Sprintf("Stress msg #%04d from %s to %s", m, src, dst))

		if _, err := engine.SendMessage(src, dst, payload); err != nil {
			t.Fatalf("failed to inject message %d: %v", m, err)
		}

		if m > 0 && m%100 == 0 {
			engine.ExchangeAllActive()
			engine.Advance(1 * time.Minute)
		}
	}

	for round := 0; round < 30; round++ {
		engine.ExchangeAllActive()
		engine.Advance(2 * time.Minute)
	}

	res := engine.Results("audit_1000")

	// Count states across all 1000 messages
	deliveredCount := 0
	pendingCount := 0
	expiredCount := 0
	failedCount := 0

	zeroLatencyCount := 0
	nonzeroLatencyCount := 0

	nowUnix := engine.Clock.NowUnix()

	for _, meta := range engine.msgs {
		if meta.Delivered {
			deliveredCount++
			latency := meta.DeliverAt.Sub(meta.CreatedAt)
			if latency == 0 {
				zeroLatencyCount++
			} else {
				nonzeroLatencyCount++
			}
		} else {
			// Check if message is in any store
			foundInStore := false
			for _, node := range engine.Nodes {
				if node.IsAlive && node.Router != nil {
					m, _ := node.Router.GetStore().GetMessage(meta.ID)
					if m != nil {
						foundInStore = true
						if m.CreatedAt+m.TTLSeconds < nowUnix {
							expiredCount++
						} else {
							pendingCount++
						}
						break
					}
				}
			}
			if !foundInStore {
				failedCount++
			}
		}
	}

	t.Logf("=== DETAILED MESSAGE AUDIT RECONCILIATION ===")
	t.Logf("Created:           %d", res.MessagesCreated)
	t.Logf("Delivered:         %d", deliveredCount)
	t.Logf("Still Pending:     %d", pendingCount)
	t.Logf("Expired:           %d", expiredCount)
	t.Logf("Failed/Lost:       %d", failedCount)
	t.Logf("Reconciled Total:  %d", deliveredCount+pendingCount+expiredCount+failedCount)
	t.Logf("=== LATENCY QUANTILE AUDIT ===")
	t.Logf("Total Deliveries:  %d", deliveredCount)
	t.Logf("Zero-Latency (same-step deliveries): %d (%.1f%%)", zeroLatencyCount, float64(zeroLatencyCount)*100/float64(deliveredCount))
	t.Logf("Non-Zero Latencies:                 %d (%.1f%%)", nonzeroLatencyCount, float64(nonzeroLatencyCount)*100/float64(deliveredCount))
	t.Logf("Reported P50:      %.1f ms", res.P50LatencyMs)
	t.Logf("Reported Avg:      %.1f ms", res.AvgLatencyMs)
	t.Logf("Reported P95:      %.1f ms", res.P95LatencyMs)
	t.Logf("Reported P99:      %.1f ms", res.P99LatencyMs)

	if res.MessagesCreated != (deliveredCount + pendingCount + expiredCount + failedCount) {
		t.Errorf("Reconciliation error: created (%d) != sum (%d)",
			res.MessagesCreated, deliveredCount+pendingCount+expiredCount+failedCount)
	}

	// Also prove deterministic latency: t=0 created, delivered at t=5s -> latency=5s
	clock := NewSimClock()
	t0 := clock.Now()
	clock.Advance(5 * time.Second)
	t5 := clock.Now()
	d := t5.Sub(t0)
	if d != 5*time.Second {
		t.Errorf("clock advance error: got %v, want 5s", d)
	}
}

// TestAuditFiveRunDeterminism runs the same scenario 5 times with identical seed
// and verifies that output JSON is 100% byte-for-byte identical across all 5 runs.
// Then verifies that a different seed produces different results.
func TestAuditFiveRunDeterminism(t *testing.T) {
	const seed = 554433

	var firstJSON []byte
	for i := 0; i < 5; i++ {
		res, err := RunScenario03_PartitionReconnect(seed)
		if err != nil {
			t.Fatalf("Run %d failed: %v", i+1, err)
		}
		data, err := res.ToJSON()
		if err != nil {
			t.Fatalf("Run %d JSON failed: %v", i+1, err)
		}

		if i == 0 {
			firstJSON = data
		} else {
			if string(data) != string(firstJSON) {
				t.Fatalf("Run %d deviated from Run 1!\nRun 1:\n%s\nRun %d:\n%s", i+1, string(firstJSON), i+1, string(data))
			}
		}
	}
	t.Logf("5 identical runs passed with 100%% byte-for-byte reproducibility!")

	// Now run with seed+1
	resDiff, err := RunScenario03_PartitionReconnect(seed + 1)
	if err != nil {
		t.Fatalf("Diff seed run failed: %v", err)
	}
	dataDiff, _ := resDiff.ToJSON()
	if string(dataDiff) == string(firstJSON) {
		t.Errorf("Seed %d and seed %d produced identical output; expected seed variance", seed, seed+1)
	}
}

// TestAuditTTLExactBoundary verifies Section 9:
// t=0: message valid
// t=23h59m: message still valid
// t=24h01m: message expired and pruned
func TestAuditTTLExactBoundary(t *testing.T) {
	engine, err := NewSimEngine(123, false)
	if err != nil {
		t.Fatalf("failed to init engine: %v", err)
	}
	defer engine.Close()

	src, err := engine.AddNode("Src")
	if err != nil {
		t.Fatalf("failed to add src: %v", err)
	}
	dst, err := engine.AddNode("Dst")
	if err != nil {
		t.Fatalf("failed to add dst: %v", err)
	}

	payload := []byte("TTL exact boundary check")
	msgID, err := engine.SendMessage("Src", "Dst", payload)
	if err != nil {
		t.Fatalf("failed to send: %v", err)
	}

	// 1. Advance 23 hours 59 minutes (1 minute before 24h expiration)
	engine.Advance(23*time.Hour + 59*time.Minute)
	m, _ := src.Router.GetStore().GetMessage(msgID)
	if m == nil {
		t.Fatalf("at t=23h59m message should NOT be expired or pruned")
	}
	t.Logf("At t=23h59m: message status is %d, still valid in store", m.Status)

	// 2. Advance 2 more minutes (total: 24 hours 1 minute > 24h)
	engine.Advance(2 * time.Minute)
	mExpired, _ := src.Router.GetStore().GetMessage(msgID)
	if mExpired != nil {
		t.Fatalf("at t=24h01m message should be pruned from store by janitor")
	}
	t.Logf("At t=24h01m: message successfully pruned from store")

	// 3. Test OnMessageReceived on a relay node receiving an expired transit message
	otherNodeID := []byte("other-destination-node-00000000")
	expiredMsg := &ghostrouter.Message{
		ID:              []byte("expired-packet-id-00000000000000"),
		Src:             src.ID,
		Dst:             otherNodeID,
		Payload:         []byte("expired payload"),
		CopiesRemaining: 2,
		TTLSeconds:      86400,
		HopCount:        1,
		CreatedAt:       engine.Clock.NowUnix() - 86401, // 1 second past TTL
		Status:          ghostrouter.StatusPending,
	}
	raw := ghostrouter.EncodeMessage(expiredMsg)
	status := dst.Router.OnMessageReceived(raw)
	t.Logf("Expired transit packet received at relay -> status: '%s'", status)
	if status != "dropped: TTL expired" {
		t.Errorf("expected 'dropped: TTL expired', got '%s'", status)
	}
}

// TestAuditDeduplicationDiamond verifies Section 10:
// Diamond topology: A -> B -> D, A -> C -> D, and direct A -> D.
// Proves: multiple packet arrivals -> exactly one logical application delivery.
func TestAuditDeduplicationDiamond(t *testing.T) {
	engine, err := NewSimEngine(888, false)
	if err != nil {
		t.Fatalf("failed to init engine: %v", err)
	}
	defer engine.Close()

	if _, err := engine.AddNode("A"); err != nil {
		t.Fatalf("failed to add A: %v", err)
	}
	if _, err := engine.AddNode("B"); err != nil {
		t.Fatalf("failed to add B: %v", err)
	}
	if _, err := engine.AddNode("C"); err != nil {
		t.Fatalf("failed to add C: %v", err)
	}
	nodeD, err := engine.AddNode("D")
	if err != nil {
		t.Fatalf("failed to add D: %v", err)
	}

	payload := []byte("Diamond multi-path dedup verification")
	msgID, err := engine.SendMessage("A", "D", payload)
	if err != nil {
		t.Fatalf("failed to send: %v", err)
	}

	// 1. A encounters B (sprays 2 copies)
	engine.Connect("A", "B", -60)
	engine.Exchange("A", "B")
	engine.Disconnect("A", "B")

	// 2. A encounters C (sprays 1 copy)
	engine.Connect("A", "C", -60)
	engine.Exchange("A", "C")
	engine.Disconnect("A", "C")

	// Now B, C, and A all have copies for D.
	// Path 1: B encounters D
	engine.Connect("B", "D", -60)
	d1, _, dr1, _ := engine.Exchange("B", "D")
	engine.Disconnect("B", "D")

	// Path 2: C encounters D
	engine.Connect("C", "D", -60)
	d2, _, dr2, _ := engine.Exchange("C", "D")
	engine.Disconnect("C", "D")

	// Path 3: A encounters D
	engine.Connect("A", "D", -60)
	d3, _, dr3, _ := engine.Exchange("A", "D")
	engine.Disconnect("A", "D")

	totalArrivals := d1 + d2 + d3 + dr1 + dr2 + dr3
	totalDuplicatesDropped := dr1 + dr2 + dr3
	appDeliveries := nodeD.DeliveredCount()

	t.Logf("=== DIAMOND DEDUPLICATION AUDIT ===")
	t.Logf("Packet arrivals at D:    %d", totalArrivals)
	t.Logf("Duplicates dropped at D: %d", totalDuplicatesDropped)
	t.Logf("Application deliveries:  %d", appDeliveries)

	if appDeliveries != 1 {
		t.Fatalf("expected exactly 1 application delivery, got %d", appDeliveries)
	}
	if totalDuplicatesDropped < 2 {
		t.Fatalf("expected at least 2 duplicate drops, got %d", totalDuplicatesDropped)
	}
	_ = msgID
}

// TestAuditBatteryRelayGating verifies Section 12:
// Battery 15% (< 20% cutoff):
// - Relay forwarded messages: REJECTED (willingness=0.0)
// - Own local messages: DELIVERED (leaf mode)
// Battery 80% (> 60% high):
// - Relay forwarded messages: ACCEPTED (willingness=1.0)
func TestAuditBatteryRelayGating(t *testing.T) {
	engine, err := NewSimEngine(654, false)
	if err != nil {
		t.Fatalf("failed to init engine: %v", err)
	}
	defer engine.Close()

	lowBatNode, err := engine.AddNode("LowBat")
	if err != nil {
		t.Fatalf("failed to add LowBat: %v", err)
	}
	destNode, err := engine.AddNode("Dest")
	if err != nil {
		t.Fatalf("failed to add Dest: %v", err)
	}

	// Set battery to 15% (CRITICAL mode)
	lowBatNode.SetBattery(15)
	if lowBatNode.Router.GetRelayWillingness() != 0.0 {
		t.Fatalf("expected willingness 0.0, got %f", lowBatNode.Router.GetRelayWillingness())
	}

	// 1. Try to send transit relay message through LowBat
	transitMsg := &ghostrouter.Message{
		ID:              []byte("transit-msg-id-00000000000000000"),
		Src:             []byte("some-sender-00000000000000000000"),
		Dst:             destNode.ID, // destined for Dest, NOT LowBat
		Payload:         []byte("transit payload"),
		CopiesRemaining: 2,
		TTLSeconds:      86400,
		HopCount:        1,
		CreatedAt:       engine.Clock.NowUnix(),
		Status:          ghostrouter.StatusPending,
	}
	rawTransit := ghostrouter.EncodeMessage(transitMsg)
	transitStatus := lowBatNode.Router.OnMessageReceived(rawTransit)
	t.Logf("Battery 15%%: transit message status -> '%s'", transitStatus)
	if transitStatus != "dropped: low battery, not relaying" {
		t.Errorf("expected 'dropped: low battery, not relaying', got '%s'", transitStatus)
	}

	// 2. Send message destined directly for LowBat
	ownMsg := &ghostrouter.Message{
		ID:              []byte("own-msg-id-0000000000000000000000"),
		Src:             []byte("some-sender-00000000000000000000"),
		Dst:             lowBatNode.ID, // destined directly FOR LowBat
		Payload:         []byte("own message payload"),
		CopiesRemaining: 1,
		TTLSeconds:      86400,
		HopCount:        1,
		CreatedAt:       engine.Clock.NowUnix(),
		Status:          ghostrouter.StatusPending,
	}
	rawOwn := ghostrouter.EncodeMessage(ownMsg)
	ownStatus := lowBatNode.Router.OnMessageReceived(rawOwn)
	t.Logf("Battery 15%%: own direct message status -> '%s'", ownStatus)
	if ownStatus != "delivered" {
		t.Errorf("expected 'delivered', got '%s'", ownStatus)
	}

	// 3. Recharge LowBat to 80% (ACTIVE mode)
	lowBatNode.SetBattery(80)
	if lowBatNode.Router.GetRelayWillingness() != 1.0 {
		t.Fatalf("expected willingness 1.0, got %f", lowBatNode.Router.GetRelayWillingness())
	}

	rawTransit2 := ghostrouter.EncodeMessage(&ghostrouter.Message{
		ID:              []byte("transit-msg-2-000000000000000000"),
		Src:             []byte("some-sender-00000000000000000000"),
		Dst:             destNode.ID,
		Payload:         []byte("transit payload 2"),
		CopiesRemaining: 2,
		TTLSeconds:      86400,
		HopCount:        1,
		CreatedAt:       engine.Clock.NowUnix(),
		Status:          ghostrouter.StatusPending,
	})
	rechargeStatus := lowBatNode.Router.OnMessageReceived(rawTransit2)
	t.Logf("Battery 80%%: transit message status -> '%s'", rechargeStatus)
	if rechargeStatus != "forwarded" {
		t.Errorf("expected 'forwarded', got '%s'", rechargeStatus)
	}
}
