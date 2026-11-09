// Modified by Cyclon in 2026: microG-private getInitState transaction reports whether the handle initialized.
package com.google.android.gms.droidguard.internal;

import com.google.android.gms.droidguard.internal.DroidGuardInitReply;
import com.google.android.gms.droidguard.internal.DroidGuardResultsRequest;

interface IDroidGuardHandle {
    oneway void init(String flow) = 0;
    byte[] snapshot(in Map map) = 1;
    oneway void close() = 2;
    DroidGuardInitReply initWithRequest(String flow, in DroidGuardResultsRequest request) = 4;

    // microG-private, additive: 1 = initialized, 2 = not initialized (failed, not yet initialized, or closed). A service
    // without this transaction leaves the reply empty, which reads as 0 (unknown). The id is far from Play's own codes.
    int getInitState() = 100;
}
