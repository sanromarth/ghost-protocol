package sim

import (
	"bytes"
	"fmt"
	"math/rand"
	"time"

	"ghostrouter"
)

// ScenarioFunc represents a callable simulation scenario.
type ScenarioFunc func(seed int64) (*SimResults, error)

// Scenarios maps canonical scenario names to runner functions.
var Scenarios = map[string]ScenarioFunc{
	"direct":             RunScenario01_Direct,
	"01_direct":          RunScenario01_Direct,
	"one_relay":          RunScenario02_OneRelay,
	"02_one_relay":       RunScenario02_OneRelay,
	"partition":          RunScenario03_PartitionReconnect,
	"03_partition":       RunScenario03_PartitionReconnect,
	"spray":              RunScenario04_FourCopySpray,
	"04_spray":           RunScenario04_FourCopySpray,
	"duplicate":          RunScenario05_DuplicateFlood,
	"05_duplicate":       RunScenario05_DuplicateFlood,
	"ttl":                RunScenario06_TTLExpiration,
	"06_ttl":             RunScenario06_TTLExpiration,
	"hop_limit":          RunScenario07_HopLimit,
	"07_hop_limit":       RunScenario07_HopLimit,
	"low_battery":        RunScenario08_LowBattery,
	"08_low_battery":     RunScenario08_LowBattery,
	"crash_restart":      RunScenario09_CrashDuringTransit,
	"09_crash_restart":   RunScenario09_CrashDuringTransit,
	"repeated_encounter": RunScenario10_RepeatedEncounters,
	"10_repeated":        RunScenario10_RepeatedEncounters,
	"packet_loss":        RunScenario11_HighPacketLoss,
	"11_packet_loss":     RunScenario11_HighPacketLoss,
	"100_node":           RunScenario12_100NodeMesh,
	"12_100_node":        RunScenario12_100NodeMesh,
}

// Scenario 01 — Direct Delivery: A <-> B
func RunScenario01_Direct(seed int64) (*SimResults, error) {
	engine, err := NewSimEngine(seed, false)
	if err != nil {
		return nil, err
	}
	defer engine.Close()

	if _, err := engine.AddNode("A"); err != nil {
		return nil, err
	}
	nodeB, err := engine.AddNode("B")
	if err != nil {
		return nil, err
	}

	engine.Connect("A", "B", -60)

	payload := []byte("Direct encrypted payload between A and B")
	if _, err := engine.SendMessage("A", "B", payload); err != nil {
		return nil, err
	}

	engine.Advance(100 * time.Millisecond)
	engine.Exchange("A", "B")

	if nodeB.DeliveredCount() != 1 {
		return nil, fmt.Errorf("node B expected 1 delivery, got %d", nodeB.DeliveredCount())
	}
	if !bytes.Equal(nodeB.DeliveredMessages[0].Payload, payload) {
		return nil, fmt.Errorf("payload mismatch on direct delivery")
	}

	res := engine.Results("01_direct")
	if !res.InvariantsPassed {
		return res, fmt.Errorf("invariants failed: %v", res.InvariantErrors)
	}
	return res, nil
}

// Scenario 02 — One Relay: A <-> B, B <-> C, A sends C
func RunScenario02_OneRelay(seed int64) (*SimResults, error) {
	engine, err := NewSimEngine(seed, false)
	if err != nil {
		return nil, err
	}
	defer engine.Close()

	if _, err := engine.AddNode("A"); err != nil {
		return nil, err
	}
	nodeB, err := engine.AddNode("B")
	if err != nil {
		return nil, err
	}
	nodeC, err := engine.AddNode("C")
	if err != nil {
		return nil, err
	}

	// Step 1: A encounters B (C is disconnected / out of range)
	engine.Connect("A", "B", -70)
	payload := []byte("DTN multi-hop message from A to C via B")
	if _, err := engine.SendMessage("A", "C", payload); err != nil {
		return nil, err
	}

	engine.Advance(500 * time.Millisecond)
	engine.Exchange("A", "B")

	// Carrier B must store relay copy, but NOT deliver to own app
	if nodeB.DeliveredCount() > 0 {
		return nil, fmt.Errorf("carrier B should not deliver transit message to app")
	}
	if nodeB.Router.MessageCount() == 0 {
		return nil, fmt.Errorf("carrier B should store forwarded message")
	}

	// Step 2: A moves away, B encounters C
	engine.Disconnect("A", "B")
	engine.Advance(10 * time.Minute)
	engine.Connect("B", "C", -65)

	engine.Exchange("B", "C")

	if nodeC.DeliveredCount() != 1 {
		return nil, fmt.Errorf("destination C expected 1 delivery, got %d", nodeC.DeliveredCount())
	}
	if !bytes.Equal(nodeC.DeliveredMessages[0].Payload, payload) {
		return nil, fmt.Errorf("payload corrupted during relay")
	}

	res := engine.Results("02_one_relay")
	if !res.InvariantsPassed {
		return res, fmt.Errorf("invariants failed: %v", res.InvariantErrors)
	}
	return res, nil
}

