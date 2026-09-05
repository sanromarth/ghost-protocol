package ghostrouter

import (
	"bytes"
	"math/rand"
	"testing"
)

// SimNode represents a simulated mesh participant.
type SimNode struct {
	Name    string
	ID      []byte
	Router  *Router
	Handler *testDeliverHandler
	DBPath  string
}

func newSimNode(t *testing.T, name string, idByte byte) *SimNode {
	t.Helper()
	id := bytes.Repeat([]byte{idByte}, 32)
	dbPath := tempDBPath(t)
	r, err := NewRouter(id, dbPath)
	if err != nil {
		t.Fatalf("[%s] NewRouter failed: %v", name, err)
	}

	handler := &testDeliverHandler{}
	r.SetHandler(handler)
	r.Start()

	return &SimNode{
		Name:    name,
		ID:      id,
		Router:  r,
		Handler: handler,
		DBPath:  dbPath,
	}
}

func (n *SimNode) Stop() {
	if n.Router != nil {
		n.Router.Stop()
	}
}

// simExchange performs a bidirectional radio encounter between two nodes.
// If batch encoding is returned, it unpacks the batch and delivers each message.
func simExchange(srcNode, dstNode *SimNode, rssi int) {
	// srcNode discovers dstNode
	blobs := srcNode.Router.OnPeerDiscovered(dstNode.ID, rssi)
	if blobs == nil || blobs.Size() == 0 {
		return
	}

	for i := 0; i < blobs.Size(); i++ {
		blob := blobs.Get(i)
		if len(blob) == 0 {
			continue
		}

		// Check if blob is a batch
		batchMsgs, err := DecodeBatch(blob)
		if err == nil && len(batchMsgs) > 0 {
			for _, msg := range batchMsgs {
				dstNode.Router.OnMessageReceived(msg)
			}
		} else {
			// Single message
			dstNode.Router.OnMessageReceived(blob)
		}
	}
}

// simExchangeBiDir runs symmetric encounter in both directions.
func simExchangeBiDir(nodeA, nodeB *SimNode, rssi int) {
	simExchange(nodeA, nodeB, rssi)
	simExchange(nodeB, nodeA, rssi)
}

// simExchangeWithLoss runs encounter with simulated packet drop probability.
func simExchangeWithLoss(srcNode, dstNode *SimNode, rssi int, lossRate float64, rng *rand.Rand) {
	blobs := srcNode.Router.OnPeerDiscovered(dstNode.ID, rssi)
	if blobs == nil || blobs.Size() == 0 {
		return
	}

	for i := 0; i < blobs.Size(); i++ {
		blob := blobs.Get(i)
		if len(blob) == 0 {
			continue
		}

		batchMsgs, err := DecodeBatch(blob)
		if err == nil && len(batchMsgs) > 0 {
			for _, msg := range batchMsgs {
				if rng.Float64() >= lossRate {
					dstNode.Router.OnMessageReceived(msg)
				}
			}
		} else {
			if rng.Float64() >= lossRate {
				dstNode.Router.OnMessageReceived(blob)
			}
		}
	}
}

// Scenario A — Direct: A -> B
func TestSimulatorScenarioA_Direct(t *testing.T) {
	nodeA := newSimNode(t, "NodeA", 0x0A)
	defer nodeA.Stop()
	nodeB := newSimNode(t, "NodeB", 0x0B)
	defer nodeB.Stop()

	payload := []byte("Direct encrypted payload between A and B")
	res := nodeA.Router.SendMessage(nodeB.ID, payload)
	if res.Status != "queued" && res.Status != "direct" {
		t.Fatalf("unexpected SendMessage status: %s", res.Status)
	}

	// Encounter between A and B
	simExchangeBiDir(nodeA, nodeB, -60)

	if !nodeB.Handler.delivered {
		t.Fatal("Scenario A: Node B did not receive direct message from A")
	}
	if !bytes.Equal(nodeB.Handler.payload, payload) {
		t.Fatalf("Scenario A: Payload corrupted: got %s, want %s", nodeB.Handler.payload, payload)
	}
}

