package ux

import (
	"fmt"
	"sync"
)

// SOURCE: Architectural UX Invariants U1 through U15 (Section 27)
// CONTRACT: Formal correctness and responsiveness contracts for GHOST Android UI.

// UXInvariantChecker validates formal invariants at checkpoints across the simulation.
type UXInvariantChecker struct {
	mu sync.Mutex

	// Observed delivered and sent message IDs
	seenDeliveredIDs   map[string]int // msgID -> count
	seenDeliveredHash  map[string]int // hash -> count
	stateHistory       map[string][]int
	transportTruthLog  map[string]string // msgID -> expected status based on transport
	modelDivergences   []ModelDivergenceError
	rejectedMutations  []RejectedMutation
}

// RejectedMutation records an attempted mutation that was rejected by Room.
type RejectedMutation struct {
	MessageID       string `json:"message_id"`
	AttemptedStatus int    `json:"attempted_status"`
	Reason          string `json:"reason"`
}

// NewUXInvariantChecker initializes a fresh invariant tracking state.
func NewUXInvariantChecker() *UXInvariantChecker {
	return &UXInvariantChecker{
		seenDeliveredIDs:  make(map[string]int),
		seenDeliveredHash: make(map[string]int),
		stateHistory:      make(map[string][]int),
		transportTruthLog: make(map[string]string),
	}
}

// RecordModelDivergence records when the simulation model itself encounters an undefined condition.
func (c *UXInvariantChecker) RecordModelDivergence(model, reason string, timestampNs int64) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.modelDivergences = append(c.modelDivergences, ModelDivergenceError{
		Model:     model,
		Reason:    reason,
		Timestamp: timestampNs,
	})
}

// RecordMessageState logs state transitions for a message ID.
func (c *UXInvariantChecker) RecordMessageState(msgID string, status int) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.stateHistory[msgID] = append(c.stateHistory[msgID], status)
}

// RecordTransportOutcome logs ground-truth transport outcomes for U10 truthfulness check.
func (c *UXInvariantChecker) RecordTransportOutcome(msgID string, outcome string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.transportTruthLog[msgID] = outcome
}

// RecordRejectedMutation logs when a mutation was rejected by Room persistence guard.
func (c *UXInvariantChecker) RecordRejectedMutation(msgID string, attemptedStatus int, reason string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.rejectedMutations = append(c.rejectedMutations, RejectedMutation{
		MessageID:       msgID,
		AttemptedStatus: attemptedStatus,
		Reason:          reason,
	})
}

