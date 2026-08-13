from pathlib import Path
import re, sys

root=Path(sys.argv[1])
adapter=root/'Source/Main/adapter.cpp'
stream=root/'Source/Main/minwavertstream.cpp'
minipairs=root/'Source/Filters/minipairs.h'
inx=root/'Source/Main/SimpleAudioSample.inx'

a=adapter.read_text(encoding='utf-8-sig').replace('\r\n','\n')
s=stream.read_text(encoding='utf-8-sig').replace('\r\n','\n')
m=minipairs.read_text(encoding='utf-8-sig').replace('\r\n','\n')
i=inx.read_text(encoding='utf-8-sig').replace('\r\n','\n')

def rep(text, old, new, label):
    if old not in text: raise SystemExit(f'PhoneBridge driver anchor missing: {label}')
    return text.replace(old,new,1)

# A user-mode PhoneBridge receiver pushes 48 kHz mono PCM16 converted to the
# sample endpoint's native 48 kHz / 32-bit / stereo byte layout via this control device.
# The WaveRT capture DPC drains only nonpaged memory; it never touches files or user memory.
insert_after='#include "minipairs.h"\n'
bridge=r'''
#include <wdmsec.h>
#pragma comment(lib, "wdmsec.lib")

#define IOCTL_PHONEBRIDGE_AUDIO_PUSH CTL_CODE(FILE_DEVICE_UNKNOWN, 0x801, METHOD_BUFFERED, FILE_WRITE_DATA)
#define IOCTL_PHONEBRIDGE_AUDIO_RESET CTL_CODE(FILE_DEVICE_UNKNOWN, 0x802, METHOD_BUFFERED, FILE_WRITE_DATA)
#define PB_AUDIO_RING_BYTES (4u*1024u*1024u)

static PUCHAR g_PbAudioRing = NULL;
static ULONG g_PbAudioRead = 0;
static ULONG g_PbAudioWrite = 0;
static KSPIN_LOCK g_PbAudioLock;
static PDEVICE_OBJECT g_PbControlDevice = NULL;
static UNICODE_STRING g_PbDosName;
static PDRIVER_DISPATCH g_PbOriginalCreate = NULL;
static PDRIVER_DISPATCH g_PbOriginalClose = NULL;
static PDRIVER_DISPATCH g_PbOriginalDeviceControl = NULL;

// {8A6B44E7-2C79-4C2F-95B2-75D94A7A0191}
static const GUID GUID_PHONEBRIDGE_AUDIO_CONTROL =
{ 0x8a6b44e7, 0x2c79, 0x4c2f, { 0x95, 0xb2, 0x75, 0xd9, 0x4a, 0x7a, 0x01, 0x91 } };

static NTSTATUS PbCompleteIrp(PIRP Irp, NTSTATUS status, ULONG_PTR information=0)
{
    Irp->IoStatus.Status=status;
    Irp->IoStatus.Information=information;
    IoCompleteRequest(Irp,IO_NO_INCREMENT);
    return status;
}

static ULONG PbRingUsedNoLock()
{
    return (g_PbAudioWrite >= g_PbAudioRead)
        ? (g_PbAudioWrite-g_PbAudioRead)
        : (PB_AUDIO_RING_BYTES-g_PbAudioRead+g_PbAudioWrite);
}

static VOID PbRingPush(const UCHAR* data, ULONG bytes)
{
    if(!g_PbAudioRing || !data || !bytes) return;
    // Keep whole recent audio if a single write is unusually large.
    if(bytes >= PB_AUDIO_RING_BYTES){ data += bytes-(PB_AUDIO_RING_BYTES-8); bytes=PB_AUDIO_RING_BYTES-8; }
    KIRQL irql; KeAcquireSpinLock(&g_PbAudioLock,&irql);
    ULONG used=PbRingUsedNoLock();
    ULONG freeBytes=PB_AUDIO_RING_BYTES-used-1;
    if(bytes>freeBytes) g_PbAudioRead=(g_PbAudioRead+(bytes-freeBytes))%PB_AUDIO_RING_BYTES;
    ULONG first=min(bytes,PB_AUDIO_RING_BYTES-g_PbAudioWrite);
    RtlCopyMemory(g_PbAudioRing+g_PbAudioWrite,data,first);
    if(bytes>first) RtlCopyMemory(g_PbAudioRing,data+first,bytes-first);
    g_PbAudioWrite=(g_PbAudioWrite+bytes)%PB_AUDIO_RING_BYTES;
    KeReleaseSpinLock(&g_PbAudioLock,irql);
}

ULONG PhoneBridgeAudioRead(PUCHAR destination, ULONG requested)
{
    if(!destination || !requested){ return 0; }
    ULONG copied=0;
    KIRQL irql; KeAcquireSpinLock(&g_PbAudioLock,&irql);
    if(g_PbAudioRing){
        ULONG used=PbRingUsedNoLock();
        copied=min(requested,used);
        ULONG first=min(copied,PB_AUDIO_RING_BYTES-g_PbAudioRead);
        if(first) RtlCopyMemory(destination,g_PbAudioRing+g_PbAudioRead,first);
        if(copied>first) RtlCopyMemory(destination+first,g_PbAudioRing,copied-first);
        g_PbAudioRead=(g_PbAudioRead+copied)%PB_AUDIO_RING_BYTES;
    }
    KeReleaseSpinLock(&g_PbAudioLock,irql);
    if(copied<requested) RtlZeroMemory(destination+copied,requested-copied);
    return copied;
}

static NTSTATUS PbDispatchCreateClose(PDEVICE_OBJECT DeviceObject, PIRP Irp)
{
    if(DeviceObject==g_PbControlDevice) return PbCompleteIrp(Irp,STATUS_SUCCESS);
    PDRIVER_DISPATCH original=(IoGetCurrentIrpStackLocation(Irp)->MajorFunction==IRP_MJ_CREATE)?g_PbOriginalCreate:g_PbOriginalClose;
    return original?original(DeviceObject,Irp):PcDispatchIrp(DeviceObject,Irp);
}

static NTSTATUS PbDispatchDeviceControl(PDEVICE_OBJECT DeviceObject, PIRP Irp)
{
    if(DeviceObject!=g_PbControlDevice)
        return g_PbOriginalDeviceControl?g_PbOriginalDeviceControl(DeviceObject,Irp):PcDispatchIrp(DeviceObject,Irp);
    PIO_STACK_LOCATION sp=IoGetCurrentIrpStackLocation(Irp);
    ULONG code=sp->Parameters.DeviceIoControl.IoControlCode;
    if(code==IOCTL_PHONEBRIDGE_AUDIO_RESET){
        KIRQL irql; KeAcquireSpinLock(&g_PbAudioLock,&irql); g_PbAudioRead=g_PbAudioWrite=0; KeReleaseSpinLock(&g_PbAudioLock,irql);
        return PbCompleteIrp(Irp,STATUS_SUCCESS);
    }
    if(code!=IOCTL_PHONEBRIDGE_AUDIO_PUSH) return PbCompleteIrp(Irp,STATUS_INVALID_DEVICE_REQUEST);
    ULONG bytes=sp->Parameters.DeviceIoControl.InputBufferLength;
    if(!Irp->AssociatedIrp.SystemBuffer || bytes==0 || bytes>(256u*1024u)) return PbCompleteIrp(Irp,STATUS_INVALID_BUFFER_SIZE);
    PbRingPush((const UCHAR*)Irp->AssociatedIrp.SystemBuffer,bytes);
    return PbCompleteIrp(Irp,STATUS_SUCCESS,bytes);
}

static NTSTATUS PhoneBridgeAudioBridgeInitialize(PDRIVER_OBJECT DriverObject)
{
    g_PbAudioRing=(PUCHAR)ExAllocatePool2(POOL_FLAG_NON_PAGED,PB_AUDIO_RING_BYTES,'rBPP');
    if(!g_PbAudioRing) return STATUS_INSUFFICIENT_RESOURCES;
    RtlZeroMemory(g_PbAudioRing,PB_AUDIO_RING_BYTES); KeInitializeSpinLock(&g_PbAudioLock);

    UNICODE_STRING devName,sddl;
    RtlInitUnicodeString(&devName,L"\\Device\\PhoneBridgeAudio");
    RtlInitUnicodeString(&g_PbDosName,L"\\DosDevices\\PhoneBridgeAudio");
    RtlInitUnicodeString(&sddl,L"D:P(A;;GA;;;SY)(A;;GA;;;BA)(A;;GRGW;;;WD)");
    NTSTATUS st=IoCreateDeviceSecure(DriverObject,0,&devName,FILE_DEVICE_UNKNOWN,FILE_DEVICE_SECURE_OPEN,FALSE,&sddl,&GUID_PHONEBRIDGE_AUDIO_CONTROL,&g_PbControlDevice);
    if(!NT_SUCCESS(st)){ ExFreePool(g_PbAudioRing); g_PbAudioRing=NULL; return st; }
    g_PbControlDevice->Flags |= DO_BUFFERED_IO;
    st=IoCreateSymbolicLink(&g_PbDosName,&devName);
    if(!NT_SUCCESS(st)){ IoDeleteDevice(g_PbControlDevice); g_PbControlDevice=NULL; ExFreePool(g_PbAudioRing); g_PbAudioRing=NULL; return st; }
    g_PbControlDevice->Flags &= ~DO_DEVICE_INITIALIZING;

    g_PbOriginalCreate=DriverObject->MajorFunction[IRP_MJ_CREATE];
    g_PbOriginalClose=DriverObject->MajorFunction[IRP_MJ_CLOSE];
    g_PbOriginalDeviceControl=DriverObject->MajorFunction[IRP_MJ_DEVICE_CONTROL];
    DriverObject->MajorFunction[IRP_MJ_CREATE]=PbDispatchCreateClose;
    DriverObject->MajorFunction[IRP_MJ_CLOSE]=PbDispatchCreateClose;
    DriverObject->MajorFunction[IRP_MJ_DEVICE_CONTROL]=PbDispatchDeviceControl;
    return STATUS_SUCCESS;
}

static VOID PhoneBridgeAudioBridgeCleanup()
{
    if(g_PbControlDevice){ IoDeleteSymbolicLink(&g_PbDosName); IoDeleteDevice(g_PbControlDevice); g_PbControlDevice=NULL; }
    if(g_PbAudioRing){ ExFreePool(g_PbAudioRing); g_PbAudioRing=NULL; }
}
'''
a=rep(a,insert_after,insert_after+bridge,'adapter includes')
a=rep(a,'    ReleaseRegistryStringBuffer();\n','    PhoneBridgeAudioBridgeCleanup();\n    ReleaseRegistryStringBuffer();\n','driver unload')
a=rep(a,
'''    //
    // To intercept stop/remove/surprise-remove.
    //
    DriverObject->MajorFunction[IRP_MJ_PNP] = PnpHandler;
''',
'''    ntStatus = PhoneBridgeAudioBridgeInitialize(DriverObject);
    IF_FAILED_ACTION_JUMP(
        ntStatus,
        DPF(D_ERROR, ("PhoneBridge audio bridge initialization failed, 0x%x", ntStatus)),
        Done);

    //
    // To intercept stop/remove/surprise-remove.
    //
    DriverObject->MajorFunction[IRP_MJ_PNP] = PnpHandler;
''','driver bridge init')

