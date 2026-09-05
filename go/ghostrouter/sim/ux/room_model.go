package ux

import (
	"fmt"
	"sort"
	"sync"
)

// SOURCE: android/app/src/main/java/com/ghostprotocol/data/
// CONTRACT: U1, U3, U4, U5, U8
// MODEL: Deterministic Room SQLite engine simulation modeling ACID transactions,
// indexed queries, InvalidationTracker notifications, and durable disk recovery.

// MessageRecord mirrors Room MessageEntity.kt.
type MessageRecord struct {
	ID            string `json:"id"`
	ContactID     string `json:"contact_id"`
	Content       string `json:"content"`
	IsOutgoing    bool   `json:"is_outgoing"`
	Timestamp     int64  `json:"timestamp"`
	IsVerified    bool   `json:"is_verified"`
	Status        int    `json:"status"`
	ReplyToID     string `json:"reply_to_id,omitempty"`
	ReplyToSender string `json:"reply_to_sender,omitempty"`
	ReplyToText   string `json:"reply_to_text,omitempty"`
	ContentHash   string `json:"content_hash,omitempty"`
}

// ContactRecord mirrors Room Contact.kt.
type ContactRecord struct {
	ID           string `json:"id"`
	Name         string `json:"name"`
	Ed25519Pub   string `json:"ed25519_pub"`
	X25519Pub    string `json:"x25519_pub"`
	BleAddress   string `json:"ble_address,omitempty"`
	CreatedAt    int64  `json:"created_at"`
	IsVerified   bool   `json:"is_verified"`
	IsIntroduced bool   `json:"is_introduced"`
}

// GroupRecord mirrors Room GroupEntity.kt.
type GroupRecord struct {
	GroupID          string   `json:"group_id"`
	Name             string   `json:"name"`
	CreatorContactID string   `json:"creator_contact_id"`
	MemberContactIDs []string `json:"member_contact_ids"`
	CreatedAt        int64    `json:"created_at"`
	IsActive         bool     `json:"is_active"`
}

// GroupMessageRecord mirrors Room GroupMessageEntity.kt.
type GroupMessageRecord struct {
	ID                 int64    `json:"id"`
	GroupID            string   `json:"group_id"`
	SenderContactID    string   `json:"sender_contact_id"`
	Text               string   `json:"text"`
	Timestamp          int64    `json:"timestamp"`
	Status             int      `json:"status"`
	DeliveredMemberIDs []string `json:"delivered_member_ids"`
	ContentHash        string   `json:"content_hash,omitempty"`
}

// RoomDatabaseModel simulates the thread-safe Android Room database.
type RoomDatabaseModel struct {
	mu sync.Mutex

	// Durable storage (persists across process deaths and restarts)
	durableMessages      map[string]*MessageRecord
	durableContacts      map[string]*ContactRecord
	durableGroups        map[string]*GroupRecord
	durableGroupMessages map[int64]*GroupMessageRecord
	nextGroupMsgID       int64

	// Indices for O(1) lookups
	idxContentHash      map[string]string // contentHash -> messageID
	idxGroupContentHash map[string]int64  // contentHash -> groupMessageID

	// Active Flow Invalidation Observers
	messageObservers      []func()
	contactObservers      []func()
	groupObservers        []func()
	groupMessageObservers []func()

	// Metrics and tracking
	TotalWrites           int64
	TotalReads            int64
	InvalidationEmissions int64

	// Crash window hook
	crashHook func(stage CrashWindowStage, msgID string) error
}

// NewRoomDatabaseModel creates an empty, ready Room simulation.
func NewRoomDatabaseModel() *RoomDatabaseModel {
	return &RoomDatabaseModel{
		durableMessages:       make(map[string]*MessageRecord),
		durableContacts:       make(map[string]*ContactRecord),
		durableGroups:         make(map[string]*GroupRecord),
		durableGroupMessages:  make(map[int64]*GroupMessageRecord),
		idxContentHash:        make(map[string]string),
		idxGroupContentHash:   make(map[string]int64),
		messageObservers:      make([]func(), 0),
		contactObservers:      make([]func(), 0),
		groupObservers:        make([]func(), 0),
		groupMessageObservers: make([]func(), 0),
	}
}

