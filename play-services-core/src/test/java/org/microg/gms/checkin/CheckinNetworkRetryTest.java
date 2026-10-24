/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.checkin;
import org.junit.Test;
import static org.junit.Assert.*;
public class CheckinNetworkRetryTest {
    @Test public void existingButBlockedNetworkNeverRegistersImmediateCallback() {
        CheckinNetworkRetry retry = new CheckinNetworkRetry();
        for (int i = 0; i < 10000; i++) assertFalse(retry.shouldRegister(i, false, true));
    }
    @Test public void missingNetworkWaitIsSingleAndCallbackCannotReregister() {
        CheckinNetworkRetry retry = new CheckinNetworkRetry();
        assertTrue(retry.shouldRegister(0, false, false));
        for (int i = 1; i < 10000; i++) assertFalse(retry.shouldRegister(i, true, false));
        assertFalse(retry.shouldRegister(10000, false, false));
        assertTrue(retry.shouldRegister(10000 + TriggerReceiver.NETWORK_RETRY_DELAY, false, false));
    }
    @Test public void repeatedBootOrAirplaneBroadcastsAreBounded() {
        CheckinNetworkRetry retry = new CheckinNetworkRetry();
        assertTrue(retry.shouldRegister(0, false, false));
        for (int i = 1; i < 10000; i++) assertFalse(retry.shouldRegister(i, false, false));
        assertTrue(retry.shouldRegister(TriggerReceiver.NETWORK_RETRY_DELAY, false, false));
    }
}
