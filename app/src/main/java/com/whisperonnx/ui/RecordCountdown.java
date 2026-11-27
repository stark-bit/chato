package com.whisperonnx.ui;

import android.app.Activity;
import android.os.CountDownTimer;
import android.widget.ProgressBar;

public final class RecordCountdown {
    private static final long LIMIT_MS = 30000;

    private RecordCountdown() {}

    public static CountDownTimer start(Activity activity, ProgressBar bar) {
        activity.runOnUiThread(() -> bar.setProgress(100));
        CountDownTimer timer = new CountDownTimer(LIMIT_MS, 1000) {
            @Override
            public void onTick(long left) {
                activity.runOnUiThread(() -> bar.setProgress((int) (left * 100 / LIMIT_MS)));
            }

            @Override
            public void onFinish() {}
        };
        timer.start();
        return timer;
    }
}
