package ux

import (
	"fmt"
	"sync"
	"time"
)

// SOURCE: android/app/src/main/java/com/ghostprotocol/ble/GattOperationQueue.kt
// CONTRACT: U7, U10
// MODEL: Serialized GATT client queue simulation enforcing single-operation serialization,
// strict state progression, per-MAC cool-off intervals, and timeout teardown.

// GattState mirrors GattOperationQueue.GattState.
type GattState string

const (
	GattIdle                GattState = "IDLE"
	GattConnecting          GattState = "CONNECTING"
	GattConnected           GattState = "CONNECTED"
	GattNegotiatingMtu      GattState = "NEGOTIATING_MTU"
	GattDiscoveringServices GattState = "DISCOVERING_SERVICES"
	GattWriting             GattState = "WRITING"
	GattDisconnecting       GattState = "DISCONNECTING"
	GattClosed              GattState = "CLOSED"
)

// GattItem represents an enqueued transmission request.
type GattItem struct {
	ID         string
	MacAddress string
	PayloadLen int
	TimeoutMs  int64
	EnqueuedAt int64
	StartedAt  int64
	OnResult   func(success bool)
}

// GattQueueModel simulates the serialized Android BluetoothGatt client pipeline.
type GattQueueModel struct {
	mu sync.Mutex

	clock       *VirtualClock
	scheduler   *EventScheduler
	profile     DeviceProfile
	state       GattState
	queue       []*GattItem
	currentItem *GattItem
	isRunning   bool

	// Cool-off map: MAC address -> timestamp of last disconnect in ns
	lastDisconnectPerMac map[string]int64

	// Metrics
	TotalEnqueued     int64
	TotalCompleted    int64
	TotalFailed       int64
	TotalTimeouts     int64
	PeakQueueDepth    int
	TotalWaitDuration time.Duration
	TotalOpDuration   time.Duration

	// Fault injection hooks
	ForceFailureRate float64
	ForceTimeout     bool
}

// NewGattQueueModel creates a GATT queue attached to the virtual clock and scheduler.
func NewGattQueueModel(clock *VirtualClock, scheduler *EventScheduler, profile DeviceProfile) *GattQueueModel {
	return &GattQueueModel{
		clock:                clock,
		scheduler:            scheduler,
		profile:              profile,
		state:                GattIdle,
		queue:                make([]*GattItem, 0, 64),
		lastDisconnectPerMac: make(map[string]int64),
	}
}

// QueueDepth returns the current depth (pending queue + active item).
func (g *GattQueueModel) QueueDepth() int {
	g.mu.Lock()
	defer g.mu.Unlock()
	d := len(g.queue)
	if g.currentItem != nil {
		d++
	}
	return d
}

// State returns current GATT state machine phase.
func (g *GattQueueModel) State() GattState {
	g.mu.Lock()
	defer g.mu.Unlock()
	return g.state
}

// Enqueue submits a transmission request into the serialized GATT queue.
func (g *GattQueueModel) Enqueue(item *GattItem) (bool, error) {
	g.mu.Lock()
	defer g.mu.Unlock()

	item.EnqueuedAt = g.clock.NowNs()
	currentDepth := len(g.queue) + 1
	if currentDepth > g.PeakQueueDepth {
		g.PeakQueueDepth = currentDepth
	}

	// Invariant U7: Bounded event queues
	if len(g.queue) >= g.profile.MaxQueueCapacity {
		g.TotalFailed++
		if item.OnResult != nil {
			item.OnResult(false)
		}
		return false, fmt.Errorf("GATT queue capacity exceeded (%d >= %d)", len(g.queue), g.profile.MaxQueueCapacity)
	}

	g.queue = append(g.queue, item)
	g.TotalEnqueued++

	if !g.isRunning {
		g.triggerNextLocked()
	}
	return true, nil
}

// CancelAll drains the pending queue and aborts active GATT connection.
func (g *GattQueueModel) CancelAll() {
	g.mu.Lock()
	defer g.mu.Unlock()

	for _, item := range g.queue {
		if item.OnResult != nil {
			item.OnResult(false)
		}
		g.TotalFailed++
	}
	g.queue = g.queue[:0]

	if g.currentItem != nil {
		if g.currentItem.OnResult != nil {
			g.currentItem.OnResult(false)
		}
		g.TotalFailed++
		g.currentItem = nil
	}
	g.state = GattIdle
	g.isRunning = false
}

