/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.checkin;

/** Bounds registration even when a matching network is unusable by our UID. */
final class CheckinNetworkRetry {
    private long nextRegistration;
    boolean shouldRegister(long now, boolean callbackDelivery, boolean physicalInternet) {
        if (callbackDelivery) nextRegistration = Math.max(nextRegistration, now + TriggerReceiver.NETWORK_RETRY_DELAY);
        if (callbackDelivery || physicalInternet || now < nextRegistration) return false;
        nextRegistration = now + TriggerReceiver.NETWORK_RETRY_DELAY;
        return true;
    }
}
