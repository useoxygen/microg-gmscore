/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.android

/** Only trusted, generated classic-nearby cache files are exposed; the owning
 * client receives a read grant for each exact URI, never a directory grant. */
class ClassicFileProvider : androidx.core.content.FileProvider()
