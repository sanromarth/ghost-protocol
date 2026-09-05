package ux

import (
	"sort"
	"sync"
)

// SOURCE: android/app/src/main/java/com/ghostprotocol/ui/ChatViewModel.kt
// SOURCE: android/app/src/main/java/com/ghostprotocol/ui/ChatScreen.kt
// CONTRACT: U1, U4, U5, U10
// MODEL: Jetpack Compose and ViewModel state pipeline simulation:
// - In-memory optimistic pending map (_optimisticPendingMessages)
// - Flow combine(messageDao, optimisticMap) with deduplicated sorting
// - WhileSubscribed(5000) lifecycle state sharing
// - LazyColumn windowing and recomposition unit estimation

// ComposeViewModelState mirrors ChatViewModel and Compose ChatScreen visible state.
type ComposeViewModelState struct {
	mu sync.Mutex

	ContactID         string
	ActiveContact     *ContactRecord
	OptimisticPending map[string]*MessageRecord
	CombinedMessages  []*MessageRecord
	VisibleMessages   []*MessageRecord

	// LazyColumn state proxy
	ScrollOffset int
	WindowSize   int

	// Metrics
	RecompositionUnits    int64
	StateEmissionsCount   int64
	OptimisticBubbleAdded int64
	OptimisticBubblePruned int64
}

// NewComposeViewModelState creates a fresh ChatViewModel simulation for a contact.
func NewComposeViewModelState(contactID string) *ComposeViewModelState {
	return &ComposeViewModelState{
		ContactID:         contactID,
		OptimisticPending: make(map[string]*MessageRecord),
		CombinedMessages:  make([]*MessageRecord, 0),
		VisibleMessages:   make([]*MessageRecord, 0),
		WindowSize:        25, // Typical smartphone viewport height in messages
	}
}

// SendOptimisticMessage implements Step 1 of ChatViewModel.sendMessage():
// Synchronously acknowledges send in <1ms by adding to in-memory optimistic map.
func (s *ComposeViewModelState) SendOptimisticMessage(msg *MessageRecord, currentRoomMsgs []*MessageRecord) {
	s.mu.Lock()
	defer s.mu.Unlock()

	rec := *msg
	s.OptimisticPending[msg.ID] = &rec
	s.OptimisticBubbleAdded++

	s.recomputeMergedStateLocked(currentRoomMsgs)
}

// OnRoomCommitted implements Step 2 of ChatViewModel.sendMessage():
// Removes message from in-memory optimistic map once Room insert commits.
func (s *ComposeViewModelState) OnRoomCommitted(msgID string, currentRoomMsgs []*MessageRecord) {
	s.mu.Lock()
	defer s.mu.Unlock()

	if _, ok := s.OptimisticPending[msgID]; ok {
		delete(s.OptimisticPending, msgID)
		s.OptimisticBubblePruned++
	}

	s.recomputeMergedStateLocked(currentRoomMsgs)
}

// OnRoomFlowEmitted is invoked whenever Room MessageDao emits updated rows.
func (s *ComposeViewModelState) OnRoomFlowEmitted(roomMsgs []*MessageRecord) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.recomputeMergedStateLocked(roomMsgs)
}

// recomputeMergedStateLocked mirrors exact combine() block in ChatViewModel.kt:
// ```kotlin
// if (optimisticMap.isEmpty()) roomMessages
// else {
//     val existingIds = roomMessages.mapTo(HashSet()) { it.id }
//     val unmerged = optimisticMap.values.filter { it.id !in existingIds }
//     if (unmerged.isEmpty()) roomMessages else (roomMessages + unmerged).sortedBy { it.timestamp }
// }
// ```
func (s *ComposeViewModelState) recomputeMergedStateLocked(roomMessages []*MessageRecord) {
	s.StateEmissionsCount++
	s.RecompositionUnits++

	if len(s.OptimisticPending) == 0 {
		s.CombinedMessages = make([]*MessageRecord, len(roomMessages))
		copy(s.CombinedMessages, roomMessages)
	} else {
		existingIDs := make(map[string]struct{}, len(roomMessages))
		for _, m := range roomMessages {
			existingIDs[m.ID] = struct{}{}
		}

		var unmerged []*MessageRecord
		for _, m := range s.OptimisticPending {
			if _, exists := existingIDs[m.ID]; !exists {
				cp := *m
				unmerged = append(unmerged, &cp)
			}
		}

		if len(unmerged) == 0 {
			s.CombinedMessages = make([]*MessageRecord, len(roomMessages))
			copy(s.CombinedMessages, roomMessages)
		} else {
			merged := make([]*MessageRecord, 0, len(roomMessages)+len(unmerged))
			merged = append(merged, roomMessages...)
			merged = append(merged, unmerged...)
			sort.Slice(merged, func(i, j int) bool {
				return merged[i].Timestamp < merged[j].Timestamp
			})
			s.CombinedMessages = merged
		}
	}

	// Update visible LazyColumn window
	total := len(s.CombinedMessages)
	if total == 0 {
		s.VisibleMessages = s.VisibleMessages[:0]
		return
	}

	start := s.ScrollOffset
	if start < 0 {
		start = 0
	}
	if start >= total {
		start = total - 1
	}

	end := start + s.WindowSize
	if end > total {
		end = total
	}

	s.VisibleMessages = s.CombinedMessages[start:end]
}

// Scroll updates LazyColumn scroll offset.
func (s *ComposeViewModelState) Scroll(newOffset int) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.ScrollOffset = newOffset
	s.recomputeMergedStateLocked(s.CombinedMessages)
}

// ClearPending clears in-memory pending map (e.g. during process death).
func (s *ComposeViewModelState) ClearPending() {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.OptimisticPending = make(map[string]*MessageRecord)
}

// Reset clears pending and resets scroll offset and cached views.
func (s *ComposeViewModelState) Reset() {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.OptimisticPending = make(map[string]*MessageRecord)
	s.ScrollOffset = 0
	s.CombinedMessages = nil
	s.VisibleMessages = nil
}

