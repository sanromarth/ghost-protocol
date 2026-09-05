package sim

import (
	"encoding/json"
	"fmt"
	"sort"
	"strings"
	"time"
)

// SimResults contains machine-readable simulation metrics, counters, and latency quantiles.
// Under no circumstances does this contain plaintext message bodies or secret keys.
type SimResults struct {
	Scenario          string   `json:"scenario"`
	Seed              int64    `json:"seed"`
	Nodes             int      `json:"nodes"`
	MessagesCreated   int      `json:"messages_created"`
	MessagesDelivered int      `json:"messages_delivered"`
	MessagesPending   int      `json:"messages_pending"`
	MessagesExpired   int      `json:"messages_expired"`
	MessagesFailed    int      `json:"messages_failed"`
	PacketsForwarded  int      `json:"packets_forwarded"`
	PacketsDropped    int      `json:"packets_dropped"`
	DuplicatesDropped int      `json:"duplicates_dropped"`
	MaxHops           int      `json:"max_hops"`
	MaxTransitStorage int      `json:"max_transit_storage"`
	SimulatedTime     string   `json:"simulated_time"`
	SimulatedSeconds  float64  `json:"simulated_seconds"`
	AvgLatencyMs      float64  `json:"avg_latency_ms"`
	P50LatencyMs      float64  `json:"p50_latency_ms"`
	P95LatencyMs      float64  `json:"p95_latency_ms"`
	P99LatencyMs      float64  `json:"p99_latency_ms"`
	InvariantsPassed  bool     `json:"invariants_passed"`
	InvariantErrors   []string `json:"invariant_errors,omitempty"`
}

// ToJSON serializes the simulation results to formatted JSON.
func (r *SimResults) ToJSON() ([]byte, error) {
	return json.MarshalIndent(r, "", "  ")
}

// String provides a human-readable summary block for CLI or test output.
func (r *SimResults) String() string {
	var sb strings.Builder
	sb.WriteString(fmt.Sprintf("=== SIMULATION RESULTS [%s] ===\n", r.Scenario))
	sb.WriteString(fmt.Sprintf("  Seed:                  %d\n", r.Seed))
	sb.WriteString(fmt.Sprintf("  Nodes:                 %d\n", r.Nodes))
	sb.WriteString(fmt.Sprintf("  Simulated Time:        %s (%.1fs)\n", r.SimulatedTime, r.SimulatedSeconds))
	sb.WriteString(fmt.Sprintf("  Messages Created:      %d\n", r.MessagesCreated))
	sb.WriteString(fmt.Sprintf("  Messages Delivered:    %d\n", r.MessagesDelivered))
	sb.WriteString(fmt.Sprintf("  Messages Pending:      %d\n", r.MessagesPending))
	sb.WriteString(fmt.Sprintf("  Messages Expired:      %d\n", r.MessagesExpired))
	sb.WriteString(fmt.Sprintf("  Messages Failed:       %d\n", r.MessagesFailed))
	sb.WriteString(fmt.Sprintf("  Packets Forwarded:     %d\n", r.PacketsForwarded))
	sb.WriteString(fmt.Sprintf("  Packets Dropped:       %d\n", r.PacketsDropped))
	sb.WriteString(fmt.Sprintf("  Duplicates Dropped:    %d\n", r.DuplicatesDropped))
	sb.WriteString(fmt.Sprintf("  Max Hops:              %d\n", r.MaxHops))
	sb.WriteString(fmt.Sprintf("  Max Transit Storage:   %d\n", r.MaxTransitStorage))
	sb.WriteString(fmt.Sprintf("  Latency (Avg/P50/P95/P99): %.1fms / %.1fms / %.1fms / %.1fms\n",
		r.AvgLatencyMs, r.P50LatencyMs, r.P95LatencyMs, r.P99LatencyMs))
	sb.WriteString(fmt.Sprintf("  Invariants:            %t\n", r.InvariantsPassed))
	if len(r.InvariantErrors) > 0 {
		sb.WriteString(fmt.Sprintf("  Invariant Errors:\n"))
		for _, err := range r.InvariantErrors {
			sb.WriteString(fmt.Sprintf("    - %s\n", err))
		}
	}
	sb.WriteString("================================")
	return sb.String()
}

// LatencyCollector calculates delivery latency quantiles.
type LatencyCollector struct {
	latencies []time.Duration
}

// Add records a message delivery latency.
func (lc *LatencyCollector) Add(d time.Duration) {
	if d < 0 {
		d = 0
	}
	lc.latencies = append(lc.latencies, d)
}

// Compute returns (avgMs, p50Ms, p95Ms, p99Ms).
func (lc *LatencyCollector) Compute() (float64, float64, float64, float64) {
	if len(lc.latencies) == 0 {
		return 0, 0, 0, 0
	}

	sorted := make([]float64, len(lc.latencies))
	var sum float64
	for i, d := range lc.latencies {
		ms := float64(d.Microseconds()) / 1000.0
		sorted[i] = ms
		sum += ms
	}
	sort.Float64s(sorted)

	avg := sum / float64(len(sorted))
	p50 := quantile(sorted, 0.50)
	p95 := quantile(sorted, 0.95)
	p99 := quantile(sorted, 0.99)

	return avg, p50, p95, p99
}

func quantile(sorted []float64, q float64) float64 {
	if len(sorted) == 0 {
		return 0
	}
	if q <= 0 {
		return sorted[0]
	}
	if q >= 1.0 {
		return sorted[len(sorted)-1]
	}
	idx := float64(len(sorted)-1) * q
	low := int(idx)
	high := low + 1
	if high >= len(sorted) {
		return sorted[low]
	}
	fraction := idx - float64(low)
	return sorted[low] + fraction*(sorted[high]-sorted[low])
}
