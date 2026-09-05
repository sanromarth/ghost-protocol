package sim

import (
	"fmt"
	"io"
	"sync"
	"time"
)

// TraceEventType identifies the kind of simulation event recorded.
type TraceEventType string

const (
	TraceNodeCreated    TraceEventType = "NODE_CREATED"
	TraceContactUp      TraceEventType = "CONTACT_UP"
	TraceContactDown    TraceEventType = "CONTACT_DOWN"
	TraceMessageCreated TraceEventType = "MESSAGE_CREATED"
	TraceForward        TraceEventType = "FORWARD"
	TraceDropped        TraceEventType = "DROPPED"
	TraceDelivered      TraceEventType = "DELIVERED"
	TraceCrash          TraceEventType = "CRASH"
	TraceRestart        TraceEventType = "RESTART"
	TraceBatteryChanged TraceEventType = "BATTERY_CHANGED"
	TraceTTLPruned      TraceEventType = "TTL_PRUNED"
)

// TraceEntry represents an immutable privacy-safe simulation event.
// Under NO circumstances does it contain plaintext message contents,
// private keys, or raw cryptographic seeds.
type TraceEntry struct {
	VirtualTime time.Time      `json:"virtual_time"`
	RelTime     time.Duration  `json:"rel_time"`
	Type        TraceEventType `json:"type"`
	MessageID   string         `json:"message_id,omitempty"` // Short hex (e.g. 8 chars)
	Source      string         `json:"source,omitempty"`
	Dest        string         `json:"dest,omitempty"`
	Carrier     string         `json:"carrier,omitempty"`
	HopCount    int            `json:"hop_count,omitempty"`
	Copies      int            `json:"copies,omitempty"`
	Reason      string         `json:"reason,omitempty"`
	Details     string         `json:"details,omitempty"`
}

// Format formats a trace entry into the canonical log representation:
// [HH:MM:SS] EVENT_TYPE details...
func (e TraceEntry) Format() string {
	totalSec := int(e.RelTime.Seconds())
	h := totalSec / 3600
	m := (totalSec % 3600) / 60
	s := totalSec % 60
	ts := fmt.Sprintf("[%02d:%02d:%02d]", h, m, s)

	switch e.Type {
	case TraceContactUp:
		return fmt.Sprintf("%s CONTACT_UP %s-%s", ts, e.Source, e.Dest)
	case TraceContactDown:
		return fmt.Sprintf("%s CONTACT_DOWN %s-%s", ts, e.Source, e.Dest)
	case TraceMessageCreated:
		return fmt.Sprintf("%s MESSAGE_CREATED msg=%s source=%s dest=%s", ts, e.MessageID, e.Source, e.Dest)
	case TraceForward:
		return fmt.Sprintf("%s FORWARD msg=%s %s->%s hop=%d copy=%d", ts, e.MessageID, e.Source, e.Carrier, e.HopCount, e.Copies)
	case TraceDelivered:
		return fmt.Sprintf("%s DELIVERED msg=%s dest=%s", ts, e.MessageID, e.Dest)
	case TraceDropped:
		return fmt.Sprintf("%s DROPPED msg=%s at=%s reason=\"%s\"", ts, e.MessageID, e.Source, e.Reason)
	case TraceCrash:
		return fmt.Sprintf("%s CRASH node=%s", ts, e.Source)
	case TraceRestart:
		return fmt.Sprintf("%s RESTART node=%s", ts, e.Source)
	case TraceBatteryChanged:
		return fmt.Sprintf("%s BATTERY_CHANGED node=%s %s", ts, e.Source, e.Details)
	case TraceTTLPruned:
		return fmt.Sprintf("%s TTL_PRUNED node=%s count=%d", ts, e.Source, e.Copies)
	default:
		return fmt.Sprintf("%s %s %s", ts, e.Type, e.Details)
	}
}

// TraceLogger is a thread-safe, privacy-safe logger collecting simulation traces.
type TraceLogger struct {
	mu      sync.RWMutex
	clock   *SimClock
	entries []TraceEntry
	output  io.Writer
	verbose bool
}

// NewTraceLogger creates a new TraceLogger attached to the given virtual clock.
func NewTraceLogger(clock *SimClock, output io.Writer, verbose bool) *TraceLogger {
	return &TraceLogger{
		clock:   clock,
		entries: make([]TraceEntry, 0, 128),
		output:  output,
		verbose: verbose,
	}
}

// Log records a new trace entry.
func (l *TraceLogger) Log(entry TraceEntry) {
	if l == nil {
		return
	}
	l.mu.Lock()
	defer l.mu.Unlock()

	if entry.VirtualTime.IsZero() && l.clock != nil {
		entry.VirtualTime = l.clock.Now()
		entry.RelTime = l.clock.Elapsed()
	}

	l.entries = append(l.entries, entry)

	if l.verbose && l.output != nil {
		fmt.Fprintln(l.output, entry.Format())
	}
}

// Entries returns a copy of all recorded trace entries.
func (l *TraceLogger) Entries() []TraceEntry {
	if l == nil {
		return nil
	}
	l.mu.RLock()
	defer l.mu.RUnlock()
	res := make([]TraceEntry, len(l.entries))
	copy(res, l.entries)
	return res
}

// Dump prints all recorded traces to the given writer.
func (l *TraceLogger) Dump(w io.Writer) {
	if l == nil {
		return
	}
	l.mu.RLock()
	defer l.mu.RUnlock()
	for _, e := range l.entries {
		fmt.Fprintln(w, e.Format())
	}
}