// Scenario 03 — Partition / Reconnect: A <-> B, C isolated, then B <-> C
func RunScenario03_PartitionReconnect(seed int64) (*SimResults, error) {
	engine, err := NewSimEngine(seed, false)
	if err != nil {
		return nil, err
	}
	defer engine.Close()

	if _, err := engine.AddNode("A"); err != nil {
		return nil, err
	}
	if _, err := engine.AddNode("B"); err != nil {
		return nil, err
	}
	nodeC, err := engine.AddNode("C")
	if err != nil {
		return nil, err
	}

	// Phase 1: Partition 1 {A, B}, C is isolated
	engine.Connect("A", "B", -60)
	payload := []byte("Partition reconvergence test payload")
	if _, err := engine.SendMessage("A", "C", payload); err != nil {
		return nil, err
	}

	engine.Advance(1 * time.Minute)
	engine.Exchange("A", "B")

	if nodeC.DeliveredCount() > 0 {
		return nil, fmt.Errorf("isolated node C received message during partition")
	}

	// Phase 2: Partition bridge heals (B meets C)
	engine.Disconnect("A", "B")
	engine.Advance(1 * time.Hour)
	engine.Connect("B", "C", -70)

	engine.Exchange("B", "C")

	if nodeC.DeliveredCount() != 1 {
		return nil, fmt.Errorf("node C expected 1 delivery after healing, got %d", nodeC.DeliveredCount())
	}

	res := engine.Results("03_partition")
	if !res.InvariantsPassed {
		return res, fmt.Errorf("invariants failed: %v", res.InvariantErrors)
	}
	return res, nil
}

