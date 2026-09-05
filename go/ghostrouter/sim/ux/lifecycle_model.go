package ux

import (
	"fmt"
	"sync"
)

// SOURCE: android/app/src/main/java/com/ghostprotocol/MainActivity.kt
// SOURCE: android/app/src/main/java/com/ghostprotocol/GhostService.kt
// CONTRACT: U3, U6, U12
// MODEL: Multi-tier Android lifecycle state machine rigorously distinguishing:
// Activity lifecycle != Process lifecycle != Foreground Service lifecycle.

// ActivityLifecycleState mirrors Android Activity lifecycle states.
type ActivityLifecycleState string

const (
	ActivityStateNone      ActivityLifecycleState = "NONE"
	ActivityStateCreated   ActivityLifecycleState = "CREATED"
	ActivityStateResumed   ActivityLifecycleState = "RESUMED"
	ActivityStatePaused    ActivityLifecycleState = "PAUSED"
	ActivityStateDestroyed ActivityLifecycleState = "DESTROYED"
)

// LifecycleModel maintains discrete lifecycle components and their interactions.
type LifecycleModel struct {
	mu sync.Mutex

	ActivityState    ActivityLifecycleState
	ProcessAlive     bool
	ServiceAlive     bool
	ScreenOn         bool
	BluetoothEnabled bool

	// Flow collector counts tracking observer leakage
	ActiveCollectors int
	PeakCollectors   int

	// Callbacks for lifecycle hooks
	OnProcessDeathHook   func()
	OnProcessRestartHook func()
	OnActivityDestroyHook func()
	OnActivityRecreateHook func()
	OnBluetoothToggleHook func(enabled bool)
}

// NewLifecycleModel initializes default active device lifecycle state.
func NewLifecycleModel() *LifecycleModel {
	return &LifecycleModel{
		ActivityState:    ActivityStateResumed,
		ProcessAlive:     true,
		ServiceAlive:     true,
		ScreenOn:         true,
		BluetoothEnabled: true,
		ActiveCollectors: 2, // Baseline: conversation list + active chat
		PeakCollectors:   2,
	}
}

// RecreateActivity simulates configuration change (e.g. screen rotation, theme switch).
// Activity is destroyed and recreated, while Process and Foreground Service survive.
func (l *LifecycleModel) RecreateActivity() error {
	l.mu.Lock()
	if !l.ProcessAlive {
		l.mu.Unlock()
		return fmt.Errorf("cannot recreate activity: process is dead")
	}

	// 1. Destroy old Activity
	l.ActivityState = ActivityStateDestroyed
	hookDestroy := l.OnActivityDestroyHook
	hookRecreate := l.OnActivityRecreateHook

	// Simulate collector cleanup during onDestroy
	l.ActiveCollectors -= 2
	if l.ActiveCollectors < 0 {
		l.ActiveCollectors = 0
	}
	l.mu.Unlock()

	if hookDestroy != nil {
		hookDestroy()
	}

	l.mu.Lock()
	// 2. Create and resume new Activity
	l.ActivityState = ActivityStateResumed
	l.ActiveCollectors += 2
	if l.ActiveCollectors > l.PeakCollectors {
		l.PeakCollectors = l.ActiveCollectors
	}
	l.mu.Unlock()

	if hookRecreate != nil {
		hookRecreate()
	}
	return nil
}

// KillProcess simulates low-memory killer (LMK) or user swipe-dismiss termination.
// Wipes all process memory, killing Activity and Service.
func (l *LifecycleModel) KillProcess() {
	l.mu.Lock()
	l.ProcessAlive = false
	l.ServiceAlive = false
	l.ActivityState = ActivityStateNone
	l.ActiveCollectors = 0
	hook := l.OnProcessDeathHook
	l.mu.Unlock()

	if hook != nil {
		hook()
	}
}

// RestartProcess simulates cold launch following process death.
func (l *LifecycleModel) RestartProcess() {
	l.mu.Lock()
	l.ProcessAlive = true
	l.ServiceAlive = true
	l.ActivityState = ActivityStateResumed
	l.ActiveCollectors = 2
	if l.ActiveCollectors > l.PeakCollectors {
		l.PeakCollectors = l.ActiveCollectors
	}
	hook := l.OnProcessRestartHook
	l.mu.Unlock()

	if hook != nil {
		hook()
	}
}

// KillForegroundService simulates Android killing the foreground service while Activity stays open.
func (l *LifecycleModel) KillForegroundService() {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.ServiceAlive = false
}

// RestartForegroundService restarts the background mesh service.
func (l *LifecycleModel) RestartForegroundService() {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.ServiceAlive = true
}

// SetScreenOn updates screen power state.
func (l *LifecycleModel) SetScreenOn(on bool) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.ScreenOn = on
	if !on && l.ActivityState == ActivityStateResumed {
		l.ActivityState = ActivityStatePaused
	} else if on && l.ActivityState == ActivityStatePaused {
		l.ActivityState = ActivityStateResumed
	}
}

// ToggleScreen is an alias for SetScreenOn.
func (l *LifecycleModel) ToggleScreen(on bool) {
	l.SetScreenOn(on)
}

// SetBluetoothEnabled toggles system Bluetooth adapter state.
func (l *LifecycleModel) SetBluetoothEnabled(enabled bool) {
	l.mu.Lock()
	l.BluetoothEnabled = enabled
	hook := l.OnBluetoothToggleHook
	l.mu.Unlock()

	if hook != nil {
		hook(enabled)
	}
}

// ToggleBluetooth is an alias for SetBluetoothEnabled.
func (l *LifecycleModel) ToggleBluetooth(enabled bool) {
	l.SetBluetoothEnabled(enabled)
}

// RegisterCollector increments active Flow collector count.
func (l *LifecycleModel) RegisterCollector() {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.ActiveCollectors++
	if l.ActiveCollectors > l.PeakCollectors {
		l.PeakCollectors = l.ActiveCollectors
	}
}

// UnregisterCollector decrements active Flow collector count.
func (l *LifecycleModel) UnregisterCollector() {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.ActiveCollectors--
	if l.ActiveCollectors < 0 {
		l.ActiveCollectors = 0
	}
}
