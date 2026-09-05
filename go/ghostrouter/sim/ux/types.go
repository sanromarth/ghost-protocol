package ux

import (
	"fmt"
	"time"
)

// Message status codes matching Android MessageEntity.kt:
// SOURCE: android/app/src/main/java/com/ghostprotocol/data/MessageEntity.kt
// CONTRACT: U4, U5, U10
const (
	StatusPending   int = 0
	StatusSent      int = 1
	StatusDelivered int = 2
	StatusFailed    int = 3
	StatusSprayed   int = 4
)

// StatusToString returns a readable name for a GHOST message status code.
func StatusToString(status int) string {
	switch status {
	case StatusPending:
		return "PENDING"
	case StatusSent:
		return "SENT"
	case StatusDelivered:
		return "DELIVERED"
	case StatusFailed:
		return "FAILED"
	case StatusSprayed:
		return "SPRAYED"
	default:
		return fmt.Sprintf("UNKNOWN(%d)", status)
	}
}

// UserActionType represents discrete virtual user interactions.
// SOURCE: Virtual User Model (Section 5)
type UserActionType string

const (
	ActionOpenApp              UserActionType = "OPEN_APP"
	ActionCloseApp             UserActionType = "CLOSE_APP"
	ActionOpenConversation     UserActionType = "OPEN_CONVERSATION"
	ActionCloseConversation    UserActionType = "CLOSE_CONVERSATION"
	ActionStartTyping          UserActionType = "START_TYPING"
	ActionTypeCharacter        UserActionType = "TYPE_CHARACTER"
	ActionPasteText            UserActionType = "PASTE_TEXT"
	ActionDeleteCharacter      UserActionType = "DELETE_CHARACTER"
	ActionSendMessage          UserActionType = "SEND_MESSAGE"
	ActionDoubleTapSend        UserActionType = "DOUBLE_TAP_SEND"
	ActionSendRepeatedly       UserActionType = "SEND_REPEATEDLY"
	ActionScrollUp             UserActionType = "SCROLL_UP"
	ActionScrollDown           UserActionType = "SCROLL_DOWN"
	ActionFling                UserActionType = "FLING"
	ActionSearch               UserActionType = "SEARCH"
	ActionClearSearch          UserActionType = "CLEAR_SEARCH"
	ActionOpenGroup            UserActionType = "OPEN_GROUP"
	ActionLeaveGroup           UserActionType = "LEAVE_GROUP"
	ActionReturnToConversation UserActionType = "RETURN_TO_CONVERSATION"
	ActionScreenOff            UserActionType = "SCREEN_OFF"
	ActionScreenOn             UserActionType = "SCREEN_ON"
	ActionActivityRecreate     UserActionType = "ACTIVITY_RECREATE"
	ActionProcessRestart       UserActionType = "PROCESS_RESTART"
)

// LifecycleEventType models discrete lifecycle boundaries.
// CONTRACT: Activity != Process != Service
type LifecycleEventType string

const (
	LifecycleActivityCreate   LifecycleEventType = "ACTIVITY_CREATE"
	LifecycleActivityResume   LifecycleEventType = "ACTIVITY_RESUME"
	LifecycleActivityPause    LifecycleEventType = "ACTIVITY_PAUSE"
	LifecycleActivityDestroy  LifecycleEventType = "ACTIVITY_DESTROY"
	LifecycleActivityRecreate LifecycleEventType = "ACTIVITY_RECREATE"
	LifecycleProcessDeath     LifecycleEventType = "PROCESS_DEATH"
	LifecycleProcessRestart   LifecycleEventType = "PROCESS_RESTART"
	LifecycleServiceSurvive   LifecycleEventType = "SERVICE_SURVIVE"
	LifecycleServiceDeath     LifecycleEventType = "SERVICE_DEATH"
	LifecycleServiceRestart   LifecycleEventType = "SERVICE_RESTART"
	LifecycleScreenOff        LifecycleEventType = "SCREEN_OFF"
	LifecycleScreenOn         LifecycleEventType = "SCREEN_ON"
	LifecycleBluetoothOff     LifecycleEventType = "BLUETOOTH_OFF"
	LifecycleBluetoothOn      LifecycleEventType = "BLUETOOTH_ON"
	LifecyclePermissionChange LifecycleEventType = "PERMISSION_CHANGE"
)

// CrashWindowStage models discrete execution boundaries around persistence.
type CrashWindowStage string

const (
	CrashWindowMutationBegin      CrashWindowStage = "MUTATION_BEGIN"
	CrashWindowMutationCommit     CrashWindowStage = "MUTATION_COMMIT"
	CrashWindowMutationObservable CrashWindowStage = "MUTATION_OBSERVABLE"
	CrashWindowFlowEmission       CrashWindowStage = "FLOW_EMISSION"
	CrashWindowComposeConsume     CrashWindowStage = "COMPOSE_CONSUME"
	CrashWindowCallbackExecute    CrashWindowStage = "CALLBACK_EXECUTE"
)

