package ux

import (
	"crypto/sha256"
	"encoding/binary"
	"fmt"
	"math/rand"
)

// SOURCE: Deterministic Scenario Generator (Section 6, 7, 8, 9, 10, 11)
// CONTRACT: U15 (Strict Determinism)
// MODEL: PRNG-driven scenario constructor with guaranteed reproducible event schedules.

// ScenarioClass categorizes test conditions.
type ScenarioClass string

const (
	ScenarioClassBoundary     ScenarioClass = "BOUNDARY"
	ScenarioClassPathological ScenarioClass = "PATHOLOGICAL"
	ScenarioClassLifecycle    ScenarioClass = "LIFECYCLE"
	ScenarioClassExtreme      ScenarioClass = "EXTREME"
	ScenarioClassSoak         ScenarioClass = "SOAK"
)

// ScenarioEventType distinguishes scenario event kinds.
type ScenarioEventType string

const (
	EventUserAction     ScenarioEventType = "USER_ACTION"
	EventBleIncoming    ScenarioEventType = "BLE_INCOMING"
	EventBleReceipt     ScenarioEventType = "BLE_RECEIPT"
	EventLifecycleEvent ScenarioEventType = "LIFECYCLE_EVENT"
	EventCrashTrigger   ScenarioEventType = "CRASH_TRIGGER"
)

// ScheduledScenarioEvent represents a timed action or external stimulus.
type ScheduledScenarioEvent struct {
	TimeOffsetNs         int64              `json:"time_offset_ns"`
	Type                 ScenarioEventType  `json:"type"`
	Action               UserActionType     `json:"action,omitempty"`
	Lifecycle            LifecycleEventType `json:"lifecycle,omitempty"`
	CrashStage           CrashWindowStage   `json:"crash_stage,omitempty"`
	SenderID             string             `json:"sender_id,omitempty"`
	RecipientID          string             `json:"recipient_id,omitempty"`
	GroupID              string             `json:"group_id,omitempty"`
	MessageID            string             `json:"message_id,omitempty"`
	Content              string             `json:"content,omitempty"`
	PayloadSize          int                `json:"payload_size,omitempty"`
	StatusOutcome        string             `json:"status_outcome,omitempty"`
	IsExplicitOutOfOrder bool               `json:"is_explicit_out_of_order,omitempty"`
	TransportFinishNs    int64              `json:"transport_finish_ns,omitempty"`
}

// CausalityAuditReport categorizes every generated delivery receipt for causality proof.
type CausalityAuditReport struct {
	TotalReceipts               int `json:"total_receipts"`
	CausallyValidReceipts       int `json:"causally_valid_receipts"`
	ExplicitStaleReceipts       int `json:"explicit_stale_receipts"`
	UnclassifiedAcausalReceipts int `json:"unclassified_acausal_receipts"`
}

// AuditScenarioCausality audits every delivery receipt in a scenario to prove causality compliance.
func AuditScenarioCausality(s *Scenario) CausalityAuditReport {
	var report CausalityAuditReport
	for _, ev := range s.Events {
		if ev.Type == EventBleReceipt {
			report.TotalReceipts++
			if ev.IsExplicitOutOfOrder {
				report.ExplicitStaleReceipts++
			} else if ev.TransportFinishNs > 0 && ev.TimeOffsetNs >= ev.TransportFinishNs {
				report.CausallyValidReceipts++
			} else {
				report.UnclassifiedAcausalReceipts++
			}
		}
	}
	return report
}

// Scenario encapsulates a full deterministic test case.
type Scenario struct {
	ID              string                   `json:"id"`
	Index           int                      `json:"index"`
	Seed            int64                    `json:"seed"`
	Class           ScenarioClass            `json:"class"`
	Profile         DeviceProfile            `json:"profile"`
	InitialMessages int                      `json:"initial_messages"`
	InitialContacts int                      `json:"initial_contacts"`
	InitialGroups   int                      `json:"initial_groups"`
	Events          []ScheduledScenarioEvent `json:"events"`
	DurationNs      int64                    `json:"duration_ns"`
}

// ScenarioGenerator generates deterministic scenarios based on master seed and index.
type ScenarioGenerator struct {
	masterSeed int64
}

