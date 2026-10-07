# Offline notices

Cyclon Services and its Companion generate `assets/cyclon/notices.json` from
selected-variant runtime artifacts and reviewed bundled resources. About reads
this asset offline and pages long notices without truncating their contents.
Original coordinates, artifact SHA-256s and native member paths/SHA-256s are
retained; local cache paths are never packaged. Fonts and map-resource notices
are scoped to Services, which ships those resources.

`registry.json` records declarations for exact versions, including parent POMs
and Jackson's own JAR declaration. Unknown coordinates fail the build. Every
`.so` member must match the reviewed native inventory; additions, removals or
changed bytes require review even if the Maven version is unchanged.
`generate.py` preserves actual LICENSE/NOTICE/COPYING/COPYRIGHT entries,
including plural filenames, from JARs, AARs and nested `classes.jar`/`libs/*.jar`.
Full component-specific texts supplement artifacts that omit notices.

Python 3 is required. The registry covers Cyclon's MapLibre (`mapbox`) and
Companion variants; other upstream flavors require their own review. Run
`python3 cyclon/notices/test_generate.py`, or `testNotices` in `cyclon/tests`.
The Core unit tests also check that long texts retain every character when
paged. Inspect each built APK's notices asset as a separate packaging check.

## Native producer review

The runtime AARs below contain 28 reviewed native members across four ABIs,
including OpenCV's duplicate prefab members. Their notice bundles are absent
from the AARs themselves. Registry provenance URLs are also included in About.

| Runtime coordinate | Bundled notice input |
| --- | --- |
| `org.maplibre.gl:android-sdk:10.2.0` | `android-v10.2.0` core and Android notice bundles, source `d5fae9910d985f86f39e5d9f97249d5511b6b76a` |
| `org.microg.gms:conscrypt-gmscore:2.5.2` | Producer `a0b6138cc7a62d31578bfb4abc3f6d482ba14b70` pins Conscrypt 2.5.2 and BoringSSL `409ea2837deef434be575d6e790ecc93df9dc899`; retain Conscrypt NOTICE and BoringSSL's complete license |
| `org.microg:cronet-native:102.5005.125` | Producer `466561fdb8171825f8ab61e3ad89ef57ce3bf3d6` pins Chromium 102.0.5005.125 (`c77ce0c0fc9ea1554d15ff6c72a7670268b128e4`); retain that Chromium release's generated Cronet LICENSE |
| `org.opencv:opencv:4.11.0` | Official 4.11.0 Android SDK notice collection plus shipped static dependencies and libc++ runtime notices, detailed below |
| `androidx.camera:camera-core:1.3.0` | AndroidX Apache-2.0 declaration and the libyuv BSD notice for the image-processing JNI library |

