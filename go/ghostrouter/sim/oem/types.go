package oem

import (
	"fmt"
	"time"
)

// SOURCE: Stage 4 Android OEM Hostile Runtime Engine Architecture
// CONTRACT: Invariants O1 through O24
// MODEL: Deterministic runtime hostility simulation at the OS/device boundary.

// ValidationScope distinguishes between what the virtual model validates,
// what Android unit/JVM tests validate, and what requires physical hardware.
type ValidationScope string

const (
	ScopeModelValidated        ValidationScope = "MODEL_VALIDATED"
	ScopeAndroidUnitValidated   ValidationScope = "ANDROID_UNIT_VALIDATED"
	ScopePhysicalDeviceRequired ValidationScope = "PHYSICAL_DEVICE_VALIDATED"
)

// Severity ladder for OEM runtime defects.
type Severity string

const (
	SeverityP0 Severity = "P0" // Data loss, security violation, unrecoverable corruption
	SeverityP1 Severity = "P1" // Persistent message delivery failure, permanent deadlock
	SeverityP2 Severity = "P2" // Duplicate logical message, stale rollback, false delivery
	SeverityP3 Severity = "P3" // Recoverable queue bloat, excessive retry, performance degradation
	SeverityP4 Severity = "P4" // Minor inefficiency, sub-optimal coalescing
)

// InvariantID identifies each formal OEM invariant O1 through O24.
type InvariantID string

const (
	O1_DurableMessageSurvival     InvariantID = "O1_DurableMessageSurvival"
	O2_ActivityDecoupling         InvariantID = "O2_ActivityDecoupling"
	O3_ServiceRestartConsistency  InvariantID = "O3_ServiceRestartConsistency"
	O4_GattQueueSerialization     InvariantID = "O4_GattQueueSerialization"
	O5_ClosedGattSafety           InvariantID = "O5_ClosedGattSafety"
	O6_TerminalDeliveryInvariance InvariantID = "O6_TerminalDeliveryInvariance"
	O7_BluetoothOffBoundedAbort   InvariantID = "O7_BluetoothOffBoundedAbort"
	O8_BluetoothOnRecovery        InvariantID = "O8_BluetoothOnRecovery"
	O9_MacRotationStability       InvariantID = "O9_MacRotationStability"
	O10_PermissionRevocationSafety InvariantID = "O10_PermissionRevocationSafety"
	O11_PermissionRestorationRecov InvariantID = "O11_PermissionRestorationRecov"
	O12_BatteryRelayGating        InvariantID = "O12_BatteryRelayGating"
	O13_BoundedQueueDepth         InvariantID = "O13_BoundedQueueDepth"
	O14_BoundedObserverGrowth     InvariantID = "O14_BoundedObserverGrowth"
	O15_NativeBoundarySafety      InvariantID = "O15_NativeBoundarySafety"
	O16_StorageFailureTransparent InvariantID = "O16_StorageFailureTransparent"
	O17_NoLogicalDuplicates       InvariantID = "O17_NoLogicalDuplicates"
	O18_NoCommittedMessageLoss    InvariantID = "O18_NoCommittedMessageLoss"
	O19_ValidStateProgression     InvariantID = "O19_ValidStateProgression"
	O20_DeadlockFreeService       InvariantID = "O20_DeadlockFreeService"
	O21_ExactDeterministicReplay  InvariantID = "O21_ExactDeterministicReplay"
	O22_WireProtocolInvariance    InvariantID = "O22_WireProtocolInvariance"
	O23_IdentityImmutability      InvariantID = "O23_IdentityImmutability"
	O24_EventualQuiescence        InvariantID = "O24_EventualQuiescence"
)

// InvariantViolation records an invariant failure with complete diagnostic context.
type InvariantViolation struct {
	ID         InvariantID     `json:"id"`
	Severity   Severity        `json:"severity"`
	Scope      ValidationScope `json:"scope"`
	Component  string          `json:"component"`
	MessageID  string          `json:"message_id,omitempty"`
	Message    string          `json:"message"`
	Timestamp  int64           `json:"timestamp_ns"`
	ScenarioID string          `json:"scenario_id,omitempty"`
}

func (v InvariantViolation) Error() string {
	return fmt.Sprintf("[%s][%s][%s] %s: %s (msg=%s @ %dns)",
		v.Severity, v.ID, v.Scope, v.Component, v.Message, v.MessageID, v.Timestamp)
}

// ActivityState represents the Android Activity lifecycle state.
type ActivityState string

const (
	ActivityStateUninitialized ActivityState = "UNINITIALIZED"
	ActivityStateCreated       ActivityState = "CREATED"
	ActivityStateResumed       ActivityState = "RESUMED"
	ActivityStatePaused        ActivityState = "PAUSED"
	ActivityStateStopped       ActivityState = "STOPPED"
	ActivityStateDestroyed     ActivityState = "DESTROYED"
)

// ServiceState represents the Android Service lifecycle state.
type ServiceState string

const (
	ServiceStateStopped    ServiceState = "STOPPED"
	ServiceStateStarting   ServiceState = "STARTING"
	ServiceStateRunning    ServiceState = "RUNNING"
	ServiceStateKilled     ServiceState = "KILLED"
	ServiceStateRestarting ServiceState = "RESTARTING"
)

