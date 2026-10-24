# UKEY2 / Nearby wire reference

Apache-2.0 source, copied without decompiled Google code.

- https://github.com/google/ukey2 commit 10fc737aa901e873a3367e7e26b88eb01cd55d69: src/main/java and src/main/proto.
- https://github.com/google/nearby: connections/implementation/proto/offline_wire_formats.proto (see pinned revision below).

Only Java protobuf lite generation/build wiring is local. Keep original notices.
Cryptography is the upstream implementation; Cyclon protocol adapters are separate.

Nearby revision: c80c38efe4b8a5fa72586b58d381bb546702f062.

The protobuf-javalite3.25.5 runtime is relocated to
`org.microg.gms.migration.protobuf` inside the consumer JAR. This isolates it
from core's existing protobuf-java2.6 runtime. Guava remains an external dependency.
Original upstream source remains unchanged; relocation happens only at packaging.
