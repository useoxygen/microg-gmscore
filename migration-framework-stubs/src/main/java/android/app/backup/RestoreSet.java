/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package android.app.backup;

/** Compile-only platform API. Never packaged. */
public class RestoreSet {
    public String name;
    public String device;
    public long token;
    public RestoreSet(String name, String device, long token) { throw new RuntimeException("Stub!"); }
    public RestoreSet(String name, String device, long token, int flags) { throw new RuntimeException("Stub!"); }
}