// InvariantID identifies each formal UX invariant U1 through U15.
type InvariantID string

const (
	U1_NoDuplicateLogicalMsg  InvariantID = "U1_NoDuplicateLogicalMsg"
	U2_NoFalseDelivery        InvariantID = "U2_NoFalseDelivery"
	U3_DurableStateSurvives   InvariantID = "U3_DurableStateSurvives"
	U4_NoImpossibleTransition InvariantID = "U4_NoImpossibleTransition"
	U5_NoStaleRollback        InvariantID = "U5_NoStaleRollback"
	U6_NoObserverExplosion    InvariantID = "U6_NoObserverExplosion"
	U7_BoundedEventQueues     InvariantID = "U7_BoundedEventQueues"
	U8_RepositoryConsistency  InvariantID = "U8_RepositoryConsistency"
	U9_GroupConsistency       InvariantID = "U9_GroupConsistency"
	U10_TransportTruthfulness InvariantID = "U10_TransportTruthfulness"
	U11_NativeBoundarySafety  InvariantID = "U11_NativeBoundarySafety"
	U12_LifecycleRecovery     InvariantID = "U12_LifecycleRecovery"
	U13_ResponsivenessBounds  InvariantID = "U13_ResponsivenessBounds"
	U14_AnimationBounded      InvariantID = "U14_AnimationBounded"
	U15_Determinism           InvariantID = "U15_Determinism"
)

// Severity defines failure severity ladder P0 through P4.
type Severity string

const (
	SeverityP0 Severity = "P0" // Data loss, security failure, unrecoverable corruption
	SeverityP1 Severity = "P1" // Persistent message delivery failure, permanent deadlock
	SeverityP2 Severity = "P2" // Duplicate message, false delivery, severe lifecycle bug
	SeverityP3 Severity = "P3" // Recoverable performance/reliability defect, queue bloat
	SeverityP4 Severity = "P4" // Minor inefficiency, sub-optimal coalescing
)

// InvariantViolation records an invariant failure with complete context.
type InvariantViolation struct {
	ID        InvariantID `json:"id"`
	Severity  Severity    `json:"severity"`
	Message   string      `json:"message"`
	Component string      `json:"component,omitempty"`
	MessageID string      `json:"message_id,omitempty"`
	Timestamp int64       `json:"timestamp_ns"`
}

func (v InvariantViolation) Error() string {
	return fmt.Sprintf("[%s][%s] %s: %s (msg=%s @ %dns)", v.Severity, v.ID, v.Component, v.Message, v.MessageID, v.Timestamp)
}

// ModelDivergenceError indicates when the simulation model itself enters an undefined
// state, separating model assumptions from GHOST application defects.
type ModelDivergenceError struct {
	Model     string `json:"model"`
	Reason    string `json:"reason"`
	Timestamp int64  `json:"timestamp_ns"`
}

func (e ModelDivergenceError) Error() string {
	return fmt.Sprintf("[MODEL_DIVERGENCE][%s] %s @ %dns", e.Model, e.Reason, e.Timestamp)
}

// CausalTransactionMetrics records the exact causal latency chain for a user/message transaction.
type CausalTransactionMetrics struct {
	MessageID             string        `json:"message_id"`
	SenderID              string        `json:"sender_id"`
	RecipientID           string        `json:"recipient_id"`
	ActionTimeNs          int64         `json:"action_time_ns"`
	StateAckTimeNs        int64         `json:"state_ack_time_ns"`
	PersistCommitTimeNs   int64         `json:"persist_commit_time_ns"`
	FlowEmissionTimeNs    int64         `json:"flow_emission_time_ns"`
	VisibleStateTimeNs    int64         `json:"visible_state_time_ns"`
	TransportAckTimeNs    int64         `json:"transport_ack_time_ns"`
	ActionToStateDuration time.Duration `json:"action_to_state"`
	ActionToPersistDur    time.Duration `json:"action_to_persistence"`
	PersistToEmissionDur  time.Duration `json:"persistence_to_emission"`
	EmissionToVisibleDur  time.Duration `json:"emission_to_visible"`
	TotalActionToVisible  time.Duration `json:"action_to_visible"`
	TotalRoundTrip        time.Duration `json:"total_round_trip"`
	QueueDepthAtAction    int           `json:"queue_depth"`
	PendingOptimisticCnt  int           `json:"pending_optimistic"`
	RecompositionUnits    int           `json:"recomposition_units"`
	GattWaitDuration      time.Duration `json:"gatt_wait_time"`
	BridgeLatency         time.Duration `json:"bridge_latency"`
}
