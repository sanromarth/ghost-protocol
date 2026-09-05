package oem

import (
	"fmt"
	"sync"
)

// SOURCE: Android runtime permissions (API 23+, API 31+ BLUETOOTH_SCAN/CONNECT/ADVERTISE)
// CONTRACT: O10 (Permission Revocation Safety), O11 (Permission Restoration Recovery)
// MODEL: Dynamic runtime permission revocation and restoration without process crash.

// PermissionModel tracks runtime permissions and validates that revocation never causes SecurityException panics.
type PermissionModel struct {
	mu sync.Mutex

	clock     *VirtualClock
	scheduler *EventScheduler

	scanGranted      bool
	connectGranted   bool
	advertiseGranted bool
	locationGranted  bool

	// Callbacks matching Android PermissionChecker / Activity results
	OnRevoked func(perm string)
	OnGranted func(perm string)

	// Metrics
	TotalRevocations int
	TotalGrants      int
	SecurityFaults   int
}

// NewPermissionModel creates a permission model with all permissions granted initially.
func NewPermissionModel(clock *VirtualClock, scheduler *EventScheduler) *PermissionModel {
	return &PermissionModel{
		clock:            clock,
		scheduler:        scheduler,
		scanGranted:      true,
		connectGranted:   true,
		advertiseGranted: true,
		locationGranted:  true,
	}
}

// HasBlePermissions returns true if core BLE permissions are granted.
func (p *PermissionModel) HasBlePermissions() bool {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.scanGranted && p.connectGranted && p.advertiseGranted
}

// CanScan returns whether scanning is permitted.
func (p *PermissionModel) CanScan() bool {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.scanGranted
}

// CanConnect returns whether GATT connection is permitted.
func (p *PermissionModel) CanConnect() bool {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.connectGranted
}

// CanAdvertise returns whether BLE advertising is permitted.
func (p *PermissionModel) CanAdvertise() bool {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.advertiseGranted
}

// RevokeAll simulates the user or OS revoking all BLE permissions via App Settings.
func (p *PermissionModel) RevokeAll() {
	p.mu.Lock()
	p.scanGranted = false
	p.connectGranted = false
	p.advertiseGranted = false
	p.TotalRevocations++
	cb := p.OnRevoked
	p.mu.Unlock()

	if cb != nil {
		cb("ALL_BLE_PERMISSIONS")
	}
}

// GrantAll simulates user granting all required BLE permissions.
func (p *PermissionModel) GrantAll() {
	p.mu.Lock()
	p.scanGranted = true
	p.connectGranted = true
	p.advertiseGranted = true
	p.locationGranted = true
	p.TotalGrants++
	cb := p.OnGranted
	p.mu.Unlock()

	if cb != nil {
		cb("ALL_BLE_PERMISSIONS")
	}
}

// CheckSecurityException verifies that an operation attempted without permissions returns
// a handled error rather than an unhandled crash (SecurityException).
func (p *PermissionModel) CheckSecurityException(op string) error {
	p.mu.Lock()
	defer p.mu.Unlock()

	switch op {
	case "scan":
		if !p.scanGranted {
			return fmt.Errorf("java.lang.SecurityException: Need android.permission.BLUETOOTH_SCAN")
		}
	case "connect":
		if !p.connectGranted {
			return fmt.Errorf("java.lang.SecurityException: Need android.permission.BLUETOOTH_CONNECT")
		}
	case "advertise":
		if !p.advertiseGranted {
			return fmt.Errorf("java.lang.SecurityException: Need android.permission.BLUETOOTH_ADVERTISE")
		}
	}
	return nil
}