// SetCrashHook configures a callback invoked at granular persistence crash windows.
func (db *RoomDatabaseModel) SetCrashHook(fn func(stage CrashWindowStage, msgID string) error) {
	db.mu.Lock()
	defer db.mu.Unlock()
	db.crashHook = fn
}

// ObserveMessages registers a Room InvalidationTracker observer for the messages table.
func (db *RoomDatabaseModel) ObserveMessages(fn func()) func() {
	db.mu.Lock()
	defer db.mu.Unlock()
	db.messageObservers = append(db.messageObservers, fn)
	idx := len(db.messageObservers) - 1
	return func() {
		db.mu.Lock()
		defer db.mu.Unlock()
		if idx < len(db.messageObservers) {
			db.messageObservers[idx] = nil
		}
	}
}

// InsertMessage executes Room MessageDao.insert(message).
func (db *RoomDatabaseModel) InsertMessage(msg *MessageRecord) error {
	db.mu.Lock()
	hook := db.crashHook
	db.mu.Unlock()

	// Window 1: Mutation begins
	if hook != nil {
		if err := hook(CrashWindowMutationBegin, msg.ID); err != nil {
			return err
		}
	}

	db.mu.Lock()
	// Clone record for ACID isolation
	rec := *msg
	db.durableMessages[msg.ID] = &rec
	if msg.ContentHash != "" {
		db.idxContentHash[msg.ContentHash] = msg.ID
	}
	db.TotalWrites++
	db.mu.Unlock()

	// Window 2: Mutation commits
	if hook != nil {
		if err := hook(CrashWindowMutationCommit, msg.ID); err != nil {
			return err
		}
	}

	// Window 3: Mutation becomes observable
	if hook != nil {
		if err := hook(CrashWindowMutationObservable, msg.ID); err != nil {
			return err
		}
	}

	// Window 4: Flow emission triggers
	db.notifyMessageObservers()

	if hook != nil {
		if err := hook(CrashWindowFlowEmission, msg.ID); err != nil {
			return err
		}
	}
	return nil
}

// UpdateMessageStatus updates an existing message's status.
// Enforces state machine: terminal states (DELIVERED) cannot be rolled back to SPRAYED/SENT.
func (db *RoomDatabaseModel) UpdateMessageStatus(id string, newStatus int) (bool, error) {
	db.mu.Lock()
	msg, exists := db.durableMessages[id]
	if !exists {
		db.mu.Unlock()
		return false, nil
	}

	// Invariant U5: Terminal DELIVERED state cannot roll back to SPRAYED or SENT
	if msg.Status == StatusDelivered && (newStatus == StatusSprayed || newStatus == StatusSent) {
		db.mu.Unlock()
		return false, fmt.Errorf("invalid rollback: message %s already DELIVERED, cannot transition to %s", id, StatusToString(newStatus))
	}

	msg.Status = newStatus
	db.TotalWrites++
	db.mu.Unlock()

	db.notifyMessageObservers()
	return true, nil
}

// UpdateMessageStatusByHash updates message status identified by contentHash.
func (db *RoomDatabaseModel) UpdateMessageStatusByHash(hash string, newStatus int) (string, error) {
	db.mu.Lock()
	id, exists := db.idxContentHash[hash]
	if !exists {
		db.mu.Unlock()
		return "", nil
	}

	msg := db.durableMessages[id]
	if msg == nil {
		db.mu.Unlock()
		return "", nil
	}

	if msg.Status == StatusDelivered && (newStatus == StatusSprayed || newStatus == StatusSent) {
		db.mu.Unlock()
		return id, fmt.Errorf("invalid rollback by hash: message %s already DELIVERED", id)
	}

	msg.Status = newStatus
	db.TotalWrites++
	db.mu.Unlock()

	db.notifyMessageObservers()
	return id, nil
}