// Scenario 04 — Four-Copy Spray: Verify L=4 binary split conservation
func RunScenario04_FourCopySpray(seed int64) (*SimResults, error) {
	engine, err := NewSimEngine(seed, false)
	if err != nil {
		return nil, err
	}
	defer engine.Close()

	nodeS, err := engine.AddNode("S")
	if err != nil {
		return nil, err
	}
	nodeC1, err := engine.AddNode("C1")
	if err != nil {
		return nil, err
	}
	nodeC2, err := engine.AddNode("C2")
	if err != nil {
		return nil, err
	}
	nodeC3, err := engine.AddNode("C3")
	if err != nil {
		return nil, err
	}
	nodeC4, err := engine.AddNode("C4")
	if err != nil {
		return nil, err
	}
	if _, err := engine.AddNode("D"); err != nil {
		return nil, err
	}

	payload := []byte("Four-copy spray binary split verification")
	msgID, err := engine.SendMessage("S", "D", payload)
	if err != nil {
		return nil, err
	}

	// S meets C1: S gives 2 copies to C1, keeps 2
	engine.Connect("S", "C1", -60)
	engine.Exchange("S", "C1")
	engine.Disconnect("S", "C1")

	// S meets C2: S gives 1 copy to C2, keeps 1
	engine.Connect("S", "C2", -60)
	engine.Exchange("S", "C2")
	engine.Disconnect("S", "C2")

	// S meets C3: S has 1 copy remaining; cannot spray to relay C3!
	engine.Connect("S", "C3", -60)
	engine.Exchange("S", "C3")
	engine.Disconnect("S", "C3")

	// C1 (has 2 copies) meets C4: C1 gives 1 copy to C4, keeps 1
	engine.Connect("C1", "C4", -60)
	engine.Exchange("C1", "C4")
	engine.Disconnect("C1", "C4")

	// Verify exact copy accounting
	mS, _ := nodeS.Router.GetStore().GetMessage(msgID)
	mC1, _ := nodeC1.Router.GetStore().GetMessage(msgID)
	mC2, _ := nodeC2.Router.GetStore().GetMessage(msgID)
	mC3, _ := nodeC3.Router.GetStore().GetMessage(msgID)
	mC4, _ := nodeC4.Router.GetStore().GetMessage(msgID)

	if mS == nil || mS.CopiesRemaining != 1 {
		return nil, fmt.Errorf("node S expected 1 copy remaining, got %+v", mS)
	}
	if mC1 == nil || mC1.CopiesRemaining != 1 {
		return nil, fmt.Errorf("node C1 expected 1 copy remaining, got %+v", mC1)
	}
	if mC2 == nil || mC2.CopiesRemaining != 1 {
		return nil, fmt.Errorf("node C2 expected 1 copy remaining, got %+v", mC2)
	}
	if mC3 != nil {
		return nil, fmt.Errorf("node C3 should NOT have received a copy (S had only 1 copy left)")
	}
	if mC4 == nil || mC4.CopiesRemaining != 1 {
		return nil, fmt.Errorf("node C4 expected 1 copy remaining, got %+v", mC4)
	}

	// Total copies in circulation: S(1) + C1(1) + C2(1) + C4(1) = 4 = SprayCopies
	totalCopies := mS.CopiesRemaining + mC1.CopiesRemaining + mC2.CopiesRemaining + mC4.CopiesRemaining
	if totalCopies != ghostrouter.SprayCopies {
		return nil, fmt.Errorf("total copies in network %d != SprayCopies %d", totalCopies, ghostrouter.SprayCopies)
	}

	res := engine.Results("04_spray")
	if !res.InvariantsPassed {
		return res, fmt.Errorf("invariants failed: %v", res.InvariantErrors)
	}
	return res, nil
}

// Scenario 05 — Duplicate Flood: Verify deduplication across redundant paths
func RunScenario05_DuplicateFlood(seed int64) (*SimResults, error) {
	engine, err := NewSimEngine(seed, false)
	if err != nil {
		return nil, err
	}
	defer engine.Close()

	if _, err := engine.AddNode("S"); err != nil {
		return nil, err
	}
	if _, err := engine.AddNode("R1"); err != nil {
		return nil, err
	}
	if _, err := engine.AddNode("R2"); err != nil {
		return nil, err
	}
	nodeD, err := engine.AddNode("D")
	if err != nil {
		return nil, err
	}

	payload := []byte("Duplicate flood mitigation payload")
	if _, err := engine.SendMessage("S", "D", payload); err != nil {
		return nil, err
	}

	// S sprays copies to R1 and R2
	engine.Connect("S", "R1", -60)
	engine.Exchange("S", "R1")
	engine.Disconnect("S", "R1")

	engine.Connect("S", "R2", -60)
	engine.Exchange("S", "R2")
	engine.Disconnect("S", "R2")

	// R1 delivers to D
	engine.Connect("R1", "D", -60)
	engine.Exchange("R1", "D")

	// R2 also delivers to D
	engine.Connect("R2", "D", -60)
	engine.Exchange("R2", "D")

	// S directly meets D as well
	engine.Connect("S", "D", -60)
	engine.Exchange("S", "D")

	// Application must receive exactly 1 delivery!
	if nodeD.DeliveredCount() != 1 {
		return nil, fmt.Errorf("node D delivered %d times, expected exactly 1", nodeD.DeliveredCount())
	}

	res := engine.Results("05_duplicate")
	if res.DuplicatesDropped < 1 {
		return nil, fmt.Errorf("expected at least 1 duplicate drop, got %d", res.DuplicatesDropped)
	}
	if !res.InvariantsPassed {
		return res, fmt.Errorf("invariants failed: %v", res.InvariantErrors)
	}
	return res, nil
}

