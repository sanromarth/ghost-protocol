package oem

import (
	"time"
)

// OemProfileType defines synthetic OEM hostility classes.
type OemProfileType string

const (
	ProfileOemStock                 OemProfileType = "OEM_STOCK"
	ProfileOemBackgroundAggressive  OemProfileType = "OEM_BACKGROUND_AGGRESSIVE"
	ProfileOemBleUnstable           OemProfileType = "OEM_BLE_UNSTABLE"
	ProfileOemMemoryPressure        OemProfileType = "OEM_MEMORY_PRESSURE"
	ProfileOemBatteryAggressive     OemProfileType = "OEM_BATTERY_AGGRESSIVE"
	ProfileOemServiceHostile        OemProfileType = "OEM_SERVICE_HOSTILE"
	ProfileOemMaximumHostility      OemProfileType = "OEM_MAXIMUM_HOSTILITY"
)

// OemProfile models synthetic OEM operating characteristics and hostility parameters.
// NOTE: These are synthetic stress envelopes, NOT physical OEM measurements.
type OemProfile struct {
	Type                     OemProfileType
	Name                     string
	GattTimeout              time.Duration
	GattConnectBase          time.Duration
	GattMtuBase              time.Duration
	GattDiscoverBase         time.Duration
	GattWriteBase            time.Duration
	GattCoolOffMs            int64
	GattFailureRate          float64
	Gatt133Rate              float64
	GattDisconnectOnWriteRate float64
	LateCallbackRate         float64
	DuplicateCallbackRate    float64
	BackgroundServiceKillRate float64
	AlarmRestartDelay        time.Duration
	MemoryBudgetMB           int
	LmkdKillRate             float64
	BatteryDrainMultiplier   float64
	MainThreadDelayBase      time.Duration
	RoomWriteLatencyBase     time.Duration
	RoomIoErrorRate          float64
	MaxQueueCapacity         int
}

// ExpectedGattDuration computes modeled round-trip operation duration for a GATT transmission.
func (p OemProfile) ExpectedGattDuration(payloadLen int) int64 {
	d := p.GattConnectBase + p.GattMtuBase + p.GattDiscoverBase + p.GattWriteBase + 10*time.Millisecond
	if payloadLen > 200 {
		d += time.Duration(payloadLen/100) * (5 * time.Millisecond)
	}
	return d.Nanoseconds()
}

// StockProfile represents standard AOSP / Google Pixel baseline behavior.
func StockProfile() OemProfile {
	return OemProfile{
		Type:                      ProfileOemStock,
		Name:                      "OEM Stock (AOSP / Pixel baseline)",
		GattTimeout:               5000 * time.Millisecond,
		GattConnectBase:           80 * time.Millisecond,
		GattMtuBase:               30 * time.Millisecond,
		GattDiscoverBase:          50 * time.Millisecond,
		GattWriteBase:             25 * time.Millisecond,
		GattCoolOffMs:             150,
		GattFailureRate:           0.02,
		Gatt133Rate:               0.01,
		GattDisconnectOnWriteRate: 0.005,
		LateCallbackRate:          0.01,
		DuplicateCallbackRate:     0.005,
		BackgroundServiceKillRate: 0.01,
		AlarmRestartDelay:         1000 * time.Millisecond,
		MemoryBudgetMB:            2048,
		LmkdKillRate:              0.005,
		BatteryDrainMultiplier:    1.0,
		MainThreadDelayBase:       2 * time.Millisecond,
		RoomWriteLatencyBase:      8 * time.Millisecond,
		RoomIoErrorRate:           0.001,
		MaxQueueCapacity:          500,
	}
}

// BackgroundAggressiveProfile models OEMs that aggressively freeze or throttle background services.
func BackgroundAggressiveProfile() OemProfile {
	p := StockProfile()
	p.Type = ProfileOemBackgroundAggressive
	p.Name = "OEM Background Aggressive (Strict background freezer)"
	p.BackgroundServiceKillRate = 0.25
	p.AlarmRestartDelay = 15000 * time.Millisecond
	p.MainThreadDelayBase = 8 * time.Millisecond
	p.LateCallbackRate = 0.08
	return p
}

