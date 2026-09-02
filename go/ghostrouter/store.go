package ghostrouter

import (
	"encoding/json"
	"fmt"
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
	db   *bbolt.DB
	path string
}

// OpenStore creates/opens the DB at the given path.
func OpenStore(path string) (*MessageStore, error) {
	db, err := bbolt.Open(path, 0600, &bbolt.Options{Timeout: 1 * time.Second})
	if err != nil {
		return nil, fmt.Errorf("failed to open BoltDB at %s: %w", path, err)
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
		return nil, fmt.Errorf("failed to create buckets: %w", err)
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
func (s *MessageStore) SaveMessage(msg *Message) error {
	data, err := json.Marshal(msg)
	if err != nil {
		return fmt.Errorf("failed to marshal message: %w", err)
	}

	return s.db.Update(func(tx *bbolt.Tx) error {
		b := tx.Bucket(bucketMessages)
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

// PruneIfNeeded checks the number of stored messages and deletes oldest if over limit.
// NOTE: We count records instead of using os.Stat file size because BoltDB never
// shrinks its file on disk — deleted pages are reused internally.
func (s *MessageStore) PruneIfNeeded() error {
	const maxMessages = 500
	const pruneKeep = 400

	// Get all messages sorted by CreatedAt
	type msgEntry struct {
		ID        []byte
		CreatedAt int64
	}
	var entries []msgEntry

	err := s.db.View(func(tx *bbolt.Tx) error {
		b := tx.Bucket(bucketMessages)
		return b.ForEach(func(k, v []byte) error {
			var msg Message
			if err := json.Unmarshal(v, &msg); err != nil {
				return nil
			}
			id := make([]byte, len(k))
			copy(id, k)
			entries = append(entries, msgEntry{ID: id, CreatedAt: msg.CreatedAt})
			return nil
		})
	})
	if err != nil {
		return err
	}

	if len(entries) <= maxMessages {
		return nil
	}

	// Sort oldest first
	sort.Slice(entries, func(i, j int) bool {
		return entries[i].CreatedAt < entries[j].CreatedAt
	})

	// Delete oldest entries to get back to pruneKeep
	deleteCount := len(entries) - pruneKeep
	toDelete := entries[:deleteCount]

	return s.db.Update(func(tx *bbolt.Tx) error {
		b := tx.Bucket(bucketMessages)
		for _, e := range toDelete {
			if err := b.Delete(e.ID); err != nil {
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
