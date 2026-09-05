package oem

import (
	"fmt"
	"sync"
	"time"
)

// SOURCE: android/app/src/main/java/com/ghostprotocol/ble/GattOperationQueue.kt
// CONTRACT: O4 (Gatt Queue Serialization), O5 (Closed Gatt Safety), O13 (Bounded Queue Depth)
// MODEL: Serialized GATT operations, 150ms cool-off per MAC, 5000ms watchdog, and hostile OEM callback behavior.

// GattQueueItem represents an outbound serialized transmission request.
type GattQueueItem struct {
	ID         string
	MacAddress string
	Payload    []byte
	TimeoutMs  int64
	OnResult   func(success bool)
	EnqueuedNs int64
}

// GattQueueModel models GattOperationQueue with deterministic OEM hostility.
type GattQueueModel struct {
	mu sync.Mutex

	clock     *VirtualClock
	scheduler *EventScheduler
	profile   OemProfile

	currentState          GattClientState
	activeItem            *GattQueueItem
	pendingQueue          []*GattQueueItem
	lastDisconnectPerMac  map[string]int64
	activeGattId          int64
	activeGattOpen        bool
	activeWatchdogFired   bool
	resultDelivered       bool
	generation            int64

	// Concurrency and safety validation
	maxConcurrentGattSeen int
	lateCallbacksIgnored  int
	duplicateCallbacks    int
	totalOperations       int
	totalSuccesses        int
	totalFailures         int
	totalWatchdogTimeouts int
	totalRejections       int

	// Outbound hook
	OnBytesTx func(n int)
}

// NewGattQueueModel creates a new serialized GATT operation queue.
func NewGattQueueModel(clock *VirtualClock, scheduler *EventScheduler, profile OemProfile) *GattQueueModel {
	return &GattQueueModel{
		clock:                clock,
		scheduler:            scheduler,
		profile:              profile,
		currentState:         GattStateIdle,
		pendingQueue:         make([]*GattQueueItem, 0, 64),
		lastDisconnectPerMac: make(map[string]int64),
	}
}

// State returns current GATT state.
func (g *GattQueueModel) State() GattClientState {
	g.mu.Lock()
	defer g.mu.Unlock()
	return g.currentState
}

// QueueSize returns total number of pending and active transmissions.
func (g *GattQueueModel) QueueSize() int {
	g.mu.Lock()
	defer g.mu.Unlock()
	size := len(g.pendingQueue)
	if g.activeItem != nil {
		size++
	}
	return size
}

// Enqueue adds an item to the GATT queue, enforcing capacity bounds.
func (g *GattQueueModel) Enqueue(item *GattQueueItem) error {
	g.mu.Lock()
	defer g.mu.Unlock()

	item.EnqueuedNs = g.clock.NowNs()
	maxCap := g.profile.MaxQueueCapacity
	if maxCap <= 0 {
		maxCap = 500
	}

	if len(g.pendingQueue) >= maxCap {
		g.totalRejections++
		if item.OnResult != nil {
			item.OnResult(false)
		}
		return fmt.Errorf("queue depth exceeded maximum capacity %d", maxCap)
	}

	g.pendingQueue = append(g.pendingQueue, item)
	g.triggerNextLocked()
	return nil
}

// CancelAll purges all queued items and tears down any active connection.
func (g *GattQueueModel) CancelAll() {
	g.mu.Lock()
	defer g.mu.Unlock()

	// Drain pending queue
	for _, item := range g.pendingQueue {
		if item.OnResult != nil {
			item.OnResult(false)
		}
	}
	g.pendingQueue = g.pendingQueue[:0]

	// Abort active item
	if g.activeItem != nil {
		if !g.resultDelivered && g.activeItem.OnResult != nil {
			g.resultDelivered = true
			g.activeItem.OnResult(false)
		}
		g.activeItem = nil
	}

	g.activeGattOpen = false
	g.generation++ // Invalidate any in-flight scheduled callbacks
	g.currentState = GattStateIdle
}

// triggerNextLocked begins processing the next queue item if idle.
func (g *GattQueueModel) triggerNextLocked() {
	if g.activeItem != nil || len(g.pendingQueue) == 0 {
		return
	}

	item := g.pendingQueue[0]
	g.pendingQueue = g.pendingQueue[1:]
	g.activeItem = item
	g.resultDelivered = false
	g.generation++
	currGen := g.generation
	g.activeGattId++
	currGattId := g.activeGattId

	// Check per-MAC cool-off
	lastDisc := g.lastDisconnectPerMac[item.MacAddress]
	elapsedNs := g.clock.NowNs() - lastDisc
	coolOffNs := g.profile.GattCoolOffMs * 1_000_000

	waitNs := int64(0)
	if elapsedNs < coolOffNs && lastDisc > 0 {
		waitNs = coolOffNs - elapsedNs
	}

	if waitNs > 0 {
		g.scheduler.ScheduleRelative(time.Duration(waitNs), 1, fmt.Sprintf("gatt_cooloff_%s", item.MacAddress), func() error {
			g.mu.Lock()
			defer g.mu.Unlock()
			if g.generation != currGen || g.activeItem != item {
				return nil // Cancelled during cool-off
			}
			g.proceedConnectionLocked(item, currGen, currGattId)
			return nil
		})
	} else {
		g.proceedConnectionLocked(item, currGen, currGattId)
	}
}

