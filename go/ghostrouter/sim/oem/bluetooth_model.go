package oem

import (
	"fmt"
	"sync"
	"time"
)

// SOURCE: android/app/src/main/java/com/ghostprotocol/ble/BleManager.kt & GhostService.kt
// CONTRACT: O7 (Bluetooth Off Bounded Abort), O8 (Bluetooth On Recovery)
// MODEL: BluetoothAdapter states, broadcast receiver dispatch, and bounded shutdown/restart mechanics.

// BluetoothModel simulates the Android Bluetooth adapter subsystem.
type BluetoothModel struct {
	mu sync.Mutex

	clock     *VirtualClock
	scheduler *EventScheduler
	profile   OemProfile

	state             BluetoothAdapterState
	scanningActive    bool
	advertisingActive bool
	turnOffTimeNs     int64
	turnOnTimeNs      int64

	// Callbacks matching BleManager / GhostService broadcast receivers
	OnStateChange func(newState BluetoothAdapterState)
	OnScanState   func(scanning bool)
	OnAdvState    func(advertising bool)

	// Metrics
	TotalTransitions int
	TotalOffEvents   int
	TotalOnEvents    int
	TotalCrashes     int
}

// NewBluetoothModel creates a Bluetooth adapter initialized to STATE_ON.
func NewBluetoothModel(clock *VirtualClock, scheduler *EventScheduler, profile OemProfile) *BluetoothModel {
	return &BluetoothModel{
		clock:             clock,
		scheduler:         scheduler,
		profile:           profile,
		state:             BtStateOn,
		scanningActive:    true,
		advertisingActive: true,
	}
}

// State returns current BluetoothAdapter state.
func (b *BluetoothModel) State() BluetoothAdapterState {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.state
}

// IsOn returns true if adapter is currently in STATE_ON.
func (b *BluetoothModel) IsOn() bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.state == BtStateOn
}

// IsScanning returns true if active scanning is enabled.
func (b *BluetoothModel) IsScanning() bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.scanningActive
}

// IsAdvertising returns true if active advertising is enabled.
func (b *BluetoothModel) IsAdvertising() bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.advertisingActive
}

// TurnOff simulates user or airplane mode turning off Bluetooth.
// Steps: STATE_ON -> STATE_TURNING_OFF -> STATE_OFF.
func (b *BluetoothModel) TurnOff() {
	b.mu.Lock()
	if b.state == BtStateOff || b.state == BtStateTurningOff {
		b.mu.Unlock()
		return
	}

	b.state = BtStateTurningOff
	b.turnOffTimeNs = b.clock.NowNs()
	b.TotalTransitions++
	b.TotalOffEvents++
	cb := b.OnStateChange
	b.mu.Unlock()

	if cb != nil {
		cb(BtStateTurningOff)
	}

	// Adapter finishes shutdown after 100ms
	b.scheduler.ScheduleRelative(100*time.Millisecond, 0, "bt_turn_off_complete", func() error {
		b.mu.Lock()
		b.state = BtStateOff
		b.scanningActive = false
		b.advertisingActive = false
		stateCb := b.OnStateChange
		scanCb := b.OnScanState
		advCb := b.OnAdvState
		b.mu.Unlock()

		if scanCb != nil {
			scanCb(false)
		}
		if advCb != nil {
			advCb(false)
		}
		if stateCb != nil {
			stateCb(BtStateOff)
		}
		return nil
	})
}

// TurnOn simulates user or system turning on Bluetooth.
// Steps: STATE_OFF -> STATE_TURNING_ON -> STATE_ON -> (1000ms stabilization) -> BleManager.start().
func (b *BluetoothModel) TurnOn() {
	b.mu.Lock()
	if b.state == BtStateOn || b.state == BtStateTurningOn {
		b.mu.Unlock()
		return
	}

	b.state = BtStateTurningOn
	b.turnOnTimeNs = b.clock.NowNs()
	b.TotalTransitions++
	b.TotalOnEvents++
	cb := b.OnStateChange
	b.mu.Unlock()

	if cb != nil {
		cb(BtStateTurningOn)
	}

	// Adapter powers up after 200ms
	b.scheduler.ScheduleRelative(200*time.Millisecond, 0, "bt_turn_on_powered", func() error {
		b.mu.Lock()
		b.state = BtStateOn
		stateCb := b.OnStateChange
		b.mu.Unlock()

		if stateCb != nil {
			stateCb(BtStateOn)
		}

		// GhostService line 115: delay(1000L) stabilization before restarting BleManager
		b.scheduler.ScheduleRelative(1000*time.Millisecond, 0, "bt_stabilization_and_restart", func() error {
			b.mu.Lock()
			if b.state == BtStateOn {
				b.scanningActive = true
				b.advertisingActive = true
				scanCb := b.OnScanState
				advCb := b.OnAdvState
				b.mu.Unlock()

				if scanCb != nil {
					scanCb(true)
				}
				if advCb != nil {
					advCb(true)
				}
			} else {
				b.mu.Unlock()
			}
			return nil
		})
		return nil
	})
}

// CrashAdapter simulates HCI controller or vendor Bluetooth HAL crash.
func (b *BluetoothModel) CrashAdapter() {
	b.mu.Lock()
	b.state = BtStateCrashed
	b.scanningActive = false
	b.advertisingActive = false
	b.TotalCrashes++
	b.TotalTransitions++
	cb := b.OnStateChange
	b.mu.Unlock()

	if cb != nil {
		cb(BtStateCrashed)
	}

	// Android BluetoothManagerService detects crash and resets adapter after 1500ms
	b.scheduler.ScheduleRelative(1500*time.Millisecond, 0, "bt_crash_recovery", func() error {
		b.TurnOn()
		return nil
	})
}

// CheckBoundedAbort verifies that when Bluetooth is OFF, no scan or advertise is active.
func (b *BluetoothModel) CheckBoundedAbort() error {
	b.mu.Lock()
	defer b.mu.Unlock()

	if b.state == BtStateOff {
		if b.scanningActive {
			return fmt.Errorf("O7 violation: BLE scanning remained active while BluetoothAdapter is STATE_OFF")
		}
		if b.advertisingActive {
			return fmt.Errorf("O7 violation: BLE advertising remained active while BluetoothAdapter is STATE_OFF")
		}
	}
	return nil
}
