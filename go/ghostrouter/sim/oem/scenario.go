package oem

import (
	"fmt"
	"sync"
	"time"
)

// SOURCE: Stage 4 Android OEM Hostile Runtime Engine Architecture
// CONTRACT: Invariants O1 through O24
// MODEL: Unified OEM device environment coordinating Activity, Service, Process, Bluetooth,
//        GATT queue, Permissions, Power, Storage, Identity, and Native layers.

// ScenarioEventType identifies adversarial events injected during simulation.
type ScenarioEventType string

const (
	EventSendMessage           ScenarioEventType = "SEND_MESSAGE"
	EventDeliveryReceipt       ScenarioEventType = "DELIVERY_RECEIPT"
	EventRelayPacket           ScenarioEventType = "RELAY_PACKET"
	EventRotateMac             ScenarioEventType = "ROTATE_MAC"
	EventKillProcess           ScenarioEventType = "KILL_PROCESS"
	EventRebootDevice          ScenarioEventType = "REBOOT_DEVICE"
	EventTaskRemoved           ScenarioEventType = "TASK_REMOVED"
	EventRecreateActivity      ScenarioEventType = "RECREATE_ACTIVITY"
	EventBackgroundActivity    ScenarioEventType = "BACKGROUND_ACTIVITY"
	EventForegroundActivity    ScenarioEventType = "FOREGROUND_ACTIVITY"
	EventToggleBluetoothOff    ScenarioEventType = "TOGGLE_BT_OFF"
	EventToggleBluetoothOn     ScenarioEventType = "TOGGLE_BT_ON"
	EventCrashBluetooth        ScenarioEventType = "CRASH_BT"
	EventRevokePermissions     ScenarioEventType = "REVOKE_PERMISSIONS"
	EventRestorePermissions    ScenarioEventType = "RESTORE_PERMISSIONS"
	EventDrainBattery          ScenarioEventType = "DRAIN_BATTERY"
	EventConnectCharger        ScenarioEventType = "CONNECT_CHARGER"
	EventDisconnectCharger     ScenarioEventType = "DISCONNECT_CHARGER"
	EventFillDisk              ScenarioEventType = "FILL_DISK"
	EventRestoreDisk           ScenarioEventType = "RESTORE_DISK"
	EventDiskIoError           ScenarioEventType = "DISK_IO_ERROR"
	EventInjectGatt133         ScenarioEventType = "INJECT_GATT_133"
	EventInjectGattTimeout     ScenarioEventType = "INJECT_GATT_TIMEOUT"
	EventInjectLateCallback    ScenarioEventType = "INJECT_LATE_CALLBACK"
	EventInjectDuplicateCallback ScenarioEventType = "INJECT_DUPLICATE_CALLBACK"
	EventInjectJniFault        ScenarioEventType = "INJECT_JNI_FAULT"
)

// ScenarioEvent defines an individual discrete scheduled event in virtual time.
type ScenarioEvent struct {
	TimestampNs int64             `json:"timestamp_ns"`
	Type        ScenarioEventType `json:"type"`
	MessageID   string            `json:"message_id,omitempty"`
	PeerID      string            `json:"peer_id,omitempty"`
	PayloadLen  int               `json:"payload_len,omitempty"`
	ParamInt    int               `json:"param_int,omitempty"`
	ParamStr    string            `json:"param_str,omitempty"`
}

// OemScenario describes a complete deterministic scenario under an OEM hostility profile.
type OemScenario struct {
	ID         string          `json:"id"`
	Seed       int64           `json:"seed"`
	Profile    OemProfile      `json:"profile"`
	DurationNs int64           `json:"duration_ns"`
	Events     []ScenarioEvent `json:"events"`
}

// OemDevice encapsulates the entire modeled Android device runtime.
type OemDevice struct {
	mu sync.Mutex

	Clock      *VirtualClock
	Scheduler  *EventScheduler
	Profile    OemProfile
	Activity   *ActivityModel
	Service    *ServiceModel
	Process    *ProcessModel
	Bluetooth  *BluetoothModel
	Gatt       *GattQueueModel
	Permission *PermissionModel
	Power      *PowerModel
	Storage    *StorageModel
	Identity   *IdentityModel
	Native     *NativeBoundaryModel

	// State tracking for verification
	sentMessages       map[string]bool
	deliveredMessages  map[string]bool
	thirdPartyRelayed  int
	thirdPartyRejected int
	violations         []InvariantViolation
	initialFingerprint string
}

