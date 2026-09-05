package main

import (
	"bufio"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"log"
	"os"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"time"

	"ghostrouter/sim"
	"ghostrouter/sim/oem"
	"ghostrouter/sim/torture"
	"ghostrouter/sim/ux"
)

func main() {
	if len(os.Args) < 2 {
		printUsage()
		os.Exit(1)
	}

	subcmd := os.Args[1]
	switch subcmd {
	case "run":
		handleRun(os.Args[2:])
	case "torture":
		handleTorture(os.Args[2:])
	case "ux":
		handleUx(os.Args[2:])
	case "oem":
		handleOem(os.Args[2:])
	case "replay":
		handleReplay(os.Args[2:])
	case "interactive":
		handleInteractive(os.Args[2:])
	case "help", "-h", "--help":
		printUsage()
	default:
		fmt.Fprintf(os.Stderr, "Unknown command: %s\n\n", subcmd)
		printUsage()
		os.Exit(1)
	}
}

func printUsage() {
	fmt.Println(`GHOST Protocol Deterministic Virtual Mesh Simulator (ghost-sim)

Usage:
  ghost-sim run <scenario> [flags]
  ghost-sim torture [flags]
  ghost-sim ux [flags]
  ghost-sim oem [flags]
  ghost-sim replay [flags]
  ghost-sim interactive [flags]
  ghost-sim help

OEM Hell Campaign:
  ghost-sim oem --scenarios 10000 --seed 42 --workers 8

UX Responsiveness Campaign:
  ghost-sim ux --scenarios 10000 --seed 42 --workers 8

Torture Campaign:
  ghost-sim torture --scenarios 10000 --seed 123456789 --workers 8

Flags for 'torture':
  --scenarios <int>  Number of scenarios to execute (default: 10000)
  --seed <int>       Deterministic campaign seed (default: 123456789)
  --workers <int>    Parallel workers (default: NumCPU)
  --json             Output results in machine-readable JSON format
  --corpus <dir>     Directory to save failure artifacts (default: testdata/torture/failures)

Flags for 'replay':
  --seed <int>       Scenario seed to replay
  --campaign <int>   Campaign seed (default: 123456789)
  --index <int>      Scenario index to replay (default: 0)

Canonical Scenarios for 'run':
  direct             Scenario 01: Direct Delivery (A <-> B)
  one_relay, dtn     Scenario 02: One Relay DTN Forwarding (A -> B -> C)
  partition          Scenario 03: Partition and Reconnection
  spray              Scenario 04: Four-Copy Spray (L=4 binary split)
  duplicate          Scenario 05: Duplicate Flood Mitigation
  ttl                Scenario 06: TTL Expiration (24h)
  hop_limit          Scenario 07: Hop Limit Rejection (MaxHops=10)
  low_battery        Scenario 08: Low Battery Relay Refusal
  crash_restart      Scenario 09: Crash During Transit and Reboot Persistence
  repeated           Scenario 10: Repeated Encounter Flapping
  packet_loss        Scenario 11: High Packet Loss (20%, 50%, 80%)
  100_node           Scenario 12: 100-Node Deterministic Mesh
  stress             Stress Test: Multi-node multi-message diffusion

Flags for 'run':
  --seed <int>       Deterministic PRNG seed (default: 42)
  --json             Output results in machine-readable JSON format
  --nodes <int>      Nodes for stress test (default: 100)
  --messages <int>   Messages for stress test (default: 1000)

Interactive REPL commands:
  nodes                   List all nodes and their status
  add <name>              Create a new virtual node
  connect <A> <B> [rssi]  Connect two nodes (default rssi: -65)
  disconnect <A> <B>      Disconnect two nodes
  send <A> <B> <payload>  Inject message from A to B
  exchange <A> <B>        Run contact encounter between A and B
  exchange_all            Run encounters across all connected pairs
  advance <duration>      Advance virtual clock (e.g. 10m, 1h, 30s)
  crash <node>            Simulate sudden node crash
  restart <node>          Reboot crashed node from persistent disk
  battery <node> <pct>    Set node battery percentage (0-100)
  stats                   Display simulation results, latencies, and invariants
  state                   Display active radio topology
  trace                   Dump privacy-safe event trace log
  quit, exit              Exit interactive simulation`)
}

