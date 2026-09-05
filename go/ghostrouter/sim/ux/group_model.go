package ux

import (
	"fmt"
	"sync"
)

// SOURCE: android/app/src/main/java/com/ghostprotocol/group/GroupProtocol.kt
// SOURCE: android/app/src/main/java/com/ghostprotocol/group/GroupMessageSender.kt
// CONTRACT: U9
// MODEL: Cell Group unicast envelope expansion and multi-member delivery receipt aggregation.

// GroupModel manages Cell Group message expansion and receipt aggregation.
type GroupModel struct {
	mu sync.Mutex

	db        *RoomDatabaseModel
	gattQueue *GattQueueModel
	bridge    *NativeBridgeModel

	// Active group delivery tracking: groupMsgID -> map[memberID]bool
	deliveredMembers map[int64]map[string]bool

	// Metrics
	TotalGroupMessages int64
	TotalEnvelopesSent int64
	TotalReceiptsRx    int64
	DuplicateReceipts  int64
}

// NewGroupModel creates a group messaging coordinator.
func NewGroupModel(db *RoomDatabaseModel, gattQueue *GattQueueModel, bridge *NativeBridgeModel) *GroupModel {
	return &GroupModel{
		db:               db,
		gattQueue:        gattQueue,
		bridge:           bridge,
		deliveredMembers: make(map[int64]map[string]bool),
	}
}

// SendGroupMessage simulates GroupMessageSender.sendGroupMessage():
// Inserts pending group message, formats wire payload, and expands into
// (M-1) individual pairwise-encrypted unicast envelopes.
func (g *GroupModel) SendGroupMessage(
	groupID string,
	senderID string,
	text string,
	memberIDs []string,
	timestamp int64,
) (int64, int, error) {
	g.mu.Lock()
	defer g.mu.Unlock()

	contentHash := ComputeMessageHash(senderID, timestamp, text)

	// 1. Insert locally as pending in Room DB
	gm := &GroupMessageRecord{
		GroupID:            groupID,
		SenderContactID:    senderID,
		Text:               text,
		Timestamp:          timestamp,
		Status:             StatusPending,
		DeliveredMemberIDs: make([]string, 0),
		ContentHash:        contentHash,
	}
	msgID := g.db.InsertGroupMessage(gm)

	// 2. Pairwise expansion to (M-1) members
	envelopesCount := 0
	g.deliveredMembers[msgID] = make(map[string]bool)

	for _, memberID := range memberIDs {
		if memberID == senderID {
			continue // Do not send to self
		}

		envelopesCount++
		g.TotalEnvelopesSent++

		// Simulate crypto encryption & GATT enqueue
		mac := fmt.Sprintf("MAC_%s", memberID)
		_, _ = g.gattQueue.Enqueue(&GattItem{
			ID:         fmt.Sprintf("grp_%d_%s", msgID, memberID),
			MacAddress: mac,
			PayloadLen: 121 + len(text) + 28, // Opcode 0x30 minimum envelope
			TimeoutMs:  5000,
			OnResult: func(success bool) {
				if success {
					g.db.UpdateGroupMessageStatus(msgID, StatusSprayed)
				}
			},
		})
	}

	g.TotalGroupMessages++
	return msgID, envelopesCount, nil
}

// ProcessGroupReceipt processes an incoming delivery receipt for a group message.
// Matches DeliveryReceiptHandler.kt handling of GroupMessageEntity.
func (g *GroupModel) ProcessGroupReceipt(groupMsgID int64, recipientMemberID string, totalMembers int) (int, bool, error) {
	g.mu.Lock()
	defer g.mu.Unlock()

	g.TotalReceiptsRx++

	deliveredMap, exists := g.deliveredMembers[groupMsgID]
	if !exists {
		deliveredMap = make(map[string]bool)
		g.deliveredMembers[groupMsgID] = deliveredMap
	}

	// Idempotent receipt check
	if deliveredMap[recipientMemberID] {
		g.DuplicateReceipts++
		return len(deliveredMap), len(deliveredMap) >= (totalMembers - 1), nil
	}

	deliveredMap[recipientMemberID] = true
	cnt := g.db.UpdateGroupDeliveredMembers(groupMsgID, recipientMemberID)

	// Invariant U9: Delivery count must never exceed verified member count
	if cnt > totalMembers {
		return cnt, false, fmt.Errorf("INVARIANT VIOLATION (U9): group message %d deliveries (%d) exceeded members (%d)", groupMsgID, cnt, totalMembers)
	}

	isComplete := (cnt >= totalMembers-1)
	return cnt, isComplete, nil
}
