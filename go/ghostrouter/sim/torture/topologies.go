package torture

import (
	"fmt"
	"math/rand"
)

// TopologyType defines pathological and standard graph shapes for torture testing.
type TopologyType string

const (
	TopologyChain        TopologyType = "chain"
	TopologyStar         TopologyType = "star"
	TopologyRing         TopologyType = "ring"
	TopologyComplete     TopologyType = "complete"
	TopologySparse       TopologyType = "sparse"
	TopologyIslands      TopologyType = "islands"
	TopologyBridge       TopologyType = "bridge"
	TopologyBottleneck   TopologyType = "bottleneck"
	TopologyDynamic      TopologyType = "dynamic"
	TopologyOscillating  TopologyType = "oscillating"
	TopologyHubFailure   TopologyType = "hub_failure"
	TopologyRapidChurn   TopologyType = "rapid_churn"
)

// GenerateLinks creates undirected edges [][2]string between node names for the specified topology.
func GenerateLinks(topType TopologyType, nodes []string, rng *rand.Rand) [][2]string {
	if rng == nil {
		rng = rand.New(rand.NewSource(42))
	}
	n := len(nodes)
	if n <= 1 {
		return nil
	}

	links := make([][2]string, 0)
	added := make(map[string]bool)

	addLink := func(a, b string) {
		if a == b {
			return
		}
		key1 := fmt.Sprintf("%s:%s", a, b)
		key2 := fmt.Sprintf("%s:%s", b, a)
		if added[key1] || added[key2] {
			return
		}
		added[key1] = true
		added[key2] = true
		links = append(links, [2]string{a, b})
	}

	switch topType {
	case TopologyChain:
		for i := 0; i < n-1; i++ {
			addLink(nodes[i], nodes[i+1])
		}

	case TopologyStar, TopologyHubFailure:
		hub := nodes[0]
		for i := 1; i < n; i++ {
			addLink(hub, nodes[i])
		}

	case TopologyRing:
		for i := 0; i < n; i++ {
			addLink(nodes[i], nodes[(i+1)%n])
		}

	case TopologyComplete:
		for i := 0; i < n; i++ {
			for j := i + 1; j < n; j++ {
				addLink(nodes[i], nodes[j])
			}
		}

	case TopologySparse:
		// Ensure at least tree or partial connectivity, plus a few random edges
		for i := 0; i < n-1; i++ {
			if rng.Float64() < 0.75 {
				addLink(nodes[i], nodes[i+1])
			}
		}
		for i := 0; i < n; i++ {
			for j := i + 2; j < n; j++ {
				if rng.Float64() < 0.15 {
					addLink(nodes[i], nodes[j])
				}
			}
		}

	case TopologyIslands:
		// Divide nodes into 2 or 3 disconnected clusters
		clusters := 2
		if n >= 6 {
			clusters = 3
		}
		chunkSize := (n + clusters - 1) / clusters
		for c := 0; c < clusters; c++ {
			start := c * chunkSize
			end := (c + 1) * chunkSize
			if end > n {
				end = n
			}
			if start >= n || end <= start {
				continue
			}
			// Ring or line inside cluster
			for i := start; i < end-1; i++ {
				addLink(nodes[i], nodes[i+1])
			}
			if end-start > 2 {
				addLink(nodes[end-1], nodes[start])
			}
		}

	case TopologyBridge:
		if n < 3 {
			addLink(nodes[0], nodes[1])
			return links
		}
		bridgeIdx := n / 2
		bridgeNode := nodes[bridgeIdx]

		// Cluster A: 0..bridgeIdx-1
		for i := 0; i < bridgeIdx-1; i++ {
			addLink(nodes[i], nodes[i+1])
		}
		if bridgeIdx > 0 {
			addLink(nodes[bridgeIdx-1], bridgeNode)
		}

		// Cluster B: bridgeIdx+1..n-1
		for i := bridgeIdx + 1; i < n-1; i++ {
			addLink(nodes[i], nodes[i+1])
		}
		if bridgeIdx+1 < n {
			addLink(bridgeNode, nodes[bridgeIdx+1])
		}

	case TopologyBottleneck:
		if n < 3 {
			addLink(nodes[0], nodes[1])
			return links
		}
		relay := nodes[0]
		// All nodes connect only to relay, bottlenecking all traffic
		for i := 1; i < n; i++ {
			addLink(nodes[i], relay)
		}

	case TopologyDynamic, TopologyRapidChurn:
		// Initial subset of links (approx 40% density)
		for i := 0; i < n; i++ {
			for j := i + 1; j < n; j++ {
				if rng.Float64() < 0.4 {
					addLink(nodes[i], nodes[j])
				}
			}
		}

	case TopologyOscillating:
		// Base chain + alternating cross-links
		for i := 0; i < n-1; i++ {
			addLink(nodes[i], nodes[i+1])
		}

	default:
		// Default to chain
		for i := 0; i < n-1; i++ {
			addLink(nodes[i], nodes[i+1])
		}
	}

	return links
}
