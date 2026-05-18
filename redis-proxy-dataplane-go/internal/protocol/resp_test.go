package protocol

import (
	"bufio"
	"strings"
	"testing"
)

func TestReadRequest(t *testing.T) {
	raw := "*2\r\n$3\r\nGET\r\n$3\r\nfoo\r\n"
	req, err := ReadRequest(bufio.NewReader(strings.NewReader(raw)), 1024)
	if err != nil {
		t.Fatal(err)
	}
	if req.Command() != "GET" || string(req.Args[1]) != "foo" || string(req.Raw) != raw {
		t.Fatalf("unexpected request: %#v", req)
	}
}
