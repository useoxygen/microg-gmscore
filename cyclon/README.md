# Cyclon release build configuration

The official microG APK includes Positon (suggested), BeaconDB, and Custom. Its
source-only default has BeaconDB and Custom: the official release supplies the
extra provider through an untracked `local.properties` file. Cyclon's clean
source builds previously lost that configuration.

`location-sources.json` declares the exact upstream release APK used as a
configuration input. The location module verifies its size and SHA-256, extracts
the embedded provider list, and uses it for `BuildConfig.ONLINE_SOURCES`. This
retains the same provider IDs, endpoint configuration, terms, suggested flag,
import behavior, and contribution flags as the official release. API tokens and
APK bytes are not committed to Git. Existing user selections are retained;
Positon is offered as suggested without automatically changing a selection or
enabling online location.

The first build downloads the pinned APK into the root build directory. An
existing exact copy can be reused, including offline, with
`-Pcyclon.locationSourceApk=/absolute/path/upstream.apk`. A mismatched input fails
the build rather than silently falling back to a reduced provider list. The
existing `location.online-sources` and `ichnaea.endpoint` overrides continue to
work for upstream development builds.

Verify the extraction and byte-pin checks without configuring Android modules:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew -p cyclon/tests \
  testLocationSources testVersionAllocation -PupstreamApk=/absolute/path/upstream.apk
```

Then generate the actual location module configuration:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=~/Library/Android/sdk \
  ./gradlew :play-services-location-core-base:generateReleaseBuildConfig \
  -Pcyclon.locationSourceApk=/absolute/path/upstream.apk
```

`version.json` allocates GmsCore and Companion version codes for this delivery and sets the
Play services version microG reports ([reported-gms-version.md](reported-gms-version.md)).
Upstream's Git-based formula gives every commit after a tag the same code, which
would leave the fixed APK unable to advance Cyclon's currently shipped versions.
The checked-in allocation is identical in CI and a clean delivery build. Bump
both codes for each subsequent delivery, and update the tag/allocation when
rebasing to a new upstream release. The product activation gate also requires
both codes to exceed the versions in the existing signed APK lock.

The CYC-927 delivery reserves GmsCore `254132105` and Companion/FakeStore
`84022705`. The immutable published pairs up to `254132104` / `84022704` cannot be reused;
both allocations also exceed the owner-reported installed `254132096` / `84022699` baseline.
The first FakeStore allocation block is exhausted, so `version.gradle` permits
the next 100 delivery codes while retaining upstream vending compatibility
version `40.2.26`. Revisit that range when changing the upstream vending version.
`testVersionAllocation` checks the published and installed baselines and rejects
equality or rollback independently for each APK; its result is separate from signing,
installation and device acceptance.

This source change requires a new product source pin, rebuilt/signed APKs, and
normal delivery acceptance before it changes an installed Cyclon image.

The [extension acceptance guide](extensions-acceptance.md) covers app troubleshooting,
location API callbacks, geofencing and native Calendar sync, including the
repeatable emulator fixture runner and the signed-device acceptance still required.
The [two-way Calendar guide](calendar-two-way.md) documents event uploads, consent,
conflict resolution and the system Transfer Phone companion contract.