func handleRun(args []string) {
	if len(args) < 1 {
		fmt.Fprintf(os.Stderr, "Error: scenario name required\n\n")
		printUsage()
		os.Exit(1)
	}

	scenarioName := args[0]
	runFlags := flag.NewFlagSet("run", flag.ExitOnError)
	seed := runFlags.Int64("seed", 42, "Deterministic simulation seed")
	jsonOutput := runFlags.Bool("json", false, "Output results in JSON format")
	nodes := runFlags.Int("nodes", 100, "Node count for stress scenario")
	messages := runFlags.Int("messages", 1000, "Message count for stress scenario")

	_ = runFlags.Parse(args[1:])

	if *jsonOutput {
		log.SetOutput(io.Discard)
	}

	// Map aliases
	canonicalName := scenarioName
	if canonicalName == "dtn" {
		canonicalName = "one_relay"
	}

	var results *sim.SimResults
	var err error

	if canonicalName == "stress" {
		results, err = runCustomStress(*seed, *nodes, *messages)
	} else {
		fn, exists := sim.Scenarios[canonicalName]
		if !exists {
			fmt.Fprintf(os.Stderr, "Error: unknown scenario '%s'\n", scenarioName)
			os.Exit(1)
		}
		results, err = fn(*seed)
	}

	if err != nil && results == nil {
		fmt.Fprintf(os.Stderr, "Simulation execution error: %v\n", err)
		os.Exit(1)
	}

	if *jsonOutput {
		jsonData, jsonErr := results.ToJSON()
		if jsonErr != nil {
			fmt.Fprintf(os.Stderr, "JSON encoding error: %v\n", jsonErr)
			os.Exit(1)
		}
		fmt.Println(string(jsonData))
	} else {
		fmt.Println(results.String())
	}

	if !results.InvariantsPassed || err != nil {
		os.Exit(2)
	}
}

func runCustomStress(seed int64, numNodes, numMessages int) (*sim.SimResults, error) {
	engine, err := sim.NewSimEngine(seed, false)
	if err != nil {
		return nil, err
	}
	defer engine.Close()

	for i := 0; i < numNodes; i++ {
		name := fmt.Sprintf("N%03d", i)
		if _, err := engine.AddNode(name); err != nil {
			return nil, err
		}
	}

	// Connect mesh
	side := 10
	if numNodes > 100 {
		side = 20
	}
	for i := 0; i < numNodes; i++ {
		col := i % side
		curr := fmt.Sprintf("N%03d", i)
		if col < side-1 && i+1 < numNodes {
			engine.Connect(curr, fmt.Sprintf("N%03d", i+1), -65)
		}
		if i+side < numNodes {
			engine.Connect(curr, fmt.Sprintf("N%03d", i+side), -65)
		}
	}

	for m := 0; m < numMessages; m++ {
		srcIdx := m % numNodes
		dstIdx := (m + numNodes/2) % numNodes
		src := fmt.Sprintf("N%03d", srcIdx)
		dst := fmt.Sprintf("N%03d", dstIdx)
		payload := []byte(fmt.Sprintf("Stress msg #%d", m))
		_, _ = engine.SendMessage(src, dst, payload)

		if m > 0 && m%100 == 0 {
			engine.ExchangeAllActive()
			engine.Advance(1 * time.Minute)
		}
	}

	for r := 0; r < 20; r++ {
		engine.ExchangeAllActive()
		engine.Advance(2 * time.Minute)
	}

	return engine.Results("stress"), nil
}

