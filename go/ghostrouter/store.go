package ghostrouter

import (
	"bytes"
	"encoding/json"
	"fmt"
	"os"
	"sort"
	"time"

	"go.etcd.io/bbolt"
)

var (
	bucketMessages = []byte("messages")
	bucketPeers    = []byte("peers")
)

// MessageStore persists messages and peers to a single BoltDB file.
type MessageStore struct {
	db      *bbolt.DB
	path    string
	localID []byte
	timeNow func() time.Time
}

// SetLocalID configures the local node identity for storage protection invariants.
func (s *MessageStore) SetLocalID(id []byte) {
	s.localID = make([]byte, len(id))
	copy(s.localID, id)
}

// SetTimeProvider overrides wall-clock time for deterministic testing and simulation.
func (s *MessageStore) SetTimeProvider(fn func() time.Time) {
	s.timeNow = fn
}

func (s *MessageStore) now() time.Time {
	if s.timeNow != nil {
		return s.timeNow()
	}
	return time.Now()
}

// OpenStore creates/opens the DB at the given path.
func OpenStore(path string) (*MessageStore, error) {
	db, err := bbolt.Open(path, 0600, &bbolt.Options{Timeout: 1 * time.Second})
	if err != nil {
		// Attempt recovery: backup corrupt DB and reinitialize clean DB
		corruptPath := fmt.Sprintf("%s.corrupt.%d", path, time.Now().UnixNano())
		_ = os.Rename(path, corruptPath)
		db, err = bbolt.Open(path, 0600, &bbolt.Options{Timeout: 1 * time.Second})
		if err != nil {
			return nil, fmt.Errorf("failed to open BoltDB at %s after recovery: %w", path, err)
		}
	}

	// Create buckets if they don't exist
	err = db.Update(func(tx *bbolt.Tx) error {
		if _, err := tx.CreateBucketIfNotExists(bucketMessages); err != nil {
			return err
		}
		if _, err := tx.CreateBucketIfNotExists(bucketPeers); err != nil {
			return err
		}
		return nil
	})
	if err != nil {
		db.Close()
		corruptPath := fmt.Sprintf("%s.corrupt.%d", path, time.Now().UnixNano())
		_ = os.Rename(path, corruptPath)
		db, err = bbolt.Open(path, 0600, &bbolt.Options{Timeout: 1 * time.Second})
		if err != nil {
			return nil, fmt.Errorf("failed to re-open BoltDB at %s: %w", path, err)
		}
		err = db.Update(func(tx *bbolt.Tx) error {
			if _, err := tx.CreateBucketIfNotExists(bucketMessages); err != nil {
				return err
			}
			if _, err := tx.CreateBucketIfNotExists(bucketPeers); err != nil {
				return err
			}
			return nil
		})
		if err != nil {
			db.Close()
			return nil, fmt.Errorf("failed to create buckets: %w", err)
		}
	}

	return &MessageStore{db: db, path: path}, nil
}

// Close closes the DB.
func (s *MessageStore) Close() error {
	if s.db != nil {
		return s.db.Close()
	}
	return nil
}

// SaveMessage stores a message. Key = message ID.
// Enforces destination quota for transit messages (max 50 messages per Dst)
// to prevent rogue peers from monopolizing storage.
func (s *MessageStore) SaveMessage(msg *Message) error {
	data, err := json.Marshal(msg)
	if err != nil {
		return fmt.Errorf("failed to marshal message: %w", err)
	}

	const maxMessagesPerDst = 50

	return s.db.Update(func(tx *bbolt.Tx) error {
		b := tx.Bucket(bucketMessages)

		// Enforce destination quota only on transit relay messages (not local authored or local inbound)
		if len(s.localID) > 0 && !bytes.Equal(msg.Src, s.localID) && !bytes.Equal(msg.Dst, s.localID) {
			type dstEntry struct {
				id        []byte
				createdAt int64
			}
			var dstRelayMsgs []dstEntry
			dstHex := fmt.Sprintf("%x", msg.Dst)

			b.ForEach(func(k, v []byte) error {
				var existing Message
				if err := json.Unmarshal(v, &existing); err == nil {
					if fmt.Sprintf("%x", existing.Dst) == dstHex && !bytes.Equal(existing.Src, s.localID) && !bytes.Equal(existing.Dst, s.localID) {
						idCopy := make([]byte, len(k))
						copy(idCopy, k)
						dstRelayMsgs = append(dstRelayMsgs, dstEntry{id: idCopy, createdAt: existing.CreatedAt})
					}
				}
				return nil
			})

			if len(dstRelayMsgs) >= maxMessagesPerDst {
				sort.Slice(dstRelayMsgs, func(i, j int) bool {
					return dstRelayMsgs[i].createdAt < dstRelayMsgs[j].createdAt
				})
				// Evict oldest relay message for this destination
				_ = b.Delete(dstRelayMsgs[0].id)
			}
		}

		return b.Put(msg.ID, data)
	})
}