// Scenario 06 — TTL Expiration: 24h message expiration
func RunScenario06_TTLExpiration(seed int64) (*SimResults, error) {
	engine, err := NewSimEngine(seed, false)
	if err != nil {
		return nil, err
	}
	defer engine.Close()

	nodeS, err := engine.AddNode("S")
	if err != nil {
		return nil, err
	}
	nodeD, err := engine.AddNode("D")
	if err != nil {
		return nil, err
	}

	payload := []byte("Time-to-live expiration payload")
	msgID, err := engine.SendMessage("S", "D", payload)
	if err != nil {
		return nil, err
	}

	// Advance 23 hours: message still valid
	engine.Advance(23 * time.Hour)
	m, _ := nodeS.Router.GetStore().GetMessage(msgID)
	if m == nil {
		return nil, fmt.Errorf("message should still be valid at 23 hours")
	}

	// Advance 2 more hours (total 25h > 24h TTL)
	engine.Advance(2 * time.Hour)

	// Janitor has run during Advance; message must be purged
	mExpired, _ := nodeS.Router.GetStore().GetMessage(msgID)
	if mExpired != nil {
		return nil, fmt.Errorf("expired message was not purged from store by janitor")
	}

	// Attempt contact: should not deliver
	engine.Connect("S", "D", -60)
	engine.Exchange("S", "D")

	if nodeD.DeliveredCount() > 0 {
		return nil, fmt.Errorf("expired message was delivered to D")
	}

	res := engine.Results("06_ttl")
	if !res.InvariantsPassed {
		return res, fmt.Errorf("invariants failed: %v", res.InvariantErrors)
	}
	return res, nil
}

// Scenario 07 — Hop Limit: MaxHops = 10 rejection
func RunScenario07_HopLimit(seed int64) (*SimResults, error) {
	engine, err := NewSimEngine(seed, false)
	if err != nil {
		return nil, err
	}
	defer engine.Close()

	// Chain of 12 nodes: N0 -> N1 -> ... -> N11
	const chainLen = 12
	nodes := make([]*SimNode, chainLen)
	for i := 0; i < chainLen; i++ {
		name := fmt.Sprintf("N%02d", i)
		n, err := engine.AddNode(name)
		if err != nil {
			return nil, err
		}
		nodes[i] = n
	}

	payload := []byte("Hop limit test message")
	// Send from N0 to N11
	if _, err := engine.SendMessage("N00", "N11", payload); err != nil {
		return nil, err
	}

	// Step-by-step forwarding along the chain
	for i := 0; i < chainLen-1; i++ {
		curr := fmt.Sprintf("N%02d", i)
		next := fmt.Sprintf("N%02d", i+1)
		engine.Connect(curr, next, -60)
		engine.Exchange(curr, next)
		engine.Disconnect(curr, next)
	}

	// Destination N11 should NOT have received it because hop count exceeded MaxHops=10
	if nodes[chainLen-1].DeliveredCount() > 0 {
		return nil, fmt.Errorf("message exceeded MaxHops=10 but was delivered to destination")
	}

	res := engine.Results("07_hop_limit")
	if !res.InvariantsPassed {
		return res, fmt.Errorf("invariants failed: %v", res.InvariantErrors)
	}
	return res, nil
}

// Scenario 08 — Low Battery: Relay refusal when battery < 20%
func RunScenario08_LowBattery(seed int64) (*SimResults, error) {
	engine, err := NewSimEngine(seed, false)
	if err != nil {
		return nil, err
	}
	defer engine.Close()

	if _, err := engine.AddNode("S"); err != nil {
		return nil, err
	}
	nodeR, err := engine.AddNode("R")
	if err != nil {
		return nil, err
	}
	nodeD, err := engine.AddNode("D")
	if err != nil {
		return nil, err
	}

	// Set R battery to 15% (CRITICAL: willingness = 0.0)
	nodeR.SetBattery(15)

	payload := []byte("Low battery refusal payload")
	if _, err := engine.SendMessage("S", "D", payload); err != nil {
		return nil, err
	}

	engine.Connect("S", "R", -60)
	engine.Exchange("S", "R")

	// R must refuse to store relay copy
	if nodeR.Router.MessageCount() > 0 {
		return nil, fmt.Errorf("low battery node stored relay message (count=%d)", nodeR.Router.MessageCount())
	}

	// Charge R to 80% (willingness = 1.0)
	nodeR.SetBattery(80)

	// Exchange again: R should now accept
	engine.Exchange("S", "R")
	if nodeR.Router.MessageCount() == 0 {
		return nil, fmt.Errorf("charged relay node did not accept relay message")
	}

	// R meets D: delivers
	engine.Disconnect("S", "R")
	engine.Connect("R", "D", -60)
	engine.Exchange("R", "D")

	if nodeD.DeliveredCount() != 1 {
		return nil, fmt.Errorf("node D expected 1 delivery, got %d", nodeD.DeliveredCount())
	}

	res := engine.Results("08_low_battery")
	if !res.InvariantsPassed {
		return res, fmt.Errorf("invariants failed: %v", res.InvariantErrors)
	}
	return res, nil
}

