#!/bin/sh
# SPDX-FileCopyrightText: 2026 Cyclon
# SPDX-License-Identifier: Apache-2.0
set -eu
source_dir=${1:?usage: build-pake.sh /path/to/boringssl /path/to/output}
output_dir=${2:?usage: build-pake.sh /path/to/boringssl /path/to/output}
pin=90c6b370770adce64ff435789bfd1940067df415
[ "$(git -C "$source_dir" rev-parse HEAD)" = "$pin" ] || { echo 'BoringSSL must match the spec pin' >&2; exit 2; }
mkdir -p "$output_dir"
cmake -S "$source_dir" -B "$output_dir/boringssl" -GNinja -DCMAKE_BUILD_TYPE=Release -DBUILD_TESTING=OFF
cmake --build "$output_dir/boringssl" --target ssl crypto -j 2
c++ -std=c++17 -pthread -I"$source_dir/include" "$(dirname "$0")/pake-peer.cc" \
  "$output_dir/boringssl/libssl.a" "$output_dir/boringssl/libcrypto.a" -o "$output_dir/pake-peer"
"$output_dir/pake-peer" --self-test
