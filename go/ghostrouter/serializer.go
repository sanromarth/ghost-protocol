package ghostrouter

import (
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
)

// EncodeMessage serializes a Message for BLE transmission.
// Format: [4 bytes: headerLen, big-endian uint32] [headerLen bytes: JSON RoutingHeader] [remaining: Payload]
func EncodeMessage(msg *Message) []byte {
	header := RoutingHeader{
		MessageID:       msg.ID,
		Src:             msg.Src,
		Dst:             msg.Dst,
		CopiesRemaining: msg.CopiesRemaining,
		TTLSeconds:      msg.TTLSeconds,
		HopCount:        msg.HopCount,
		CreatedAt:       msg.CreatedAt,
	}

	headerBytes, err := json.Marshal(header)
	if err != nil {
		headerBytes = []byte("{}")
	}

	headerLen := uint32(len(headerBytes))
	result := make([]byte, 4+len(headerBytes)+len(msg.Payload))

	binary.BigEndian.PutUint32(result[0:4], headerLen)
	copy(result[4:4+headerLen], headerBytes)
	copy(result[4+headerLen:], msg.Payload)

	return result
}

// DecodeResult holds the result of decoding a wire-format message.
// gomobile requires at most (value, error) return signature.
type DecodeResult struct {
	Header  *RoutingHeader
	Payload []byte
}

// decodeMessage is the internal implementation (not exported to gomobile).
func decodeMessage(data []byte) (*DecodeResult, error) {
	if len(data) < 4 {
		return nil, errors.New("data too short: need at least 4 bytes for header length")
	}

	headerLen := binary.BigEndian.Uint32(data[0:4])

	// Guard against uint32 overflow (4 + 0xFFFFFFFF wraps to 3) and absurd headers
	if headerLen > 10000 {
		return nil, fmt.Errorf("header too large: %d bytes", headerLen)
	}
	if uint64(len(data)) < uint64(4)+uint64(headerLen) {
		return nil, fmt.Errorf("data too short: need %d bytes for header, have %d", headerLen, len(data)-4)
	}

	headerBytes := data[4 : 4+headerLen]
	payload := data[4+headerLen:]

	var header RoutingHeader
	if err := json.Unmarshal(headerBytes, &header); err != nil {
		return nil, fmt.Errorf("failed to parse routing header: %w", err)
	}

	return &DecodeResult{Header: &header, Payload: payload}, nil
}
