/*
 * Copyright (C) 2013-2017 microG Project Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.microg.tools;

import org.microg.gms.cyclon.DeviceAccount;
import java.util.Arrays;
import java.util.ArrayList;
import android.accounts.AccountManager;
import android.accounts.Account;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;

/**
 * This is just an activity that forwards to the systems native account picker
 */
public class AccountPickerActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle extras = getIntent().getExtras();
        if (extras == null) extras = new Bundle();
        leaveOutDeviceAccount(extras);
        Intent intent = new Intent();
        ComponentName componentName =
                ComponentName.unflattenFromString("android/.accounts.ChooseTypeAndAccountActivity");
        intent.setClassName(componentName.getPackageName(), componentName.getClassName());
        intent.putExtras(extras);
        intent.addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT);
        startActivity(intent);
        finish();
    }

    /**
     * Cyclon's device account is never offered (CYC-927): for a Google-account choice, the system chooser gets the
     * allowed accounts without it. The system chooser lists as the system, which Cyclon's OS also keeps it out of.
     */
    private void leaveOutDeviceAccount(Bundle extras) {
        if (DeviceAccount.INSTANCE.shown()) return;
        String[] types = extras.getStringArray("allowableAccountTypes");
        if (types == null || types.length != 1 || !"com.google".equals(types[0])) return;
        ArrayList<Account> allowed = extras.getParcelableArrayList("allowableAccounts");
        if (allowed == null) allowed = new ArrayList<>(Arrays.asList(AccountManager.get(this).getAccountsByType("com.google")));
        allowed.removeIf(account -> DeviceAccount.INSTANCE.isDevice(this, account));
        extras.putParcelableArrayList("allowableAccounts", allowed);
    }
}
