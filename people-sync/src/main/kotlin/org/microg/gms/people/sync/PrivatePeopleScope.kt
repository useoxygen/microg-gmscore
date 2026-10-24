/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.people.sync

enum class PrivatePeopleScope(val scope: String) {
    READONLY("https://www.googleapis.com/auth/peopleapi.readonly"),
    READWRITE("https://www.googleapis.com/auth/peopleapi.readwrite"),
    LEGACY_READWRITE("https://www.googleapis.com/auth/peopleapi.legacy.readwrite")
}