# Replace sample tone generator with audio delivered by the PhoneBridge receiver.
if 'ULONG PhoneBridgeAudioRead(PUCHAR destination, ULONG requested);' not in s:
    idx=s.find('#include')
    # place declaration after the include block using a reliable sample include.
    s=rep(s,'#include "minwavertstream.h"\n','#include "minwavertstream.h"\n\nULONG PhoneBridgeAudioRead(PUCHAR destination, ULONG requested);\n','stream declaration')
s=rep(s,'        m_ToneGenerator.GenerateSine(m_pDmaBuffer + bufferOffset, runWrite);','        PhoneBridgeAudioRead(m_pDmaBuffer + bufferOffset, runWrite);','capture data source')

# Do not expose the sample speaker; PhoneBridge is capture-only.
m=rep(m,'#define g_cRenderEndpoints  (SIZEOF_ARRAY(g_RenderEndpoints))','#define g_cRenderEndpoints  0','capture only')

# User-visible driver identity. Keep project/binary names stable for WDK build simplicity.
i=i.replace('ROOT\\SimpleAudioSample','ROOT\\PhoneBridgeMicrophone')
i=i.replace('ProviderName = "TODO-Set-Provider"','ProviderName = "PhoneBridge"')
i=i.replace('MfgName      = "TODO-Set-Manufacturer"','MfgName      = "PhoneBridge"')
i=i.replace('SIMPLEAUDIOSAMPLE_SA.DeviceDesc="Virtual Audio Device (WDM) - Simple Audio Sample"','SIMPLEAUDIOSAMPLE_SA.DeviceDesc="PhoneBridge Virtual Microphone"')
i=i.replace('SimpleAudioSample.SvcDesc="Virtual Audio Device (WDM) - Simple Audio Sample Driver"','SimpleAudioSample.SvcDesc="PhoneBridge Virtual Microphone Driver"')
i=i.replace('SIMPLEAUDIOSAMPLE.WaveMicArray1.szPname="Simple Audio Sample Wave Microphone Array - Front"','SIMPLEAUDIOSAMPLE.WaveMicArray1.szPname="PhoneBridge Microphone"')
i=i.replace('SIMPLEAUDIOSAMPLE.TopologyMicArray1.szPname="Simple Audio Sample Topology Microphone Array - Front"','SIMPLEAUDIOSAMPLE.TopologyMicArray1.szPname="PhoneBridge Microphone Topology"')
i=i.replace('MicArray1CustomName= "Internal Microphone Array - Front"','MicArray1CustomName= "PhoneBridge Microphone"')
# Remove render interface registration from the INF; the render miniport count is already zero.
for line in [
'AddInterface=%KSCATEGORY_AUDIO%, %KSNAME_WaveSpeaker%, SIMPLEAUDIOSAMPLE.I.WaveSpeaker',
'AddInterface=%KSCATEGORY_RENDER%, %KSNAME_WaveSpeaker%, SIMPLEAUDIOSAMPLE.I.WaveSpeaker',
'AddInterface=%KSCATEGORY_REALTIME%, %KSNAME_WaveSpeaker%, SIMPLEAUDIOSAMPLE.I.WaveSpeaker',
'AddInterface=%KSCATEGORY_AUDIO%, %KSNAME_TopologySpeaker%, SIMPLEAUDIOSAMPLE.I.TopologySpeaker',
'AddInterface=%KSCATEGORY_TOPOLOGY%, %KSNAME_TopologySpeaker%, SIMPLEAUDIOSAMPLE.I.TopologySpeaker']:
    i=i.replace(line,'; PhoneBridge capture-only: '+line)

for marker in ['IoCreateDeviceSecure','IOCTL_PHONEBRIDGE_AUDIO_PUSH','PhoneBridgeAudioRead','ROOT\\PhoneBridgeMicrophone','PhoneBridge Microphone']:
    if marker not in a+s+i: raise SystemExit(f'PhoneBridge driver marker missing: {marker}')
if 'm_ToneGenerator.GenerateSine(m_pDmaBuffer + bufferOffset, runWrite);' in s:
    raise SystemExit('Tone generator still active in capture path')

adapter.write_text(a,encoding='utf-8',newline='\n')
stream.write_text(s,encoding='utf-8',newline='\n')
minipairs.write_text(m,encoding='utf-8',newline='\n')
inx.write_text(i,encoding='utf-8',newline='\n')
print('Patched Microsoft Simple Audio Sample into PhoneBridge capture endpoint')
