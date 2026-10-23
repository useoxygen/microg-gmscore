# Phone-only official Password Manager reader check

Part of CYC-653 / CYC-654. This is an opt-in synthetic-account fixture, not an
agent credential API or a production authentication bypass.

`PasswordOfficialClientAndroidTest` and `GooglePasswordCompare.java` compare the
native vault with the actual official Google web manager on the designated phone.
The owner completes Google's account verification and leaves the approved entry's
Edit dialog open with its password masked. The native fixture selects only the
approved synthetic realm/username. It opens a temporary local socket and accepts
only the phone's shell/root peer. The helper uses Chrome's phone-local debug socket
to read the matching masked editor and performs the comparison on the phone.
Only readiness, masking, presence and equality booleans return through
instrumentation. Passwords, their hashes, cookies and raw debug responses must
never be written to host output, files, arguments or telemetry.

Build the helper with the current Android SDK `android.jar`, Java target 8, and
`d8 --min-api 26`; package its `classes.dex` in a temporary jar. Execute it with
`app_process` on the explicitly owned userdebug device while the opt-in native
reader test is waiting. Record the helper source/artifact checksum separately
from the signed product and instrumentation APKs. Remove only that temporary jar.

Keep one Services package installed throughout the official-client round trip.
Use instrumentation's `targetProcess=com.google.android.gms:ui` to avoid replacing
the running account service between stages. Restore the original APK and remove
the temporary test package after the entire check. A matched hidden value proves
this reader path only; real account revocation, production Core fill and wider
Password Manager parity remain separate acceptance gates.