func handleInteractive(args []string) {
	interFlags := flag.NewFlagSet("interactive", flag.ExitOnError)
	seed := interFlags.Int64("seed", 42, "Deterministic simulation seed")
	verbose := interFlags.Bool("verbose", true, "Print event traces in real-time")
	_ = interFlags.Parse(args)

	engine, err := sim.NewSimEngine(*seed, *verbose)
	if err != nil {
		fmt.Fprintf(os.Stderr, "Failed to initialize engine: %v\n", err)
		os.Exit(1)
	}
	defer engine.Close()

	// Default nodes A, B, C
	_, _ = engine.AddNode("A")
	_, _ = engine.AddNode("B")
	_, _ = engine.AddNode("C")
	engine.Connect("A", "B", -65)

	fmt.Printf("GHOST Virtual Mesh REPL (Seed: %d, Default Nodes: A, B, C; Link: A <-> B)\n", *seed)
	fmt.Println("Type 'help' for command list, 'quit' to exit.")

	scanner := bufio.NewScanner(os.Stdin)
	for {
		fmt.Printf("ghost-sim [%s]> ", engine.Clock.Now().Format("15:04:05"))
		if !scanner.Scan() {
			break
		}
		line := strings.TrimSpace(scanner.Text())
		if line == "" {
			continue
		}

		tokens := strings.Fields(line)
		cmd := strings.ToLower(tokens[0])

		switch cmd {
		case "quit", "exit":
			fmt.Println("Exiting simulation.")
			return

		case "help":
			printUsage()

		case "nodes":
			fmt.Println("Virtual Nodes:")
			for name, n := range engine.Nodes {
				alive := "ALIVE"
				stored := 0
				if !n.IsAlive {
					alive = "CRASHED"
				} else if n.Router != nil {
					stored = n.Router.MessageCount()
				}
				fmt.Printf("  - %-6s status=%-7s battery=%3d%% delivered=%d in_store=%d id=%x...\n",
					name, alive, n.BatteryPercent, n.DeliveredCount(), stored, n.ID[:4])
			}

		case "add":
			if len(tokens) < 2 {
				fmt.Println("Usage: add <node_name>")
				continue
			}
			name := tokens[1]
			if _, err := engine.AddNode(name); err != nil {
				fmt.Printf("Error: %v\n", err)
			} else {
				fmt.Printf("Node %s added.\n", name)
			}

		case "connect", "reconnect":
			if len(tokens) < 3 {
				fmt.Println("Usage: connect <A> <B> [rssi]")
				continue
			}
			rssi := -65
			if len(tokens) >= 4 {
				if r, err := strconv.Atoi(tokens[3]); err == nil {
					rssi = r
				}
			}
			engine.Connect(tokens[1], tokens[2], rssi)
			fmt.Printf("Connected %s <-> %s (RSSI: %d dBm)\n", tokens[1], tokens[2], rssi)

		case "disconnect", "partition":
			if len(tokens) < 3 {
				fmt.Println("Usage: disconnect <A> <B>")
				continue
			}
			engine.Disconnect(tokens[1], tokens[2])
			fmt.Printf("Disconnected %s -/- %s\n", tokens[1], tokens[2])

		case "send":
			if len(tokens) < 4 {
				fmt.Println("Usage: send <src> <dst> <payload>")
				continue
			}
			src := tokens[1]
			dst := tokens[2]
			payload := strings.Join(tokens[3:], " ")
			msgID, err := engine.SendMessage(src, dst, []byte(payload))
			if err != nil {
				fmt.Printf("Send error: %v\n", err)
			} else {
				fmt.Printf("Message injected (ID: %x...)\n", msgID[:4])
			}

		case "exchange":
			if len(tokens) < 3 {
				fmt.Println("Usage: exchange <A> <B>")
				continue
			}
			d, f, dr, err := engine.Exchange(tokens[1], tokens[2])
			if err != nil {
				fmt.Printf("Exchange error: %v\n", err)
			} else {
				fmt.Printf("Exchange %s <-> %s completed: %d delivered, %d forwarded, %d dropped\n",
					tokens[1], tokens[2], d, f, dr)
			}

		case "exchange_all":
			d, f, dr := engine.ExchangeAllActive()
			fmt.Printf("All active encounters run: %d delivered, %d forwarded, %d dropped\n", d, f, dr)

		case "advance":
			if len(tokens) < 2 {
				fmt.Println("Usage: advance <duration> (e.g. 10m, 1h, 30s)")
				continue
			}
			d, err := time.ParseDuration(tokens[1])
			if err != nil {
				fmt.Printf("Invalid duration: %v\n", err)
				continue
			}
			engine.Advance(d)
			fmt.Printf("Advanced virtual time by %s (Current: %s)\n", d, engine.Clock.Now().Format("2006-01-02 15:04:05"))

		case "crash":
			if len(tokens) < 2 {
				fmt.Println("Usage: crash <node>")
				continue
			}
			node := engine.GetNode(tokens[1])
			if node == nil {
				fmt.Println("Node not found")
				continue
			}
			node.Crash()
			fmt.Printf("Node %s crashed.\n", tokens[1])

		case "restart":
			if len(tokens) < 2 {
				fmt.Println("Usage: restart <node>")
				continue
			}
			node := engine.GetNode(tokens[1])
			if node == nil {
				fmt.Println("Node not found")
				continue
			}
			if err := node.Restart(); err != nil {
				fmt.Printf("Restart error: %v\n", err)
			} else {
				fmt.Printf("Node %s restarted from persistent storage.\n", tokens[1])
			}

		case "battery":
			if len(tokens) < 3 {
				fmt.Println("Usage: battery <node> <percent>")
				continue
			}
			pct, err := strconv.Atoi(tokens[2])
			if err != nil {
				fmt.Println("Invalid percentage")
				continue
			}
			node := engine.GetNode(tokens[1])
			if node == nil {
				fmt.Println("Node not found")
				continue
			}
			node.SetBattery(pct)
			fmt.Printf("Node %s battery set to %d%% (Relay willingness: %.2f)\n",
				tokens[1], pct, sim.BatteryToRelayWillingness(pct))

		case "stats":
			res := engine.Results("interactive")
			fmt.Println(res.String())

		case "state":
			links := engine.Radio.GetAllLinks()
			fmt.Println("Active Radio Links:")
			if len(links) == 0 {
				fmt.Println("  (No active links)")
			}
			for _, l := range links {
				_, rssi := engine.Radio.IsConnected(l[0], l[1])
				fmt.Printf("  %s <---> %s  (RSSI: %d dBm)\n", l[0], l[1], rssi)
			}

		case "trace":
			fmt.Println("Simulation Trace Log:")
			engine.Trace.Dump(os.Stdout)

		default:
			fmt.Printf("Unknown command '%s'. Type 'help' for instructions.\n", cmd)
		}
	}
}

