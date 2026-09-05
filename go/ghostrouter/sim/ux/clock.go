package ux

import (
	"container/heap"
	"fmt"
	"sync"
	"time"
)

// VirtualClock provides deterministic nanosecond-precision virtual time.
// CONTRACT: Virtual time is represented as int64 nanoseconds; the scheduler
// permits events at arbitrary nanosecond timestamps without wall-clock sleeps.
type VirtualClock struct {
	mu     sync.Mutex
	timeNs int64
	start  time.Time
}

// NewVirtualClock creates a new deterministic clock starting at base time.
func NewVirtualClock() *VirtualClock {
	base := time.Date(2026, 9, 4, 12, 0, 0, 0, time.UTC)
	return &VirtualClock{
		timeNs: base.UnixNano(),
		start:  base,
	}
}

// NowNs returns the current virtual time in nanoseconds since epoch.
func (c *VirtualClock) NowNs() int64 {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.timeNs
}

// Now returns the current virtual time as a time.Time.
func (c *VirtualClock) Now() time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	return time.Unix(0, c.timeNs).UTC()
}

// Advance advances virtual time by the given duration.
func (c *VirtualClock) Advance(d time.Duration) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if d > 0 {
		c.timeNs += d.Nanoseconds()
	}
}

// SetTimeNs explicitly sets the virtual clock to a given timestamp.
func (c *VirtualClock) SetTimeNs(t int64) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if t > c.timeNs {
		c.timeNs = t
	}
}

// Elapsed returns duration elapsed since virtual clock start.
func (c *VirtualClock) Elapsed() time.Duration {
	c.mu.Lock()
	defer c.mu.Unlock()
	return time.Duration(c.timeNs - c.start.UnixNano())
}

// ScheduledEvent represents an action scheduled to execute at a specific virtual time.
type ScheduledEvent struct {
	TimestampNs int64
	Priority    int // Lower value = higher priority
	SequenceID  int64
	Name        string
	Action      func() error
	index       int
}

type eventHeap []*ScheduledEvent

func (h eventHeap) Len() int { return len(h) }
func (h eventHeap) Less(i, j int) bool {
	if h[i].TimestampNs != h[j].TimestampNs {
		return h[i].TimestampNs < h[j].TimestampNs
	}
	if h[i].Priority != h[j].Priority {
		return h[i].Priority < h[j].Priority
	}
	return h[i].SequenceID < h[j].SequenceID
}
func (h eventHeap) Swap(i, j int) {
	h[i], h[j] = h[j], h[i]
	h[i].index = i
	h[j].index = j
}
func (h *eventHeap) Push(x interface{}) {
	n := len(*h)
	item := x.(*ScheduledEvent)
	item.index = n
	*h = append(*h, item)
}
func (h *eventHeap) Pop() interface{} {
	old := *h
	n := len(old)
	item := old[n-1]
	old[n-1] = nil
	item.index = -1
	*h = old[0 : n-1]
	return item
}

// EventScheduler orchestrates discrete-event execution in strict deterministic order.
type EventScheduler struct {
	mu          sync.Mutex
	clock       *VirtualClock
	queue       eventHeap
	nextSeq     int64
	eventsFired int64
}

// NewEventScheduler initializes a deterministic event queue on the given virtual clock.
func NewEventScheduler(clock *VirtualClock) *EventScheduler {
	s := &EventScheduler{
		clock: clock,
		queue: make(eventHeap, 0, 256),
	}
	heap.Init(&s.queue)
	return s
}

// Schedule enqueues an event at a target nanosecond timestamp.
func (s *EventScheduler) Schedule(atNs int64, priority int, name string, action func() error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	s.nextSeq++
	ev := &ScheduledEvent{
		TimestampNs: atNs,
		Priority:    priority,
		SequenceID:  s.nextSeq,
		Name:        name,
		Action:      action,
	}
	heap.Push(&s.queue, ev)
}

// ScheduleRelative schedules an action relative to current virtual time.
func (s *EventScheduler) ScheduleRelative(delay time.Duration, priority int, name string, action func() error) {
	now := s.clock.NowNs()
	s.Schedule(now+delay.Nanoseconds(), priority, name, action)
}

// HasPending returns true if events are waiting in the queue.
func (s *EventScheduler) HasPending() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return len(s.queue) > 0
}

// PendingCount returns number of queued events.
func (s *EventScheduler) PendingCount() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return len(s.queue)
}

// Step executes the next single chronological event, advancing clock to its timestamp.
func (s *EventScheduler) Step() (bool, error) {
	s.mu.Lock()
	if len(s.queue) == 0 {
		s.mu.Unlock()
		return false, nil
	}

	ev := heap.Pop(&s.queue).(*ScheduledEvent)
	s.eventsFired++
	s.mu.Unlock()

	// Advance virtual time strictly to event timestamp
	s.clock.SetTimeNs(ev.TimestampNs)

	if ev.Action != nil {
		if err := ev.Action(); err != nil {
			return true, fmt.Errorf("event '%s' failed at %d ns: %w", ev.Name, ev.TimestampNs, err)
		}
	}
	return true, nil
}

// RunUntil advances and runs all events scheduled up to targetNs.
func (s *EventScheduler) RunUntil(targetNs int64) error {
	for {
		s.mu.Lock()
		if len(s.queue) == 0 || s.queue[0].TimestampNs > targetNs {
			s.mu.Unlock()
			break
		}
		s.mu.Unlock()

		ok, err := s.Step()
		if err != nil {
			return err
		}
		if !ok {
			break
		}
	}
	s.clock.SetTimeNs(targetNs)
	return nil
}

// RunAll drains the scheduler until completely empty.
func (s *EventScheduler) RunAll() error {
	for {
		ok, err := s.Step()
		if err != nil {
			return err
		}
		if !ok {
			break
		}
	}
	return nil
}
