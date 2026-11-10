package mobile

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/saileshbro/photos-to-google/internal/gotohp/core"
)

func TestStatus(t *testing.T) {
	cases := map[string]core.FileUploadResult{
		"failed":   {IsError: true},
		"exists":   {Skipped: true, SkipCode: "remote-duplicate"},
		"skipped":  {Skipped: true, SkipCode: "unsupported"},
		"uploaded": {MediaKey: "AF1Qip"},
	}
	for want, r := range cases {
		if got := status(r); got != want {
			t.Errorf("status(%+v) = %q, want %q", r, got, want)
		}
	}
}

type events []string

func (e *events) OnEvent(s string) { *e = append(*e, s) }

// With no account, every file comes back as a failed result and the run still
// ends with a stop event, so the Kotlin side never waits forever.
func TestUploadWithoutAccountFailsAndStops(t *testing.T) {
	if err := Init(filepath.Join(t.TempDir(), "ptg.config"), nil); err != nil {
		t.Fatal(err)
	}
	img := filepath.Join(t.TempDir(), "a.jpg")
	if err := os.WriteFile(img, []byte{0xff, 0xd8, 0xff, 0xd9}, 0o600); err != nil {
		t.Fatal(err)
	}
	paths, _ := json.Marshal([]string{img})

	var got events
	if err := Upload(string(paths), `{"Threads":2}`, &got); err != nil {
		t.Fatal(err)
	}
	all := strings.Join(got, "\n")
	if !strings.Contains(all, `"Status":"failed"`) || !strings.HasSuffix(all, `{"data":null,"event":"stop"}`) {
		t.Fatalf("unexpected events:\n%s", all)
	}
}

// The Kotlin side decodes these exact keys; a rename here breaks sign-in silently.
func TestAccountsJSONKeys(t *testing.T) {
	if err := Init(filepath.Join(t.TempDir(), "ptg.config"), nil); err != nil {
		t.Fatal(err)
	}
	if got, want := Accounts(), `{"accounts":[],"selected":""}`; got != want {
		t.Fatalf("Accounts() = %s, want %s", got, want)
	}
}

// xorSealer stands in for the Keystore: reversible, and visibly not plaintext.
type xorSealer struct{}

func (xorSealer) Seal(b []byte) ([]byte, error) { return xor(append([]byte("SEAL"), b...)), nil }
func (xorSealer) Open(b []byte) ([]byte, error) {
	if p := xor(b); len(p) >= 4 && string(p[:4]) == "SEAL" {
		return p[4:], nil
	}
	return b, nil // plaintext from an older version
}
func xor(b []byte) []byte {
	out := make([]byte, len(b))
	for i, c := range b {
		out[i] = c ^ 0x5a
	}
	return out
}

// A plaintext credential file is encrypted on Init, and still loads afterwards.
func TestPlaintextConfigGetsSealed(t *testing.T) {
	path := filepath.Join(t.TempDir(), "ptg.config")
	plain := "account:\n    credentials:\n        - Email=a@b.com&Token=secret\n    selected: a@b.com\n"
	if err := os.WriteFile(path, []byte(plain), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := Init(path, xorSealer{}); err != nil {
		t.Fatal(err)
	}
	stored, _ := os.ReadFile(path)
	if strings.Contains(string(stored), "secret") || strings.Contains(string(stored), "a@b.com") {
		t.Fatalf("credential still in plaintext on disk:\n%s", stored)
	}
	if err := Init(path, xorSealer{}); err != nil {
		t.Fatal(err)
	}
	if got := Accounts(); !strings.Contains(got, `"email":"a@b.com"`) {
		t.Fatalf("account lost after sealing: %s", got)
	}
}
