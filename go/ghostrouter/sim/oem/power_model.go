package oem

import (
	"fmt"
	"sync"
)

// SOURCE: android/app/src/main/java/com/ghostprotocol/power/PowerPolicyEngine.kt
// CONTRACT: O12 (Battery Relay Gating)
// MODEL: Battery percentage, charging status, PowerPolicyEngine transitions, and relay gating.

// PowerModel simulates battery level, charging state, and PowerPolicyEngine outputs.
type PowerModel struct {
	mu sync.Mutex

	clock     *VirtualClock
	scheduler *EventScheduler
	profile   OemProfile

	batteryPercent   int
	isCharging       bool
	screenOn         bool
	peerCount        int
	queueSize        int
	lastEncounterNs  int64

	currentMode      PowerMode
	relayWillingness float32
	wakeLockRequired bool
	scanIntervalMs   int64
	scanWindowMs     int64

	// Metrics
	TotalTransitions int
	RelayDropCount   int
}

// NewPowerModel creates a power model initialized to 80% battery, discharging.
func NewPowerModel(clock *VirtualClock, scheduler *EventScheduler, profile OemProfile) *PowerModel {
	p := &PowerModel{
		clock:            clock,
		scheduler:        scheduler,
		profile:          profile,
		batteryPercent:   80,
		isCharging:       false,
		screenOn:         true,
		currentMode:      PowerModeEco,
		relayWillingness: 1.0,
		wakeLockRequired: true,
		scanIntervalMs:   2000,
		scanWindowMs:     100,
	}
	p.recomputePolicyLocked()
	return p
}

// BatteryPercent returns current battery percentage.
func (p *PowerModel) BatteryPercent() int {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.batteryPercent
}

// IsCharging returns true if device is plugged into AC/USB charger.
func (p *PowerModel) IsCharging() bool {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.isCharging
}

// Mode returns current active PowerMode.
func (p *PowerModel) Mode() PowerMode {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.currentMode
}

// RelayWillingness returns current relay willingness factor (0.0 to 1.0).
func (p *PowerModel) RelayWillingness() float32 {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.relayWillingness
}

// SetBatteryPercent updates battery level and recomputes power policy.
func (p *PowerModel) SetBatteryPercent(pct int) {
	p.mu.Lock()
	defer p.mu.Unlock()

	if pct < 0 {
		pct = 0
	}
	if pct > 100 {
		pct = 100
	}
	p.batteryPercent = pct
	p.recomputePolicyLocked()
}

// SetCharging updates charger connection state.
func (p *PowerModel) SetCharging(charging bool) {
	p.mu.Lock()
	defer p.mu.Unlock()

	p.isCharging = charging
	p.recomputePolicyLocked()
}

// SetScreenOn updates screen interactive state.
func (p *PowerModel) SetScreenOn(on bool) {
	p.mu.Lock()
	defer p.mu.Unlock()

	p.screenOn = on
	p.recomputePolicyLocked()
}

// DrainBattery simulates battery depletion over time based on OEM multiplier.
func (p *PowerModel) DrainBattery(pct int) {
	p.mu.Lock()
	defer p.mu.Unlock()

	multiplier := p.profile.BatteryDrainMultiplier
	if multiplier <= 0 {
		multiplier = 1.0
	}
	effectiveDrain := int(float64(pct) * multiplier)
	if effectiveDrain <= 0 {
		effectiveDrain = 1
	}

	p.batteryPercent -= effectiveDrain
	if p.batteryPercent < 0 {
		p.batteryPercent = 0
	}
	p.recomputePolicyLocked()
}

// CanRelayThirdParty evaluates Invariant O12:
// If battery < 20% and not charging -> relayWillingness is strictly 0.0 -> refuse relay.
func (p *PowerModel) CanRelayThirdParty() bool {
	p.mu.Lock()
	defer p.mu.Unlock()

	if p.batteryPercent < 20 && !p.isCharging {
		p.RelayDropCount++
		return false
	}
	return p.relayWillingness > 0.0
}

// recomputePolicyLocked implements the logic of PowerPolicyEngine.kt computePolicy.
func (p *PowerModel) recomputePolicyLocked() {
	prevMode := p.currentMode

	switch {
	case p.batteryPercent < 20 && !p.isCharging:
		// CRITICAL: battery dying, zero relay willingness
		p.currentMode = PowerModeCritical
		p.relayWillingness = 0.0
		p.scanIntervalMs = 60000
		p.scanWindowMs = 200
		p.wakeLockRequired = false

	case p.isCharging || (p.peerCount > 10 && p.queueSize > 0):
		// ACTIVE: charging or dense crowd
		p.currentMode = PowerModeActive
		p.relayWillingness = 1.0
		p.scanIntervalMs = 500
		p.scanWindowMs = 100
		p.wakeLockRequired = true

	case p.batteryPercent > 20 && !p.isCharging && !p.screenOn && p.peerCount == 0 &&
		(p.clock.NowNs()-p.lastEncounterNs) > (30 * 60 * 1_000_000_000):
		// DEEP_SLEEP: idle, screen off, no peers for 30 min
		p.currentMode = PowerModeDeepSleep
		p.relayWillingness = 0.0
		p.scanIntervalMs = 300000
		p.scanWindowMs = 500
		p.wakeLockRequired = false

	default:
		// ECO: normal walking around
		p.currentMode = PowerModeEco
		p.scanIntervalMs = 2000
		p.scanWindowMs = 100
		p.wakeLockRequired = true
		if p.batteryPercent > 60 {
			p.relayWillingness = 1.0
		} else if p.batteryPercent > 30 {
			p.relayWillingness = 0.6
		} else {
			p.relayWillingness = 0.3
		}
	}

	if p.currentMode != prevMode {
		p.TotalTransitions++
	}
}

// CheckBatteryRelayGating validates Invariant O12.
func (p *PowerModel) CheckBatteryRelayGating() error {
	p.mu.Lock()
	defer p.mu.Unlock()

	if p.batteryPercent < 20 && !p.isCharging {
		if p.relayWillingness > 0.0 {
			return fmt.Errorf("O12 violation: relayWillingness is %f (>0) while battery is %d%% (<20%%) and not charging",
				p.relayWillingness, p.batteryPercent)
		}
	}
	return nil
}