func (g *GattQueueModel) triggerNextLocked() {
	if g.isRunning || len(g.queue) == 0 {
		return
	}

	item := g.queue[0]
	g.queue = g.queue[1:]
	g.currentItem = item
	g.isRunning = true

	nowNs := g.clock.NowNs()
	item.StartedAt = nowNs
	waitTime := time.Duration(nowNs - item.EnqueuedAt)
	g.TotalWaitDuration += waitTime

	// Calculate per-MAC cool-off delay
	coolOffDelay := time.Duration(0)
	if lastDisc, ok := g.lastDisconnectPerMac[item.MacAddress]; ok {
		requiredCoolOffNs := g.profile.GattCoolOffMs * 1_000_000
		elapsedSinceDisc := nowNs - lastDisc
		if elapsedSinceDisc < requiredCoolOffNs {
			coolOffDelay = time.Duration(requiredCoolOffNs - elapsedSinceDisc)
		}
	}

	// Step 1: Connect
	g.state = GattConnecting
	g.scheduler.ScheduleRelative(coolOffDelay+g.profile.GattConnectBase, 1, "gatt_connected", func() error {
		return g.onConnected()
	})
}

func (g *GattQueueModel) onConnected() error {
	g.mu.Lock()
	defer g.mu.Unlock()

	if g.currentItem == nil {
		return nil
	}

	if g.ForceTimeout {
		g.TotalTimeouts++
		g.completeItemLocked(false)
		return nil
	}

	// Step 2: Negotiate MTU
	g.state = GattNegotiatingMtu
	g.scheduler.ScheduleRelative(g.profile.GattMtuBase, 1, "gatt_mtu", func() error {
		return g.onMtuNegotiated()
	})
	return nil
}

func (g *GattQueueModel) onMtuNegotiated() error {
	g.mu.Lock()
	defer g.mu.Unlock()

	if g.currentItem == nil {
		return nil
	}

	// Step 3: Discover Services
	g.state = GattDiscoveringServices
	g.scheduler.ScheduleRelative(g.profile.GattDiscoverBase, 1, "gatt_services", func() error {
		return g.onServicesDiscovered()
	})
	return nil
}

func (g *GattQueueModel) onServicesDiscovered() error {
	g.mu.Lock()
	defer g.mu.Unlock()

	if g.currentItem == nil {
		return nil
	}

	// Step 4: Write Characteristic
	g.state = GattWriting
	writeDuration := g.profile.GattWriteBase
	if g.currentItem.PayloadLen > 200 {
		// Batched / large payload chunk multiplier
		writeDuration += time.Duration(g.currentItem.PayloadLen/100) * (5 * time.Millisecond)
	}

	g.scheduler.ScheduleRelative(writeDuration, 1, "gatt_write_done", func() error {
		return g.onWriteCompleted()
	})
	return nil
}

func (g *GattQueueModel) onWriteCompleted() error {
	g.mu.Lock()
	defer g.mu.Unlock()

	if g.currentItem == nil {
		return nil
	}

	success := true
	if g.ForceFailureRate > 0 {
		// Fault injected failure
		success = false
	}

	// Step 5: Disconnect and teardown
	g.state = GattDisconnecting
	g.scheduler.ScheduleRelative(10*time.Millisecond, 1, "gatt_closed", func() error {
		g.mu.Lock()
		defer g.mu.Unlock()
		if g.currentItem == nil {
			g.state = GattIdle
			g.isRunning = false
			return nil
		}
		g.state = GattClosed
		g.lastDisconnectPerMac[g.currentItem.MacAddress] = g.clock.NowNs()
		g.completeItemLocked(success)
		return nil
	})
	return nil
}

func (g *GattQueueModel) completeItemLocked(success bool) {
	item := g.currentItem
	g.currentItem = nil
	g.isRunning = false
	g.state = GattIdle

	if item != nil {
		opDur := time.Duration(g.clock.NowNs() - item.StartedAt)
		g.TotalOpDuration += opDur
		if success {
			g.TotalCompleted++
		} else {
			g.TotalFailed++
		}
		if item.OnResult != nil {
			item.OnResult(success)
		}
	}

	// Trigger next enqueued request
	g.triggerNextLocked()
}
