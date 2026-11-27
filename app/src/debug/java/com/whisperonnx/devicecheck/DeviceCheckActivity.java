package com.whisperonnx.devicecheck;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;
import com.whisperonnx.R;

public class DeviceCheckActivity extends Activity {
    private int doneCount;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_device_check);
        EditText field = findViewById(R.id.check_field);
        TextView done = findViewById(R.id.check_done_count);
        done.setText(getString(R.string.device_check_done, doneCount));
        field.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_DONE) return false;
            doneCount++;
            done.setText(getString(R.string.device_check_done, doneCount));
            return true;
        });
        field.requestFocus();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        EditText field = findViewById(R.id.check_field);
        field.setText("");
        field.requestFocus();
    }
}