// ProcessState represents Android process execution state.
type ProcessState string

const (
	ProcessStateAlive   ProcessState = "ALIVE"
	ProcessStateKilled  ProcessState = "KILLED"
	ProcessStateReboot  ProcessState = "REBOOT"
)

// BluetoothAdapterState represents Android BluetoothAdapter states.
type BluetoothAdapterState string

const (
	BtStateOff        BluetoothAdapterState = "STATE_OFF"
	BtStateTurningOn  BluetoothAdapterState = "STATE_TURNING_ON"
	BtStateOn         BluetoothAdapterState = "STATE_ON"
	BtStateTurningOff BluetoothAdapterState = "STATE_TURNING_OFF"
	BtStateCrashed    BluetoothAdapterState = "STATE_CRASHED"
)

// GattClientState mirrors Android BluetoothGatt client state machine.
type GattClientState string

const (
	GattStateIdle                GattClientState = "IDLE"
	GattStateConnecting          GattClientState = "CONNECTING"
	GattStateConnected           GattClientState = "CONNECTED"
	GattStateNegotiatingMtu      GattClientState = "NEGOTIATING_MTU"
	GattStateDiscoveringServices GattClientState = "DISCOVERING_SERVICES"
	GattStateWriting             GattClientState = "WRITING"
	GattStateDisconnecting       GattClientState = "DISCONNECTING"
	GattStateClosed              GattClientState = "CLOSED"
)

// GattOutcome specifies the simulated result of a GATT transaction.
type GattOutcome string

const (
	GattOutcomeSuccess         GattOutcome = "GATT_SUCCESS"
	GattOutcomeFailureGeneral   GattOutcome = "GATT_FAILURE"
	GattOutcomeStatus133       GattOutcome = "GATT_133" // Android common connection failure
	GattOutcomeStatus8         GattOutcome = "GATT_8"   // Timeout / connection loss
	GattOutcomeTimeoutWatchdog GattOutcome = "GATT_WATCHDOG_TIMEOUT"
	GattOutcomeDisconnect      GattOutcome = "GATT_DISCONNECT_UNEXPECTED"
	GattOutcomeLateCallback    GattOutcome = "GATT_LATE_CALLBACK"
	GattOutcomeDuplicateCb     GattOutcome = "GATT_DUPLICATE_CALLBACK"
)

// MessageStatus mirrors Android Room MessageEntity / GHOST message state.
const (
	MsgStatusPending   int = 0
	MsgStatusSent      int = 1
	MsgStatusDelivered int = 2
	MsgStatusSprayed   int = 3
	MsgStatusFailed    int = 4
)

// MsgStatusToString returns human-readable status name.
func MsgStatusToString(s int) string {
	switch s {
	case MsgStatusPending:
		return "PENDING"
	case MsgStatusSent:
		return "SENT"
	case MsgStatusDelivered:
		return "DELIVERED"
	case MsgStatusSprayed:
		return "SPRAYED"
	case MsgStatusFailed:
		return "FAILED"
	default:
		return fmt.Sprintf("UNKNOWN(%d)", s)
	}
}

// PowerMode mirrors PowerPolicyEngine.PowerMode.
type PowerMode string

const (
	PowerModeActive    PowerMode = "ACTIVE"
	PowerModeEco       PowerMode = "ECO"
	PowerModeCritical  PowerMode = "CRITICAL"
	PowerModeDeepSleep PowerMode = "DEEP_SLEEP"
)

// MemoryPressureLevel models Android onTrimMemory / LMKD pressure levels.
type MemoryPressureLevel string

const (
	MemPressureNormal   MemoryPressureLevel = "NORMAL"
	MemPressureModerate MemoryPressureLevel = "TRIM_MEMORY_RUNNING_MODERATE"
	MemPressureLow      MemoryPressureLevel = "TRIM_MEMORY_RUNNING_LOW"
	MemPressureCritical MemoryPressureLevel = "TRIM_MEMORY_RUNNING_CRITICAL"
	MemPressureLmkdKill MemoryPressureLevel = "LMKD_PROCESS_KILL"
)

// StorageState models SQLite filesystem persistence conditions.
type StorageState string

const (
	StorageStateNormal    StorageState = "STORAGE_NORMAL"
	StorageStateLow       StorageState = "STORAGE_LOW"
	StorageStateDiskFull  StorageState = "STORAGE_DISK_FULL"
	StorageStateIoError   StorageState = "STORAGE_IO_ERROR"
	StorageStateCorrupted StorageState = "STORAGE_CORRUPTED"
)

// PhysicalEventRecord represents an event imported from physical Android device logs.
type PhysicalEventRecord struct {
	TimestampNs int64             `json:"timestamp_ns"`
	EventType   string            `json:"event_type"`
	Component   string            `json:"component"`
	Details     map[string]string `json:"details,omitempty"`
}

// PhysicalObservationTrace represents a normalized log of real Android OEM behavior.
type PhysicalObservationTrace struct {
	DeviceModel    string                `json:"device_model"`
	AndroidVersion int                   `json:"android_version"`
	Manufacturer   string                `json:"manufacturer"`
	Events         []PhysicalEventRecord `json:"events"`
	ObservedAt     time.Time             `json:"observed_at"`
}