// NewOemDevice initializes and wires all subsystem models together.
func NewOemDevice(seed int64, profile OemProfile) *OemDevice {
	clock := NewVirtualClock()
	scheduler := NewEventScheduler(clock)

	activity := NewActivityModel(clock, scheduler)
	service := NewServiceModel(clock, scheduler, profile)
	process := NewProcessModel(clock, scheduler, profile)
	bluetooth := NewBluetoothModel(clock, scheduler, profile)
	gatt := NewGattQueueModel(clock, scheduler, profile)
	permission := NewPermissionModel(clock, scheduler)
	power := NewPowerModel(clock, scheduler, profile)
	storage := NewStorageModel(clock, scheduler, profile)
	identity := NewIdentityModel(int(seed%1000+1), clock)
	native := NewNativeBoundaryModel(clock)

	dev := &OemDevice{
		Clock:              clock,
		Scheduler:          scheduler,
		Profile:            profile,
		Activity:           activity,
		Service:            service,
		Process:            process,
		Bluetooth:          bluetooth,
		Gatt:               gatt,
		Permission:         permission,
		Power:              power,
		Storage:            storage,
		Identity:           identity,
		Native:             native,
		sentMessages:       make(map[string]bool),
		deliveredMessages:  make(map[string]bool),
		violations:         make([]InvariantViolation, 0),
		initialFingerprint: identity.LocalFingerprint(),
	}

	dev.wireSubsystems()
	return dev
}

// wireSubsystems sets up lifecycle and callback connections between models.
func (d *OemDevice) wireSubsystems() {
	// Service wiring
	d.Service.OnBleStart = func() {
		if d.Permission.HasBlePermissions() && d.Bluetooth.IsOn() {
			// BLE active
		}
	}
	d.Service.OnBleStop = func() {
		d.Gatt.CancelAll()
	}

	// Bluetooth state changes
	d.Bluetooth.OnStateChange = func(newState BluetoothAdapterState) {
		if newState == BtStateOff || newState == BtStateTurningOff || newState == BtStateCrashed {
			d.Gatt.CancelAll()
		}
	}

	// Permission changes
	d.Permission.OnRevoked = func(perm string) {
		d.Gatt.CancelAll()
	}

	// Process death clearing volatile state
	d.Process.OnVolatileClear = func() {
		d.Activity.ClearVolatileState()
		d.Gatt.CancelAll()
		d.Service.Kill(true) // OEM task-kill / AlarmManager restart
	}

	d.Process.OnProcessSpawn = func(newPid int) {
		// New process starts GhostService again
		d.Service.Start()
	}
}

// RecordViolation appends an invariant violation.
func (d *OemDevice) RecordViolation(v InvariantViolation) {
	d.mu.Lock()
	defer d.mu.Unlock()
	d.violations = append(d.violations, v)
}

// Violations returns all recorded violations.
func (d *OemDevice) Violations() []InvariantViolation {
	d.mu.Lock()
	defer d.mu.Unlock()
	copied := make([]InvariantViolation, len(d.violations))
	copy(copied, d.violations)
	return copied
}

// StartBaseline boots the device into standard operational state.
func (d *OemDevice) StartBaseline() {
	d.Activity.OnCreate()
	d.Activity.OnResume()
	d.Service.Start()
	d.Service.AcquireWakeLock()
}

// HandleSendMessage models sending a message from Compose UI through Room and GATT queue.
func (d *OemDevice) HandleSendMessage(msgId string, peerId string, payloadLen int) error {
	d.mu.Lock()
	d.sentMessages[msgId] = true
	d.mu.Unlock()

	// 1. Compose / ChatViewModel optimistic pending
	d.Activity.AddOptimisticPending(msgId)

	// 2. Room SQLite insert (PENDING)
	msg := &StoredMessage{
		ID:         msgId,
		ContactID:  peerId,
		Content:    fmt.Sprintf("Payload size %d", payloadLen),
		Timestamp:  d.Clock.NowNs(),
		IsOutgoing: true,
		Status:     MsgStatusPending,
		Signature:  fmt.Sprintf("sig_%s", msgId),
	}

	if err := d.Storage.Insert(msg); err != nil {
		// SQLite write failed (e.g. disk full / SQLiteFullException)
		// UI rolls back optimistic state cleanly (Invariant O16)
		d.Activity.RemoveOptimisticPending(msgId)
		return nil
	}

	// Room write committed -> remove optimistic pending
	d.Activity.RemoveOptimisticPending(msgId)

	// 3. Resolve peer MAC
	peer, exists := d.Identity.ResolvePeerByFingerprint(peerId)
	targetMac := "AA:BB:CC:DD:01:02"
	if exists {
		targetMac = peer.ActiveBleMac
	}

	// 4. Enqueue to serialized GATT queue if permissions and BT are enabled
	if !d.Permission.CanConnect() || !d.Bluetooth.IsOn() {
		// Bluetooth is OFF or permissions not granted: message remains PENDING in Room
		// Exactly matches Android GattOperationQueue.enqueue adapter disabled check
		return nil
	}

	payload := make([]byte, payloadLen)
	item := &GattQueueItem{
		ID:         msgId,
		MacAddress: targetMac,
		Payload:    payload,
		TimeoutMs:  d.Profile.GattTimeout.Milliseconds(),
		OnResult: func(success bool) {
			if success {
				d.Storage.UpdateStatus(msgId, MsgStatusSent)
			}
		},
	}

	return d.Gatt.Enqueue(item)
}

