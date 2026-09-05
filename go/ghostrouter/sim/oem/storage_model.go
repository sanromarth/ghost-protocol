package oem

import (
	"fmt"
	"sync"
)

// SOURCE: android/app/src/main/java/com/ghostprotocol/data/MessageDao.kt & GhostDatabase.kt
// CONTRACT: O1 (Durable Message Survival), O6 (Terminal Delivery Invariance), O16 (Storage Failure Transparent),
//           O17 (No Logical Duplicates), O18 (No Committed Message Loss), O19 (Valid State Progression)
// MODEL: SQLite disk persistence with atomic status guard AND (status != 2 OR :status = 2) and hostile disk conditions.

// StoredMessage models an entity in the Room messages table.
type StoredMessage struct {
	ID          string
	ContactID   string
	Content     string
	Timestamp   int64
	IsOutgoing  bool
	Status      int   // 0=PENDING, 1=SENT, 2=DELIVERED, 3=SPRAYED, 4=FAILED
	Signature   string
	DurableAtNs int64
}

// StorageModel simulates the Android SQLite / Room database layer.
type StorageModel struct {
	mu sync.Mutex

	clock     *VirtualClock
	scheduler *EventScheduler
	profile   OemProfile

	state         StorageState
	committedRows map[string]*StoredMessage // ID -> message (durable on disk)
	history       map[string][]int          // ID -> sequence of status transitions

	// Metrics
	TotalWrites     int
	TotalReads      int
	FailedWrites    int
	DowngradeRejects int
}

// NewStorageModel creates a StorageModel with normal storage conditions.
func NewStorageModel(clock *VirtualClock, scheduler *EventScheduler, profile OemProfile) *StorageModel {
	return &StorageModel{
		clock:         clock,
		scheduler:     scheduler,
		profile:       profile,
		state:         StorageStateNormal,
		committedRows: make(map[string]*StoredMessage),
		history:       make(map[string][]int),
	}
}

// SetStorageState alters SQLite storage conditions (e.g. disk full, I/O error).
func (s *StorageModel) SetStorageState(state StorageState) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.state = state
}

// InsertOrUpdate simulates Room DAO insertion or update.
func (s *StorageModel) Insert(msg *StoredMessage) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	s.TotalWrites++

	// Check storage health
	switch s.state {
	case StorageStateDiskFull:
		s.FailedWrites++
		return fmt.Errorf("android.database.sqlite.SQLiteFullException: database or disk is full (code 13)")
	case StorageStateIoError:
		s.FailedWrites++
		return fmt.Errorf("android.database.sqlite.SQLiteDiskIOException: disk I/O error (code 778)")
	case StorageStateCorrupted:
		s.FailedWrites++
		return fmt.Errorf("android.database.sqlite.SQLiteDatabaseCorruptException: database disk image is malformed (code 11)")
	}

	// Clone message to simulate commit to durable disk
	stored := &StoredMessage{
		ID:          msg.ID,
		ContactID:   msg.ContactID,
		Content:     msg.Content,
		Timestamp:   msg.Timestamp,
		IsOutgoing:  msg.IsOutgoing,
		Status:      msg.Status,
		Signature:   msg.Signature,
		DurableAtNs: s.clock.NowNs(),
	}

	s.committedRows[msg.ID] = stored
	s.history[msg.ID] = append(s.history[msg.ID], msg.Status)
	return nil
}

// UpdateStatus simulates the atomic SQL query in MessageDao:
// UPDATE messages SET status = :status WHERE id = :id AND (status != 2 OR :status = 2)
func (s *StorageModel) UpdateStatus(id string, newStatus int) (bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	s.TotalWrites++

	switch s.state {
	case StorageStateDiskFull:
		s.FailedWrites++
		return false, fmt.Errorf("android.database.sqlite.SQLiteFullException: database or disk is full")
	case StorageStateIoError:
		s.FailedWrites++
		return false, fmt.Errorf("android.database.sqlite.SQLiteDiskIOException: disk I/O error")
	case StorageStateCorrupted:
		s.FailedWrites++
		return false, fmt.Errorf("android.database.sqlite.SQLiteDatabaseCorruptException: database disk image is malformed")
	}

	row, exists := s.committedRows[id]
	if !exists {
		return false, nil // Row doesn't exist
	}

	// ATOMIC STATUS GUARD: AND (status != 2 OR :status = 2)
	// If current status is DELIVERED (2) and newStatus != 2, update is rejected
	if row.Status == MsgStatusDelivered && newStatus != MsgStatusDelivered {
		s.DowngradeRejects++
		// The SQL WHERE condition evaluated to false, so 0 rows were updated. Not an error.
		return false, nil
	}

	row.Status = newStatus
	s.history[id] = append(s.history[id], newStatus)
	return true, nil
}

// GetMessage retrieves message by ID from disk.
func (s *StorageModel) GetMessage(id string) (*StoredMessage, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	s.TotalReads++
	if s.state == StorageStateCorrupted {
		return nil, fmt.Errorf("android.database.sqlite.SQLiteDatabaseCorruptException: disk image is malformed")
	}

	row, exists := s.committedRows[id]
	if !exists {
		return nil, nil
	}

	// Return a copy
	return &StoredMessage{
		ID:          row.ID,
		ContactID:   row.ContactID,
		Content:     row.Content,
		Timestamp:   row.Timestamp,
		IsOutgoing:  row.IsOutgoing,
		Status:      row.Status,
		Signature:   row.Signature,
		DurableAtNs: row.DurableAtNs,
	}, nil
}

// AllCommittedMessages returns all messages currently committed to storage.
func (s *StorageModel) AllCommittedMessages() []*StoredMessage {
	s.mu.Lock()
	defer s.mu.Unlock()

	list := make([]*StoredMessage, 0, len(s.committedRows))
	for _, m := range s.committedRows {
		list = append(list, &StoredMessage{
			ID:          m.ID,
			ContactID:   m.ContactID,
			Content:     m.Content,
			Timestamp:   m.Timestamp,
			IsOutgoing:  m.IsOutgoing,
			Status:      m.Status,
			Signature:   m.Signature,
			DurableAtNs: m.DurableAtNs,
		})
	}
	return list
}

// CheckTerminalDeliveryInvariance verifies Invariant O6:
// No message in history ever transitioned from DELIVERED (2) back to a lower status.
func (s *StorageModel) CheckTerminalDeliveryInvariance() error {
	s.mu.Lock()
	defer s.mu.Unlock()

	for id, trans := range s.history {
		seenDelivered := false
		for _, st := range trans {
			if seenDelivered && st != MsgStatusDelivered {
				return fmt.Errorf("O6 violation: message %s transitioned from DELIVERED to status %d (history=%v)",
					id, st, trans)
			}
			if st == MsgStatusDelivered {
				seenDelivered = true
			}
		}
	}
	return nil
}

// CheckCommittedMessageSurvival verifies Invariant O1 & O18:
// Every message successfully committed prior to a process death remains present.
func (s *StorageModel) CheckCommittedMessageSurvival(expectedIds []string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	for _, id := range expectedIds {
		if _, exists := s.committedRows[id]; !exists {
			return fmt.Errorf("O1/O18 violation: committed message %s missing from durable storage", id)
		}
	}
	return nil
}
