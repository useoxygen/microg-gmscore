# Offline notices

Cyclon Services and its Companion generate `assets/cyclon/notices.json` from
the selected variant's external runtime artifacts. The About screen reads this
asset without a network request. Original Maven coordinates and artifact SHA-256
hashes are retained in the inventory; local cache paths are never packaged.

`registry.json` records reviewed license declarations for exact versions, including
parent POM declarations and Jackson's own JAR license declaration. Unknown runtime
coordinates fail the build and require review rather than defaulting to Apache.
`generate.py` retains actual LICENSE/NOTICE/COPYING entries from JARs, AARs and
their nested `classes.jar`/`libs/*.jar` files. Canonical full license texts and
component-specific BSD copyright notices supplement artifacts that omit them.
Python 3 is required on the build host. This registry covers the Cyclon MapLibre
(`mapbox`) and Companion variants. Other upstream flavors require their own
license review before they can be built with this gate.

The concrete release gaps repaired here are SLF4J's full MIT license (including
QOS.ch copyright) and Jackson's original NOTICE (including Tatu Saloranta).
New Cyclon fonts retain their full OFL notices. Original microG icon resources
remain unmodified, with their CC-BY-SA attribution; Cyclon uses its own artwork.

Run `python3 cyclon/notices/test_generate.py`, or the standalone Gradle
`testNotices` task in `cyclon/tests`. Inspect the built APK's notices asset as
well: metadata review alone is not packaging verification.

## Coverage and release review

This inventories Maven runtime artifacts and known bundled resources. It is not
a complete license audit of native libraries, embedded native dependencies, map
styles, sprites, glyphs, or the complete Android image. In particular, the
packaged Cronet, Conscrypt, MapLibre, OpenCV and CameraX native code needs review
at its exact shipped revision. Map artwork requirements are documented upstream
in `artwork/styles/README.md`. This inventory does not grant rights to Google
services or establish Positon API-key coverage.

Before a broader commercial OTA, complete that native/asset review and review
the image's notices and any corresponding-source obligations. Preserve the
existing package identities and dedicated signer when promoting artifacts.
Do not represent this branding change as legal clearance or Google certification.

## Input provenance

- Apache-2.0 and CC0-1.0 texts: the existing upstream `LICENSES/` files.
- Manrope and Space Mono: unchanged Cyclon ShellUi fonts and their OFL files.
- MapLibre Native: `maplibre/maplibre-native`, tag `android-v10.2.0`, `LICENSE.md`.
- Mapbox gestures: `mapbox/mapbox-gestures-android`, tag `v0.7.0`, `LICENSE.md`.
- MapLibre annotations: `maplibre/maplibre-plugins-android`, `LICENSE` (Mapbox
  copyright; matched to the published component's BSD declaration).
- Protocol Buffers: `protocolbuffers/protobuf`, tag `v2.6.0`, `COPYING.txt`.
- SLF4J 1.7.36: `qos-ch/slf4j`, tag `v_1.7.36`, `LICENSE.txt`; 2.1.0-alpha1
  supplies its license directly inside the selected JAR.
- libyuv: upstream libyuv `LICENSE`, supplementing CameraX's explicit BSD declaration.
- CC-BY-SA-4.0: SPDX license-list-data `v3.27.0` canonical text.

Retain component-specific copyrights and notices when updating these inputs;
do not replace a BSD/MIT license with an authorless generic template.
