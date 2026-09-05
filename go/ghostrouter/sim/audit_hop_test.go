package sim

import (
	"fmt"
	"testing"

	"ghostrouter"
)

// TestAuditHopLimitChain verifies Section 8:
// Exactly where forwarding stops in A -> B -> ... -> L.
// Last accepted hop vs first rejected hop.
func TestAuditHopLimitChain(t *testing.T) {
	engine, err := NewSimEngine(999, false)
	if err != nil {
		t.Fatalf("failed to init engine: %v", err)
	}
	defer engine.Close()

	const chainLen = 12
	nodes := make([]*SimNode, chainLen)
	for i := 0; i < chainLen; i++ {
		name := fmt.Sprintf("N%02d", i)
		n, err := engine.AddNode(name)
		if err != nil {
			t.Fatalf("failed to add node %s: %v", name, err)
		}
		nodes[i] = n
	}

	payload := []byte("Hop audit message")
	msgID, err := engine.SendMessage("N00", "N11", payload)
	if err != nil {
		t.Fatalf("failed to send: %v", err)
	}

	for i := 0; i < chainLen-1; i++ {
		curr := fmt.Sprintf("N%02d", i)
		next := fmt.Sprintf("N%02d", i+1)
		engine.Connect(curr, next, -60)
		engine.Exchange(curr, next)
		engine.Disconnect(curr, next)

		// Check if next has the message and its hop count
		m, _ := nodes[i+1].Router.GetStore().GetMessage(msgID)
		if m != nil {
			t.Logf("Hop %d -> %s: accepted, HopCount=%d, Copies=%d", i+1, next, m.HopCount, m.CopiesRemaining)
		} else {
			t.Logf("Hop %d -> %s: REJECTED / NOT STORED", i+1, next)
		}
	}

	t.Logf("Destination N11 deliveries: %d", nodes[chainLen-1].DeliveredCount())
	if nodes[chainLen-1].DeliveredCount() > 0 {
		t.Errorf("destination delivered packet beyond MaxHops=10")
	}

	// Verify MaxHops is 10
	if ghostrouter.MaxHops != 10 {
		t.Errorf("MaxHops expected 10, got %d", ghostrouter.MaxHops)
	}
}

// TestAuditHopLimitBoundary directly tests the exact boundary at MaxHops=10:
// Hop 9 is accepted.
// Hop 10 is rejected ("dropped: hop limit").
// Hop 11 is rejected ("dropped: hop limit").
func TestAuditHopLimitBoundary(t *testing.T) {
	engine, err := NewSimEngine(777, false)
	if err != nil {
		t.Fatalf("failed to init engine: %v", err)
	}
	defer engine.Close()

	relayNode, err := engine.AddNode("Relay")
	if err != nil {
		t.Fatalf("failed to add node: %v", err)
	}
	destNode, err := engine.AddNode("Dest")
	if err != nil {
		t.Fatalf("failed to add node: %v", err)
	}

	for _, hop := range []int{8, 9, 10, 11} {
		msg := &ghostrouter.Message{
			ID:              []byte(fmt.Sprintf("test-msg-hop-%02d-00000000000000", hop)),
			Src:             []byte("source-node-id-00000000000000000"),
			Dst:             destNode.ID,
			Payload:         []byte(fmt.Sprintf("Payload at hop %d", hop)),
			CopiesRemaining: 2,
			TTLSeconds:      86400,
			HopCount:        hop,
			CreatedAt:       engine.Clock.NowUnix(),
			Status:          ghostrouter.StatusPending,
		}
		raw := ghostrouter.EncodeMessage(msg)

		status := relayNode.Router.OnMessageReceived(raw)
		t.Logf("HopCount=%d -> OnMessageReceived status: '%s'", hop, status)

		if hop < ghostrouter.MaxHops {
			if status != "forwarded" {
				t.Errorf("Hop %d should be accepted as 'forwarded', got '%s'", hop, status)
			}
		} else {
			if status != "dropped: hop limit" {
				t.Errorf("Hop %d should be rejected as 'dropped: hop limit', got '%s'", hop, status)
			}
		}
	}
}
