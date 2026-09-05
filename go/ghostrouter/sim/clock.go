package sim

import (
	"sync"
	"time"
)

// DefaultEpoch is the baseline starting point for virtual time (2026-01-01 00:00:00 UTC).
var DefaultEpoch = time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)

// SimClock provides a deterministic virtual clock for discrete-event simulation.
// It eliminates any dependency on wall-clock time (time.Now or time.Sleep)
// and allows virtual time to advance instantly by seconds, hours, or days.
type SimClock struct {
	mu      sync.RWMutex
	current time.Time
	epoch   time.Time
}

// NewSimClock creates a new deterministic clock initialized to DefaultEpoch.
func NewSimClock() *SimClock {
	return NewSimClockAt(DefaultEpoch)
}

// NewSimClockAt creates a new deterministic clock initialized to a specific time.
func NewSimClockAt(t time.Time) *SimClock {
	return &SimClock{
		current: t,
		epoch:   t,
	}
}

// Now returns the current virtual time.
func (c *SimClock) Now() time.Time {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.current
}

// NowUnix returns the current virtual time in Unix seconds.
func (c *SimClock) NowUnix() int64 {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.current.Unix()
}

// Advance advances the virtual clock by the given duration.
// Duration must not be negative.
func (c *SimClock) Advance(d time.Duration) time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	if d > 0 {
		c.current = c.current.Add(d)
	}
	return c.current
}

// Set sets the virtual clock to a specific time.
func (c *SimClock) Set(t time.Time) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.current = t
}

// Elapsed returns the total virtual duration elapsed since clock creation.
func (c *SimClock) Elapsed() time.Duration {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.current.Sub(c.epoch)
}

// Since returns the virtual duration elapsed since virtual time t.
func (c *SimClock) Since(t time.Time) time.Duration {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.current.Sub(t)
}
