package ux

import (
	"fmt"
	"math"
	"sort"
	"sync"
	"time"
)

// SOURCE: Causal Transaction Metrics & Responsiveness Contracts (Section 24, 25)
// CONTRACT: U13 (Responsiveness Bounds), U14 (Bounded Animation/Recomposition)
// MODEL: Discrete event latency tracker and statistical aggregator.

// LatencyBucket defines standard responsiveness thresholds.
type LatencyBucket string

const (
	BucketUnder16ms  LatencyBucket = "<16ms (Instant / 60fps frame)"
	Bucket16to32ms   LatencyBucket = "16-32ms (1 frame delay)"
	Bucket32to50ms   LatencyBucket = "32-50ms (Imperceptible)"
	Bucket50to100ms  LatencyBucket = "50-100ms (Noticeable)"
	Bucket100to250ms LatencyBucket = "100-250ms (Sluggish)"
	Bucket250to500ms LatencyBucket = "250-500ms (High Latency)"
	Bucket500msto1s  LatencyBucket = "500ms-1s (Stall)"
	BucketOver1s     LatencyBucket = ">1s (Critical Lag / Near ANR)"
)

// CategorizeDuration maps a duration into standard UX responsiveness buckets.
func CategorizeDuration(d time.Duration) LatencyBucket {
	ms := d.Milliseconds()
	switch {
	case ms < 16:
		return BucketUnder16ms
	case ms < 32:
		return Bucket16to32ms
	case ms < 50:
		return Bucket32to50ms
	case ms < 100:
		return Bucket50to100ms
	case ms < 250:
		return Bucket100to250ms
	case ms < 500:
		return Bucket250to500ms
	case ms < 1000:
		return Bucket500msto1s
	default:
		return BucketOver1s
	}
}

// PercentileMetrics stores statistical latency percentiles.
type PercentileMetrics struct {
	Count int           `json:"count"`
	Min   time.Duration `json:"min"`
	P50   time.Duration `json:"p50"`
	P90   time.Duration `json:"p90"`
	P95   time.Duration `json:"p95"`
	P99   time.Duration `json:"p99"`
	Max   time.Duration `json:"max"`
	Mean  time.Duration `json:"mean"`
}

func calculatePercentiles(durations []time.Duration) PercentileMetrics {
	n := len(durations)
	if n == 0 {
		return PercentileMetrics{}
	}
	sorted := make([]time.Duration, n)
	copy(sorted, durations)
	sort.Slice(sorted, func(i, j int) bool { return sorted[i] < sorted[j] })

	var total time.Duration
	for _, d := range sorted {
		total += d
	}

	pIndex := func(p float64) int {
		idx := int(math.Ceil(p*float64(n))) - 1
		if idx < 0 {
			idx = 0
		}
		if idx >= n {
			idx = n - 1
		}
		return idx
	}

	return PercentileMetrics{
		Count: n,
		Min:   sorted[0],
		P50:   sorted[pIndex(0.50)],
		P90:   sorted[pIndex(0.90)],
		P95:   sorted[pIndex(0.95)],
		P99:   sorted[pIndex(0.99)],
		Max:   sorted[n-1],
		Mean:  time.Duration(int64(total) / int64(n)),
	}
}

// MainThreadWorkRecord models synthetic main-thread execution load.
// NOTE: This is an architectural performance proxy simulating main-thread dispatch load,
// NOT a measurement of physical Android hardware display frame rendering.
type MainThreadWorkRecord struct {
	TimestampNs      int64         `json:"timestamp_ns"`
	Duration         time.Duration `json:"duration"`
	TaskName         string        `json:"task_name"`
	DroppedFrameProx int           `json:"dropped_frames_proxy"`
	IsANRProxy       bool          `json:"is_anr_proxy"`
}

// UXMetricsAggregator collects and analyzes performance across thousands of UX scenarios.
type UXMetricsAggregator struct {
	mu sync.Mutex

	// Causal transaction records
	transactions []*CausalTransactionMetrics

	// Latency histograms (durations)
	actionToStateDurations    []time.Duration
	actionToPersistDurations  []time.Duration
	persistToEmissionDur      []time.Duration
	emissionToVisibleDur      []time.Duration
	totalActionToVisibleDur   []time.Duration
	totalRoundTripDur         []time.Duration
	gattWaitDurations         []time.Duration
	bridgeDurations           []time.Duration

	// Main-thread load tracking
	mainThreadWorkRecords     []MainThreadWorkRecord
	totalDroppedFramesProxy   int
	anrCountProxy             int

	// Bucket counters for ActionToVisible
	visibleBuckets            map[LatencyBucket]int
}

