package sim

import (
	"fmt"
	"math/rand"
	"sync"
)

// RadioLink represents a directional or bidirectional communication link between two nodes.
type RadioLink struct {
	RSSI     int     // RSSI in dBm (e.g. -65)
	LossRate float64 // 0.0 (perfect) to 1.0 (100% loss)
}

// VirtualRadio manages the virtual RF topology and deterministic channel characteristics.
type VirtualRadio struct {
	mu         sync.RWMutex
	links      map[string]map[string]*RadioLink
	globalLoss float64
	rng        *rand.Rand
	trace      *TraceLogger
}

// NewVirtualRadio creates a virtual radio environment seeded deterministically.
func NewVirtualRadio(seed int64, trace *TraceLogger) *VirtualRadio {
	return &VirtualRadio{
		links: make(map[string]map[string]*RadioLink),
		rng:   rand.New(rand.NewSource(seed)),
		trace: trace,
	}
}

// Connect establishes a bidirectional virtual radio link between nodeA and nodeB.
func (r *VirtualRadio) Connect(a, b string, rssi int) {
	r.mu.Lock()
	defer r.mu.Unlock()

	if rssi == 0 {
		rssi = -65 // Default BLE RSSI
	}

	r.ensureMap(a)[b] = &RadioLink{RSSI: rssi, LossRate: 0.0}
	r.ensureMap(b)[a] = &RadioLink{RSSI: rssi, LossRate: 0.0}

	if r.trace != nil {
		r.trace.Log(TraceEntry{
			Type:   TraceContactUp,
			Source: a,
			Dest:   b,
		})
	}
}

// Disconnect breaks the link between nodeA and nodeB.
func (r *VirtualRadio) Disconnect(a, b string) {
	r.mu.Lock()
	defer r.mu.Unlock()

	if m, ok := r.links[a]; ok {
		delete(m, b)
	}
	if m, ok := r.links[b]; ok {
		delete(m, a)
	}

	if r.trace != nil {
		r.trace.Log(TraceEntry{
			Type:   TraceContactDown,
			Source: a,
			Dest:   b,
		})
	}
}

// IsConnected returns whether a direct link exists from a to b and its RSSI.
func (r *VirtualRadio) IsConnected(a, b string) (bool, int) {
	r.mu.RLock()
	defer r.mu.RUnlock()

	if m, ok := r.links[a]; ok {
		if link, ok2 := m[b]; ok2 {
			return true, link.RSSI
		}
	}
	return false, 0
}

// SetLinkLoss sets a specific packet loss rate for communication between a and b.
func (r *VirtualRadio) SetLinkLoss(a, b string, lossRate float64) {
	r.mu.Lock()
	defer r.mu.Unlock()

	if lossRate < 0 {
		lossRate = 0
	} else if lossRate > 1.0 {
		lossRate = 1.0
	}

	if m, ok := r.links[a]; ok {
		if link, ok2 := m[b]; ok2 {
			link.LossRate = lossRate
		}
	}
	if m, ok := r.links[b]; ok {
		if link, ok2 := m[a]; ok2 {
			link.LossRate = lossRate
		}
	}
}

// SetGlobalLoss sets a global default loss rate applied when link-specific loss is zero.
func (r *VirtualRadio) SetGlobalLoss(lossRate float64) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if lossRate < 0 {
		lossRate = 0
	} else if lossRate > 1.0 {
		lossRate = 1.0
	}
	r.globalLoss = lossRate
}

// ShouldDrop deterministically calculates whether a transmission from src to dst should be dropped.
func (r *VirtualRadio) ShouldDrop(src, dst string) bool {
	r.mu.Lock()
	defer r.mu.Unlock()

	effectiveLoss := r.globalLoss
	if m, ok := r.links[src]; ok {
		if link, ok2 := m[dst]; ok2 && link.LossRate > 0 {
			effectiveLoss = link.LossRate
		}
	}

	if effectiveLoss <= 0 {
		return false
	}
	if effectiveLoss >= 1.0 {
		return true
	}

	return r.rng.Float64() < effectiveLoss
}

// GetNeighbors returns a slice of node names currently linked to node.
func (r *VirtualRadio) GetNeighbors(node string) []string {
	r.mu.RLock()
	defer r.mu.RUnlock()

	neighbors := make([]string, 0)
	if m, ok := r.links[node]; ok {
		for n := range m {
			neighbors = append(neighbors, n)
		}
	}
	return neighbors
}

// GetAllLinks returns a snapshot of all active undirected links.
func (r *VirtualRadio) GetAllLinks() [][2]string {
	r.mu.RLock()
	defer r.mu.RUnlock()

	seen := make(map[string]bool)
	links := make([][2]string, 0)

	for a, m := range r.links {
		for b := range m {
			key := fmt.Sprintf("%s-%s", a, b)
			revKey := fmt.Sprintf("%s-%s", b, a)
			if !seen[key] && !seen[revKey] {
				seen[key] = true
				links = append(links, [2]string{a, b})
			}
		}
	}
	return links
}

func (r *VirtualRadio) ensureMap(node string) map[string]*RadioLink {
	if _, ok := r.links[node]; !ok {
		r.links[node] = make(map[string]*RadioLink)
	}
	return r.links[node]
}
