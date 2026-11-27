package com.whisperonnx.recording;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;

// External: exclusive transient audio focus pauses media apps; most resume on release, apps ignoring focus play on
public class OtherAudioPauser {
    private final AudioManager audioManager;
    private final AudioFocusRequest request;
    private boolean held;

    public OtherAudioPauser(Context context) {
        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        request = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setOnAudioFocusChangeListener(
                        change -> {}) // External: a call taking audio focus doesn't stop the recording
                .build();
    }

    public synchronized void pause() {
        if (held) return;
        held = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    public synchronized void release() {
        if (!held) return;
        audioManager.abandonAudioFocusRequest(request);
        held = false;
    }
}
