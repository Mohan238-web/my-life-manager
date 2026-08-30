//go:build windows

package main

import (
	"fmt"
	"syscall"
	"unsafe"
)

type dataBlob struct {
	cbData uint32
	pbData *byte
}

var (
	crypt32 = syscall.NewLazyDLL("crypt32.dll")
	kernel32 = syscall.NewLazyDLL("kernel32.dll")
	cryptProtectData = crypt32.NewProc("CryptProtectData")
	cryptUnprotectData = crypt32.NewProc("CryptUnprotectData")
	localFree = kernel32.NewProc("LocalFree")
)

func blob(data []byte) dataBlob {
	if len(data) == 0 { return dataBlob{} }
	return dataBlob{cbData:uint32(len(data)), pbData:&data[0]}
}

func protect(data []byte) ([]byte, error) {
	in := blob(data); var out dataBlob
	r, _, err := cryptProtectData.Call(uintptr(unsafe.Pointer(&in)), 0, 0, 0, 0, 1, uintptr(unsafe.Pointer(&out)))
	if r == 0 { return nil, fmt.Errorf("Windows data protection failed: %w", err) }
	defer localFree.Call(uintptr(unsafe.Pointer(out.pbData)))
	return append([]byte(nil), unsafe.Slice(out.pbData, out.cbData)...), nil
}

func unprotect(data []byte) ([]byte, error) {
	in := blob(data); var out dataBlob
	r, _, err := cryptUnprotectData.Call(uintptr(unsafe.Pointer(&in)), 0, 0, 0, 0, 1, uintptr(unsafe.Pointer(&out)))
	if r == 0 { return nil, fmt.Errorf("Windows data protection failed: %w", err) }
	defer localFree.Call(uintptr(unsafe.Pointer(out.pbData)))
	return append([]byte(nil), unsafe.Slice(out.pbData, out.cbData)...), nil
}
