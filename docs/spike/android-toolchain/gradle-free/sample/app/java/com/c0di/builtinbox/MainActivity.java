package com.c0di.builtinbox;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        TextView build = (TextView) findViewById(R.id.build_info);
        build.setText(BuildInfo.TEXT);

        final TextView runtime = (TextView) findViewById(R.id.runtime_info);
        Button b = (Button) findViewById(R.id.whereami);

        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                StringBuilder s = new StringBuilder();
                s.append("device    ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
                s.append("android   ").append(Build.VERSION.RELEASE)
                 .append("  (API ").append(Build.VERSION.SDK_INT).append(")\n");
                s.append("abi       ").append(Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?").append('\n');
                s.append("cores     ").append(Runtime.getRuntime().availableProcessors()).append('\n');
                s.append('\n');
                s.append("Same silicon that compiled it —\nminus the emulator in between.");
                runtime.setText(s.toString());
            }
        });
    }
}