// NewUXMetricsAggregator initializes a thread-safe metrics aggregator.
func NewUXMetricsAggregator() *UXMetricsAggregator {
	return &UXMetricsAggregator{
		visibleBuckets: make(map[LatencyBucket]int),
	}
}

// RecordTransaction adds a completed causal transaction chain.
func (a *UXMetricsAggregator) RecordTransaction(tx *CausalTransactionMetrics) {
	if tx == nil {
		return
	}

	// Calculate durations if not already populated
	if tx.ActionToStateDuration == 0 && tx.StateAckTimeNs > tx.ActionTimeNs {
		tx.ActionToStateDuration = time.Duration(tx.StateAckTimeNs - tx.ActionTimeNs)
	}
	if tx.ActionToPersistDur == 0 && tx.PersistCommitTimeNs > tx.ActionTimeNs {
		tx.ActionToPersistDur = time.Duration(tx.PersistCommitTimeNs - tx.ActionTimeNs)
	}
	if tx.PersistToEmissionDur == 0 && tx.FlowEmissionTimeNs > tx.PersistCommitTimeNs {
		tx.PersistToEmissionDur = time.Duration(tx.FlowEmissionTimeNs - tx.PersistCommitTimeNs)
	}
	if tx.EmissionToVisibleDur == 0 && tx.VisibleStateTimeNs > tx.FlowEmissionTimeNs {
		tx.EmissionToVisibleDur = time.Duration(tx.VisibleStateTimeNs - tx.FlowEmissionTimeNs)
	}
	if tx.TotalActionToVisible == 0 && tx.VisibleStateTimeNs > tx.ActionTimeNs {
		tx.TotalActionToVisible = time.Duration(tx.VisibleStateTimeNs - tx.ActionTimeNs)
	}
	if tx.TotalRoundTrip == 0 && tx.TransportAckTimeNs > tx.ActionTimeNs {
		tx.TotalRoundTrip = time.Duration(tx.TransportAckTimeNs - tx.ActionTimeNs)
	}

	a.mu.Lock()
	defer a.mu.Unlock()

	a.transactions = append(a.transactions, tx)

	if tx.ActionToStateDuration > 0 {
		a.actionToStateDurations = append(a.actionToStateDurations, tx.ActionToStateDuration)
	}
	if tx.ActionToPersistDur > 0 {
		a.actionToPersistDurations = append(a.actionToPersistDurations, tx.ActionToPersistDur)
	}
	if tx.PersistToEmissionDur > 0 {
		a.persistToEmissionDur = append(a.persistToEmissionDur, tx.PersistToEmissionDur)
	}
	if tx.EmissionToVisibleDur > 0 {
		a.emissionToVisibleDur = append(a.emissionToVisibleDur, tx.EmissionToVisibleDur)
	}
	if tx.TotalActionToVisible > 0 {
		a.totalActionToVisibleDur = append(a.totalActionToVisibleDur, tx.TotalActionToVisible)
		b := CategorizeDuration(tx.TotalActionToVisible)
		a.visibleBuckets[b]++
	}
	if tx.TotalRoundTrip > 0 {
		a.totalRoundTripDur = append(a.totalRoundTripDur, tx.TotalRoundTrip)
	}
	if tx.GattWaitDuration > 0 {
		a.gattWaitDurations = append(a.gattWaitDurations, tx.GattWaitDuration)
	}
	if tx.BridgeLatency > 0 {
		a.bridgeDurations = append(a.bridgeDurations, tx.BridgeLatency)
	}
}

// RecordMainThreadWork adds a synthetic main-thread execution workload.
func (a *UXMetricsAggregator) RecordMainThreadWork(taskName string, duration time.Duration, timestampNs int64) {
	a.mu.Lock()
	defer a.mu.Unlock()

	// 16.6ms frame budget (60 fps)
	frameBudget := 16666 * time.Microsecond
	dropped := 0
	if duration > frameBudget {
		dropped = int(duration / frameBudget)
	}

	isANR := duration >= 5*time.Second

	rec := MainThreadWorkRecord{
		TimestampNs:      timestampNs,
		Duration:         duration,
		TaskName:         taskName,
		DroppedFrameProx: dropped,
		IsANRProxy:       isANR,
	}

	a.mainThreadWorkRecords = append(a.mainThreadWorkRecords, rec)
	a.totalDroppedFramesProxy += dropped
	if isANR {
		a.anrCountProxy++
	}
}