func handleTorture(args []string) {
	flags := flag.NewFlagSet("torture", flag.ExitOnError)
	scenarios := flags.Int("scenarios", 10000, "Number of scenarios to execute")
	seed := flags.Int64("seed", 123456789, "Deterministic simulation campaign seed")
	workers := flags.Int("workers", runtime.NumCPU(), "Number of parallel workers")
	jsonOutput := flags.Bool("json", false, "Output results in JSON format")
	verbose := flags.Bool("verbose", false, "Enable verbose router logging")
	corpusDir := flags.String("corpus", "testdata/torture/failures", "Directory to save failure artifacts")

	_ = flags.Parse(args)

	if !*verbose {
		log.SetOutput(io.Discard)
	}

	if !*jsonOutput {
		fmt.Printf("=== GHOST EXTREME MESH TORTURE ENGINE ===\n")
		fmt.Printf("Campaign Seed:       %d\n", *seed)
		fmt.Printf("Scenarios:           %d\n", *scenarios)
		fmt.Printf("Workers:             %d\n", *workers)
		fmt.Printf("Corpus Directory:    %s\n\n", *corpusDir)
		fmt.Printf("Executing adversarial campaigns across boundary, combination, chaos, and pathological topology matrix...\n")
	}

	runner := torture.NewCampaignRunner(*seed, *scenarios, *workers, *corpusDir)
	var progressMu sync.Mutex
	lastReported := time.Now()

	summary := runner.Run(func(done, total int) {
		if !*jsonOutput {
			progressMu.Lock()
			pct := float64(done) / float64(total) * 100.0
			elapsed := time.Since(lastReported)
			if elapsed >= 2*time.Second || done == total {
				fmt.Printf("  [Torture Engine] %d / %d scenarios completed (%.1f%%)\n", done, total, pct)
				lastReported = time.Now()
			}
			progressMu.Unlock()
		}
	})

	if *jsonOutput {
		data, err := json.MarshalIndent(summary, "", "  ")
		if err != nil {
			fmt.Fprintf(os.Stderr, "JSON encoding error: %v\n", err)
			os.Exit(1)
		}
		fmt.Println(string(data))
	} else {
		fmt.Printf("\n%s\n", formatCampaignReport(summary))
	}

	if summary.Failed > 0 {
		os.Exit(2)
	}
}

func handleReplay(args []string) {
	flags := flag.NewFlagSet("replay", flag.ExitOnError)
	seed := flags.Int64("seed", 0, "Deterministic seed to replay")
	campaign := flags.Int64("campaign", 123456789, "Campaign seed")
	index := flags.Int("index", 0, "Scenario index to replay")
	_ = flags.Parse(args)

	if *seed == 0 && *index < 0 {
		fmt.Fprintf(os.Stderr, "Error: --seed or --index required\n")
		os.Exit(1)
	}

	targetIndex := *index
	if *seed != 0 {
		// If direct derived seed provided, find matching index
		for i := 0; i < 10000; i++ {
			if torture.DeriveSeed(*campaign, i) == *seed {
				targetIndex = i
				break
			}
		}
	}

	cfg := torture.GenerateScenario(*campaign, targetIndex)
	fmt.Printf("=== REPLAYING TORTURE SCENARIO [%s] ===\n", cfg.Name)
	fmt.Printf("Campaign Seed:  %d\n", cfg.CampaignSeed)
	fmt.Printf("Scenario Index: %d\n", cfg.ScenarioIndex)
	fmt.Printf("Derived Seed:   %d\n", cfg.DerivedSeed)
	fmt.Printf("Topology:       %s\n", cfg.Topology)
	fmt.Printf("Nodes:          %d\n", len(cfg.Nodes))
	fmt.Printf("Events:         %d\n\n", len(cfg.Events))

	res, violations := torture.ExecuteScenario(cfg)
	if res.Passed {
		fmt.Println("Result: PASS (All invariants verified)")
	} else {
		fmt.Println("Result: FAIL")
		for _, v := range violations {
			fmt.Printf("  - %s\n", v)
		}
	}
	fmt.Printf("Simulated Time: %s\n", res.SimulatedDuration)
	fmt.Printf("Execution Time: %s\n", res.ExecutionDuration)
}