// GetMessage retrieves a message by ID.
func (s *MessageStore) GetMessage(id []byte) (*Message, error) {
	var msg Message
	err := s.db.View(func(tx *bbolt.Tx) error {
		b := tx.Bucket(bucketMessages)
		data := b.Get(id)
		if data == nil {
			return fmt.Errorf("message not found")
		}
		return json.Unmarshal(data, &msg)
	})
	if err != nil {
		return nil, err
	}
	return &msg, nil
}

// GetPendingMessages returns all messages with Status == StatusPending or StatusSprayed.
func (s *MessageStore) GetPendingMessages() ([]*Message, error) {
	var msgs []*Message
	err := s.db.View(func(tx *bbolt.Tx) error {
		b := tx.Bucket(bucketMessages)
		return b.ForEach(func(k, v []byte) error {
			var msg Message
			if err := json.Unmarshal(v, &msg); err != nil {
				return nil // skip corrupt entries
			}
			if msg.Status == StatusPending || msg.Status == StatusSprayed {
				msgs = append(msgs, &msg)
			}
			return nil
		})
	})
	return msgs, err
}

// GetMessagesForDst returns all pending/sprayed messages where Dst matches.
func (s *MessageStore) GetMessagesForDst(dst []byte) ([]*Message, error) {
	var msgs []*Message
	dstHex := fmt.Sprintf("%x", dst)
	err := s.db.View(func(tx *bbolt.Tx) error {
		b := tx.Bucket(bucketMessages)
		return b.ForEach(func(k, v []byte) error {
			var msg Message
			if err := json.Unmarshal(v, &msg); err != nil {
				return nil
			}
			if fmt.Sprintf("%x", msg.Dst) == dstHex &&
				(msg.Status == StatusPending || msg.Status == StatusSprayed) {
				msgs = append(msgs, &msg)
			}
			return nil
		})
	})
	return msgs, err
}

// UpdateMessageStatus updates status by ID.
func (s *MessageStore) UpdateMessageStatus(id []byte, status int) error {
	return s.db.Update(func(tx *bbolt.Tx) error {
		b := tx.Bucket(bucketMessages)
		data := b.Get(id)
		if data == nil {
			return fmt.Errorf("message not found")
		}
		var msg Message
		if err := json.Unmarshal(data, &msg); err != nil {
			return err
		}
		msg.Status = status
		updated, err := json.Marshal(msg)
		if err != nil {
			return err
		}
		return b.Put(id, updated)
	})
}

// DeleteMessage removes a message by ID.
func (s *MessageStore) DeleteMessage(id []byte) error {
	return s.db.Update(func(tx *bbolt.Tx) error {
		b := tx.Bucket(bucketMessages)
		return b.Delete(id)
	})
}

// DeleteExpired removes all messages where CreatedAt + TTLSeconds < now.
// Returns the number of messages deleted.
func (s *MessageStore) DeleteExpired(nowUnix int64) (int, error) {
	var toDelete [][]byte
	err := s.db.View(func(tx *bbolt.Tx) error {
		b := tx.Bucket(bucketMessages)
		return b.ForEach(func(k, v []byte) error {
			var msg Message
			if err := json.Unmarshal(v, &msg); err != nil {
				return nil
			}
			if msg.CreatedAt+msg.TTLSeconds < nowUnix {
				id := make([]byte, len(k))
				copy(id, k)
				toDelete = append(toDelete, id)
			}
			return nil
		})
	})
	if err != nil {
		return 0, err
	}

	if len(toDelete) == 0 {
		return 0, nil
	}

	err = s.db.Update(func(tx *bbolt.Tx) error {
		b := tx.Bucket(bucketMessages)
		for _, id := range toDelete {
			if err := b.Delete(id); err != nil {
				return err
			}
		}
		return nil
	})
	return len(toDelete), err
}

