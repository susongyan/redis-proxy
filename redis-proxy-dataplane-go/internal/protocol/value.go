package protocol

import (
	"bufio"
	"bytes"
	"fmt"
	"io"
	"strconv"
)

type ValueKind byte

const (
	SimpleString ValueKind = '+'
	Error        ValueKind = '-'
	Integer      ValueKind = ':'
	BulkString   ValueKind = '$'
	Array        ValueKind = '*'
)

type Value struct {
	Kind  ValueKind
	Bytes []byte
	Int   int64
	Array []Value
}

func ParseValue(raw []byte) (Value, error) {
	return ReadValue(bufio.NewReader(bytes.NewReader(raw)))
}

func ReadValue(br *bufio.Reader) (Value, error) {
	prefix, err := br.ReadByte()
	if err != nil {
		return Value{}, err
	}
	switch ValueKind(prefix) {
	case SimpleString, Error:
		line, err := readValueLine(br)
		return Value{Kind: ValueKind(prefix), Bytes: line}, err
	case Integer:
		line, err := readValueLine(br)
		if err != nil {
			return Value{}, err
		}
		n, err := strconv.ParseInt(string(line), 10, 64)
		return Value{Kind: Integer, Int: n}, err
	case BulkString:
		line, err := readValueLine(br)
		if err != nil {
			return Value{}, err
		}
		n, err := strconv.Atoi(string(line))
		if err != nil {
			return Value{}, err
		}
		if n < 0 {
			return Value{Kind: BulkString}, nil
		}
		buf := make([]byte, n+2)
		if _, err := io.ReadFull(br, buf); err != nil {
			return Value{}, err
		}
		if buf[n] != '\r' || buf[n+1] != '\n' {
			return Value{}, ErrInvalidRESP
		}
		return Value{Kind: BulkString, Bytes: buf[:n]}, nil
	case Array:
		line, err := readValueLine(br)
		if err != nil {
			return Value{}, err
		}
		n, err := strconv.Atoi(string(line))
		if err != nil {
			return Value{}, err
		}
		if n < 0 {
			return Value{Kind: Array}, nil
		}
		items := make([]Value, 0, n)
		for i := 0; i < n; i++ {
			item, err := ReadValue(br)
			if err != nil {
				return Value{}, err
			}
			items = append(items, item)
		}
		return Value{Kind: Array, Array: items}, nil
	default:
		return Value{}, fmt.Errorf("invalid RESP value prefix: %q", prefix)
	}
}

func readValueLine(br *bufio.Reader) ([]byte, error) {
	line, err := br.ReadBytes('\n')
	if err != nil {
		return nil, err
	}
	if len(line) < 2 || line[len(line)-2] != '\r' {
		return nil, ErrInvalidRESP
	}
	return line[:len(line)-2], nil
}
