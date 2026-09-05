package oem

import (
	"container/heap"
	"fmt"
	"sync"
	"time"
)

// VirtualClock provides discrete, deterministic time advancing strictly monotonically.
type VirtualClock struct {
	mu  sync.Mutex
	now int64 // Nanoseconds since epoch
}

// NewVirtualClock creates a clock initialized to a deterministic starting time (100ms).
func NewVirtualClock() *VirtualClock {
	return &VirtualClock{
		now: 100_000_000, // Start at 100ms virtual time
	}
}

// NowNs returns the current virtual time in nanoseconds.
func (c *VirtualClock) NowNs() int64 {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.now
}

// Advance moves the virtual clock forward by the given duration.
func (c *VirtualClock) Advance(d time.Duration) int64 {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.now += d.Nanoseconds()
	return c.now
}

// AdvanceTo moves the virtual clock forward to targetNs if targetNs > now.
func (c *VirtualClock) AdvanceTo(targetNs int64) int64 {
	c.mu.Lock()
	defer c.mu.Unlock()
	if targetNs > c.now {
		c.now = targetNs
	}
	return c.now
}

// ScheduledTask represents an item in the event scheduler priority queue.
type ScheduledTask struct {
	ID          string
	ExecutionNs int64
	Priority    int   // Lower value = higher priority
	Sequence    int64 // Insertion order tie-breaker
	Action      func() error
	index       int   // Index inside the heap
}

// taskHeap implements heap.Interface for ScheduledTask.
type taskHeap []*ScheduledTask

func (h taskHeap) Len() int { return len(h) }
func (h taskHeap) Less(i, j int) bool {
	if h[i].ExecutionNs != h[j].ExecutionNs {
		return h[i].ExecutionNs < h[j].ExecutionNs
	}
	if h[i].Priority != h[j].Priority {
		return h[i].Priority < h[j].Priority
	}
	return h[i].Sequence < h[j].Sequence
}
func (h taskHeap) Swap(i, j int) {
	h[i], h[j] = h[j], h[i]
	h[i].index = i
	h[j].index = j
}
func (h *taskHeap) Push(x interface{}) {
	n := len(*h)
	item := x.(*ScheduledTask)
	item.index = n
	*h = append(*h, item)
}
func (h *taskHeap) Pop() interface{} {
	old := *h
	n := len(old)
	item := old[n-1]
	old[n-1] = nil
	item.index = -1
	*h = old[0 : n-1]
	return item
}

// EventScheduler orders and executes events deterministically.
type EventScheduler struct {
	mu       sync.Mutex
	clock    *VirtualClock
	queue    taskHeap
	seqCount int64
}

// NewEventScheduler creates a scheduler bound to a virtual clock.
func NewEventScheduler(clock *VirtualClock) *EventScheduler {
	h := make(taskHeap, 0, 128)
	heap.Init(&h)
	return &EventScheduler{
		clock: clock,
		queue: h,
	}
}

// Schedule enqueues an action at an absolute virtual timestamp.
func (s *EventScheduler) Schedule(execNs int64, priority int, id string, action func() error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	s.seqCount++
	task := &ScheduledTask{
		ID:          id,
		ExecutionNs: execNs,
		Priority:    priority,
		Sequence:    s.seqCount,
		Action:      action,
	}
	heap.Push(&s.queue, task)
}

// ScheduleRelative enqueues an action relative to current virtual time.
func (s *EventScheduler) ScheduleRelative(delay time.Duration, priority int, id string, action func() error) {
	execNs := s.clock.NowNs() + delay.Nanoseconds()
	s.Schedule(execNs, priority, id, action)
}

// PendingCount returns the number of scheduled tasks waiting to execute.
func (s *EventScheduler) PendingCount() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return len(s.queue)
}

// Step pops and executes the next earliest event, advancing the virtual clock.
func (s *EventScheduler) Step() (bool, error) {
	s.mu.Lock()
	if len(s.queue) == 0 {
		s.mu.Unlock()
		return false, nil
	}
	task := heap.Pop(&s.queue).(*ScheduledTask)
	s.mu.Unlock()

	// Advance virtual time strictly forward to event execution time
	s.clock.AdvanceTo(task.ExecutionNs)

	if task.Action != nil {
		if err := task.Action(); err != nil {
			return true, fmt.Errorf("task %s failed at %dns: %w", task.ID, task.ExecutionNs, err)
		}
	}
	return true, nil
}

// RunUntil advances and runs all events scheduled up to untilNs.
func (s *EventScheduler) RunUntil(untilNs int64) error {
	for {
		s.mu.Lock()
		if len(s.queue) == 0 || s.queue[0].ExecutionNs > untilNs {
			s.mu.Unlock()
			break
		}
		s.mu.Unlock()

		_, err := s.Step()
		if err != nil {
			return err
		}
	}
	s.clock.AdvanceTo(untilNs)
	return nil
}

// RunAll drains all queued events in the scheduler.
func (s *EventScheduler) RunAll() error {
	for {
		hasMore, err := s.Step()
		if err != nil {
			return err
		}
		if !hasMore {
			break
		}
	}
	return nil
}
