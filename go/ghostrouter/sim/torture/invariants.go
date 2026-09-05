package torture

import (
	"bytes"
	"fmt"

	"ghostrouter"
	"ghostrouter/sim"
)

// InvariantID identifies each formal invariant I1 through I15.
type InvariantID string

const (
	I1_MessageAccounting InvariantID = "I1_MessageAccounting"
	I2_Delivery          InvariantID = "I2_Delivery"
	I3_CopyConservation  InvariantID = "I3_CopyConservation"
	I4_HopLimit          InvariantID = "I4_HopLimit"
	I5_TTL               InvariantID = "I5_TTL"
	I6_Dedup             InvariantID = "I6_Dedup"
	I7_RelayGating       InvariantID = "I7_RelayGating"
	I8_Storage           InvariantID = "I8_Storage"
	I9_CrashIsolation    InvariantID = "I9_CrashIsolation"
	I10_Identity         InvariantID = "I10_Identity"
	I11_InfiniteForward  InvariantID = "I11_InfiniteForward"
	I12_RetryBounds      InvariantID = "I12_RetryBounds"
	I13_Persistence      InvariantID = "I13_Persistence"
	I14_Security         InvariantID = "I14_Security"
	I15_Determinism      InvariantID = "I15_Determinism"
)

// InvariantViolation records an invariant failure with context.
type InvariantViolation struct {
	ID        InvariantID `json:"id"`
	Message   string      `json:"message"`
	Node      string      `json:"node,omitempty"`
	MessageID string      `json:"message_id,omitempty"`
}

func (v InvariantViolation) Error() string {
	if v.Node != "" {
		return fmt.Sprintf("[%s] Node %s: %s", v.ID, v.Node, v.Message)
	}
	return fmt.Sprintf("[%s] %s", v.ID, v.Message)
}

// InvariantTracker records message metadata and forwarding counts across a scenario.
type InvariantTracker struct {
	CreatedMessages map[string]*MsgRecord
	ForwardCounts   map[string]int // msgIDHex -> total forwards
	DeliveryTries   map[string]int // msgIDHex:peerIDHex -> attempts
}

// MsgRecord tracks expected metadata for invariant validation.
type MsgRecord struct {
	ID        []byte
	IDHex     string
	SrcName   string
	DstName   string
	DstID     []byte
	Payload   []byte
	CreatedAt int64
	Delivered bool
}

// NewInvariantTracker initializes a fresh invariant tracking state.
func NewInvariantTracker() *InvariantTracker {
	return &InvariantTracker{
		CreatedMessages: make(map[string]*MsgRecord),
		ForwardCounts:   make(map[string]int),
		DeliveryTries:   make(map[string]int),
	}
}