// NewScenarioGenerator initializes a generator with a master seed.
func NewScenarioGenerator(masterSeed int64) *ScenarioGenerator {
	return &ScenarioGenerator{masterSeed: masterSeed}
}

// DeriveScenarioSeed derives an independent deterministic 64-bit seed for a scenario index.
func DeriveScenarioSeed(masterSeed int64, index int) int64 {
	h := sha256.New()
	var buf [16]byte
	binary.LittleEndian.PutUint64(buf[0:8], uint64(masterSeed))
	binary.LittleEndian.PutUint64(buf[8:16], uint64(index))
	h.Write(buf[:])
	sum := h.Sum(nil)
	return int64(binary.LittleEndian.Uint64(sum[0:8]))
}

// GenerateScenario creates a scenario according to index and campaign strategy.
func (g *ScenarioGenerator) GenerateScenario(index int) Scenario {
	seed := DeriveScenarioSeed(g.masterSeed, index)
	rng := rand.New(rand.NewSource(seed))

	// Select device profile: 40% Low-End, 40% Mid-Range, 20% High-End
	var profile DeviceProfile
	profRoll := rng.Float64()
	switch {
	case profRoll < 0.40:
		profile = ProfileLowEnd()
	case profRoll < 0.80:
		profile = ProfileMidRange()
	default:
		profile = ProfileHighEnd()
	}

	// Class distribution across the campaign:
	// 25% Boundary, 25% Pathological, 25% Lifecycle Chaos, 25% Extreme
	classRoll := index % 4
	var class ScenarioClass
	switch classRoll {
	case 0:
		class = ScenarioClassBoundary
	case 1:
		class = ScenarioClassPathological
	case 2:
		class = ScenarioClassLifecycle
	default:
		class = ScenarioClassExtreme
	}

	scenario := Scenario{
		ID:      fmt.Sprintf("SCENARIO_%s_%06d", class, index),
		Index:   index,
		Seed:    seed,
		Class:   class,
		Profile: profile,
	}

	switch class {
	case ScenarioClassBoundary:
		g.generateBoundaryScenario(&scenario, rng)
	case ScenarioClassPathological:
		g.generatePathologicalScenario(&scenario, rng)
	case ScenarioClassLifecycle:
		g.generateLifecycleScenario(&scenario, rng)
	case ScenarioClassExtreme:
		g.generateExtremeScenario(&scenario, rng)
	}

	return scenario
}

