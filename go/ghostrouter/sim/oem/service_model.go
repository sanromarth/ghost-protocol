package oem

import (
	"fmt"
	"sync"
	"time"
)

// SOURCE: android/app/src/main/java/com/ghostprotocol/GhostService.kt
// CONTRACT: O2 (Activity Decoupling), O3 (Service Restart Consistency), O20 (Deadlock Free Service)
// MODEL: Foreground service lifecycle (START_STICKY), wake locks, and AlarmManager restart mechanics.

// ServiceModel simulates the lifecycle of GhostService and its background execution guarantees.
type ServiceModel struct {
	mu sync.Mutex

	clock     *VirtualClock
	scheduler *EventScheduler
	profile   OemProfile

	state                  ServiceState
	foregroundNotificationId int
	wakeLockHeld           bool
	restartScheduled       bool
	restartScheduledAtNs   int64
	restartCount           int
	serviceScopeActive     bool
	routerRunning          bool
	bleRunning             bool

	// Hooks for interacting with BLE and Router layers
	OnBleStart  func()
	OnBleStop   func()
	OnRouterStart func()
	OnRouterStop  func()

	// Metrics
	TotalStarts     int
	TotalStops      int
	TotalKills      int
	TotalRestarts   int
	WakeLockAcquires int
	WakeLockReleases int
}

// NewServiceModel creates a ServiceModel bound to virtual clock, scheduler, and OEM profile.
func NewServiceModel(clock *VirtualClock, scheduler *EventScheduler, profile OemProfile) *ServiceModel {
	return &ServiceModel{
		clock:                  clock,
		scheduler:              scheduler,
		profile:                profile,
		state:                  ServiceStateStopped,
		foregroundNotificationId: 0,
	}
}

// State returns current service lifecycle state.
func (s *ServiceModel) State() ServiceState {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.state
}

// IsRunning returns true if the service is actively running as a foreground service.
func (s *ServiceModel) IsRunning() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.state == ServiceStateRunning
}

// IsWakeLockHeld returns whether a partial wake lock is currently held.
func (s *ServiceModel) IsWakeLockHeld() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.wakeLockHeld
}

// Start initiates GhostService as a foreground service.
func (s *ServiceModel) Start() error {
	s.mu.Lock()
	defer s.mu.Unlock()

	s.state = ServiceStateStarting
	s.TotalStarts++
	s.serviceScopeActive = true
	s.foregroundNotificationId = 1 // NotificationHelper / startForeground(1, ...)
	s.state = ServiceStateRunning
	s.routerRunning = true
	s.bleRunning = true

	if s.OnRouterStart != nil {
		s.OnRouterStart()
	}
	if s.OnBleStart != nil {
		s.OnBleStart()
	}

	return nil
}

// AcquireWakeLock simulates obtaining a partial wake lock under appropriate power policies.
func (s *ServiceModel) AcquireWakeLock() {
	s.mu.Lock()
	defer s.mu.Unlock()
	if !s.wakeLockHeld && s.state == ServiceStateRunning {
		s.wakeLockHeld = true
		s.WakeLockAcquires++
	}
}

// ReleaseWakeLock simulates releasing the partial wake lock.
func (s *ServiceModel) ReleaseWakeLock() {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.wakeLockHeld {
		s.wakeLockHeld = false
		s.WakeLockReleases++
	}
}

// OnTaskRemoved simulates Android onTaskRemoved (user swiped app from recents).
// GhostService schedules an AlarmManager ELAPSED_REALTIME_WAKEUP restart.
func (s *ServiceModel) OnTaskRemoved() {
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.restartScheduled {
		return
	}

	delay := s.profile.AlarmRestartDelay
	if delay <= 0 {
		delay = 1000 * time.Millisecond
	}

	s.restartScheduled = true
	s.restartScheduledAtNs = s.clock.NowNs() + delay.Nanoseconds()

	s.scheduler.ScheduleRelative(delay, 1, "alarm_manager_service_restart", func() error {
		return s.executeRestart()
	})
}

// Kill simulates Android OS killing the service (e.g. background restriction or task killer).
func (s *ServiceModel) Kill(scheduleAlarmRestart bool) {
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.state == ServiceStateStopped || s.state == ServiceStateKilled {
		return
	}

	s.state = ServiceStateKilled
	s.serviceScopeActive = false
	s.routerRunning = false
	s.bleRunning = false
	if s.wakeLockHeld {
		s.wakeLockHeld = false
		s.WakeLockReleases++
	}
	s.TotalKills++

	if s.OnBleStop != nil {
		s.OnBleStop()
	}
	if s.OnRouterStop != nil {
		s.OnRouterStop()
	}

	if scheduleAlarmRestart && !s.restartScheduled {
		delay := s.profile.AlarmRestartDelay
		if delay <= 0 {
			delay = 1000 * time.Millisecond
		}
		s.restartScheduled = true
		s.restartScheduledAtNs = s.clock.NowNs() + delay.Nanoseconds()

		s.scheduler.ScheduleRelative(delay, 1, "oem_kill_service_restart", func() error {
			return s.executeRestart()
		})
	}
}

// Stop cleanly terminates GhostService (simulates onDestroy).
func (s *ServiceModel) Stop() {
	s.mu.Lock()
	defer s.mu.Unlock()

	s.state = ServiceStateStopped
	s.serviceScopeActive = false
	s.routerRunning = false
	s.bleRunning = false
	s.foregroundNotificationId = 0
	if s.wakeLockHeld {
		s.wakeLockHeld = false
		s.WakeLockReleases++
	}
	s.TotalStops++

	if s.OnBleStop != nil {
		s.OnBleStop()
	}
	if s.OnRouterStop != nil {
		s.OnRouterStop()
	}
}

// executeRestart runs when the AlarmManager fires or START_STICKY resurrects the service.
func (s *ServiceModel) executeRestart() error {
	s.mu.Lock()
	s.restartScheduled = false
	s.restartScheduledAtNs = 0
	s.state = ServiceStateRestarting
	s.TotalRestarts++
	s.restartCount++
	s.mu.Unlock()

	// Advance virtual time small amount for service creation overhead (50ms)
	s.scheduler.ScheduleRelative(50*time.Millisecond, 1, "service_resurrect_complete", func() error {
		s.mu.Lock()
		defer s.mu.Unlock()
		s.state = ServiceStateRunning
		s.serviceScopeActive = true
		s.foregroundNotificationId = 1
		s.routerRunning = true
		s.bleRunning = true

		if s.OnRouterStart != nil {
			s.OnRouterStart()
		}
		if s.OnBleStart != nil {
			s.OnBleStart()
		}
		return nil
	})
	return nil
}

// CheckDeadlockFree verifies that the service is in a stable responsive state without infinite loops.
func (s *ServiceModel) CheckDeadlockFree() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.state == ServiceStateStarting || s.state == ServiceStateRestarting {
		// If stuck in transitional state without pending events in scheduler
		if s.scheduler.PendingCount() == 0 {
			return fmt.Errorf("service stuck in transitional state %s without scheduled completion", s.state)
		}
	}
	return nil
}
