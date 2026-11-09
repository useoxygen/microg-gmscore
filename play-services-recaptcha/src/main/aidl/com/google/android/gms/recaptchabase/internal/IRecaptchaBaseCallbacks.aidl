package com.google.android.gms.recaptchabase.internal;

import com.google.android.gms.common.api.Status;
import com.google.android.gms.recaptchabase.ExecuteResult;
import com.google.android.gms.recaptchabase.InitResult;

// Transaction codes 1 and 2 are the Play services wire contract; both are one-way.
oneway interface IRecaptchaBaseCallbacks {
    void onInitResponse(in Status status, in InitResult result) = 0;
    void onExecuteResponse(in Status status, in ExecuteResult result) = 1;
}
