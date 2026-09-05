package oem

import (
	"fmt"
	"sync"
	"time"
)

// SOURCE: Android OS Low Memory Killer Daemon (LMKD) & Process Lifecycle
// CONTRACT: O1 (Durable Message Survival), O3 (Service Restart Consistency), O18 (No Committed Message Loss)
// DIRECTIVE: Distinguish virtual engine durability from physical device reboot durability.

// ProcessModel simulates Android process lifetime, memory pressure kills, and process resurrection.
type ProcessModel struct {
	mu sync.Mutex

	clock     *VirtualClock
	scheduler *EventScheduler
	profile   OemProfile

	state         ProcessState
	pid           int
	generation    int
	memoryUsedMB  int
	pressureLevel MemoryPressureLevel

	// Callbacks invoked when process is killed or resurrected
	OnVolatileClear func()
	OnProcessSpawn  func(newPid int)

	// Durability tracking
	TotalKills   int
	TotalSpawns  int
	TotalReboots int
}

// NewProcessModel initializes a new running Android process.
func NewProcessModel(clock *VirtualClock, scheduler *EventScheduler, profile OemProfile) *ProcessModel {
	return &ProcessModel{
		clock:         clock,
		scheduler:     scheduler,
		profile:       profile,
		state:         ProcessStateAlive,
		pid:           10240,
		generation:    1,
		memoryUsedMB:  64,
		pressureLevel: MemPressureNormal,
	}
}

// State returns current process state.
func (p *ProcessModel) State() ProcessState {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.state
}

// Pid returns current Linux process ID.
func (p *ProcessModel) Pid() int {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.pid
}

// Generation returns number of process incarnations.
func (p *ProcessModel) Generation() int {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.generation
}

// IsAlive returns true if the process is active.
func (p *ProcessModel) IsAlive() bool {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.state == ProcessStateAlive
}

// AllocateMemory simulates heap growth and checks against OEM memory budget.
func (p *ProcessModel) AllocateMemory(mb int) MemoryPressureLevel {
	p.mu.Lock()
	defer p.mu.Unlock()

	p.memoryUsedMB += mb
	budget := p.profile.MemoryBudgetMB
	if budget <= 0 {
		budget = 512
	}

	ratio := float64(p.memoryUsedMB) / float64(budget)
	switch {
	case ratio > 1.2:
		p.pressureLevel = MemPressureLmkdKill
	case ratio > 0.9:
		p.pressureLevel = MemPressureCritical
	case ratio > 0.7:
		p.pressureLevel = MemPressureLow
	case ratio > 0.5:
		p.pressureLevel = MemPressureModerate
	default:
		p.pressureLevel = MemPressureNormal
	}
	return p.pressureLevel
}

// TrimMemory simulates Android ComponentCallbacks2.onTrimMemory(level).
func (p *ProcessModel) TrimMemory(level MemoryPressureLevel) {
	p.mu.Lock()
	defer p.mu.Unlock()

	p.pressureLevel = level
	if level == MemPressureCritical || level == MemPressureLow {
		// Evict in-memory caches to reduce heap usage
		if p.memoryUsedMB > 48 {
			p.memoryUsedMB = 48
		}
	}
}

// KillProcess simulates immediate process termination by LMKD or OS task kill.
// Volatile heap state is destroyed; durable SQLite data is preserved.
func (p *ProcessModel) KillProcess(reason string) {
	p.mu.Lock()
	if p.state != ProcessStateAlive {
		p.mu.Unlock()
		return
	}

	p.state = ProcessStateKilled
	p.TotalKills++
	clearCb := p.OnVolatileClear
	p.mu.Unlock()

	if clearCb != nil {
		clearCb()
	}
}

// RebootDevice simulates a complete Android device reboot.
// DIRECTIVE: This tests VIRTUAL ENGINE durability (ScopeModelValidated).
// Physical reboot durability requires ScopePhysicalDeviceRequired.
func (p *ProcessModel) RebootDevice() {
	p.mu.Lock()
	p.state = ProcessStateReboot
	p.TotalReboots++
	clearCb := p.OnVolatileClear
	p.mu.Unlock()

	if clearCb != nil {
		clearCb()
	}

	// Device reboot takes 25-45 seconds
	p.scheduler.ScheduleRelative(30*time.Second, 0, "device_reboot_complete", func() error {
		p.SpawnProcess()
		return nil
	})
}

// SpawnProcess resurrects the Android process with a new PID.
func (p *ProcessModel) SpawnProcess() {
	p.mu.Lock()
	p.generation++
	p.pid += 137
	p.state = ProcessStateAlive
	p.memoryUsedMB = 64
	p.pressureLevel = MemPressureNormal
	p.TotalSpawns++
	spawnCb := p.OnProcessSpawn
	newPid := p.pid
	p.mu.Unlock()

	if spawnCb != nil {
		spawnCb(newPid)
	}
}

// GetMemoryUsage returns current memory consumption in MB.
func (p *ProcessModel) GetMemoryUsage() int {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.memoryUsedMB
}

// DiagnosticSummary provides debug string of process state.
func (p *ProcessModel) DiagnosticSummary() string {
	p.mu.Lock()
	defer p.mu.Unlock()
	return fmt.Sprintf("Process[pid=%d, gen=%d, state=%s, mem=%dMB, kills=%d, reboots=%d]",
		p.pid, p.generation, p.state, p.memoryUsedMB, p.TotalKills, p.TotalReboots)
}
