package backend

import "testing"

func TestSelectClientByAffinityIsStable(t *testing.T) {
	first := &Client{}
	first.active.Store(true)
	second := &Client{}
	second.active.Store(true)
	pools := &Pools{
		conns: map[string][]*Client{
			"127.0.0.1:7000": {first, second},
		},
	}

	if got := pools.selectClientByAffinity("127.0.0.1:7000", 1); got != second {
		t.Fatalf("selected client=%p want %p", got, second)
	}
	if got := pools.selectClientByAffinity("127.0.0.1:7000", 1); got != second {
		t.Fatalf("selection is not stable: got %p want %p", got, second)
	}
}

func TestSelectClientByAffinityFallsBackToNextActive(t *testing.T) {
	first := &Client{}
	second := &Client{}
	second.active.Store(true)
	pools := &Pools{
		conns: map[string][]*Client{
			"127.0.0.1:7000": {first, second},
		},
	}

	if got := pools.selectClientByAffinity("127.0.0.1:7000", 0); got != second {
		t.Fatalf("selected client=%p want %p", got, second)
	}
}
