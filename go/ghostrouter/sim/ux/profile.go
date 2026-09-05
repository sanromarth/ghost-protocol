package ux

import (
	"time"
)

// ProfileType classifies synthetic device performance profiles.
type ProfileType string

const (
	ProfileTypeLowEnd   ProfileType = "LOW_END"
	ProfileTypeMidRange ProfileType = "MID_RANGE"
	ProfileTypeHighEnd  ProfileType = "HIGH_END"
)

// DeviceProfile models synthetic hardware resources and latency envelopes.
// MODEL: Synthetic performance model — does not represent a specific physical phone.
type DeviceProfile struct {
	Type                  ProfileType
	Name                  string
	CpuMultiplier         float64
	RoomWriteLatencyBase  time.Duration
	RoomReadLatencyBase   time.Duration
	BridgeLatencyBase     time.Duration
	GattConnectBase       time.Duration
	GattWriteBase         time.Duration
	GattMtuBase           time.Duration
	GattDiscoverBase      time.Duration
	GattCoolOffMs         int64
	ComposeRecomposeBase  time.Duration
	MaxQueueCapacity      int
	MemoryBudgetMB        int
	MaxConcurrentBleConns int
}

// LowEndProfile models resource-constrained entry-level hardware.
func LowEndProfile() DeviceProfile {
	return DeviceProfile{
		Type:                  ProfileTypeLowEnd,
		Name:                  "Low-End (4x CPU, High I/O Latency)",
		CpuMultiplier:         4.0,
		RoomWriteLatencyBase:  35 * time.Millisecond,
		RoomReadLatencyBase:   15 * time.Millisecond,
		BridgeLatencyBase:     8 * time.Millisecond,
		GattConnectBase:       200 * time.Millisecond,
		GattWriteBase:         80 * time.Millisecond,
		GattMtuBase:           100 * time.Millisecond,
		GattDiscoverBase:      150 * time.Millisecond,
		GattCoolOffMs:         250,
		ComposeRecomposeBase:  24 * time.Millisecond,
		MaxQueueCapacity:      200,
		MemoryBudgetMB:        256,
		MaxConcurrentBleConns: 1,
	}
}

// ProfileLowEnd is an alias for LowEndProfile.
func ProfileLowEnd() DeviceProfile {
	return LowEndProfile()
}

// MidRangeProfile models standard mainstream hardware.
func MidRangeProfile() DeviceProfile {
	return DeviceProfile{
		Type:                  ProfileTypeMidRange,
		Name:                  "Mid-Range (Standard Octa-Core)",
		CpuMultiplier:         1.5,
		RoomWriteLatencyBase:  10 * time.Millisecond,
		RoomReadLatencyBase:   4 * time.Millisecond,
		BridgeLatencyBase:     2 * time.Millisecond,
		GattConnectBase:       80 * time.Millisecond,
		GattWriteBase:         35 * time.Millisecond,
		GattMtuBase:           40 * time.Millisecond,
		GattDiscoverBase:      60 * time.Millisecond,
		GattCoolOffMs:         150,
		ComposeRecomposeBase:  8 * time.Millisecond,
		MaxQueueCapacity:      500,
		MemoryBudgetMB:        1024,
		MaxConcurrentBleConns: 1,
	}
}

// ProfileMidRange is an alias for MidRangeProfile.
func ProfileMidRange() DeviceProfile {
	return MidRangeProfile()
}

// HighEndProfile models flagship hardware with fast storage and low latency.
func HighEndProfile() DeviceProfile {
	return DeviceProfile{
		Type:                  ProfileTypeHighEnd,
		Name:                  "High-End (Flagship UFS 4.0)",
		CpuMultiplier:         1.0,
		RoomWriteLatencyBase:  2 * time.Millisecond,
		RoomReadLatencyBase:   1 * time.Millisecond,
		BridgeLatencyBase:     500 * time.Microsecond,
		GattConnectBase:       30 * time.Millisecond,
		GattWriteBase:         15 * time.Millisecond,
		GattMtuBase:           15 * time.Millisecond,
		GattDiscoverBase:      25 * time.Millisecond,
		GattCoolOffMs:         100,
		ComposeRecomposeBase:  3 * time.Millisecond,
		MaxQueueCapacity:      1000,
		MemoryBudgetMB:        4096,
		MaxConcurrentBleConns: 2,
	}
}

// ProfileHighEnd is an alias for HighEndProfile.
func ProfileHighEnd() DeviceProfile {
	return HighEndProfile()
}

// ExpectedGattDuration computes the modeled round-trip operation duration for a GATT transmission in nanoseconds.
func (p DeviceProfile) ExpectedGattDuration(payloadLen int) int64 {
	d := p.GattConnectBase + p.GattMtuBase + p.GattDiscoverBase + p.GattWriteBase + 10*time.Millisecond
	if payloadLen > 200 {
		d += time.Duration(payloadLen/100) * (5 * time.Millisecond)
	}
	return d.Nanoseconds()
}

// GetProfile returns the profile for a given type name.
func GetProfile(p ProfileType) DeviceProfile {
	switch p {
	case ProfileTypeLowEnd:
		return LowEndProfile()
	case ProfileTypeHighEnd:
		return HighEndProfile()
	default:
		return MidRangeProfile()
	}
}

