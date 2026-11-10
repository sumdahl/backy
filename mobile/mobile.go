// Package mobile is the gomobile bridge the Android app calls. Everything that
// crosses the boundary is a string, a bool or an error, and structured data is
// JSON, so the Kotlin side needs no generated types beyond this file.
package mobile

import (
	"encoding/json"
	"io"
	"log/slog"
	"os"
	"strings"
	"sync"

	"github.com/saileshbro/photos-to-google/internal/gotohp/core"
)

// Listener receives one JSON event per call: {"event": "...", "data": {...}}.
// Events: start, total_bytes, total_bytes_delta, warning, thread, result,
// album_progress, album_complete, album_error, stop.
type Listener interface {
	OnEvent(json string)
}

// Sealer encrypts the credential file at rest. The app implements it with an Android
// Keystore key. Open must return plaintext input unchanged (an older, unencrypted file).
type Sealer interface {
	Seal(plain []byte) ([]byte, error)
	Open(stored []byte) ([]byte, error)
}

// Init points the credential store at a file in the app's private storage, encrypted with
// sealer when it isn't nil. Call once before anything else.
func Init(configPath string, sealer Sealer) error {
	if sealer != nil {
		core.ConfigSealer = sealer
	}
	if err := core.LoadConfig(configPath); err != nil {
		return err
	}
	if sealer != nil {
		if _, err := os.Stat(configPath); err == nil {
			return core.SaveConfig() // encrypts a file left in plaintext by an older version
		}
	}
	return nil
}

// Login exchanges a single-use EmbeddedSetup oauth_token (or a raw credential
// string) for a stored credential, selects it, and returns the account email.
func Login(oauthToken string) (string, error) {
	var mgr core.ConfigManager
	email, err := mgr.AddGoogleAccount(oauthToken)
	if err != nil {
		return "", err
	}
	mgr.SetSelected(email)
	return email, nil
}

// Accounts returns {"accounts":[{"email":...}],"selected":"..."} (core.AccountsState JSON tags).
func Accounts() string {
	var mgr core.ConfigManager
	return toJSON(mgr.GetAccounts())
}

func SelectAccount(email string) {
	var mgr core.ConfigManager
	mgr.SetSelected(email)
}

func Logout(email string) error {
	forget(email)
	var mgr core.ConfigManager
	return mgr.RemoveCredentials(email)
}

var (
	runMu   sync.Mutex
	current *core.UploadManager
)

// Upload runs one pass over pathsJSON (a JSON array of file paths) with
// optionsJSON (core.UploadOptions, Go field names) and blocks until it ends.
// Retries across passes are the caller's job, as in the CLI.
//
// DeleteFromHost is forced off: on Android a plain unlink of someone else's
// media fails, so the app deletes through MediaStore after a confirmed result.
func Upload(pathsJSON, optionsJSON string, l Listener) error {
	var paths []string
	if err := json.Unmarshal([]byte(pathsJSON), &paths); err != nil {
		return err
	}
	opts := core.UploadOptions{Threads: 3}
	if optionsJSON != "" {
		if err := json.Unmarshal([]byte(optionsJSON), &opts); err != nil {
			return err
		}
	}
	opts.DeleteFromHost = false
	opts.Recursive = false
	// Albums are added by the app afterwards (CreateAlbum/AddToAlbum), so they survive restarts
	// and are never created twice.
	opts.AlbumName = ""
	opts.AlbumAutoMode = false

	rep := &reporter{l: l, done: make(chan struct{})}
	mgr := core.NewUploadManager(rep, slog.New(slog.NewTextHandler(io.Discard, nil)))
	runMu.Lock()
	current = mgr
	runMu.Unlock()
	defer func() {
		runMu.Lock()
		current = nil
		runMu.Unlock()
	}()

	mgr.Upload(paths, opts)
	<-rep.done
	return nil
}

