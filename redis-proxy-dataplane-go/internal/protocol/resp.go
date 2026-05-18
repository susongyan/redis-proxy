package protocol

import (
	"bufio"
	"bytes"
	"errors"
	"fmt"
	"io"
	"strconv"
)

var ErrInvalidRESP = errors.New("invalid RESP frame")

type Request struct {
	Raw  []byte
	Args [][]byte
}

func (r Request) Command() string {
	if len(r.Args) == 0 {
		return ""
	}
	return string(bytes.ToUpper(r.Args[0]))
}

func ReadRequest(br *bufio.Reader, maxBytes int) (Request, error) {
	var raw bytes.Buffer
	first, err := br.ReadByte()
	if err != nil {
		return Request{}, err
	}
	raw.WriteByte(first)
	if first != '*' {
		return Request{}, ErrInvalidRESP
	}
	n, err := readLen(br, &raw)
	if err != nil {
		return Request{}, err
	}
	if n <= 0 {
		return Request{}, ErrInvalidRESP
	}
	args := make([][]byte, 0, n)
	for i := 0; i < n; i++ {
		prefix, err := br.ReadByte()
		if err != nil {
			return Request{}, err
		}
		raw.WriteByte(prefix)
		if prefix != '$' {
			return Request{}, ErrInvalidRESP
		}
		size, err := readLen(br, &raw)
		if err != nil {
			return Request{}, err
		}
		if size < 0 {
			args = append(args, nil)
			continue
		}
		buf := make([]byte, size+2)
		if _, err := io.ReadFull(br, buf); err != nil {
			return Request{}, err
		}
		if buf[size] != '\r' || buf[size+1] != '\n' {
			return Request{}, ErrInvalidRESP
		}
		raw.Write(buf)
		arg := make([]byte, size)
		copy(arg, buf[:size])
		args = append(args, arg)
		if maxBytes > 0 && raw.Len() > maxBytes {
			return Request{}, fmt.Errorf("request frame exceeds %d bytes", maxBytes)
		}
	}
	return Request{Raw: raw.Bytes(), Args: args}, nil
}

func ReadFrameRaw(br *bufio.Reader, maxBytes int) ([]byte, error) {
	var raw bytes.Buffer
	if err := readAny(br, &raw, maxBytes); err != nil {
		return nil, err
	}
	return raw.Bytes(), nil
}

func readAny(br *bufio.Reader, raw *bytes.Buffer, maxBytes int) error {
	prefix, err := br.ReadByte()
	if err != nil {
		return err
	}
	raw.WriteByte(prefix)
	switch prefix {
	case '+', '-', ':':
		_, err = readLine(br, raw)
	case '$':
		n, err := readLen(br, raw)
		if err != nil || n < 0 {
			return err
		}
		buf := make([]byte, n+2)
		_, err = io.ReadFull(br, buf)
		raw.Write(buf)
	case '*':
		n, err := readLen(br, raw)
		if err != nil || n < 0 {
			return err
		}
		for i := 0; i < n; i++ {
			if err := readAny(br, raw, maxBytes); err != nil {
				return err
			}
		}
	default:
		err = ErrInvalidRESP
	}
	if maxBytes > 0 && raw.Len() > maxBytes {
		return fmt.Errorf("response frame exceeds %d bytes", maxBytes)
	}
	return err
}

func readLen(br *bufio.Reader, raw *bytes.Buffer) (int, error) {
	line, err := readLine(br, raw)
	if err != nil {
		return 0, err
	}
	return strconv.Atoi(string(line))
}

func readLine(br *bufio.Reader, raw *bytes.Buffer) ([]byte, error) {
	line, err := br.ReadBytes('\n')
	if err != nil {
		return nil, err
	}
	raw.Write(line)
	if len(line) < 2 || line[len(line)-2] != '\r' {
		return nil, ErrInvalidRESP
	}
	return line[:len(line)-2], nil
}

func ErrorFrame(message string) []byte {
	return []byte("-ERR " + message + "\r\n")
}