// Scenario B — Multi-hop: A -> B -> C
func TestSimulatorScenarioB_MultiHop(t *testing.T) {
	nodeA := newSimNode(t, "NodeA", 0x0A)
	defer nodeA.Stop()
	nodeB := newSimNode(t, "NodeB", 0x0B)
	defer nodeB.Stop()
	nodeC := newSimNode(t, "NodeC", 0x0C)
	defer nodeC.Stop()

	payload := []byte("Multi-hop relay message from A to C via B")
	nodeA.Router.SendMessage(nodeC.ID, payload)

	// Step 1: A encounters B (C is out of range)
	simExchangeBiDir(nodeA, nodeB, -70)

	// Verify B received the relay message (not delivered to B's application)
	if nodeB.Handler.delivered {
		t.Fatal("Scenario B: Relay carrier B should NOT deliver message to its own app")
	}
	if nodeB.Router.store.MessageCount() == 0 {
		t.Fatal("Scenario B: Relay carrier B store should contain forwarded message")
	}

	// Step 2: B moves and encounters C (A is out of range)
	simExchangeBiDir(nodeB, nodeC, -65)

	// Step 3: Verify C received and delivered the message
	if !nodeC.Handler.delivered {
		t.Fatal("Scenario B: Final destination C did not receive message via relay carrier B")
	}
	if !bytes.Equal(nodeC.Handler.payload, payload) {
		t.Fatalf("Scenario B: Payload corrupted: got %s, want %s", nodeC.Handler.payload, payload)
	}
}

// Scenario C — Partition: A/B vs C/D then reconverge
func TestSimulatorScenarioC_PartitionReconvergence(t *testing.T) {
	nodeA := newSimNode(t, "NodeA", 0x0A)
	defer nodeA.Stop()
	nodeB := newSimNode(t, "NodeB", 0x0B)
	defer nodeB.Stop()
	nodeC := newSimNode(t, "NodeC", 0x0C)
	defer nodeC.Stop()
	nodeD := newSimNode(t, "NodeD", 0x0D)
	defer nodeD.Stop()

	payload := []byte("Cross-partition tactical communication A -> D")
	nodeA.Router.SendMessage(nodeD.ID, payload)

	// Phase 1: Partition 1 encounter {A, B}
	simExchangeBiDir(nodeA, nodeB, -60)

	// Phase 1: Partition 2 encounter {C, D}
	simExchangeBiDir(nodeC, nodeD, -60)

	// D must NOT have received message yet
	if nodeD.Handler.delivered {
		t.Fatal("Scenario C: D should not receive message during partition")
	}

	// Phase 2: Reconvergence — B bridges the partition and meets C
	simExchangeBiDir(nodeB, nodeC, -75)

	// Phase 3: C meets D
	simExchangeBiDir(nodeC, nodeD, -65)

	// Verify D delivered
	if !nodeD.Handler.delivered {
		t.Fatal("Scenario C: D failed to receive message after partition reconvergence")
	}
	if !bytes.Equal(nodeD.Handler.payload, payload) {
		t.Fatalf("Scenario C: Payload corruption: got %s, want %s", nodeD.Handler.payload, payload)
	}
}

// Scenario D — Churn: Repeated contacts and disappearances
func TestSimulatorScenarioD_Churn(t *testing.T) {
	rng := rand.New(rand.NewSource(424242))

	nodeA := newSimNode(t, "NodeA", 0x0A)
	defer nodeA.Stop()
	nodeB := newSimNode(t, "NodeB", 0x0B)
	defer nodeB.Stop()
	nodeC := newSimNode(t, "NodeC", 0x0C)
	defer nodeC.Stop()

	payload := []byte("Churn resilient message")
	nodeA.Router.SendMessage(nodeC.ID, payload)

	nodes := []*SimNode{nodeA, nodeB, nodeC}

	// Simulate 30 rounds of random intermittent encounters
	for round := 0; round < 30; round++ {
		i := rng.Intn(len(nodes))
		j := rng.Intn(len(nodes))
		if i != j {
			simExchangeBiDir(nodes[i], nodes[j], -70-rng.Intn(20))
		}
	}

	if !nodeC.Handler.delivered {
		t.Fatal("Scenario D: Destination C did not receive message under high churn")
	}
	if !bytes.Equal(nodeC.Handler.payload, payload) {
		t.Fatal("Scenario D: Payload corrupted under churn")
	}
}