// SummaryReport provides a consolidated statistical overview of responsiveness metrics.
type SummaryReport struct {
	TotalTransactions       int                       `json:"total_transactions"`
	ActionToState           PercentileMetrics         `json:"action_to_state"`
	ActionToPersistence     PercentileMetrics         `json:"action_to_persistence"`
	PersistenceToEmission   PercentileMetrics         `json:"persistence_to_emission"`
	EmissionToVisible       PercentileMetrics         `json:"emission_to_visible"`
	TotalActionToVisible    PercentileMetrics         `json:"total_action_to_visible"`
	TotalRoundTrip          PercentileMetrics         `json:"total_round_trip"`
	GattWait                PercentileMetrics         `json:"gatt_wait"`
	BridgeLatency           PercentileMetrics         `json:"bridge_latency"`
	VisibleLatencyBuckets   map[LatencyBucket]int     `json:"visible_latency_buckets"`
	TotalDroppedFramesProxy int                       `json:"total_dropped_frames_proxy"`
	ANRProxyCount           int                       `json:"anr_proxy_count"`
}

// GenerateSummary calculates percentiles and distribution summaries.
func (a *UXMetricsAggregator) GenerateSummary() SummaryReport {
	a.mu.Lock()
	defer a.mu.Unlock()

	bucketCopy := make(map[LatencyBucket]int)
	for k, v := range a.visibleBuckets {
		bucketCopy[k] = v
	}

	return SummaryReport{
		TotalTransactions:       len(a.transactions),
		ActionToState:           calculatePercentiles(a.actionToStateDurations),
		ActionToPersistence:     calculatePercentiles(a.actionToPersistDurations),
		PersistenceToEmission:   calculatePercentiles(a.persistToEmissionDur),
		EmissionToVisible:       calculatePercentiles(a.emissionToVisibleDur),
		TotalActionToVisible:    calculatePercentiles(a.totalActionToVisibleDur),
		TotalRoundTrip:          calculatePercentiles(a.totalRoundTripDur),
		GattWait:                calculatePercentiles(a.gattWaitDurations),
		BridgeLatency:           calculatePercentiles(a.bridgeDurations),
		VisibleLatencyBuckets:   bucketCopy,
		TotalDroppedFramesProxy: a.totalDroppedFramesProxy,
		ANRProxyCount:           a.anrCountProxy,
	}
}

// PrintReport formats the summary in a readable console representation.
func (s SummaryReport) PrintReport() string {
	var out string
	out += fmt.Sprintf("Total Transactions Analyzed: %d\n", s.TotalTransactions)
	out += fmt.Sprintf("Action -> State (Optimistic): P50=%v, P95=%v, P99=%v, Max=%v\n",
		s.ActionToState.P50, s.ActionToState.P95, s.ActionToState.P99, s.ActionToState.Max)
	out += fmt.Sprintf("Action -> Persistence (Room): P50=%v, P95=%v, P99=%v, Max=%v\n",
		s.ActionToPersistence.P50, s.ActionToPersistence.P95, s.ActionToPersistence.P99, s.ActionToPersistence.Max)
	out += fmt.Sprintf("Persistence -> Emission:     P50=%v, P95=%v, P99=%v, Max=%v\n",
		s.PersistenceToEmission.P50, s.PersistenceToEmission.P95, s.PersistenceToEmission.P99, s.PersistenceToEmission.Max)
	out += fmt.Sprintf("Emission -> Visible:         P50=%v, P95=%v, P99=%v, Max=%v\n",
		s.EmissionToVisible.P50, s.EmissionToVisible.P95, s.EmissionToVisible.P99, s.EmissionToVisible.Max)
	out += fmt.Sprintf("Total Action -> Visible:     P50=%v, P95=%v, P99=%v, Max=%v\n",
		s.TotalActionToVisible.P50, s.TotalActionToVisible.P95, s.TotalActionToVisible.P99, s.TotalActionToVisible.Max)
	out += fmt.Sprintf("GATT Wait Duration:          P50=%v, P95=%v, P99=%v, Max=%v\n",
		s.GattWait.P50, s.GattWait.P95, s.GattWait.P99, s.GattWait.Max)
	out += fmt.Sprintf("Total Round Trip:            P50=%v, P95=%v, P99=%v, Max=%v\n",
		s.TotalRoundTrip.P50, s.TotalRoundTrip.P95, s.TotalRoundTrip.P99, s.TotalRoundTrip.Max)
	out += fmt.Sprintf("Main Thread Load: Dropped Frame Proxies=%d, ANR Proxies=%d\n",
		s.TotalDroppedFramesProxy, s.ANRProxyCount)
	return out
}