// Scenario 09 — Crash During Transit: Relay crashes and restarts with BoltDB state recovery
func RunScenario09_CrashDuringTransit(seed int64) (*SimResults, error) {
	engine, err := NewSimEngine(seed, false)
	if err != nil {
		return nil, err
	}
	defer engine.Close()

	if _, err := engine.AddNode("S"); err != nil {
		return nil, err
	}
	nodeR, err := engine.AddNode("R")
	if err != nil {
		return nil, err
	}
	nodeD, err := engine.AddNode("D")
	if err != nil {
		return nil, err
	}

	payload := []byte("Crash resilience and state persistence payload")
	if _, err := engine.SendMessage("S", "D", payload); err != nil {
		return nil, err
	}

	// S gives relay copy to R
	engine.Connect("S", "R", -60)
	engine.Exchange("S", "R")
	engine.Disconnect("S", "R")

	if nodeR.Router.MessageCount() == 0 {
		return nil, fmt.Errorf("relay R failed to receive message before crash")
	}

	// CRASH R!
	nodeR.Crash()
	if nodeR.IsAlive {
		return nil, fmt.Errorf("crashed node marked alive")
	}

	// While crashed, R cannot communicate
	engine.Connect("R", "D", -60)
	d, f, _, _ := engine.Exchange("R", "D")
	if d > 0 || f > 0 {
		return nil, fmt.Errorf("crashed node participated in exchange")
	}

	// RESTART R!
	if err := nodeR.Restart(); err != nil {
		return nil, fmt.Errorf("failed to restart node R: %w", err)
	}

	// Verify recovered stored message count from BoltDB
	if nodeR.Router.MessageCount() == 0 {
		return nil, fmt.Errorf("restarted node lost stored transit messages from BoltDB")
	}

	// Now R meets D and delivers
	engine.Exchange("R", "D")

	if nodeD.DeliveredCount() != 1 {
		return nil, fmt.Errorf("destination D expected 1 delivery after restart, got %d", nodeD.DeliveredCount())
	}

	res := engine.Results("09_crash_restart")
	if !res.InvariantsPassed {
		return res, fmt.Errorf("invariants failed: %v", res.InvariantErrors)
	}
	return res, nil
}

// Scenario 10 — Repeated Encounters: A <-> B flapping
func RunScenario10_RepeatedEncounters(seed int64) (*SimResults, error) {
	engine, err := NewSimEngine(seed, false)
	if err != nil {
		return nil, err
	}
	defer engine.Close()

	if _, err := engine.AddNode("A"); err != nil {
		return nil, err
	}
	nodeB, err := engine.AddNode("B")
	if err != nil {
		return nil, err
	}

	payload := []byte("Repeated encounter flapping payload")
	if _, err := engine.SendMessage("A", "B", payload); err != nil {
		return nil, err
	}

	// Repeatedly flap link 10 times
	for i := 0; i < 10; i++ {
		engine.Connect("A", "B", -60)
		engine.Exchange("A", "B")
		engine.Disconnect("A", "B")
		engine.Advance(1 * time.Second)
	}

	if nodeB.DeliveredCount() != 1 {
		return nil, fmt.Errorf("node B expected 1 delivery, got %d", nodeB.DeliveredCount())
	}

	res := engine.Results("10_repeated")
	if !res.InvariantsPassed {
		return res, fmt.Errorf("invariants failed: %v", res.InvariantErrors)
	}
	return res, nil
}

