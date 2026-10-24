/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package android.app.backup;

/** Compile-only platform API. Never packaged. */
public class RestoreDescription {
    public static final int TYPE_KEY_VALUE = 1;
    public static final int TYPE_FULL_STREAM = 2;
    public static final RestoreDescription NO_MORE_PACKAGES = null;
    public RestoreDescription(String packageName, int dataType) { throw new RuntimeException("Stub!"); }
    public String getPackageName() { throw new RuntimeException("Stub!"); }
    public int getDataType() { throw new RuntimeException("Stub!"); }
}
