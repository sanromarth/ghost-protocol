package ghostrouter

import "sync"

// DedupCache is a thread-safe, bounded in-memory cache with deterministic TTL expiration.
// Used to deduplicate delivered messages and prevent unbounded memory growth.
type DedupCache struct {
	mu         sync.Mutex
	maxEntries int
	ttlSecs    int64
	entries    map[string]int64 // idHex -> timestampUnix
	ring       []string         // circular ring buffer of keys for O(1) FIFO eviction
	head       int
	count      int
}

// NewDedupCache creates a bounded cache with maxEntries and ttlSecs.
func NewDedupCache(maxEntries int, ttlSecs int64) *DedupCache {
	if maxEntries <= 0 {
		maxEntries = 2048
	}
	if ttlSecs <= 0 {
		ttlSecs = 86400 // 24 hours default
	}
	return &DedupCache{
		maxEntries: maxEntries,
		ttlSecs:    ttlSecs,
		entries:    make(map[string]int64, maxEntries),
		ring:       make([]string, maxEntries),
	}
}

// Seen returns true if the ID was recorded within the TTL window.
func (c *DedupCache) Seen(id string, nowUnix int64) bool {
	c.mu.Lock()
	defer c.mu.Unlock()

	ts, exists := c.entries[id]
	if !exists {
		return false
	}
	if nowUnix-ts > c.ttlSecs {
		delete(c.entries, id)
		return false
	}
	return true
}

// Add records an ID in the cache with the current timestamp.
// If capacity is reached, the oldest inserted ID is evicted in O(1).
func (c *DedupCache) Add(id string, nowUnix int64) {
	c.mu.Lock()
	defer c.mu.Unlock()

	if _, exists := c.entries[id]; exists {
		c.entries[id] = nowUnix
		return
	}

	if c.count >= c.maxEntries {
		oldKey := c.ring[c.head]
		if oldKey != "" {
			delete(c.entries, oldKey)
		}
		c.ring[c.head] = id
		c.entries[id] = nowUnix
		c.head = (c.head + 1) % c.maxEntries
	} else {
		idx := (c.head + c.count) % c.maxEntries
		c.ring[idx] = id
		c.entries[id] = nowUnix
		c.count++
	}
}

// Size returns the active number of tracked entries.
func (c *DedupCache) Size() int {
	c.mu.Lock()
	defer c.mu.Unlock()
	return len(c.entries)
}
