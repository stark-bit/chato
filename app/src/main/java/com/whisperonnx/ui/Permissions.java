package com.whisperonnx.ui;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.whisperonnx.R;

import java.util.ArrayList;
import java.util.List;

public final class Permissions {
    private Permissions() {}

    public static void requestMissing(Activity activity, boolean bluetooth) {
        List<String> perms = new ArrayList<>();
        if (missing(activity, Manifest.permission.RECORD_AUDIO)) {
            perms.add(Manifest.permission.RECORD_AUDIO);
            Toast.makeText(activity, activity.getString(R.string.need_record_audio_permission), Toast.LENGTH_SHORT).show();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && missing(activity, Manifest.permission.POST_NOTIFICATIONS)) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (bluetooth && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && missing(activity, Manifest.permission.BLUETOOTH_CONNECT)) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (!perms.isEmpty()) activity.requestPermissions(perms.toArray(new String[0]), 0);
    }

    private static boolean missing(Activity activity, String permission) {
        return ContextCompat.checkSelfPermission(activity, permission) != PackageManager.PERMISSION_GRANTED;
    }
}
