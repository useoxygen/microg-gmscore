/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.migration.lab
import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import org.microg.gms.migration.backup.GmsClassicPeerProbe
class ProbeActivity : Activity() {
    override fun onCreate(state: Bundle?) { super.onCreate(state); val text = TextView(this).apply { text = "Synthetic Nearby interop test\nNo accounts or personal data\nRunning…"; textSize = 24f; setPadding(40,80,40,40) }; setContentView(text); GmsClassicPeerProbe.start(this) { message -> runOnUiThread { text.text = message } } }
}