// GenerateSoakScenario creates a 24-hour virtual time continuous soak test scenario.
func (g *ScenarioGenerator) GenerateSoakScenario(seed int64, targetEvents int) Scenario {
	rng := rand.New(rand.NewSource(seed))
	profile := ProfileMidRange()

	scenario := Scenario{
		ID:              "SCENARIO_SOAK_24H",
		Index:           0,
		Seed:            seed,
		Class:           ScenarioClassSoak,
		Profile:         profile,
		InitialMessages: 2500,
		InitialContacts: 20,
		InitialGroups:   5,
		DurationNs:      24 * 3600 * 1_000_000_000, // 24 hours virtual time
	}

	currentTime := int64(1_000_000) // start at 1ms
	contacts := []string{"contact_alice", "contact_bob", "contact_charlie", "contact_dave"}
	groups := []string{"group_ops", "group_field"}

	for i := 0; i < targetEvents; i++ {
		// Advance virtual time between 500ms and 60 seconds
		advance := int64(500_000_000 + rng.Int63n(59_500_000_000))
		currentTime += advance

		roll := rng.Float64()
		switch {
		case roll < 0.40:
			// Regular user message send
			contact := contacts[rng.Intn(len(contacts))]
			msgID := fmt.Sprintf("soak_msg_%d", i)
			content := fmt.Sprintf("Soak telemetry test ping #%d", i)
			opDuration := profile.ExpectedGattDuration(len(content) + 120)
			transportFinishNs := currentTime + opDuration

			scenario.Events = append(scenario.Events, ScheduledScenarioEvent{
				TimeOffsetNs:      currentTime,
				Type:              EventUserAction,
				Action:            ActionSendMessage,
				RecipientID:       contact,
				MessageID:         msgID,
				Content:           content,
				StatusOutcome:     "GATT_SUCCESS",
				TransportFinishNs: transportFinishNs,
			})
			// Receipt scheduled later (1-5s) after transportFinishNs
			scenario.Events = append(scenario.Events, ScheduledScenarioEvent{
				TimeOffsetNs:         transportFinishNs + int64(1_000_000_000+rng.Int63n(4_000_000_000)),
				Type:                 EventBleReceipt,
				MessageID:            msgID,
				SenderID:             contact,
				RecipientID:          "self_node",
				StatusOutcome:        "DELIVERED",
				TransportFinishNs:    transportFinishNs,
				IsExplicitOutOfOrder: false,
			})

		case roll < 0.65:
			// Incoming BLE message from peer
			sender := contacts[rng.Intn(len(contacts))]
			msgID := fmt.Sprintf("soak_inbound_%d", i)
			scenario.Events = append(scenario.Events, ScheduledScenarioEvent{
				TimeOffsetNs: currentTime,
				Type:         EventBleIncoming,
				SenderID:     sender,
				RecipientID:  "self_node",
				MessageID:    msgID,
				Content:      fmt.Sprintf("Inbound mesh sync #%d", i),
			})

		case roll < 0.80:
			// Group message send
			grp := groups[rng.Intn(len(groups))]
			msgID := fmt.Sprintf("soak_grp_%d", i)
			scenario.Events = append(scenario.Events, ScheduledScenarioEvent{
				TimeOffsetNs: currentTime,
				Type:         EventUserAction,
				Action:       ActionSendMessage,
				GroupID:      grp,
				MessageID:    msgID,
				Content:      fmt.Sprintf("Group alert #%d", i),
			})

		case roll < 0.90:
			// Screen toggles (lock/unlock)
			scenario.Events = append(scenario.Events, ScheduledScenarioEvent{
				TimeOffsetNs: currentTime,
				Type:         EventLifecycleEvent,
				Lifecycle:    LifecycleScreenOff,
			})
			scenario.Events = append(scenario.Events, ScheduledScenarioEvent{
				TimeOffsetNs: currentTime + int64(10_000_000_000+rng.Int63n(50_000_000_000)),
				Type:         EventLifecycleEvent,
				Lifecycle:    LifecycleScreenOn,
			})

		case roll < 0.98:
			// User scroll / browse
			scenario.Events = append(scenario.Events, ScheduledScenarioEvent{
				TimeOffsetNs: currentTime,
				Type:         EventUserAction,
				Action:       ActionScrollUp,
			})

		default:
			// Activity recreation (e.g. rotation or memory pressure while service runs)
			scenario.Events = append(scenario.Events, ScheduledScenarioEvent{
				TimeOffsetNs: currentTime,
				Type:         EventLifecycleEvent,
				Lifecycle:    LifecycleActivityRecreate,
			})
		}
	}

	return scenario
}

