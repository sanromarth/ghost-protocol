package ux

import (
	"sort"
	"sync"
)

// SOURCE: android/app/src/main/java/com/ghostprotocol/data/ConversationRepository.kt
// CONTRACT: U8
// MODEL: Multi-flow aggregation simulation modeling combine(contacts, groups, messages, groupMessages, peers).
// Calculates exact state emission amplification ratio: repository emissions / source events.

// ConversationItemModel mirrors ConversationItem.Direct and ConversationItem.Group.
type ConversationItemModel struct {
	ID              string
	Name            string
	IsGroup         bool
	LastMessageText string
	LastMessageTime int64
	LastStatus      *int
	IsDirectRadio   bool
	MemberCount     int
}

// ConversationRepoModel simulates ConversationRepository aggregation and flow joins.
type ConversationRepoModel struct {
	mu sync.Mutex

	CurrentItems []ConversationItemModel

	// Amplification tracking
	SourceEventsCount        int64
	RepositoryEmissionsCount int64
	TotalJoinComputations    int64
}

// NewConversationRepoModel creates a fresh conversation repository model.
func NewConversationRepoModel() *ConversationRepoModel {
	return &ConversationRepoModel{
		CurrentItems: make([]ConversationItemModel, 0),
	}
}

// RecordSourceEvent logs an input database or BLE event trigger.
func (r *ConversationRepoModel) RecordSourceEvent() {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.SourceEventsCount++
}

// GetItemsCount returns the current count of aggregated conversations thread-safely.
func (r *ConversationRepoModel) GetItemsCount() int {
	r.mu.Lock()
	defer r.mu.Unlock()
	return len(r.CurrentItems)
}

// Recompute executes the 5-way combine logic from ConversationRepository.kt:
// 1. Group direct messages by contactId (latest message by timestamp DESC)
// 2. Group cell group messages by groupId
// 3. Match discovered BLE radio peers
// 4. Merge and sort chronologically by lastMessageTime DESC
func (r *ConversationRepoModel) Recompute(
	contacts []*ContactRecord,
	groups []*GroupRecord,
	messages []*MessageRecord,
	groupMessages []*GroupMessageRecord,
	activePeers map[string]bool,
) []ConversationItemModel {
	r.mu.Lock()
	defer r.mu.Unlock()

	r.RepositoryEmissionsCount++
	r.TotalJoinComputations++

	// 1. Latest message by contact
	latestByContact := make(map[string]*MessageRecord, len(contacts))
	for _, m := range messages {
		if _, exists := latestByContact[m.ContactID]; !exists {
			latestByContact[m.ContactID] = m
		}
	}

	// 2. Latest group message by group
	latestByGroup := make(map[string]*GroupMessageRecord, len(groups))
	for _, gm := range groupMessages {
		if _, exists := latestByGroup[gm.GroupID]; !exists {
			latestByGroup[gm.GroupID] = gm
		}
	}

	var items []ConversationItemModel

	// Direct conversations
	for _, c := range contacts {
		lastMsg := latestByContact[c.ID]
		lastTime := c.CreatedAt
		var lastText string
		var lastStatus *int
		if lastMsg != nil {
			lastTime = lastMsg.Timestamp
			lastText = lastMsg.Content
			statusCopy := lastMsg.Status
			lastStatus = &statusCopy
		}

		isDirect := false
		if activePeers != nil && activePeers[c.ID] {
			isDirect = true
		}

		items = append(items, ConversationItemModel{
			ID:              c.ID,
			Name:            c.Name,
			IsGroup:         false,
			LastMessageText: lastText,
			LastMessageTime: lastTime,
			LastStatus:      lastStatus,
			IsDirectRadio:   isDirect,
		})
	}

	// Group conversations
	for _, g := range groups {
		if !g.IsActive {
			continue
		}
		lastGm := latestByGroup[g.GroupID]
		lastTime := g.CreatedAt
		var lastText string
		var lastStatus *int
		if lastGm != nil {
			lastTime = lastGm.Timestamp
			lastText = lastGm.Text
			statusCopy := lastGm.Status
			lastStatus = &statusCopy
		}

		items = append(items, ConversationItemModel{
			ID:              g.GroupID,
			Name:            g.Name,
			IsGroup:         true,
			LastMessageText: lastText,
			LastMessageTime: lastTime,
			LastStatus:      lastStatus,
			MemberCount:     len(g.MemberContactIDs),
		})
	}

	// Sort chronologically descending
	sort.Slice(items, func(i, j int) bool {
		return items[i].LastMessageTime > items[j].LastMessageTime
	})

	r.CurrentItems = items
	return items
}

// AmplificationRatio returns repository emissions / source events.
func (r *ConversationRepoModel) AmplificationRatio() float64 {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.SourceEventsCount == 0 {
		return 0.0
	}
	return float64(r.RepositoryEmissionsCount) / float64(r.SourceEventsCount)
}
