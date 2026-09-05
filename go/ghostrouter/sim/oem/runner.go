package oem

import (
	"fmt"
	"runtime"
	"sync"
	"sync/atomic"
	"time"
)

// SOURCE: Stage 4 Android OEM Hostile Runtime Engine Architecture
// CONTRACT: Invariants O1 through O24 & multi-worker execution
// MODEL: Parallel scenario execution engine with deterministic seed distribution.

// CampaignConfig configures a multi-scenario campaign.
type CampaignConfig struct {
	TotalScenarios int
	BaseSeed       int64
	NumWorkers     int
	ProfileFilter  *OemProfileType // Optional filter for specific profile
	OnProgress     func(completed int, total int, passed int, failed int)
}

// ExecuteOemScenario runs a single scenario from start to finish on a virtual OemDevice.
func ExecuteOemScenario(scenario *OemScenario) (*ScenarioResult, error) {
	startWall := time.Now()

	dev := NewOemDevice(scenario.Seed, scenario.Profile)
	dev.StartBaseline()

	// Schedule all scenario events
	for _, event := range scenario.Events {
		ev := event
		dev.Scheduler.Schedule(ev.TimestampNs, 1, fmt.Sprintf("event_%s_%d", ev.Type, ev.TimestampNs), func() error {
			return dev.ExecuteEvent(ev)
		})
	}

	// Execute through active duration
	if err := dev.Scheduler.RunUntil(scenario.DurationNs); err != nil {
		return nil, fmt.Errorf("scenario %s execution error: %w", scenario.ID, err)
	}

	// Drain any trailing quiescent tasks
	if err := dev.Scheduler.RunAll(); err != nil {
		return nil, fmt.Errorf("scenario %s drain error: %w", scenario.ID, err)
	}

	// Evaluate all invariants
	violations := CheckAllInvariants(dev, scenario)

	result := &ScenarioResult{
		ScenarioID:        scenario.ID,
		ProfileType:       scenario.Profile.Type,
		Seed:              scenario.Seed,
		Passed:            len(violations) == 0,
		Violations:        violations,
		DurationNs:        scenario.DurationNs,
		MessagesSent:      len(dev.sentMessages),
		MessagesDelivered: len(dev.deliveredMessages),
		MessagesRelayed:   dev.thirdPartyRelayed,
		MessagesGated:     dev.thirdPartyRejected,
		ProcessKills:      dev.Process.TotalKills,
		ServiceRestarts:   dev.Service.TotalRestarts,
		Gatt133Count:      dev.Gatt.totalFailures,
		GattTimeouts:      dev.Gatt.totalWatchdogTimeouts,
		LateCallbacks:     dev.Gatt.lateCallbacksIgnored,
		ExecutionWallTime: time.Since(startWall),
	}

	return result, nil
}

// RunOemCampaign executes a full campaign of scenarios concurrently across workers.
func RunOemCampaign(cfg CampaignConfig) (*CampaignMetrics, []*ScenarioResult, error) {
	if cfg.TotalScenarios <= 0 {
		cfg.TotalScenarios = 1000
	}
	if cfg.NumWorkers <= 0 {
		cfg.NumWorkers = runtime.NumCPU()
	}
	if cfg.NumWorkers > 32 {
		cfg.NumWorkers = 32
	}

	generator := NewScenarioGenerator(cfg.BaseSeed)
	metrics := NewCampaignMetrics()
	results := make([]*ScenarioResult, cfg.TotalScenarios)

	profiles := []OemProfileType{
		ProfileOemStock,
		ProfileOemBackgroundAggressive,
		ProfileOemBleUnstable,
		ProfileOemMemoryPressure,
		ProfileOemBatteryAggressive,
		ProfileOemServiceHostile,
		ProfileOemMaximumHostility,
	}

	jobs := make(chan int, cfg.TotalScenarios)
	for i := 0; i < cfg.TotalScenarios; i++ {
		jobs <- i
	}
	close(jobs)

	var completedCount int64
	var passedCount int64
	var failedCount int64
	var wg sync.WaitGroup

	wallStart := time.Now()

	for w := 0; w < cfg.NumWorkers; w++ {
		wg.Add(1)
		go func() {
			defer wg.Done()

			for index := range jobs {
				var pType OemProfileType
				if cfg.ProfileFilter != nil {
					pType = *cfg.ProfileFilter
				} else {
					pType = profiles[index%len(profiles)]
				}

				scenario := generator.GenerateScenario(index, pType)
				res, err := ExecuteOemScenario(scenario)
				if err != nil {
					// Treat unhandled error as severe violation
					res = &ScenarioResult{
						ScenarioID:  scenario.ID,
						ProfileType: pType,
						Seed:        scenario.Seed,
						Passed:      false,
						Violations: []InvariantViolation{
							{
								ID:        O20_DeadlockFreeService,
								Severity:  SeverityP0,
								Scope:     ScopeModelValidated,
								Component: "Executor",
								Message:   err.Error(),
							},
						},
					}
				}

				results[index] = res
				metrics.RecordScenario(res)

				curCompleted := atomic.AddInt64(&completedCount, 1)
				if res.Passed {
					atomic.AddInt64(&passedCount, 1)
				} else {
					atomic.AddInt64(&failedCount, 1)
				}

				if cfg.OnProgress != nil && (curCompleted%500 == 0 || curCompleted == int64(cfg.TotalScenarios)) {
					cfg.OnProgress(int(curCompleted), cfg.TotalScenarios, int(atomic.LoadInt64(&passedCount)), int(atomic.LoadInt64(&failedCount)))
				}
			}
		}()
	}

	wg.Wait()
	metrics.TotalWallTime = time.Since(wallStart)

	return metrics, results, nil
}