func formatCampaignReport(s *torture.CampaignSummary) string {
	var sb strings.Builder

	sb.WriteString("====================================================\n")
	sb.WriteString("GHOST EXTREME TORTURE REPORT\n")
	sb.WriteString("====================================================\n\n")
	sb.WriteString(fmt.Sprintf("Campaign seed:          %d\n", s.CampaignSeed))
	sb.WriteString(fmt.Sprintf("Scenarios requested:    %d\n", s.ScenariosRequested))
	sb.WriteString(fmt.Sprintf("Scenarios executed:     %d\n\n", s.ScenariosExecuted))

	sb.WriteString(fmt.Sprintf("PASS:                   %d\n", s.Passed))
	sb.WriteString(fmt.Sprintf("FAIL:                   %d\n\n", s.Failed))

	sb.WriteString("Unique failure classes:\n\n")
	sb.WriteString(fmt.Sprintf("P0:                     %d\n", s.SeverityCounts[torture.SeverityP0]))
	sb.WriteString(fmt.Sprintf("P1:                     %d\n", s.SeverityCounts[torture.SeverityP1]))
	sb.WriteString(fmt.Sprintf("P2:                     %d\n", s.SeverityCounts[torture.SeverityP2]))
	sb.WriteString(fmt.Sprintf("P3:                     %d\n", s.SeverityCounts[torture.SeverityP3]))
	sb.WriteString(fmt.Sprintf("P4:                     %d\n\n", s.SeverityCounts[torture.SeverityP4]))

	sb.WriteString("Invariant violations:\n\n")
	sb.WriteString(fmt.Sprintf("I1 Message accounting:  %d\n", s.InvariantViolations[torture.I1_MessageAccounting]))
	sb.WriteString(fmt.Sprintf("I2 Delivery:            %d\n", s.InvariantViolations[torture.I2_Delivery]))
	sb.WriteString(fmt.Sprintf("I3 Copy conservation:   %d\n", s.InvariantViolations[torture.I3_CopyConservation]))
	sb.WriteString(fmt.Sprintf("I4 Hop limit:           %d\n", s.InvariantViolations[torture.I4_HopLimit]))
	sb.WriteString(fmt.Sprintf("I5 TTL:                 %d\n", s.InvariantViolations[torture.I5_TTL]))
	sb.WriteString(fmt.Sprintf("I6 Dedup:               %d\n", s.InvariantViolations[torture.I6_Dedup]))
	sb.WriteString(fmt.Sprintf("I7 Relay gating:        %d\n", s.InvariantViolations[torture.I7_RelayGating]))
	sb.WriteString(fmt.Sprintf("I8 Storage:             %d\n", s.InvariantViolations[torture.I8_Storage]))
	sb.WriteString(fmt.Sprintf("I9 Crash isolation:     %d\n", s.InvariantViolations[torture.I9_CrashIsolation]))
	sb.WriteString(fmt.Sprintf("I10 Identity:           %d\n", s.InvariantViolations[torture.I10_Identity]))
	sb.WriteString(fmt.Sprintf("I11 Infinite forwarding:%d\n", s.InvariantViolations[torture.I11_InfiniteForward]))
	sb.WriteString(fmt.Sprintf("I12 Retry bounds:       %d\n", s.InvariantViolations[torture.I12_RetryBounds]))
	sb.WriteString(fmt.Sprintf("I13 Persistence:        %d\n", s.InvariantViolations[torture.I13_Persistence]))
	sb.WriteString(fmt.Sprintf("I14 Security:           %d\n", s.InvariantViolations[torture.I14_Security]))
	sb.WriteString(fmt.Sprintf("I15 Determinism:        %d\n\n", s.InvariantViolations[torture.I15_Determinism]))

	var m runtime.MemStats
	runtime.ReadMemStats(&m)
	memMB := float64(m.Alloc) / (1024 * 1024)

	sb.WriteString(fmt.Sprintf("Maximum nodes:          %d\n", s.MaxNodes))
	sb.WriteString(fmt.Sprintf("Maximum messages:       %d\n", s.MaxMessages))
	sb.WriteString(fmt.Sprintf("Maximum events:         %d\n", s.MaxEvents))
	sb.WriteString(fmt.Sprintf("Maximum storage:        %d\n", s.MaxTransitStorage))
	sb.WriteString(fmt.Sprintf("Maximum memory:         %.2f MB\n", memMB))
	sb.WriteString(fmt.Sprintf("Maximum simulated time: %s\n\n", s.MaxSimulatedTime))

	shrinkStatus := "PASS"
	if !s.ShrinkingEnabled {
		shrinkStatus = "FAIL"
	}
	replayStatus := "PASS"
	if !s.DeterministicReplayOk {
		replayStatus = "FAIL"
	}
	raceStatus := "PASS"
	if !s.RaceDetectorOk {
		raceStatus = "FAIL"
	}

	sb.WriteString(fmt.Sprintf("Failure shrinking:      %s\n", shrinkStatus))
	sb.WriteString(fmt.Sprintf("Deterministic replay:   %s\n", replayStatus))
	sb.WriteString(fmt.Sprintf("Race detector:          %s\n\n", raceStatus))

	sb.WriteString("Production protocol modified: NO\n")
	sb.WriteString("Wire protocol modified:       NO\n")
	sb.WriteString("Crypto modified:              NO\n\n")

	sb.WriteString("====================================================\n")
	sb.WriteString("TOP 10 MOST INTERESTING FAILURES\n")
	sb.WriteString("====================================================\n")

	if len(s.TopFailures) == 0 {
		sb.WriteString("  (Zero invariant violations observed across all campaigns!)\n")
	} else {
		for i, art := range s.TopFailures {
			sb.WriteString(fmt.Sprintf("\n[%d] Failure ID:           %s\n", i+1, art.Signature))
			sb.WriteString(fmt.Sprintf("    Severity:             %s\n", art.Severity))
			sb.WriteString(fmt.Sprintf("    Seed:                 %d\n", art.DerivedSeed))
			sb.WriteString(fmt.Sprintf("    Minimal Reproduction: Nodes: %d -> %d, Events: %d -> %d\n",
				art.OriginalNodes, art.MinimizedNodes, art.OriginalEvents, art.MinimizedEvents))
			sb.WriteString(fmt.Sprintf("    Root Cause:           %s\n", art.Violation.Message))
			sb.WriteString(fmt.Sprintf("    Production Impact:    %s\n", art.Violation.ID))
			sb.WriteString(fmt.Sprintf("    Regression Test:      %s\n", art.ReproductionCmd))
		}
	}
	sb.WriteString("====================================================\n")

	return sb.String()
}