MapLibre uses the historical `LICENSE.mbgl-core.md` and
`platform/android/LICENSE.md`, rather than a newer release's bundle. Cronet's
producer packaging script omits the generated license; the supplemented file is
[the official 102.0.5005.125 bundle](https://storage.googleapis.com/chromium-cronet/android/102.0.5005.125/Release/cronet/LICENSE).
Its producer and pinned Chromium source URLs remain alongside the text.
These are producer-version notice matches, not a claim of reproducible builds or
a complete transitive SBOM proved from every native binary.

OpenCV's shipped `getBuildInformation` identifies 4.11.0 and NDK 26.3.11579264,
with libprotobuf, ADE, TBB, ITTNotify, JPEG Turbo, WebP, PNG, TIFF, OpenJPEG,
OpenEXR, Carotene and KleidiCV 0.3.0. The notice collection comes from the
[official Android SDK](https://github.com/opencv/opencv/releases/download/4.11.0/opencv-4.11.0-android-sdk.zip).
The SDK's arm64 OpenCV binary hash was checked against the runtime AAR;
libc++ is shipped separately by the Maven packaging. The unrelated license-plate cascade data is excluded. ITTNotify uses its
BSD option; the producer's alternative GPL text is retained in the collection.
Source notices for WebP, TIFF and NVIDIA Carotene supplement SDK omissions.
KleidiCV is matched to OpenCV's pinned 0.3.0 tarball MD5
`51a77b0185c2bac2a968a2163869b1ed`; Arm's Apache license is retained.
The libc++/libc++abi/libunwind texts come from the shipped compiler revision
`d9f89f4d16663d5012e5c09495f3b30ece3d2362`, including the LLVM exceptions and
legacy notices. Preserve producer notices when updating these inputs.

## Bundled artwork and fonts

SLF4J retains QOS.ch's full MIT notice; Jackson retains Tatu Saloranta's NOTICE.
Cyclon's Manrope and Space Mono fonts retain their OFL files. Original microG
icon resources remain unmodified with CC-BY-SA attribution; Cyclon uses its own
launcher artwork and keeps the visible “Based on microG” credit.

The map inventory supplements `artwork/styles/README.md` with Mapbox Open
Styles' BSD code / CC-BY-3.0 design / CC0 sprite notice and Stadia OSM Bright /
Outdoors CC-BY-4.0 attribution. The shipped Roboto 2.137 fonts use Apache-2.0;
**Open Sans 3.000 uses OFL-1.1**, not the older Open Sans Apache license.
The legacy glyph asset name containing “Arial Unicode MS” is a symlink to
Roboto Regular; no Arial font was found in these bundled glyph inputs.

The historical Open Styles license does **not** establish permission to
redistribute `style-mapbox-outdoors-v12.json`. That bundled asset remains a
release review item; terrain selection is unchanged here. Remote tiles,
imagery and API contracts also retain their own terms. About credits do not
replace attribution required in the map interface.

## Android image and OTA review

This change does not establish Google certification, Google-service rights,
Positon API-key coverage, or permission under map-provider contracts. Resolve
those service/asset terms before a broader commercial distribution.

The whole-image review is separate from this APK inventory. Freeze the intended
signed target-files, exact manifest and every shipped copyleft component's
corresponding source/build inputs. Verify image notices and the applicable
source-delivery and GPLv3 installation-information requirements against those
actual bytes before making a source offer. The product's current release tooling
does not yet enforce a corresponding-source publication gate.

Earlier Akita release `akita-1791208837` had 330 kernel modules: 60 GKI and 270
Pixel device modules. GKI source `b3d4cdadf33987734d971192fa729768e3a0501d`
was identified; exact corresponding source for the Pixel modules was unresolved.
That historical inventory is not acceptance of a newer signed image. Removing
optional terminal tools does not remove kernel, Aurora, Etar or other remaining
copyleft obligations. This PR neither publishes a legal page/source offer nor
claims full Android-image clearance.

Promotion still requires a new product source pin, rebuilt APKs under the
existing dedicated signer, signed-image acceptance and the normal OTA workflow.
Source merge and emulator UI checks do not satisfy those release gates.

## Other input provenance

- Apache-2.0 and CC0-1.0: existing upstream `LICENSES/` files.
- Manrope / Space Mono: unchanged Cyclon ShellUi fonts and their OFL files.
- Mapbox gestures: `mapbox/mapbox-gestures-android`, `v0.7.0`, `LICENSE.md`.
- MapLibre annotations: `maplibre/maplibre-plugins-android`, original Mapbox BSD copyright.
- Protocol Buffers: `protocolbuffers/protobuf`, `v2.6.0`, `COPYING.txt`.
- SLF4J 1.7.36: `qos-ch/slf4j`, `v_1.7.36`, `LICENSE.txt`; 2.1.0-alpha1 supplies its own JAR license.
- libyuv: upstream `LICENSE`, supplementing CameraX's explicit BSD declaration.
- CC-BY-SA-4.0 / CC-BY-3.0 / CC-BY-4.0: SPDX license-list-data `v3.27.0` texts.
- Mapbox Open Styles: `mapbox/mapbox-gl-styles`, `LICENSE.md` (effective styles as of May 3, 2016).
- Stadia design attribution: [official attribution page](https://stadiamaps.com/attribution/).
- Open Sans: embedded Version 3.000 / 2020 Open Sans Project Authors copyright, matching `google/fonts/ofl/opensans/OFL.txt`.

Retain author-specific copyrights rather than substituting generic BSD/MIT text.