// PruneIfNeeded checks the number of stored messages and prunes if over limit.
// Invariant: Never evict locally authored unsent messages (Src == localID && Status == StatusPending).
// Eviction order:
// 1. Expired messages (CreatedAt + TTLSeconds < now)
// 2. Completed delivered messages (Status == StatusDelivered)
// 3. Oldest transit relay messages (Src != localID)
// 4. Oldest local messages with no copies remaining (CopiesRemaining <= 1)
func (s *MessageStore) PruneIfNeeded() error {
	const maxMessages = 500
	const pruneKeep = 400

	type pruneCandidate struct {
		id        []byte
		createdAt int64
		priority  int // 1: expired, 2: delivered, 3: relay, 4: sprayed-exhausted
	}

	now := s.now().Unix()
	var candidates []pruneCandidate
	totalCount := 0

	err := s.db.View(func(tx *bbolt.Tx) error {
		b := tx.Bucket(bucketMessages)
		return b.ForEach(func(k, v []byte) error {
			totalCount++
			var msg Message
			if err := json.Unmarshal(v, &msg); err != nil {
				return nil
			}
			id := make([]byte, len(k))
			copy(id, k)

			isLocal := len(s.localID) > 0 && bytes.Equal(msg.Src, s.localID)

			switch {
			case msg.CreatedAt+msg.TTLSeconds < now:
				candidates = append(candidates, pruneCandidate{id: id, createdAt: msg.CreatedAt, priority: 1})
			case msg.Status == StatusDelivered:
				candidates = append(candidates, pruneCandidate{id: id, createdAt: msg.CreatedAt, priority: 2})
			case !isLocal:
				// Relay / transit message
				candidates = append(candidates, pruneCandidate{id: id, createdAt: msg.CreatedAt, priority: 3})
			case isLocal && msg.CopiesRemaining <= 1 && msg.Status == StatusSprayed:
				// Local message that was already sprayed to carriers
				candidates = append(candidates, pruneCandidate{id: id, createdAt: msg.CreatedAt, priority: 4})
			default:
				// Local pending messages are strictly PROTECTED from eviction
			}
			return nil
		})
	})
	if err != nil {
		return err
	}

	if totalCount <= maxMessages {
		return nil
	}

	// Sort candidates by priority ascending (1 first, then 2, then 3, then 4),
	// and by CreatedAt ascending within each priority level.
	sort.Slice(candidates, func(i, j int) bool {
		if candidates[i].priority != candidates[j].priority {
			return candidates[i].priority < candidates[j].priority
		}
		return candidates[i].createdAt < candidates[j].createdAt
	})

	deleteTarget := totalCount - pruneKeep
	if deleteTarget > len(candidates) {
		deleteTarget = len(candidates)
	}

	toDelete := candidates[:deleteTarget]

	return s.db.Update(func(tx *bbolt.Tx) error {
		b := tx.Bucket(bucketMessages)
		for _, c := range toDelete {
			if err := b.Delete(c.id); err != nil {
				return err
			}
		}
		return nil
	})
}

// SavePeer stores/updates peer info.
func (s *MessageStore) SavePeer(peer *PeerInfo) error {
	data, err := json.Marshal(peer)
	if err != nil {
		return err
	}
	return s.db.Update(func(tx *bbolt.Tx) error {
		b := tx.Bucket(bucketPeers)
		return b.Put(peer.ID, data)
	})
}

// GetPeer retrieves peer by ID.
func (s *MessageStore) GetPeer(id []byte) (*PeerInfo, error) {
	var peer PeerInfo
	err := s.db.View(func(tx *bbolt.Tx) error {
		b := tx.Bucket(bucketPeers)
		data := b.Get(id)
		if data == nil {
			return fmt.Errorf("peer not found")
		}
		return json.Unmarshal(data, &peer)
	})
	if err != nil {
		return nil, err
	}
	return &peer, nil
}

// GetAllPeers returns every known peer.
func (s *MessageStore) GetAllPeers() ([]*PeerInfo, error) {
	var peers []*PeerInfo
	err := s.db.View(func(tx *bbolt.Tx) error {
		b := tx.Bucket(bucketPeers)
		return b.ForEach(func(k, v []byte) error {
			var peer PeerInfo
			if err := json.Unmarshal(v, &peer); err != nil {
				return nil
			}
			peers = append(peers, &peer)
			return nil
		})
	})
	return peers, err
}

// DeleteStalePeers removes peers not seen in the given duration (Unix millis).
func (s *MessageStore) DeleteStalePeers(cutoffMillis int64) (int, error) {
	var toDelete [][]byte
	err := s.db.View(func(tx *bbolt.Tx) error {
		b := tx.Bucket(bucketPeers)
		return b.ForEach(func(k, v []byte) error {
			var peer PeerInfo
			if err := json.Unmarshal(v, &peer); err != nil {
				return nil
			}
			if peer.LastSeen < cutoffMillis {
				id := make([]byte, len(k))
				copy(id, k)
				toDelete = append(toDelete, id)
			}
			return nil
		})
	})
	if err != nil {
		return 0, err
	}

	if len(toDelete) == 0 {
		return 0, nil
	}

	err = s.db.Update(func(tx *bbolt.Tx) error {
		b := tx.Bucket(bucketPeers)
		for _, id := range toDelete {
			if err := b.Delete(id); err != nil {
				return err
			}
		}
		return nil
	})
	return len(toDelete), err
}

// MessageCount returns the number of messages in the store.
func (s *MessageStore) MessageCount() int {
	count := 0
	s.db.View(func(tx *bbolt.Tx) error {
		b := tx.Bucket(bucketMessages)
		count = b.Stats().KeyN
		return nil
	})
	return count
}

// PeerCount returns the number of peers in the store.
func (s *MessageStore) PeerCount() int {
	count := 0
	s.db.View(func(tx *bbolt.Tx) error {
		b := tx.Bucket(bucketPeers)
		count = b.Stats().KeyN
		return nil
	})
	return count
}