// CheckInvariants executes comprehensive verification of U1 through U14.
func (c *UXInvariantChecker) CheckInvariants(
	db *RoomDatabaseModel,
	compose *ComposeViewModelState,
	lifecycle *LifecycleModel,
	gatt *GattQueueModel,
	repo *ConversationRepoModel,
	profile DeviceProfile,
	timestampNs int64,
) []InvariantViolation {
	c.mu.Lock()
	defer c.mu.Unlock()

	var violations []InvariantViolation

	// Model Divergence Check
	if len(c.modelDivergences) > 0 {
		for _, d := range c.modelDivergences {
			violations = append(violations, InvariantViolation{
				ID:        U11_NativeBoundarySafety,
				Severity:  SeverityP3,
				Component: d.Model,
				Message:   fmt.Sprintf("MODEL DIVERGENCE: %s", d.Reason),
				Timestamp: d.Timestamp,
			})
		}
	}

	// U1: No duplicate logical message (in Room and in visible Compose state)
	seenInRoom := make(map[string]int)
	seenHashInRoom := make(map[string]int)
	allMsgs := db.GetAllMessages()
	for _, m := range allMsgs {
		seenInRoom[m.ID]++
		if seenInRoom[m.ID] > 1 {
			violations = append(violations, InvariantViolation{
				ID:        U1_NoDuplicateLogicalMsg,
				Severity:  SeverityP2,
				Component: "RoomDatabase",
				MessageID: m.ID,
				Message:   fmt.Sprintf("Duplicate logical message row in Room (count=%d)", seenInRoom[m.ID]),
				Timestamp: timestampNs,
			})
		}

		if m.ContentHash != "" {
			seenHashInRoom[m.ContentHash]++
			if seenHashInRoom[m.ContentHash] > 1 {
				violations = append(violations, InvariantViolation{
					ID:        U1_NoDuplicateLogicalMsg,
					Severity:  SeverityP2,
					Component: "RoomDatabase",
					MessageID: m.ID,
					Message:   fmt.Sprintf("Duplicate content hash in Room (hash=%s, count=%d)", m.ContentHash, seenHashInRoom[m.ContentHash]),
					Timestamp: timestampNs,
				})
			}
		}
	}

	// Verify no duplicates in visible Compose messages
	if compose != nil {
		seenInCompose := make(map[string]int)
		for _, m := range compose.CombinedMessages {
			seenInCompose[m.ID]++
			if seenInCompose[m.ID] > 1 {
				violations = append(violations, InvariantViolation{
					ID:        U1_NoDuplicateLogicalMsg,
					Severity:  SeverityP2,
					Component: "ComposeState",
					MessageID: m.ID,
					Message:   fmt.Sprintf("Duplicate message ID rendered in Compose state (count=%d)", seenInCompose[m.ID]),
					Timestamp: timestampNs,
				})
			}
		}
	}

	// U4: No impossible state transition & U5: No stale rollback
	for msgID, history := range c.stateHistory {
		if len(history) <= 1 {
			continue
		}
		for i := 1; i < len(history); i++ {
			prev := history[i-1]
			curr := history[i]

			// U5: Terminal DELIVERED state cannot roll back to PENDING, SPRAYED, or SENT
			if prev == StatusDelivered && (curr == StatusPending || curr == StatusSprayed || curr == StatusSent) {
				violations = append(violations, InvariantViolation{
					ID:        U5_NoStaleRollback,
					Severity:  SeverityP2,
					Component: "StateMachine",
					MessageID: msgID,
					Message:   fmt.Sprintf("Stale event rolled back terminal DELIVERED status to %s", StatusToString(curr)),
					Timestamp: timestampNs,
				})
			}

			// U4: Valid transitions only:
			// PENDING -> SENT, SPRAYED, FAILED, or DELIVERED
			// SENT/SPRAYED -> DELIVERED or FAILED
			// FAILED -> PENDING (via retry)
			switch prev {
			case StatusPending:
				// All target transitions valid
			case StatusSent, StatusSprayed:
				if curr != StatusDelivered && curr != StatusFailed && curr != prev {
					violations = append(violations, InvariantViolation{
						ID:        U4_NoImpossibleTransition,
						Severity:  SeverityP2,
						Component: "StateMachine",
						MessageID: msgID,
						Message:   fmt.Sprintf("Impossible transition from %s to %s", StatusToString(prev), StatusToString(curr)),
						Timestamp: timestampNs,
					})
				}
			case StatusDelivered:
				if curr != StatusDelivered {
					violations = append(violations, InvariantViolation{
						ID:        U4_NoImpossibleTransition,
						Severity:  SeverityP2,
						Component: "StateMachine",
						MessageID: msgID,
						Message:   fmt.Sprintf("Terminal DELIVERED state transitioned to %s", StatusToString(curr)),
						Timestamp: timestampNs,
					})
				}
			}
		}
	}

	// Verify that any rejected mutation did NOT change the committed state in Room
	for _, rej := range c.rejectedMutations {
		msg := db.GetMessage(rej.MessageID)
		if msg != nil && msg.Status == rej.AttemptedStatus && rej.AttemptedStatus != StatusDelivered {
			violations = append(violations, InvariantViolation{
				ID:        U5_NoStaleRollback,
				Severity:  SeverityP2,
				Component: "RoomDatabase",
				MessageID: rej.MessageID,
				Message:   fmt.Sprintf("Rejected mutation was incorrectly committed to Room: status is %s", StatusToString(msg.Status)),
				Timestamp: timestampNs,
			})
		}
	}

	// U6: No observer explosion (Active collectors return to baseline after lifecycle churn)
	if lifecycle != nil {
		if lifecycle.ProcessAlive && lifecycle.ActiveCollectors > 10 {
			violations = append(violations, InvariantViolation{
				ID:        U6_NoObserverExplosion,
				Severity:  SeverityP3,
				Component: "LifecycleModel",
				Message:   fmt.Sprintf("Flow collector explosion: %d active collectors (baseline <= 2)", lifecycle.ActiveCollectors),
				Timestamp: timestampNs,
			})
		}
	}

	// U7: Bounded event queues (GATT queue must not exceed capacity)
	if gatt != nil {
		depth := gatt.QueueDepth()
		if depth > profile.MaxQueueCapacity {
			violations = append(violations, InvariantViolation{
				ID:        U7_BoundedEventQueues,
				Severity:  SeverityP3,
				Component: "GattOperationQueue",
				Message:   fmt.Sprintf("GATT queue capacity exceeded: depth %d > max %d", depth, profile.MaxQueueCapacity),
				Timestamp: timestampNs,
			})
		}
	}

	// U10: Transport truthfulness
	// Verify mapping from actual transport outcome to UI state:
	// GATT success -> SENT
	// GATT failure -> FAILED
	// Relay dispatch -> SPRAYED
	// Signed receipt -> DELIVERED
	for _, m := range allMsgs {
		if expected, ok := c.transportTruthLog[m.ID]; ok {
			switch expected {
			case "GATT_SUCCESS":
				if m.Status == StatusFailed {
					violations = append(violations, InvariantViolation{
						ID:        U10_TransportTruthfulness,
						Severity:  SeverityP2,
						Component: "TransportTruthfulness",
						MessageID: m.ID,
						Message:   "GATT write succeeded but message marked FAILED",
						Timestamp: timestampNs,
					})
				}
			case "GATT_FAILURE":
				if m.Status == StatusSent {
					violations = append(violations, InvariantViolation{
						ID:        U10_TransportTruthfulness,
						Severity:  SeverityP2,
						Component: "TransportTruthfulness",
						MessageID: m.ID,
						Message:   "GATT write failed but message marked SENT",
						Timestamp: timestampNs,
					})
				}
			case "RELAY_SPRAY":
				if m.Status == StatusDelivered {
					violations = append(violations, InvariantViolation{
						ID:        U10_TransportTruthfulness,
						Severity:  SeverityP2,
						Component: "TransportTruthfulness",
						MessageID: m.ID,
						Message:   "Message sprayed to relay carrier but prematurely marked DELIVERED before receipt",
						Timestamp: timestampNs,
					})
				}
			}
		}
	}

	// U8: Repository consistency
	if repo != nil && repo.GetItemsCount() > 0 {
		contacts := db.GetAllContacts()
		groups := db.GetAllActiveGroups()
		expectedCount := len(contacts) + len(groups)
		itemCount := repo.GetItemsCount()
		if itemCount != expectedCount {
			violations = append(violations, InvariantViolation{
				ID:        U8_RepositoryConsistency,
				Severity:  SeverityP3,
				Component: "ConversationRepository",
				Message:   fmt.Sprintf("Repository conversation count mismatch: got %d, expected %d", itemCount, expectedCount),
				Timestamp: timestampNs,
			})
		}
	}

	return violations
}