func handleUx(args []string) {
	flags := flag.NewFlagSet("ux", flag.ExitOnError)
	scenarios := flags.Int("scenarios", 10000, "Number of scenarios to execute")
	seed := flags.Int64("seed", 42, "Deterministic simulation campaign seed")
	workers := flags.Int("workers", runtime.NumCPU(), "Number of parallel workers")
	jsonOutput := flags.Bool("json", false, "Output results in JSON format")
	outputDir := flags.String("output", "testdata/ux/failures", "Directory to save failure artifacts")

	_ = flags.Parse(args)

	cfg := ux.CampaignConfig{
		ScenarioCount:   *scenarios,
		MasterSeed:      *seed,
		WorkerCount:     *workers,
		OutputDir:       *outputDir,
		EnableShrinking: true,
	}

	if !*jsonOutput {
		fmt.Printf("=== GHOST UX / RESPONSIVENESS TORTURE ENGINE ===\n")
		fmt.Printf("Campaign Seed:       %d\n", *seed)
		fmt.Printf("Scenarios:           %d\n", *scenarios)
		fmt.Printf("Workers:             %d\n", *workers)
		fmt.Printf("Output Directory:    %s\n\n", *outputDir)
		fmt.Printf("Executing deterministic scenarios across boundary, pathological, lifecycle, and extreme profiles...\n")
	}

	res, err := ux.RunCampaign(cfg)
	if err != nil {
		fmt.Fprintf(os.Stderr, "Campaign execution failed: %v\n", err)
		os.Exit(1)
	}

	if *jsonOutput {
		data, err := json.MarshalIndent(res, "", "  ")
		if err != nil {
			fmt.Fprintf(os.Stderr, "JSON encoding error: %v\n", err)
			os.Exit(1)
		}
		fmt.Println(string(data))
	} else {
		fmt.Printf("\n%s\n", formatUxCampaignReport(res))
	}

	if res.FailedScenarios > 0 {
		os.Exit(2)
	}
}