// Scenario E — Packet Loss: 25% loss rate over multiple encounters
func TestSimulatorScenarioE_Loss(t *testing.T) {
	rng := rand.New(rand.NewSource(99999))

	nodeA := newSimNode(t, "NodeA", 0x0A)
	defer nodeA.Stop()
	nodeB := newSimNode(t, "NodeB", 0x0B)
	defer nodeB.Stop()
	nodeC := newSimNode(t, "NodeC", 0x0C)
	defer nodeC.Stop()

	payload := []byte("Loss-tolerant DTN message")
	nodeA.Router.SendMessage(nodeC.ID, payload)

	// Multiple encounters with 30% loss rate
	for round := 0; round < 10; round++ {
		simExchangeWithLoss(nodeA, nodeB, -70, 0.30, rng)
		simExchangeWithLoss(nodeB, nodeC, -70, 0.30, rng)
	}

	if !nodeC.Handler.delivered {
		t.Fatal("Scenario E: Destination C failed to receive message with 30% loss rate")
	}
	if !bytes.Equal(nodeC.Handler.payload, payload) {
		t.Fatal("Scenario E: Payload corrupted")
	}
}

// Scenario F — Duplicate encounters: Repeated contact between same peers
func TestSimulatorScenarioF_DuplicateEncounters(t *testing.T) {
	nodeA := newSimNode(t, "NodeA", 0x0A)
	defer nodeA.Stop()
	nodeB := newSimNode(t, "NodeB", 0x0B)
	defer nodeB.Stop()

	deliverCount := 0
	nodeB.Handler = &testDeliverHandler{
		failTest: nil,
	}
	// Custom counting handler
	nodeB.Router.SetHandler(&countingDeliverHandler{
		onDeliver: func() { deliverCount++ },
	})

	payload := []byte("Single delivery assertion payload")
	nodeA.Router.SendMessage(nodeB.ID, payload)

	// 10 consecutive encounters between A and B
	for i := 0; i < 10; i++ {
		simExchangeBiDir(nodeA, nodeB, -60)
	}

	if deliverCount != 1 {
		t.Fatalf("Scenario F: Duplicate suppression failed: delivered %d times, expected exactly 1", deliverCount)
	}
}

type countingDeliverHandler struct {
	onDeliver func()
}

func (h *countingDeliverHandler) OnDeliver(dst []byte, payload []byte) {
	if h.onDeliver != nil {
		h.onDeliver()
	}
}

// Scenario G — Relay refusal: Low battery node (Willingness = 0)
func TestSimulatorScenarioG_RelayRefusal(t *testing.T) {
	nodeA := newSimNode(t, "NodeA", 0x0A)
	defer nodeA.Stop()
	nodeB := newSimNode(t, "NodeB", 0x0B)
	defer nodeB.Stop()
	nodeC := newSimNode(t, "NodeC", 0x0C)
	defer nodeC.Stop()

	// Carrier B refuses relay (battery < 20%)
	nodeB.Router.SetRelayWillingness(0.0)

	payload := []byte("Message that cannot traverse low-battery carrier B")
	nodeA.Router.SendMessage(nodeC.ID, payload)

	// A meets B
	simExchangeBiDir(nodeA, nodeB, -65)

	// Carrier B store should be EMPTY because it rejected the relay
	if count := nodeB.Router.store.MessageCount(); count != 0 {
		t.Fatalf("Scenario G: Node B accepted %d relay messages while willingness=0.0", count)
	}

	// B meets C
	simExchangeBiDir(nodeB, nodeC, -65)

	// C should not have received message
	if nodeC.Handler.delivered {
		t.Fatal("Scenario G: Destination C should NOT receive message through refusing relay")
	}

	// Now B is recharged -> Willingness = 1.0
	nodeB.Router.SetRelayWillingness(1.0)

	// A meets B again
	simExchangeBiDir(nodeA, nodeB, -65)

	// B should now accept
	if count := nodeB.Router.store.MessageCount(); count == 0 {
		t.Fatal("Scenario G: Node B failed to accept relay message after recharge")
	}

	// B meets C
	simExchangeBiDir(nodeB, nodeC, -65)

	// C delivers!
	if !nodeC.Handler.delivered {
		t.Fatal("Scenario G: Destination C failed to deliver after carrier recharged")
	}
}
