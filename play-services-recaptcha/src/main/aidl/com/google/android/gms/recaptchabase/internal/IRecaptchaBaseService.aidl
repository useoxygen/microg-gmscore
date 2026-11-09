package com.google.android.gms.recaptchabase.internal;

import com.google.android.gms.recaptchabase.ExecuteRequest;
import com.google.android.gms.recaptchabase.InitRequest;
import com.google.android.gms.recaptchabase.internal.IRecaptchaBaseCallbacks;

// Transaction codes 1 (init) and 2 (execute) are the Play services wire contract.
// Both are synchronous calls that return immediately; results arrive on the callbacks.
interface IRecaptchaBaseService {
    void init(IRecaptchaBaseCallbacks callbacks, in InitRequest request) = 0;
    void execute(IRecaptchaBaseCallbacks callbacks, in ExecuteRequest request) = 1;
}
