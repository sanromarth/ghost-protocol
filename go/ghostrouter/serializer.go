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

// DecodeMessage deserializes a wire-format message into routing header and payload.
func DecodeMessage(data []byte) (*DecodeResult, error) {
	return decodeMessage(data)
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

// EncodeBatch packs multiple already-encoded messages into a single blob
// for transmission in one GATT session.
// Wire format: [1 byte: count N] [4 bytes: len1, BE uint32] [msg1] [4 bytes: len2] [msg2] ...
// Each message is the output of EncodeMessage() (routing header + payload).
func EncodeBatch(encodedMessages [][]byte) ([]byte, error) {
	count := len(encodedMessages)
	if count == 0 {
		return nil, errors.New("cannot encode empty batch")
	}
	if count > 255 {
		return nil, fmt.Errorf("batch too large: %d messages (max 255)", count)
	}

	// Calculate total size: 1 (count) + sum(4 + len(msg))
	totalSize := 1
	for _, msg := range encodedMessages {
		totalSize += 4 + len(msg)
	}

	result := make([]byte, totalSize)
	result[0] = byte(count)
	offset := 1

	for _, msg := range encodedMessages {
		binary.BigEndian.PutUint32(result[offset:offset+4], uint32(len(msg)))
		offset += 4
		copy(result[offset:offset+len(msg)], msg)
		offset += len(msg)
	}

	return result, nil
}

// DecodeBatch unpacks a batched blob into individual encoded messages.
// Returns the individual messages (each still in routing header + payload format).
func DecodeBatch(data []byte) ([][]byte, error) {
	if len(data) < 1 {
		return nil, errors.New("batch data too short: need at least 1 byte for count")
	}

	count := int(data[0])
	if count == 0 {
		return nil, errors.New("batch count is 0")
	}

	offset := 1
	messages := make([][]byte, 0, count)

	for i := 0; i < count; i++ {
		if offset+4 > len(data) {
			return nil, fmt.Errorf("batch truncated at message %d: need 4 bytes for length at offset %d, have %d", i, offset, len(data))
		}
		msgLen := int(binary.BigEndian.Uint32(data[offset : offset+4]))
		offset += 4

		if msgLen > 100000 {
			return nil, fmt.Errorf("batch message %d too large: %d bytes", i, msgLen)
		}
		if offset+msgLen > len(data) {
			return nil, fmt.Errorf("batch truncated at message %d: need %d bytes at offset %d, have %d", i, msgLen, offset, len(data))
		}

		msg := make([]byte, msgLen)
		copy(msg, data[offset:offset+msgLen])
		offset += msgLen
		messages = append(messages, msg)
	}

	return messages, nil
}