func (g *ScenarioGenerator) generateBoundaryScenario(s *Scenario, rng *rand.Rand) {
	// Boundary test cases:
	// 1. Initial message count: 0, 10, 100, 1000, 10000, 50000
	msgCounts := []int{0, 1, 10, 100, 1000, 10000, 50000}
	s.InitialMessages = msgCounts[rng.Intn(len(msgCounts))]
	s.InitialContacts = 1 + rng.Intn(20)
	s.InitialGroups = rng.Intn(5)

	currentTime := int64(100_000) // 100µs
	nextGattAvailableNs := currentTime

	// Generate burst actions: up to 50 rapid sends
	burstCount := 5 + rng.Intn(45)
	for i := 0; i < burstCount; i++ {
		currentTime += int64(100_000 + rng.Intn(1_900_000)) // 100µs - 2ms intervals
		msgID := fmt.Sprintf("boundary_msg_%d_%d", s.Index, i)
		contactID := fmt.Sprintf("contact_%d", rng.Intn(s.InitialContacts+1))
		payloadSize := 10 + rng.Intn(500)

		outcome := "GATT_SUCCESS"
		if rng.Float64() < 0.15 {
			outcome = "GATT_FAILURE"
		} else if rng.Float64() < 0.30 {
			outcome = "RELAY_SPRAY"
		}

		opDuration := s.Profile.ExpectedGattDuration(payloadSize + 120)
		sendStartTime := currentTime
		if sendStartTime < nextGattAvailableNs {
			sendStartTime = nextGattAvailableNs
		}
		transportFinishNs := sendStartTime + opDuration
		nextGattAvailableNs = transportFinishNs

		s.Events = append(s.Events, ScheduledScenarioEvent{
			TimeOffsetNs:      currentTime,
			Type:              EventUserAction,
			Action:            ActionSendMessage,
			SenderID:          "self_node",
			RecipientID:       contactID,
			MessageID:         msgID,
			Content:           fmt.Sprintf("Boundary payload #%d", i),
			PayloadSize:       payloadSize,
			StatusOutcome:     outcome,
			TransportFinishNs: transportFinishNs,
		})

		// Follow-up receipt if success
		if outcome == "GATT_SUCCESS" {
			isOutOfOrder := rng.Float64() < 0.10 // 10% explicit out-of-order race tests
			var receiptTime int64
			if isOutOfOrder {
				receiptTime = currentTime + int64(1_000_000+rng.Intn(5_000_000))
				if receiptTime >= transportFinishNs {
					receiptTime = transportFinishNs - 2_000_000
					if receiptTime <= currentTime {
						receiptTime = currentTime + 500_000
					}
				}
			} else {
				receiptTime = transportFinishNs + int64(10_000_000+rng.Intn(60_000_000))
			}

			s.Events = append(s.Events, ScheduledScenarioEvent{
				TimeOffsetNs:         receiptTime,
				Type:                 EventBleReceipt,
				MessageID:            msgID,
				SenderID:             contactID,
				RecipientID:          "self_node",
				StatusOutcome:        "DELIVERED",
				IsExplicitOutOfOrder: isOutOfOrder,
				TransportFinishNs:    transportFinishNs,
			})
		}
	}
	maxTime := currentTime
	if nextGattAvailableNs > maxTime {
		maxTime = nextGattAvailableNs
	}
	s.DurationNs = maxTime + 500_000_000
}

func (g *ScenarioGenerator) generatePathologicalScenario(s *Scenario, rng *rand.Rand) {
	s.InitialMessages = 100 + rng.Intn(2000)
	s.InitialContacts = 5
	s.InitialGroups = 2

	currentTime := int64(500_000)

	// Pathological pattern: Double-tap sends (1ms apart), rapid navigation, fling scroll
	for i := 0; i < 20; i++ {
		currentTime += int64(5_000_000 + rng.Intn(20_000_000))

		patRoll := rng.Float64()
		switch {
		case patRoll < 0.35:
			// Double tap send (same content or rapid sends 1ms apart)
			msgID1 := fmt.Sprintf("patho_dt1_%d_%d", s.Index, i)
			msgID2 := fmt.Sprintf("patho_dt2_%d_%d", s.Index, i)
			s.Events = append(s.Events, ScheduledScenarioEvent{
				TimeOffsetNs:  currentTime,
				Type:          EventUserAction,
				Action:        ActionSendMessage,
				MessageID:     msgID1,
				RecipientID:   "contact_alice",
				Content:       "Double tap message",
				StatusOutcome: "GATT_SUCCESS",
			})
			s.Events = append(s.Events, ScheduledScenarioEvent{
				TimeOffsetNs:  currentTime + 1_000_000, // 1ms later!
				Type:          EventUserAction,
				Action:        ActionDoubleTapSend,
				MessageID:     msgID2,
				RecipientID:   "contact_alice",
				Content:       "Double tap message",
				StatusOutcome: "GATT_SUCCESS",
			})

		case patRoll < 0.65:
			// Rapid conversation switching & scroll fling
			s.Events = append(s.Events, ScheduledScenarioEvent{
				TimeOffsetNs: currentTime,
				Type:         EventUserAction,
				Action:       ActionCloseConversation,
			})
			s.Events = append(s.Events, ScheduledScenarioEvent{
				TimeOffsetNs: currentTime + 2_000_000,
				Type:         EventUserAction,
				Action:       ActionOpenConversation,
				RecipientID:  "contact_bob",
			})
			s.Events = append(s.Events, ScheduledScenarioEvent{
				TimeOffsetNs: currentTime + 5_000_000,
				Type:         EventUserAction,
				Action:       ActionFling,
			})

		default:
			// Inbound message burst during search
			s.Events = append(s.Events, ScheduledScenarioEvent{
				TimeOffsetNs: currentTime,
				Type:         EventUserAction,
				Action:       ActionSearch,
				Content:      "urgent",
			})
			for b := 0; b < 5; b++ {
				s.Events = append(s.Events, ScheduledScenarioEvent{
					TimeOffsetNs: currentTime + int64((b+1)*1_000_000),
					Type:         EventBleIncoming,
					SenderID:     "contact_charlie",
					RecipientID:  "self_node",
					MessageID:    fmt.Sprintf("burst_in_%d_%d_%d", s.Index, i, b),
					Content:      fmt.Sprintf("Inbound while searching %d", b),
				})
			}
		}
	}
	s.DurationNs = currentTime + 200_000_000
}

