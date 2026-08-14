from pathlib import Path
import sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else 'PhoneBridge')
main_path = root / 'android/app/src/main/java/com/phonebridge/app/MainActivity.java'
svc_path = root / 'android/app/src/main/java/com/phonebridge/app/StreamService.java'
main = main_path.read_text(encoding='utf-8-sig').replace('\r\n','\n')
svc = svc_path.read_text(encoding='utf-8-sig').replace('\r\n','\n')

# Keep the existing FIT_CENTER phone preview, but make Preview and ImageAnalysis
# request the same CameraX resolution/aspect. This reduces the field-of-view mismatch
# between what the user sees on the phone and what PhoneBridge sends to the PC.
old = '''                Preview preview = null;
                if (pv != null) {
                    preview = new Preview.Builder()
                            .setTargetRotation(targetRotation)
                            .build();
                    preview.setSurfaceProvider(pv.getSurfaceProvider());
                }

                ResolutionSelector resolutionSelector = new ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(new ResolutionStrategy(
                                new Size(targetWidth, targetHeight),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                        .build();

                ImageAnalysis analysis = new ImageAnalysis.Builder()
'''
new = '''                // PhoneBridge v1.6 preview/share match: use the SAME resolution selector
                // for both Preview and ImageAnalysis. PreviewView remains FIT_CENTER, so
                // front/rear framing seen on the phone closely matches the transmitted frame.
                ResolutionSelector resolutionSelector = new ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(new ResolutionStrategy(
                                new Size(targetWidth, targetHeight),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                        .build();

                Preview preview = null;
                if (pv != null) {
                    preview = new Preview.Builder()
                            .setResolutionSelector(resolutionSelector)
                            .setTargetRotation(targetRotation)
                            .build();
                    preview.setSurfaceProvider(pv.getSurfaceProvider());
                }

                ImageAnalysis analysis = new ImageAnalysis.Builder()
'''
if old not in svc:
    raise SystemExit('v1.6 Android anchor missing: Preview/ResolutionSelector block')
svc = svc.replace(old, new, 1)

# When the PreviewView has a viewport, bind Preview and ImageAnalysis as one UseCaseGroup.
# CameraX then applies one viewport/crop coordinate space to both use cases. In background
# mode there is intentionally no Preview surface and analysis-only binding is preserved.
if 'import androidx.camera.core.UseCaseGroup;' not in svc:
    svc = svc.replace('import androidx.camera.core.Preview;\n', 'import androidx.camera.core.Preview;\nimport androidx.camera.core.UseCaseGroup;\nimport androidx.camera.core.ViewPort;\n', 1)

old_bind = '''                CameraSelector selector = new CameraSelector.Builder().requireLensFacing(lensFacing).build();
                camera = preview != null
                        ? provider.bindToLifecycle(this, selector, preview, analysis)
                        : provider.bindToLifecycle(this, selector, analysis);
                notifyStatus(preview != null ? "Camera active • streaming video" : "Camera active in background");
'''
new_bind = '''                CameraSelector selector = new CameraSelector.Builder().requireLensFacing(lensFacing).build();
                if (preview != null) {
                    UseCaseGroup.Builder group = new UseCaseGroup.Builder()
                            .addUseCase(preview)
                            .addUseCase(analysis);
                    ViewPort viewPort = pv == null ? null : pv.getViewPort();
                    if (viewPort != null) group.setViewPort(viewPort);
                    camera = provider.bindToLifecycle(this, selector, group.build());
                } else {
                    // Preserve v1.2+ background camera behaviour: no Activity/preview surface
                    // is required, so switching to another app does not stop video sharing.
                    camera = provider.bindToLifecycle(this, selector, analysis);
                }
                notifyStatus(preview != null ? "Camera active • preview/share framing matched" : "Camera active in background");
'''
if old_bind not in svc:
    raise SystemExit('v1.6 Android anchor missing: CameraX bind block')
svc = svc.replace(old_bind, new_bind, 1)

main = main.replace('TextView version = text("  v1.2", 12, false);', 'TextView version = text("  v1.6", 12, false);')

for marker in [
    'PhoneBridge v1.6 preview/share match',
    '.setResolutionSelector(resolutionSelector)',
    'UseCaseGroup.Builder',
    'group.setViewPort(viewPort)',
    'provider.bindToLifecycle(this, selector, analysis)',
    'Camera active in background'
]:
    if marker not in svc:
        raise SystemExit('v1.6 Android required marker missing: ' + marker)
if 'PreviewView.ScaleType.FIT_CENTER' not in main:
    raise SystemExit('Regression: Android FIT_CENTER preview missing')

main_path.write_text(main, encoding='utf-8', newline='\n')
svc_path.write_text(svc, encoding='utf-8', newline='\n')
print('Applied PhoneBridge v1.6 matching Preview/ImageAnalysis framing while preserving background streaming')
