package core

import (
	"context"
	"os"
	"path/filepath"
	"testing"
)

// fakeLivePhotoAPI reports the still as already in the library and fails on anything else.
type fakeLivePhotoAPI struct{ remoteStillKey string }

func (f fakeLivePhotoAPI) FindRemoteMediaByHash(hash []byte) (string, error) {
	if string(hash) == string(sha1Of("still")) {
		return f.remoteStillKey, nil
	}
	return "", nil
}
func (fakeLivePhotoAPI) GetUploadToken(string, int64) (string, error) { panic("no upload expected") }
func (fakeLivePhotoAPI) UploadFileWithProgress(context.Context, string, string, UploadProgressCallback) (ScottyFinalizeToken, error) {
	panic("no upload expected")
}
func (fakeLivePhotoAPI) CommitLivePhoto(LivePhotoCreateRequest) (string, error) {
	panic("no commit expected")
}
func (fakeLivePhotoAPI) ReconcileLivePhoto(LivePhotoReconcileRequest) (string, error) {
	panic("no reconcile expected")
}

func sha1Of(s string) []byte {
	dir, _ := os.MkdirTemp("", "sha")
	defer os.RemoveAll(dir)
	p := filepath.Join(dir, "f")
	_ = os.WriteFile(p, []byte(s), 0o600)
	h, _ := CalculateSHA1(context.Background(), p)
	return h
}

// A skipped pair must still carry the existing item's key, or it can never be put in an album.
func TestSkippedLivePhotoReturnsExistingKey(t *testing.T) {
	dir := t.TempDir()
	pair := LivePhotoPair{PhotoPath: filepath.Join(dir, "a.heic"), VideoPath: filepath.Join(dir, "a.mov")}
	if err := os.WriteFile(pair.PhotoPath, []byte("still"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(pair.VideoPath, []byte("video"), 0o600); err != nil {
		t.Fatal(err)
	}
	key, skipped, err := uploadLivePhotoWithCallback(context.Background(), fakeLivePhotoAPI{"AF1QipExisting"}, pair,
		LivePhotoUploadOptions{}, 0, NopReporter{})
	if err != nil || !skipped || key != "AF1QipExisting" {
		t.Fatalf("got key=%q skipped=%v err=%v, want AF1QipExisting, true, nil", key, skipped, err)
	}
}
