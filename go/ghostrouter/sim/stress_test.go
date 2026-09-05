package sim

import (
	"fmt"
	"math/rand"
	"testing"
	"time"
)

// TestStress100Nodes1000Messages executes Section 18 benchmark:
// 100 nodes, 1,000 messages routed across virtual mesh with full invariant checks
// and latency quantiles (Avg, P50, P95, P99).
func TestStress100Nodes1000Messages(t *testing.T) {
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
		row := i / 10
		col := i % 10
		curr := fmt.Sprintf("N%03d", i)

		// Grid neighbors
		if col < 9 {
			engine.Connect(curr, fmt.Sprintf("N%03d", i+1), -65)
		}
		if row < 9 {
			engine.Connect(curr, fmt.Sprintf("N%03d", i+10), -65)
		}
		// Diagonal cross links
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

		// Periodic contact diffusion while messages are being created
		if m > 0 && m%100 == 0 {
			engine.ExchangeAllActive()
			engine.Advance(1 * time.Minute)
		}
	}

	// 4. Run diffusion contact rounds to converge
	for round := 0; round < 30; round++ {
		engine.ExchangeAllActive()
		engine.Advance(2 * time.Minute)
	}

	results := engine.Results("stress_100_nodes_1000_msgs")
	t.Logf("\n%s\n", results.String())

	if !results.InvariantsPassed {
		t.Fatalf("Stress test invariant failure: %v", results.InvariantErrors)
	}
	if results.MessagesCreated != numMessages {
		t.Errorf("messages created: got %d, want %d", results.MessagesCreated, numMessages)
	}
	if results.MessagesDelivered == 0 {
		t.Errorf("zero deliveries recorded in 1000 message stress test")
	}
}

// TestStress500Nodes5000Messages executes extended large-scale benchmark.
// Guarded with testing.Short() so it does not slow down normal test cycles.
func TestStress500Nodes5000Messages(t *testing.T) {
	if testing.Short() {
		t.Skip("skipping 500-node / 5000-message stress test in -short mode")
	}

	const (
		seed        = 987654321
		numNodes    = 500
		numMessages = 5000
	)

	engine, err := NewSimEngine(seed, false)
	if err != nil {
		t.Fatalf("failed to init engine: %v", err)
	}
	defer engine.Close()

	rng := rand.New(rand.NewSource(seed))

	for i := 0; i < numNodes; i++ {
		name := fmt.Sprintf("N%04d", i)
		if _, err := engine.AddNode(name); err != nil {
			t.Fatalf("failed to add node %s: %v", name, err)
		}
	}

	// Build 20x25 mesh
	for i := 0; i < numNodes; i++ {
		col := i % 25
		row := i / 25
		curr := fmt.Sprintf("N%04d", i)

		if col < 24 {
			engine.Connect(curr, fmt.Sprintf("N%04d", i+1), -65)
		}
		if row < 19 {
			engine.Connect(curr, fmt.Sprintf("N%04d", i+25), -65)
		}
	}

	for m := 0; m < numMessages; m++ {
		srcIdx := rng.Intn(numNodes)
		dstIdx := rng.Intn(numNodes)
		for dstIdx == srcIdx {
			dstIdx = rng.Intn(numNodes)
		}

		src := fmt.Sprintf("N%04d", srcIdx)
		dst := fmt.Sprintf("N%04d", dstIdx)
		payload := []byte(fmt.Sprintf("Stress 5k msg #%05d from %s to %s", m, src, dst))

		if _, err := engine.SendMessage(src, dst, payload); err != nil {
			t.Fatalf("failed to inject message %d: %v", m, err)
		}

		if m > 0 && m%500 == 0 {
			engine.ExchangeAllActive()
			engine.Advance(1 * time.Minute)
		}
	}

	for round := 0; round < 20; round++ {
		engine.ExchangeAllActive()
		engine.Advance(2 * time.Minute)
	}

	results := engine.Results("stress_500_nodes_5000_msgs")
	t.Logf("\n%s\n", results.String())

	if !results.InvariantsPassed {
		t.Fatalf("Stress 500-node invariant failure: %v", results.InvariantErrors)
	}
}