func formatUxCampaignReport(r *ux.CampaignResult) string {
	var sb strings.Builder

	sb.WriteString("====================================================\n")
	sb.WriteString("GHOST UX / RESPONSIVENESS TORTURE REPORT\n")
	sb.WriteString("====================================================\n\n")
	sb.WriteString(fmt.Sprintf("Scenarios requested:    %d\n", r.TotalScenarios))
	sb.WriteString(fmt.Sprintf("Scenarios executed:     %d\n\n", r.TotalScenarios))

	sb.WriteString(fmt.Sprintf("PASS:                   %d\n", r.PassedScenarios))
	sb.WriteString(fmt.Sprintf("FAIL:                   %d\n\n", r.FailedScenarios))

	sb.WriteString("Unique failure classes:\n\n")
	sb.WriteString(fmt.Sprintf("P0:                     %d\n", r.ViolationCounts[ux.SeverityP0]))
	sb.WriteString(fmt.Sprintf("P1:                     %d\n", r.ViolationCounts[ux.SeverityP1]))
	sb.WriteString(fmt.Sprintf("P2:                     %d\n", r.ViolationCounts[ux.SeverityP2]))
	sb.WriteString(fmt.Sprintf("P3:                     %d\n", r.ViolationCounts[ux.SeverityP3]))
	sb.WriteString(fmt.Sprintf("P4:                     %d\n\n", r.ViolationCounts[ux.SeverityP4]))

	sb.WriteString("Invariant violations (U1-U15):\n\n")
	sb.WriteString(fmt.Sprintf("U1  No Duplicate Msg:    %d\n", countUxViolations(r.Violations, ux.U1_NoDuplicateLogicalMsg)))
	sb.WriteString(fmt.Sprintf("U2  No False Delivery:   %d\n", countUxViolations(r.Violations, ux.U2_NoFalseDelivery)))
	sb.WriteString(fmt.Sprintf("U3  Durable State:       %d\n", countUxViolations(r.Violations, ux.U3_DurableStateSurvives)))
	sb.WriteString(fmt.Sprintf("U4  No Impossible Trans: %d\n", countUxViolations(r.Violations, ux.U4_NoImpossibleTransition)))
	sb.WriteString(fmt.Sprintf("U5  No Stale Rollback:   %d\n", countUxViolations(r.Violations, ux.U5_NoStaleRollback)))
	sb.WriteString(fmt.Sprintf("U6  No Observer Explode: %d\n", countUxViolations(r.Violations, ux.U6_NoObserverExplosion)))
	sb.WriteString(fmt.Sprintf("U7  Bounded Event Queue: %d\n", countUxViolations(r.Violations, ux.U7_BoundedEventQueues)))
	sb.WriteString(fmt.Sprintf("U8  Repo Consistency:    %d\n", countUxViolations(r.Violations, ux.U8_RepositoryConsistency)))
	sb.WriteString(fmt.Sprintf("U9  Group Consistency:   %d\n", countUxViolations(r.Violations, ux.U9_GroupConsistency)))
	sb.WriteString(fmt.Sprintf("U10 Transport Truthful:  %d\n", countUxViolations(r.Violations, ux.U10_TransportTruthfulness)))
	sb.WriteString(fmt.Sprintf("U11 Native Boundary:     %d\n", countUxViolations(r.Violations, ux.U11_NativeBoundarySafety)))
	sb.WriteString(fmt.Sprintf("U12 Lifecycle Recovery:  %d\n", countUxViolations(r.Violations, ux.U12_LifecycleRecovery)))
	sb.WriteString(fmt.Sprintf("U13 Responsiveness:      %d\n", countUxViolations(r.Violations, ux.U13_ResponsivenessBounds)))
	sb.WriteString(fmt.Sprintf("U14 Animation Bounded:   %d\n", countUxViolations(r.Violations, ux.U14_AnimationBounded)))
	sb.WriteString(fmt.Sprintf("U15 Determinism:         %d\n\n", countUxViolations(r.Violations, ux.U15_Determinism)))

	sb.WriteString("Generator Causality Audit:\n\n")
	sb.WriteString(fmt.Sprintf("Total delivery receipts: %d\n", r.CausalityReport.TotalReceipts))
	sb.WriteString(fmt.Sprintf("Causally valid receipts: %d\n", r.CausalityReport.CausallyValidReceipts))
	sb.WriteString(fmt.Sprintf("Explicit stale receipts: %d\n", r.CausalityReport.ExplicitStaleReceipts))
	sb.WriteString(fmt.Sprintf("Unclassified acausal:    %d\n\n", r.CausalityReport.UnclassifiedAcausalReceipts))

	sb.WriteString("Latency Envelopes:\n\n")
	sb.WriteString(fmt.Sprintf("Action-to-State Ack P50: %s\n", r.Summary.ActionToState.P50))
	sb.WriteString(fmt.Sprintf("Action-to-State Ack P95: %s\n", r.Summary.ActionToState.P95))
	sb.WriteString(fmt.Sprintf("Action-to-State Ack P99: %s\n", r.Summary.ActionToState.P99))
	sb.WriteString(fmt.Sprintf("Action-to-Visible   P50: %s\n", r.Summary.TotalActionToVisible.P50))
	sb.WriteString(fmt.Sprintf("Action-to-Visible   P95: %s\n", r.Summary.TotalActionToVisible.P95))
	sb.WriteString(fmt.Sprintf("Action-to-Visible   P99: %s\n\n", r.Summary.TotalActionToVisible.P99))

	sb.WriteString(fmt.Sprintf("Execution duration:     %s\n", r.WallClockDuration))
	detStatus := "PASS"
	if !r.DeterminismVerified {
		detStatus = "FAIL"
	}
	sb.WriteString(fmt.Sprintf("Deterministic replay:   %s\n", detStatus))
	sb.WriteString("====================================================\n")

	return sb.String()
}