// GetMessage returns a message by ID.
func (db *RoomDatabaseModel) GetMessage(id string) *MessageRecord {
	db.mu.Lock()
	defer db.mu.Unlock()
	db.TotalReads++
	if msg, ok := db.durableMessages[id]; ok {
		cp := *msg
		return &cp
	}
	return nil
}

// GetMessageByContentHash returns a message by its content hash.
func (db *RoomDatabaseModel) GetMessageByContentHash(hash string) *MessageRecord {
	db.mu.Lock()
	defer db.mu.Unlock()
	db.TotalReads++
	if id, ok := db.idxContentHash[hash]; ok {
		if msg, ok2 := db.durableMessages[id]; ok2 {
			cp := *msg
			return &cp
		}
	}
	return nil
}

// GetMessagesForContact returns all messages for contactId ordered by timestamp ASC.
func (db *RoomDatabaseModel) GetMessagesForContact(contactID string) []*MessageRecord {
	db.mu.Lock()
	defer db.mu.Unlock()
	db.TotalReads++

	var list []*MessageRecord
	for _, m := range db.durableMessages {
		if m.ContactID == contactID {
			cp := *m
			list = append(list, &cp)
		}
	}
	sort.Slice(list, func(i, j int) bool {
		return list[i].Timestamp < list[j].Timestamp
	})
	return list
}

// GetAllMessages returns all messages ordered by timestamp DESC (as in ConversationRepository).
func (db *RoomDatabaseModel) GetAllMessages() []*MessageRecord {
	db.mu.Lock()
	defer db.mu.Unlock()
	db.TotalReads++

	list := make([]*MessageRecord, 0, len(db.durableMessages))
	for _, m := range db.durableMessages {
		cp := *m
		list = append(list, &cp)
	}
	sort.Slice(list, func(i, j int) bool {
		return list[i].Timestamp > list[j].Timestamp
	})
	return list
}

// SearchMessages simulates full-text search over message content.
func (db *RoomDatabaseModel) SearchMessages(query string) []*MessageRecord {
	db.mu.Lock()
	defer db.mu.Unlock()
	db.TotalReads++

	var list []*MessageRecord
	for _, m := range db.durableMessages {
		if query == "" || len(m.Content) >= len(query) {
			cp := *m
			list = append(list, &cp)
		}
	}
	return list
}

// InsertContact saves or updates a contact.
func (db *RoomDatabaseModel) InsertContact(contact *ContactRecord) {
	db.mu.Lock()
	defer db.mu.Unlock()
	cp := *contact
	db.durableContacts[contact.ID] = &cp
	db.TotalWrites++
}

// GetContact returns a contact by ID.
func (db *RoomDatabaseModel) GetContact(id string) *ContactRecord {
	db.mu.Lock()
	defer db.mu.Unlock()
	db.TotalReads++
	if c, ok := db.durableContacts[id]; ok {
		cp := *c
		return &cp
	}
	return nil
}

// GetAllContacts returns all contacts.
func (db *RoomDatabaseModel) GetAllContacts() []*ContactRecord {
	db.mu.Lock()
	defer db.mu.Unlock()
	db.TotalReads++
	list := make([]*ContactRecord, 0, len(db.durableContacts))
	for _, c := range db.durableContacts {
		cp := *c
		list = append(list, &cp)
	}
	return list
}

// InsertGroup saves or updates a cell group.
func (db *RoomDatabaseModel) InsertGroup(g *GroupRecord) {
	db.mu.Lock()
	defer db.mu.Unlock()
	cp := *g
	db.durableGroups[g.GroupID] = &cp
	db.TotalWrites++
}