// Cancel stops the running Upload, if any. Safe to call from any thread.
func Cancel() {
	runMu.Lock()
	defer runMu.Unlock()
	if current != nil {
		current.Cancel()
	}
}

var (
	apiMu sync.Mutex
	apis  = map[string]*core.Api{}
)

// client reuses one Api per account so the auth token is fetched once, not per album call.
func client(account, proxy string) (*core.Api, error) {
	apiMu.Lock()
	defer apiMu.Unlock()
	k := account + "\x00" + proxy
	if a, ok := apis[k]; ok {
		return a, nil
	}
	a, err := core.NewApi(core.ApiOptions{Account: account, Proxy: proxy})
	if err != nil {
		return nil, err
	}
	apis[k] = a
	return a, nil
}

// CreateAlbum makes an album called name holding mediaKeysJSON (a JSON array, at most
// core.AlbumBatchSize keys) and returns its album key. The app stores that key and uses
// AddToAlbum from then on, so a name is never created twice.
func CreateAlbum(account, proxy, name, mediaKeysJSON string) (string, error) {
	var keys []string
	if err := json.Unmarshal([]byte(mediaKeysJSON), &keys); err != nil {
		return "", err
	}
	a, err := client(account, proxy)
	if err != nil {
		return "", err
	}
	return a.CreateAlbum(name, keys)
}

// AddToAlbum adds mediaKeysJSON (at most core.AlbumBatchSize keys) to an existing album.
// Errors keep core's "status NNN" text so the caller can tell overload from a missing album.
func AddToAlbum(account, proxy, albumKey, mediaKeysJSON string) error {
	var keys []string
	if err := json.Unmarshal([]byte(mediaKeysJSON), &keys); err != nil {
		return err
	}
	a, err := client(account, proxy)
	if err != nil {
		return err
	}
	return a.AddMediaToAlbum(albumKey, keys)
}

// Logout also drops the cached client, so a removed account can't keep uploading.
func forget(email string) {
	apiMu.Lock()
	defer apiMu.Unlock()
	for k := range apis {
		if strings.HasPrefix(k, email+"\x00") {
			delete(apis, k)
		}
	}
}

// Status maps a core result onto the four statuses the CLI reports.
func status(r core.FileUploadResult) string {
	switch {
	case r.IsError:
		return "failed"
	case r.Skipped && r.SkipCode == "remote-duplicate":
		return "exists"
	case r.Skipped:
		return "skipped"
	}
	return "uploaded"
}

type reporter struct {
	l    Listener
	once sync.Once
	done chan struct{}
}

func (r *reporter) emit(event string, data any) {
	r.l.OnEvent(toJSON(map[string]any{"event": event, "data": data}))
}

func (r *reporter) UploadStart(b core.UploadBatchStart) { r.emit("start", b) }
func (r *reporter) UploadStop() {
	r.once.Do(func() {
		r.emit("stop", nil)
		close(r.done)
	})
}
func (r *reporter) TotalBytes(n int64)               { r.emit("total_bytes", n) }
func (r *reporter) TotalBytesDelta(n int64)          { r.emit("total_bytes_delta", n) }
func (r *reporter) Warning(w core.PreflightWarning)  { r.emit("warning", w) }
func (r *reporter) ThreadStatus(t core.ThreadStatus) { r.emit("thread", t) }
func (r *reporter) AlbumProgress(a core.AlbumStatus) { r.emit("album_progress", a) }
func (r *reporter) AlbumComplete(a core.AlbumStatus) { r.emit("album_complete", a) }
func (r *reporter) AlbumError(a core.AlbumError)     { r.emit("album_error", a) }
func (r *reporter) FileResult(f core.FileUploadResult) {
	r.emit("result", struct {
		core.FileUploadResult
		Status string `json:"Status"`
	}{f, status(f)})
}

func toJSON(v any) string {
	b, _ := json.Marshal(v)
	return string(b)
}