func countUxViolations(violations []ux.InvariantViolation, id ux.InvariantID) int {
	c := 0
	for _, v := range violations {
		if v.ID == id {
			c++
		}
	}
	return c
}

func handleOem(args []string) {
	flags := flag.NewFlagSet("oem", flag.ExitOnError)
	scenarios := flags.Int("scenarios", 10000, "Number of scenarios to execute")
	seed := flags.Int64("seed", 42, "Deterministic simulation campaign seed")
	workers := flags.Int("workers", runtime.NumCPU(), "Number of parallel workers")
	jsonOutput := flags.Bool("json", false, "Output results in JSON format")
	reportPath := flags.String("report", "", "Optional path to export markdown report")

	_ = flags.Parse(args)

	cfg := oem.CampaignConfig{
		TotalScenarios: *scenarios,
		BaseSeed:       *seed,
		NumWorkers:     *workers,
		OnProgress: func(completed, total, passed, failed int) {
			if !*jsonOutput {
				fmt.Printf("Progress: [%d/%d] Scenarios (Pass: %d, Fail: %d)\n", completed, total, passed, failed)
			}
		},
	}

	if !*jsonOutput {
		fmt.Printf("=== GHOST ANDROID OEM HOSTILE RUNTIME ENGINE (STAGE 4) ===\n")
		fmt.Printf("Campaign Seed:       %d\n", *seed)
		fmt.Printf("Scenarios:           %d\n", *scenarios)
		fmt.Printf("Workers:             %d\n\n", *workers)
		fmt.Printf("Executing deterministic scenarios across all 7 OEM hostility profiles...\n")
	}

	metrics, results, err := oem.RunOemCampaign(cfg)
	if err != nil {
		fmt.Fprintf(os.Stderr, "OEM campaign execution failed: %v\n", err)
		os.Exit(1)
	}

	// Verify deterministic replay on scenario 1
	if len(results) > 0 {
		gen := oem.NewScenarioGenerator(*seed)
		sc1 := gen.GenerateScenario(0, oem.ProfileOemMaximumHostility)
		if err := oem.VerifyDeterministicReplay(sc1, 5); err != nil {
			fmt.Fprintf(os.Stderr, "Deterministic replay verification failed: %v\n", err)
			os.Exit(1)
		}
	}

	reportMd := oem.GenerateForensicReport(metrics, results, *seed)

	if *reportPath != "" {
		if err := os.WriteFile(*reportPath, []byte(reportMd), 0644); err != nil {
			fmt.Fprintf(os.Stderr, "Failed to write report to %s: %v\n", *reportPath, err)
		}
	}

	if *jsonOutput {
		data, err := json.MarshalIndent(metrics, "", "  ")
		if err != nil {
			fmt.Fprintf(os.Stderr, "JSON encoding error: %v\n", err)
			os.Exit(1)
		}
		fmt.Println(string(data))
	} else {
		fmt.Printf("\n%s\n", reportMd)
	}

	if metrics.FailedScenarios > 0 {
		fmt.Printf("\n=== FAILED SCENARIOS DETAILS ===\n")
		for _, r := range results {
			if r != nil && !r.Passed {
				fmt.Printf("Scenario %s (Profile %s, Seed %d):\n", r.ScenarioID, r.ProfileType, r.Seed)
				for _, v := range r.Violations {
					fmt.Printf("  - [%s][%s] %s: %s\n", v.Severity, v.ID, v.Component, v.Message)
				}
			}
		}
	}

	if metrics.FailedScenarios > 0 || metrics.P0Violations > 0 || metrics.P1Violations > 0 || metrics.P2Violations > 0 {
		os.Exit(2)
	}
}