// Scenario 11 — High Packet Loss: 20%, 50%, 80%
func RunScenario11_HighPacketLoss(seed int64) (*SimResults, error) {
	lossRates := []float64{0.20, 0.50, 0.80}
	var lastRes *SimResults

	for _, loss := range lossRates {
		engine, err := NewSimEngine(seed, false)
		if err != nil {
			return nil, err
		}

		if _, err := engine.AddNode("A"); err != nil {
			engine.Close()
			return nil, err
		}
		nodeB, err := engine.AddNode("B")
		if err != nil {
			engine.Close()
			return nil, err
		}

		engine.Radio.SetGlobalLoss(loss)
		engine.Connect("A", "B", -60)

		payload := []byte(fmt.Sprintf("Loss rate %.0f%% payload", loss*100))
		if _, err := engine.SendMessage("A", "B", payload); err != nil {
			engine.Close()
			return nil, err
		}

		// Perform multiple exchange rounds to allow eventual delivery through loss
		for round := 0; round < 30; round++ {
			engine.Exchange("A", "B")
			engine.Advance(500 * time.Millisecond)
			if nodeB.DeliveredCount() == 1 {
				break
			}
		}

		res := engine.Results(fmt.Sprintf("11_packet_loss_%.0f", loss*100))
		if !res.InvariantsPassed {
			engine.Close()
			return res, fmt.Errorf("invariants failed under %.0f%% loss: %v", loss*100, res.InvariantErrors)
		}
		if loss == 0.80 {
			res.Scenario = "11_packet_loss"
			lastRes = res
		}
		engine.Close()
	}

	if lastRes != nil {
		return lastRes, nil
	}
	return &SimResults{Scenario: "11_packet_loss", Seed: seed}, nil
}

// Scenario 12 — 100-Node Mesh
func RunScenario12_100NodeMesh(seed int64) (*SimResults, error) {
	engine, err := NewSimEngine(seed, false)
	if err != nil {
		return nil, err
	}
	defer engine.Close()

	const numNodes = 100
	rng := rand.New(rand.NewSource(seed))

	// Create 100 nodes
	for i := 0; i < numNodes; i++ {
		name := fmt.Sprintf("N%03d", i)
		if _, err := engine.AddNode(name); err != nil {
			return nil, err
		}
	}

	// Build deterministic topology: 10x10 grid with diagonal cross-links
	for i := 0; i < numNodes; i++ {
		row := i / 10
		col := i % 10
		curr := fmt.Sprintf("N%03d", i)

		// Right neighbor
		if col < 9 {
			engine.Connect(curr, fmt.Sprintf("N%03d", i+1), -65)
		}
		// Bottom neighbor
		if row < 9 {
			engine.Connect(curr, fmt.Sprintf("N%03d", i+10), -65)
		}
		// Diagonal
		if col < 9 && row < 9 {
			engine.Connect(curr, fmt.Sprintf("N%03d", i+11), -75)
		}
	}

	// Inject 50 messages across random pairs
	const numMessages = 50
	for m := 0; m < numMessages; m++ {
		srcIdx := rng.Intn(numNodes)
		dstIdx := rng.Intn(numNodes)
		for dstIdx == srcIdx {
			dstIdx = rng.Intn(numNodes)
		}

		src := fmt.Sprintf("N%03d", srcIdx)
		dst := fmt.Sprintf("N%03d", dstIdx)
		payload := []byte(fmt.Sprintf("Mesh msg %d from %s to %s", m, src, dst))
		if _, err := engine.SendMessage(src, dst, payload); err != nil {
			return nil, err
		}
	}

	// Run 25 discrete contact diffusion steps
	for step := 0; step < 25; step++ {
		engine.ExchangeAllActive()
		engine.Advance(2 * time.Minute)
	}

	res := engine.Results("12_100_node")
	if !res.InvariantsPassed {
		return res, fmt.Errorf("invariants failed on 100-node mesh: %v", res.InvariantErrors)
	}
	return res, nil
}