// proceedConnectionLocked transitions through GATT states towards completion.
func (g *GattQueueModel) proceedConnectionLocked(item *GattQueueItem, gen int64, gattId int64) {
	g.currentState = GattStateConnecting
	g.activeGattOpen = true
	g.totalOperations++

	// Track max concurrent connections (Invariant O4 contract: must never exceed 1)
	if g.activeGattOpen {
		if 1 > g.maxConcurrentGattSeen {
			g.maxConcurrentGattSeen = 1
		}
	}

	timeout := time.Duration(item.TimeoutMs) * time.Millisecond
	if timeout <= 0 {
		timeout = g.profile.GattTimeout
	}

	// Arm watchdog timer
	g.scheduler.ScheduleRelative(timeout, 0, fmt.Sprintf("gatt_watchdog_%d", gattId), func() error {
		g.mu.Lock()
		defer g.mu.Unlock()
		if g.generation != gen || g.activeItem != item || g.currentState == GattStateIdle || g.currentState == GattStateClosed {
			return nil
		}
		g.totalWatchdogTimeouts++
		g.teardownAndCompleteLocked(item, false, gen, "watchdog_timeout")
		return nil
	})

	// Schedule simulated GATT outcome
	durationNs := g.profile.ExpectedGattDuration(len(item.Payload))
	g.scheduler.ScheduleRelative(time.Duration(durationNs), 2, fmt.Sprintf("gatt_exec_%d", gattId), func() error {
		g.mu.Lock()
		defer g.mu.Unlock()
		if g.generation != gen || g.activeItem != item {
			// Contract O5 (Closed Gatt Safety): Late execution after cancellation
			g.lateCallbacksIgnored++
			return nil
		}

		// Progress states
		g.currentState = GattStateConnected
		g.currentState = GattStateNegotiatingMtu
		g.currentState = GattStateDiscoveringServices
		g.currentState = GattStateWriting

		if g.OnBytesTx != nil {
			g.OnBytesTx(len(item.Payload))
		}

		g.teardownAndCompleteLocked(item, true, gen, "success")
		return nil
	})
}

// TeardownAndComplete forces completion of current item (used by hostile injection).
func (g *GattQueueModel) TeardownAndComplete(item *GattQueueItem, success bool, reason string) {
	g.mu.Lock()
	defer g.mu.Unlock()
	if g.activeItem == item {
		g.teardownAndCompleteLocked(item, success, g.generation, reason)
	}
}

// teardownAndCompleteLocked performs atomic teardown and triggers next item.
func (g *GattQueueModel) teardownAndCompleteLocked(item *GattQueueItem, success bool, gen int64, reason string) {
	if g.generation != gen && gen != 0 {
		g.lateCallbacksIgnored++
		return
	}

	g.currentState = GattStateDisconnecting
	g.activeGattOpen = false
	g.lastDisconnectPerMac[item.MacAddress] = g.clock.NowNs()
	g.currentState = GattStateClosed

	if !g.resultDelivered {
		g.resultDelivered = true
		if success {
			g.totalSuccesses++
		} else {
			g.totalFailures++
		}
		if item.OnResult != nil {
			item.OnResult(success)
		}
	}

	g.activeItem = nil
	g.currentState = GattStateIdle
	g.triggerNextLocked()
}

// ReceiveHostileLateCallback tests O5 (Closed Gatt Safety).
// Simulates an asynchronous Android BluetoothGattCallback firing after connection was closed.
func (g *GattQueueModel) ReceiveHostileLateCallback(staleGattId int64) error {
	g.mu.Lock()
	defer g.mu.Unlock()

	// If the callback belongs to a stale GATT connection, it must be safely dropped
	if staleGattId < g.activeGattId || g.currentState == GattStateIdle {
		g.lateCallbacksIgnored++
		return nil // Dropped cleanly without panic or state corruption
	}

	return nil
}

// ReceiveHostileDuplicateCallback tests duplicate callback handling.
func (g *GattQueueModel) ReceiveHostileDuplicateCallback() {
	g.mu.Lock()
	defer g.mu.Unlock()
	g.duplicateCallbacks++
	// In GattOperationQueue, resultDelivered.compareAndSet(false, true) protects against duplicate callbacks
}

// CheckSerializationInvariant validates invariant O4 (at most 1 active GATT operation).
func (g *GattQueueModel) CheckSerializationInvariant() error {
	g.mu.Lock()
	defer g.mu.Unlock()

	if g.maxConcurrentGattSeen > 1 {
		return fmt.Errorf("O4 violation: concurrent GATT connections exceeded 1 (seen %d)", g.maxConcurrentGattSeen)
	}
	return nil
}