// HandleDeliveryReceipt processes an incoming delivery receipt from destination.
func (d *OemDevice) HandleDeliveryReceipt(msgId string) error {
	d.mu.Lock()
	d.deliveredMessages[msgId] = true
	d.mu.Unlock()

	// Room update status to DELIVERED (atomic status guard enforced).
	// Under SQLiteFullException or I/O errors, DeliveryReceiptHandler catches Exception cleanly (Invariant O16).
	_, _ = d.Storage.UpdateStatus(msgId, MsgStatusDelivered)
	return nil
}

// HandleRelayPacket processes third-party mesh relay packet, subject to battery gating (O12).
func (d *OemDevice) HandleRelayPacket(packetId string) bool {
	d.mu.Lock()
	defer d.mu.Unlock()

	if !d.Power.CanRelayThirdParty() {
		d.thirdPartyRejected++
		return false // Gated due to low battery (<20%) or deep sleep
	}

	d.thirdPartyRelayed++
	return true
}

// ExecuteEvent dispatches an individual scenario event.
func (d *OemDevice) ExecuteEvent(e ScenarioEvent) error {
	switch e.Type {
	case EventSendMessage:
		return d.HandleSendMessage(e.MessageID, e.PeerID, e.PayloadLen)

	case EventDeliveryReceipt:
		return d.HandleDeliveryReceipt(e.MessageID)

	case EventRelayPacket:
		d.HandleRelayPacket(e.MessageID)
		return nil

	case EventRotateMac:
		d.Identity.RotateLocalMac(e.ParamStr)
		return nil

	case EventKillProcess:
		d.Process.KillProcess("LMKD_PRESSURE")
		// OS resurrects process after delay
		d.Scheduler.ScheduleRelative(1500*time.Millisecond, 1, "resurrect_process", func() error {
			d.Process.SpawnProcess()
			return nil
		})
		return nil

	case EventRebootDevice:
		d.Process.RebootDevice()
		return nil

	case EventTaskRemoved:
		d.Service.OnTaskRemoved()
		return nil

	case EventRecreateActivity:
		return d.Activity.Recreate()

	case EventBackgroundActivity:
		d.Activity.OnPause()
		d.Activity.OnStop()
		return nil

	case EventForegroundActivity:
		d.Activity.OnCreate()
		d.Activity.OnResume()
		return nil

	case EventToggleBluetoothOff:
		d.Bluetooth.TurnOff()
		return nil

	case EventToggleBluetoothOn:
		d.Bluetooth.TurnOn()
		return nil

	case EventCrashBluetooth:
		d.Bluetooth.CrashAdapter()
		return nil

	case EventRevokePermissions:
		d.Permission.RevokeAll()
		return nil

	case EventRestorePermissions:
		d.Permission.GrantAll()
		return nil

	case EventDrainBattery:
		d.Power.DrainBattery(e.ParamInt)
		return nil

	case EventConnectCharger:
		d.Power.SetCharging(true)
		return nil

	case EventDisconnectCharger:
		d.Power.SetCharging(false)
		return nil

	case EventFillDisk:
		d.Storage.SetStorageState(StorageStateDiskFull)
		return nil

	case EventRestoreDisk:
		d.Storage.SetStorageState(StorageStateNormal)
		return nil

	case EventDiskIoError:
		d.Storage.SetStorageState(StorageStateIoError)
		return nil

	case EventInjectGatt133:
		if d.Gatt.activeItem != nil {
			d.Gatt.TeardownAndComplete(d.Gatt.activeItem, false, "GATT_133")
		}
		return nil

	case EventInjectGattTimeout:
		if d.Gatt.activeItem != nil {
			d.Gatt.TeardownAndComplete(d.Gatt.activeItem, false, "WATCHDOG_TIMEOUT")
		}
		return nil

	case EventInjectLateCallback:
		return d.Gatt.ReceiveHostileLateCallback(d.Gatt.activeGattId - 1)

	case EventInjectDuplicateCallback:
		d.Gatt.ReceiveHostileDuplicateCallback()
		return nil

	case EventInjectJniFault:
		d.Native.InjectJniOom = true
		d.Scheduler.ScheduleRelative(100*time.Millisecond, 1, "restore_jni", func() error {
			d.Native.InjectJniOom = false
			return nil
		})
		return nil

	default:
		return fmt.Errorf("unknown scenario event type: %s", e.Type)
	}
}
