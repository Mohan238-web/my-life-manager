#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <objbase.h>
#include <iostream>

using DllGetClassObjectFn = HRESULT (STDAPICALLTYPE*)(REFCLSID, REFIID, LPVOID*);

int wmain(int argc, wchar_t** argv) {
    if (argc != 2) {
        std::wcerr << L"usage: PhoneBridgeComProbe <VirtualCameraMediaSource.dll>\n";
        return 2;
    }

    HMODULE dll = LoadLibraryW(argv[1]);
    if (!dll) {
        std::wcerr << L"LoadLibrary failed: " << GetLastError() << L"\n";
        return 3;
    }

    auto fn = reinterpret_cast<DllGetClassObjectFn>(GetProcAddress(dll, "DllGetClassObject"));
    if (!fn) {
        std::wcerr << L"DllGetClassObject export missing\n";
        FreeLibrary(dll);
        return 4;
    }

    CLSID clsid{};
    HRESULT hr = CLSIDFromString(L"{A7318E11-4B4C-4BCC-A19F-FA192BA8BA5D}", &clsid);
    if (FAILED(hr)) {
        std::wcerr << L"CLSIDFromString failed: 0x" << std::hex << static_cast<unsigned long>(hr) << L"\n";
        FreeLibrary(dll);
        return 5;
    }

    IClassFactory* factory = nullptr;
    hr = fn(clsid, IID_IClassFactory, reinterpret_cast<void**>(&factory));
    if (FAILED(hr) || !factory) {
        std::wcerr << L"DllGetClassObject rejected PhoneBridge CLSID: 0x"
                   << std::hex << static_cast<unsigned long>(hr) << L"\n";
        FreeLibrary(dll);
        return 6;
    }

    factory->Release();
    FreeLibrary(dll);
    std::wcout << L"PhoneBridge COM class-factory probe passed\n";
    return 0;
}