func (g *ScenarioGenerator) generateLifecycleScenario(s *Scenario, rng *rand.Rand) {
	s.InitialMessages = 50 + rng.Intn(500)
	s.InitialContacts = 4
	s.InitialGroups = 1

	currentTime := int64(1_000_000)

	stages := []CrashWindowStage{
		CrashWindowMutationBegin,
		CrashWindowMutationCommit,
		CrashWindowMutationObservable,
		CrashWindowFlowEmission,
		CrashWindowComposeConsume,
		CrashWindowCallbackExecute,
	}

	for i := 0; i < 15; i++ {
		currentTime += int64(10_000_000 + rng.Intn(30_000_000))

		// Schedule a message send
		msgID := fmt.Sprintf("life_msg_%d_%d", s.Index, i)
		s.Events = append(s.Events, ScheduledScenarioEvent{
			TimeOffsetNs:  currentTime,
			Type:          EventUserAction,
			Action:        ActionSendMessage,
			MessageID:     msgID,
			RecipientID:   "contact_alice",
			Content:       fmt.Sprintf("Lifecycle msg %d", i),
			StatusOutcome: "GATT_SUCCESS",
		})

		// Inject lifecycle or crash trigger right around this action
		stage := stages[rng.Intn(len(stages))]
		offset := int64(1_000_000 + rng.Intn(5_000_000))

		roll := rng.Float64()
		switch {
		case roll < 0.30:
			// Process restart at chosen crash window stage
			s.Events = append(s.Events, ScheduledScenarioEvent{
				TimeOffsetNs: currentTime + offset,
				Type:         EventCrashTrigger,
				CrashStage:   stage,
				MessageID:    msgID,
			})
		case roll < 0.60:
			// Activity recreation (screen rotate)
			s.Events = append(s.Events, ScheduledScenarioEvent{
				TimeOffsetNs: currentTime + offset,
				Type:         EventLifecycleEvent,
				Lifecycle:    LifecycleActivityRecreate,
			})
		case roll < 0.85:
			// Bluetooth toggle
			s.Events = append(s.Events, ScheduledScenarioEvent{
				TimeOffsetNs: currentTime + offset,
				Type:         EventLifecycleEvent,
				Lifecycle:    LifecycleBluetoothOff,
			})
			s.Events = append(s.Events, ScheduledScenarioEvent{
				TimeOffsetNs: currentTime + offset + 15_000_000,
				Type:         EventLifecycleEvent,
				Lifecycle:    LifecycleBluetoothOn,
			})
		default:
			// Screen off / on
			s.Events = append(s.Events, ScheduledScenarioEvent{
				TimeOffsetNs: currentTime + offset,
				Type:         EventLifecycleEvent,
				Lifecycle:    LifecycleScreenOff,
			})
		}
	}
	s.DurationNs = currentTime + 300_000_000
}