// BleUnstableProfile models flaky vendor Bluetooth firmware with high GATT 133 / disconnect rates.
func BleUnstableProfile() OemProfile {
	p := StockProfile()
	p.Type = ProfileOemBleUnstable
	p.Name = "OEM BLE Unstable (Flaky vendor stack & GATT 133)"
	p.Gatt133Rate = 0.20
	p.GattFailureRate = 0.15
	p.GattDisconnectOnWriteRate = 0.12
	p.LateCallbackRate = 0.15
	p.DuplicateCallbackRate = 0.08
	p.GattTimeout = 3000 * time.Millisecond
	p.GattCoolOffMs = 250
	return p
}

// MemoryPressureProfile models constrained low-RAM hardware prone to LMKD process kills.
func MemoryPressureProfile() OemProfile {
	p := StockProfile()
	p.Type = ProfileOemMemoryPressure
	p.Name = "OEM Memory Pressure (Low RAM / aggressive LMKD)"
	p.MemoryBudgetMB = 256
	p.LmkdKillRate = 0.30
	p.MaxQueueCapacity = 100
	p.MainThreadDelayBase = 16 * time.Millisecond
	p.RoomWriteLatencyBase = 25 * time.Millisecond
	return p
}

// BatteryAggressiveProfile models aggressive vendor battery savers killing wake locks and radio.
func BatteryAggressiveProfile() OemProfile {
	p := StockProfile()
	p.Type = ProfileOemBatteryAggressive
	p.Name = "OEM Battery Aggressive (Deep sleep & wake lock suppression)"
	p.BatteryDrainMultiplier = 3.0
	p.BackgroundServiceKillRate = 0.35
	p.AlarmRestartDelay = 30000 * time.Millisecond
	return p
}

// ServiceHostileProfile models proprietary task killers that terminate services on screen off or swipe.
func ServiceHostileProfile() OemProfile {
	p := StockProfile()
	p.Type = ProfileOemServiceHostile
	p.Name = "OEM Service Hostile (Task-killer / swipe termination)"
	p.BackgroundServiceKillRate = 0.50
	p.AlarmRestartDelay = 60000 * time.Millisecond
	p.LmkdKillRate = 0.20
	return p
}

// MaximumHostilityProfile combines all failure mechanisms simultaneously at extreme rates.
func MaximumHostilityProfile() OemProfile {
	return OemProfile{
		Type:                      ProfileOemMaximumHostility,
		Name:                      "OEM Maximum Hostility (Full combinatorial stress)",
		GattTimeout:               2000 * time.Millisecond,
		GattConnectBase:           150 * time.Millisecond,
		GattMtuBase:               80 * time.Millisecond,
		GattDiscoverBase:          100 * time.Millisecond,
		GattWriteBase:             60 * time.Millisecond,
		GattCoolOffMs:             300,
		GattFailureRate:           0.25,
		Gatt133Rate:               0.25,
		GattDisconnectOnWriteRate: 0.20,
		LateCallbackRate:          0.20,
		DuplicateCallbackRate:     0.15,
		BackgroundServiceKillRate: 0.40,
		AlarmRestartDelay:         45000 * time.Millisecond,
		MemoryBudgetMB:            128,
		LmkdKillRate:              0.35,
		BatteryDrainMultiplier:    4.0,
		MainThreadDelayBase:       24 * time.Millisecond,
		RoomWriteLatencyBase:      40 * time.Millisecond,
		RoomIoErrorRate:           0.05,
		MaxQueueCapacity:          50,
	}
}

// GetOemProfile returns the profile corresponding to the profile type.
func GetOemProfile(t OemProfileType) OemProfile {
	switch t {
	case ProfileOemBackgroundAggressive:
		return BackgroundAggressiveProfile()
	case ProfileOemBleUnstable:
		return BleUnstableProfile()
	case ProfileOemMemoryPressure:
		return MemoryPressureProfile()
	case ProfileOemBatteryAggressive:
		return BatteryAggressiveProfile()
	case ProfileOemServiceHostile:
		return ServiceHostileProfile()
	case ProfileOemMaximumHostility:
		return MaximumHostilityProfile()
	default:
		return StockProfile()
	}
}
