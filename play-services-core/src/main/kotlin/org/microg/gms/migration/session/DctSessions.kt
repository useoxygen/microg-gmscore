/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.session

import android.content.Context
import org.microg.gms.migration.controller.DctController

/** Only package/certificate-checked brokers and the non-exported setup service call this registry. */
internal object DctSessions {
    private var current: DctController? = null
    @Synchronized fun retained(): DctController? = current?.takeUnless { it.isClosed }
    @Synchronized fun create(context: Context, session: DctSession = DctSession.create(), serviceId: String = "quickStart", remote: Pair<String, Int>? = null): DctController {
        check(retained() == null) { "An import session is already active" }
        return DctController(context.applicationContext, session, serviceId, remote).also { current = it }
    }
}