func (g *ScenarioGenerator) generateExtremeScenario(s *Scenario, rng *rand.Rand) {
	// Extreme combined scenario:
	// Large initial DB, multiple groups (10-50 members), heavy concurrency,
	// mixed BLE incoming, user sends, GATT stalls, and lifecycle transitions.
	s.InitialMessages = 1000 + rng.Intn(10000)
	s.InitialContacts = 20
	s.InitialGroups = 5

	currentTime := int64(1_000_000)
	nextGattAvailableNs := currentTime
	eventsCount := 30 + rng.Intn(50)

	for i := 0; i < eventsCount; i++ {
		currentTime += int64(2_000_000 + rng.Intn(15_000_000))

		roll := rng.Float64()
		switch {
		case roll < 0.30:
			// Group message send (multi-recipient expansion)
			grpID := fmt.Sprintf("group_%d", rng.Intn(s.InitialGroups+1))
			s.Events = append(s.Events, ScheduledScenarioEvent{
				TimeOffsetNs: currentTime,
				Type:         EventUserAction,
				Action:       ActionSendMessage,
				GroupID:      grpID,
				MessageID:    fmt.Sprintf("ext_grp_%d_%d", s.Index, i),
				Content:      fmt.Sprintf("Extreme group message %d", i),
			})

		case roll < 0.55:
			// Unicast send with random outcome
			outcomes := []string{"GATT_SUCCESS", "GATT_FAILURE", "RELAY_SPRAY"}
			outcome := outcomes[rng.Intn(len(outcomes))]
			contactID := fmt.Sprintf("contact_%d", rng.Intn(s.InitialContacts+1))
			msgID := fmt.Sprintf("ext_uni_%d_%d", s.Index, i)
			payloadSize := 10 + rng.Intn(500)

			opDuration := s.Profile.ExpectedGattDuration(payloadSize + 120)
			sendStartTime := currentTime
			if sendStartTime < nextGattAvailableNs {
				sendStartTime = nextGattAvailableNs
			}
			transportFinishNs := sendStartTime + opDuration
			nextGattAvailableNs = transportFinishNs

			s.Events = append(s.Events, ScheduledScenarioEvent{
				TimeOffsetNs:      currentTime,
				Type:              EventUserAction,
				Action:            ActionSendMessage,
				SenderID:          "self_node",
				RecipientID:       contactID,
				MessageID:         msgID,
				Content:           fmt.Sprintf("Extreme unicast message %d", i),
				PayloadSize:       payloadSize,
				StatusOutcome:     outcome,
				TransportFinishNs: transportFinishNs,
			})

			if outcome == "GATT_SUCCESS" {
				isOutOfOrder := rng.Float64() < 0.08 // 8% explicit out-of-order race tests
				var receiptTime int64
				if isOutOfOrder {
					receiptTime = currentTime + int64(2_000_000+rng.Intn(10_000_000))
					if receiptTime >= transportFinishNs {
						receiptTime = transportFinishNs - 2_000_000
						if receiptTime <= currentTime {
							receiptTime = currentTime + 500_000
						}
					}
				} else {
					receiptTime = transportFinishNs + int64(15_000_000+rng.Intn(80_000_000))
				}

				s.Events = append(s.Events, ScheduledScenarioEvent{
					TimeOffsetNs:         receiptTime,
					Type:                 EventBleReceipt,
					MessageID:            msgID,
					SenderID:             contactID,
					RecipientID:          "self_node",
					StatusOutcome:        "DELIVERED",
					IsExplicitOutOfOrder: isOutOfOrder,
					TransportFinishNs:    transportFinishNs,
				})
			}

		case roll < 0.80:
			// Incoming BLE message
			senderID := fmt.Sprintf("contact_%d", rng.Intn(s.InitialContacts+1))
			s.Events = append(s.Events, ScheduledScenarioEvent{
				TimeOffsetNs: currentTime,
				Type:         EventBleIncoming,
				SenderID:     senderID,
				RecipientID:  "self_node",
				MessageID:    fmt.Sprintf("ext_in_%d_%d", s.Index, i),
				Content:      fmt.Sprintf("Inbound packet %d", i),
			})

		case roll < 0.92:
			// Lifecycle churn
			s.Events = append(s.Events, ScheduledScenarioEvent{
				TimeOffsetNs: currentTime,
				Type:         EventLifecycleEvent,
				Lifecycle:    LifecycleActivityRecreate,
			})

		default:
			// Crash trigger
			s.Events = append(s.Events, ScheduledScenarioEvent{
				TimeOffsetNs: currentTime,
				Type:         EventCrashTrigger,
				CrashStage:   CrashWindowMutationCommit,
			})
		}
	}
	maxTime := currentTime
	if nextGattAvailableNs > maxTime {
		maxTime = nextGattAvailableNs
	}
	s.DurationNs = maxTime + 500_000_000
}
