package oem

import (
	"sync"
	"time"
)

// SOURCE: android/app/src/main/java/com/ghostprotocol/MainActivity.kt & ChatViewModel.kt
// CONTRACT: O2 (Activity Decoupling), O14 (Bounded Observer Growth)
// MODEL: Android Activity lifecycle, ViewModel scope, and Flow WhileSubscribed(5000) collector behavior.

// ActivityModel simulates MainActivity lifecycle and its interaction with ViewModels and Flows.
type ActivityModel struct {
	mu sync.Mutex

	clock     *VirtualClock
	scheduler *EventScheduler

	state               ActivityState
	activeUiCollectors  int
	viewModelActive     bool
	optimisticPending   map[string]int // messageId -> status
	selectedContactId   string
	stopTimestampNs     int64
	resubscribeTimerId  string

	// Metrics
	TotalRecreations int
	TotalPauses      int
	TotalResumes     int
	TotalDestroys    int
}

// NewActivityModel creates an Activity initialized in UNINITIALIZED state.
func NewActivityModel(clock *VirtualClock, scheduler *EventScheduler) *ActivityModel {
	return &ActivityModel{
		clock:             clock,
		scheduler:         scheduler,
		state:             ActivityStateUninitialized,
		optimisticPending: make(map[string]int),
		selectedContactId: "contact_default",
	}
}

// State returns current Activity lifecycle state.
func (a *ActivityModel) State() ActivityState {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.state
}

// ActiveCollectors returns the count of active coroutine flow collectors attached to UI.
func (a *ActivityModel) ActiveCollectors() int {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.activeUiCollectors
}

// OnCreate simulates Activity.onCreate() + ViewModel initialization.
func (a *ActivityModel) OnCreate() {
	a.mu.Lock()
	defer a.mu.Unlock()

	a.state = ActivityStateCreated
	a.viewModelActive = true
	// Baseline UI collectors (Conversation list + Chat flow when screen visible)
	a.activeUiCollectors = 2
}

// OnResume simulates Activity.onResume() (Activity in foreground, interactive).
func (a *ActivityModel) OnResume() {
	a.mu.Lock()
	defer a.mu.Unlock()

	a.state = ActivityStateResumed
	a.TotalResumes++
	if a.viewModelActive {
		a.activeUiCollectors = 2
	}
}

// OnPause simulates Activity.onPause() (Activity partially obscured or backgrounding).
func (a *ActivityModel) OnPause() {
	a.mu.Lock()
	defer a.mu.Unlock()

	a.state = ActivityStatePaused
	a.TotalPauses++
}

// OnStop simulates Activity.onStop(). Triggers WhileSubscribed(5000) timeout.
func (a *ActivityModel) OnStop() {
	a.mu.Lock()
	defer a.mu.Unlock()

	a.state = ActivityStateStopped
	a.stopTimestampNs = a.clock.NowNs()

	// WhileSubscribed(5000ms): flow collectors remain active for 5s, then teardown
	a.scheduler.ScheduleRelative(5000*time.Millisecond, 1, "while_subscribed_timeout", func() error {
		a.mu.Lock()
		defer a.mu.Unlock()
		if a.state == ActivityStateStopped || a.state == ActivityStateDestroyed {
			// Subscriptions cancelled to prevent memory leaks when app backgrounded
			a.activeUiCollectors = 0
		}
		return nil
	})
}

// OnDestroy simulates Activity.onDestroy() (Activity tear-down).
func (a *ActivityModel) OnDestroy() {
	a.mu.Lock()
	defer a.mu.Unlock()

	a.state = ActivityStateDestroyed
	a.activeUiCollectors = 0
	a.TotalDestroys++
}

// Recreate simulates configuration change (e.g. screen rotation, theme switch).
// Activity is destroyed and recreated; ViewModels typically survive or re-subscribe.
func (a *ActivityModel) Recreate() error {
	a.OnPause()
	a.OnStop()
	a.OnDestroy()

	a.mu.Lock()
	a.TotalRecreations++
	a.mu.Unlock()

	// Recreate immediately
	a.OnCreate()
	a.OnResume()
	return nil
}

// AddOptimisticPending adds a message to the in-memory optimistic pending map.
func (a *ActivityModel) AddOptimisticPending(msgId string) {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.optimisticPending[msgId] = MsgStatusPending
}

// RemoveOptimisticPending removes message from optimistic map once Room commits.
func (a *ActivityModel) RemoveOptimisticPending(msgId string) {
	a.mu.Lock()
	defer a.mu.Unlock()
	delete(a.optimisticPending, msgId)
}

// OptimisticPendingCount returns the number of uncommitted optimistic messages.
func (a *ActivityModel) OptimisticPendingCount() int {
	a.mu.Lock()
	defer a.mu.Unlock()
	return len(a.optimisticPending)
}

// ClearVolatileState simulates ViewModel destruction during process death.
func (a *ActivityModel) ClearVolatileState() {
	a.mu.Lock()
	defer a.mu.Unlock()

	a.state = ActivityStateUninitialized
	a.viewModelActive = false
	a.activeUiCollectors = 0
	a.optimisticPending = make(map[string]int)
}