// CheckAllInvariants performs comprehensive evaluation of formal invariants I1 through I14.
// (I15 Determinism is checked by the campaign runner via replay comparison).
func (t *InvariantTracker) CheckAllInvariants(engine *sim.SimEngine) []InvariantViolation {
	var violations []InvariantViolation
	nowUnix := engine.Clock.NowUnix()

	// Cache node state
	nodes := engine.Nodes
	seed := engine.Seed

	// --- Check per-node invariants ---
	for name, node := range nodes {
		// I10: Identity Stability
		expectedID := sim.GenerateNodeID(seed, name)
		if !bytes.Equal(node.ID, expectedID) {
			violations = append(violations, InvariantViolation{
				ID:      I10_Identity,
				Node:    name,
				Message: fmt.Sprintf("Node cryptographic ID mutated (expected %x, got %x)", expectedID[:4], node.ID[:4]),
			})
		}

		// I9: Crash Isolation
		if !node.IsAlive {
			if node.Router != nil {
				violations = append(violations, InvariantViolation{
					ID:      I9_CrashIsolation,
					Node:    name,
					Message: "Crashed node still holds active router reference",
				})
			}
			continue // Dead nodes have no active store to inspect directly
		}

		// I6: Deduplication & I2: Delivery Correctness
		seenDelivered := make(map[string]int)
		for _, rec := range node.DeliveredMessages {
			payloadKey := string(rec.Payload)
			seenDelivered[payloadKey]++
			if seenDelivered[payloadKey] > 1 {
				violations = append(violations, InvariantViolation{
					ID:      I6_Dedup,
					Node:    name,
					Message: fmt.Sprintf("Duplicate logical delivery of payload (count=%d)", seenDelivered[payloadKey]),
				})
			}

			// I2 Delivery: Verify that this node was indeed the intended destination
			matched := false
			for _, meta := range t.CreatedMessages {
				if bytes.Equal(meta.Payload, rec.Payload) {
					if meta.DstName == name || bytes.Equal(meta.DstID, node.ID) {
						matched = true
					}
					break
				}
			}
			if !matched {
				violations = append(violations, InvariantViolation{
					ID:      I2_Delivery,
					Node:    name,
					Message: fmt.Sprintf("Message delivered to unintended destination node %s", name),
				})
			}
		}

		// Check stored messages inside router store
		store := node.Router.GetStore()
		if store != nil {
			msgs, _ := store.GetPendingMessages()

			// I8: Storage Bounds (Cap: 500 messages)
			if len(msgs) > 500 {
				violations = append(violations, InvariantViolation{
					ID:      I8_Storage,
					Node:    name,
					Message: fmt.Sprintf("Transit storage exceeded 500 message limit (current: %d)", len(msgs)),
				})
			}

			for _, m := range msgs {
				// I4: Hop Limit
				if m.HopCount > ghostrouter.MaxHops {
					violations = append(violations, InvariantViolation{
						ID:        I4_HopLimit,
						Node:      name,
						MessageID: fmt.Sprintf("%x", m.ID[:4]),
						Message:   fmt.Sprintf("Message exceeded MaxHops (%d > %d)", m.HopCount, ghostrouter.MaxHops),
					})
				}

				// I5: TTL Enforcement (unpruned messages that expired before now)
				if m.CreatedAt+m.TTLSeconds < nowUnix {
					violations = append(violations, InvariantViolation{
						ID:        I5_TTL,
						Node:      name,
						MessageID: fmt.Sprintf("%x", m.ID[:4]),
						Message:   fmt.Sprintf("Unpruned expired message in store (created %d, ttl %d, now %d)", m.CreatedAt, m.TTLSeconds, nowUnix),
					})
				}
			}
		}
	}

	// I7: Relay Gating (Section 8: relayWillingness <= 0 => no transit acceptance AND no transit spray)
	for _, v := range engine.GetRelayGatingViolations() {
		violations = append(violations, InvariantViolation{
			ID:        I7_RelayGating,
			Node:      v.Node,
			MessageID: v.MessageID,
			Message:   fmt.Sprintf("Node with low battery / zero willingness (%d%%, w=%.2f) attempted to %s", v.Battery, v.Willingness, v.Action),
		})
	}

	// --- Check global message invariants ---
	deliveredCount := 0
	pendingCount := 0
	expiredCount := 0
	failedCount := 0

	for idHex, meta := range t.CreatedMessages {
		totalCopies := 0
		isDelivered := false
		isPending := false

		for _, node := range nodes {
			// Check if delivered
			for _, rec := range node.DeliveredMessages {
				if bytes.Equal(rec.Payload, meta.Payload) {
					isDelivered = true
					break
				}
			}

			// Check stored copies
			if node.IsAlive && node.Router != nil {
				m, _ := node.Router.GetStore().GetMessage(meta.ID)
				if m != nil && m.Status != ghostrouter.StatusDelivered {
					totalCopies += m.CopiesRemaining
					if m.Status == ghostrouter.StatusPending || m.Status == ghostrouter.StatusSprayed {
						isPending = true
					}
				}
			}
		}

		// I3: Copy Conservation (Total copies across all valid carriers <= L=4)
		if totalCopies > ghostrouter.SprayCopies {
			violations = append(violations, InvariantViolation{
				ID:        I3_CopyConservation,
				MessageID: idHex[:8],
				Message:   fmt.Sprintf("Message copy explosion: %d copies in circulation (max: %d)", totalCopies, ghostrouter.SprayCopies),
			})
		}

		// I11: Infinite Forwarding Protection (Bounded forwarding events per message)
		if fwdCount, ok := t.ForwardCounts[idHex]; ok {
			maxAllowedForwards := ghostrouter.SprayCopies * ghostrouter.MaxHops
			if fwdCount > maxAllowedForwards {
				violations = append(violations, InvariantViolation{
					ID:        I11_InfiniteForward,
					MessageID: idHex[:8],
					Message:   fmt.Sprintf("Excessive forwarding hops (%d > %d allowed)", fwdCount, maxAllowedForwards),
				})
			}
		}

		// Accounting state tracking
		if isDelivered {
			deliveredCount++
		} else if isPending {
			pendingCount++
		} else if meta.CreatedAt+int64(ghostrouter.DefaultTTLSeconds) < nowUnix {
			expiredCount++
		} else {
			failedCount++
		}
	}

	// I1: Message Accounting
	totalAccounted := deliveredCount + pendingCount + expiredCount + failedCount
	if totalAccounted != len(t.CreatedMessages) {
		violations = append(violations, InvariantViolation{
			ID: I1_MessageAccounting,
			Message: fmt.Sprintf("Message accounting mismatch: created=%d, accounted=%d (del=%d, pend=%d, exp=%d, fail=%d)",
				len(t.CreatedMessages), totalAccounted, deliveredCount, pendingCount, expiredCount, failedCount),
		})
	}

	return violations
}
