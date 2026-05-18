package router

import "testing"

func TestSlotExamples(t *testing.T) {
	tests := map[string]int{
		"123456789": 12739,
		"foo":       12182,
		"{user}:1":  5474,
		"{user}:2":  5474,
	}
	for key, want := range tests {
		if got := Slot([]byte(key)); got != want {
			t.Fatalf("slot(%q)=%d want %d", key, got, want)
		}
	}
}
