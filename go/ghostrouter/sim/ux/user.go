package ux

import (
	"fmt"
	"math/rand"
	"time"
)

// UserAction represents a single user interaction event with timestamp.
type UserAction struct {
	Type        UserActionType
	TimestampNs int64
	TargetID    string
	TextPayload string
	Delta       int
}

// VirtualUser models a deterministic user interacting with the GHOST Android app.
type VirtualUser struct {
	rng           *rand.Rand
	clock         *VirtualClock
	currentScreen string // "CONVERSATIONS", "CHAT", "GROUP_CHAT", "SETTINGS"
	activeChatID  string
	inputText     string
	isTyping      bool
}

// NewVirtualUser creates a user model with deterministic PRNG seed.
func NewVirtualUser(seed int64, clock *VirtualClock) *VirtualUser {
	return &VirtualUser{
		rng:           rand.New(rand.NewSource(seed)),
		clock:         clock,
		currentScreen: "CONVERSATIONS",
	}
}

// GenerateAction produces the next logical user action based on current state.
func (u *VirtualUser) GenerateAction(availableContacts []string, availableGroups []string) UserAction {
	now := u.clock.NowNs()

	switch u.currentScreen {
	case "CONVERSATIONS":
		r := u.rng.Float64()
		switch {
		case r < 0.40 && len(availableContacts) > 0:
			contactID := availableContacts[u.rng.Intn(len(availableContacts))]
			u.currentScreen = "CHAT"
			u.activeChatID = contactID
			return UserAction{Type: ActionOpenConversation, TimestampNs: now, TargetID: contactID}

		case r < 0.60 && len(availableGroups) > 0:
			groupID := availableGroups[u.rng.Intn(len(availableGroups))]
			u.currentScreen = "GROUP_CHAT"
			u.activeChatID = groupID
			return UserAction{Type: ActionOpenGroup, TimestampNs: now, TargetID: groupID}

		case r < 0.75:
			return UserAction{Type: ActionScrollDown, TimestampNs: now, Delta: 10}

		case r < 0.85:
			return UserAction{Type: ActionSearch, TimestampNs: now, TextPayload: "Alice"}

		case r < 0.95:
			return UserAction{Type: ActionActivityRecreate, TimestampNs: now}

		default:
			return UserAction{Type: ActionScreenOff, TimestampNs: now}
		}

	case "CHAT":
		r := u.rng.Float64()
		switch {
		case r < 0.25:
			// Send message
			text := fmt.Sprintf("Message from virtual user %d", u.rng.Intn(10000))
			return UserAction{Type: ActionSendMessage, TimestampNs: now, TargetID: u.activeChatID, TextPayload: text}

		case r < 0.35:
			// Pathological double tap send (within 1-5ms)
			text := fmt.Sprintf("Double tap burst %d", u.rng.Intn(10000))
			return UserAction{Type: ActionDoubleTapSend, TimestampNs: now, TargetID: u.activeChatID, TextPayload: text}

		case r < 0.45:
			// Rapid send burst
			return UserAction{Type: ActionSendRepeatedly, TimestampNs: now, TargetID: u.activeChatID, TextPayload: "rapid", Delta: 5}

		case r < 0.60:
			// Scroll LazyColumn
			return UserAction{Type: ActionScrollUp, TimestampNs: now, Delta: 15}

		case r < 0.70:
			return UserAction{Type: ActionFling, TimestampNs: now, Delta: 50}

		case r < 0.80:
			// Type / paste text
			u.inputText += " typing"
			return UserAction{Type: ActionTypeCharacter, TimestampNs: now, TextPayload: "a"}

		case r < 0.90:
			// Activity recreation while in chat
			return UserAction{Type: ActionActivityRecreate, TimestampNs: now}

		default:
			u.currentScreen = "CONVERSATIONS"
			prev := u.activeChatID
			u.activeChatID = ""
			return UserAction{Type: ActionCloseConversation, TimestampNs: now, TargetID: prev}
		}

	case "GROUP_CHAT":
		r := u.rng.Float64()
		switch {
		case r < 0.35:
			text := fmt.Sprintf("Group message %d", u.rng.Intn(10000))
			return UserAction{Type: ActionSendMessage, TimestampNs: now, TargetID: u.activeChatID, TextPayload: text}

		case r < 0.55:
			return UserAction{Type: ActionScrollUp, TimestampNs: now, Delta: 20}

		case r < 0.70:
			return UserAction{Type: ActionDoubleTapSend, TimestampNs: now, TargetID: u.activeChatID, TextPayload: "group double tap"}

		case r < 0.85:
			return UserAction{Type: ActionActivityRecreate, TimestampNs: now}

		default:
			u.currentScreen = "CONVERSATIONS"
			prev := u.activeChatID
			u.activeChatID = ""
			return UserAction{Type: ActionCloseConversation, TimestampNs: now, TargetID: prev}
		}

	default:
		u.currentScreen = "CONVERSATIONS"
		return UserAction{Type: ActionOpenApp, TimestampNs: now}
	}
}

// GenerateInterActionDelay returns realistic or pathological user delay between actions.
func (u *VirtualUser) GenerateInterActionDelay(isAdversarial bool) time.Duration {
	if isAdversarial {
		// Pathological rapid bursts (0ms, 1ms, 5ms, 10ms)
		delays := []time.Duration{0, 1 * time.Millisecond, 5 * time.Millisecond, 10 * time.Millisecond, 25 * time.Millisecond}
		return delays[u.rng.Intn(len(delays))]
	}

	// Normal human interaction intervals (100ms - 2s)
	ms := 100 + u.rng.Intn(1900)
	return time.Duration(ms) * time.Millisecond
}
