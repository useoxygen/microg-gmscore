/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.migration.fixture;
import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.security.MessageDigest;
/** Synthetic bytes only. Destination launch MUST NOT seed and hide missing restore. */
public class FixtureActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String result;
        try {
            File file = new File(getFilesDir(), "fixture.bin");
            if (getIntent().getBooleanExtra("seed", false)) {
                byte[] bytes = new byte[100003];
                for (int i=0;i<bytes.length;i++) bytes[i]=(byte)(i%251);
                try (FileOutputStream out = new FileOutputStream(file)) { out.write(bytes); out.getFD().sync(); }
                getSharedPreferences("fixture",0).edit().putInt("counter",712).putString("note","synthetic restore receipt").commit();
            }
            if (!file.isFile()) result = "EMPTY: no restored fixture (destination has not been seeded)";
            else {
                MessageDigest digest=MessageDigest.getInstance("SHA-256"); byte[] buffer=new byte[4096];
                try(FileInputStream in=new FileInputStream(file)) { int count; while((count=in.read(buffer))!=-1) digest.update(buffer,0,count); }
                StringBuilder hex=new StringBuilder(); for(byte b:digest.digest())hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
                result="bytes="+file.length()+"\nsha256="+hex+"\ncounter="+getSharedPreferences("fixture",0).getInt("counter",-1);
            }
        } catch(Exception e) { result="FAIL: "+e.getClass().getSimpleName(); }
        TextView text=new TextView(this); text.setTextSize(22); text.setPadding(40,80,40,40); text.setText(result); setContentView(text);
        android.util.Log.i("Cyc712Fixture",result);
    }
}