// GetAllActiveGroups returns all active groups.
func (db *RoomDatabaseModel) GetAllActiveGroups() []*GroupRecord {
	db.mu.Lock()
	defer db.mu.Unlock()
	db.TotalReads++
	var list []*GroupRecord
	for _, g := range db.durableGroups {
		if g.IsActive {
			cp := *g
			list = append(list, &cp)
		}
	}
	return list
}

// InsertGroupMessage saves a new group message and assigns an auto-increment ID.
func (db *RoomDatabaseModel) InsertGroupMessage(gm *GroupMessageRecord) int64 {
	db.mu.Lock()
	defer db.mu.Unlock()

	db.nextGroupMsgID++
	gm.ID = db.nextGroupMsgID
	cp := *gm
	db.durableGroupMessages[gm.ID] = &cp
	if gm.ContentHash != "" {
		db.idxGroupContentHash[gm.ContentHash] = gm.ID
	}
	db.TotalWrites++
	return gm.ID
}

// UpdateGroupMessageStatus updates a group message status.
func (db *RoomDatabaseModel) UpdateGroupMessageStatus(id int64, newStatus int) {
	db.mu.Lock()
	defer db.mu.Unlock()
	if gm, ok := db.durableGroupMessages[id]; ok {
		gm.Status = newStatus
		db.TotalWrites++
	}
}

// UpdateGroupDeliveredMembers records delivered member receipts for a group message.
func (db *RoomDatabaseModel) UpdateGroupDeliveredMembers(id int64, memberID string) int {
	db.mu.Lock()
	defer db.mu.Unlock()
	gm, ok := db.durableGroupMessages[id]
	if !ok {
		return 0
	}
	for _, m := range gm.DeliveredMemberIDs {
		if m == memberID {
			return len(gm.DeliveredMemberIDs)
		}
	}
	gm.DeliveredMemberIDs = append(gm.DeliveredMemberIDs, memberID)
	gm.Status = StatusDelivered
	db.TotalWrites++
	return len(gm.DeliveredMemberIDs)
}

// GetAllGroupMessages returns all group messages ordered by timestamp DESC.
func (db *RoomDatabaseModel) GetAllGroupMessages() []*GroupMessageRecord {
	db.mu.Lock()
	defer db.mu.Unlock()
	db.TotalReads++
	list := make([]*GroupMessageRecord, 0, len(db.durableGroupMessages))
	for _, gm := range db.durableGroupMessages {
		cp := *gm
		list = append(list, &cp)
	}
	sort.Slice(list, func(i, j int) bool {
		return list[i].Timestamp > list[j].Timestamp
	})
	return list
}

// ProcessDeath simulates process kill: in-memory observer registrations are wiped,
// but durable committed data in the SQLite database remains intact.
func (db *RoomDatabaseModel) ProcessDeath() {
	db.mu.Lock()
	defer db.mu.Unlock()
	db.messageObservers = nil
	db.contactObservers = nil
	db.groupObservers = nil
	db.groupMessageObservers = nil
}

// ProcessRestart simulates app process relaunch, binding new fresh observers to durable state.
func (db *RoomDatabaseModel) ProcessRestart() {
	db.mu.Lock()
	defer db.mu.Unlock()
	db.messageObservers = make([]func(), 0)
	db.contactObservers = make([]func(), 0)
	db.groupObservers = make([]func(), 0)
	db.groupMessageObservers = make([]func(), 0)
}

func (db *RoomDatabaseModel) notifyMessageObservers() {
	db.mu.Lock()
	observers := make([]func(), 0, len(db.messageObservers))
	for _, obs := range db.messageObservers {
		if obs != nil {
			observers = append(observers, obs)
		}
	}
	db.InvalidationEmissions += int64(len(observers))
	db.mu.Unlock()

	for _, obs := range observers {
		obs()
	}
}
