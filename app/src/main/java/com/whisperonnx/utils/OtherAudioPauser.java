package com.whisperonnx.utils;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;

/**
 * Pauses other apps' audio while recording, so the mic doesn't pick up what the phone plays.
 * Takes exclusive transient audio focus: media apps pause, and most resume when it's released.
 * Apps that ignore audio focus keep playing.
 */
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
                .setOnAudioFocusChangeListener(change -> { })  // a call taking focus doesn't stop the recording
                .build();
    }

    /** Safe to call repeatedly; only the first call after a release requests focus. */
    public synchronized void pause() {
        if (held) return;
        held = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    /** Lets the paused apps resume. Safe to call when nothing is paused. */
    public synchronized void release() {
        if (!held) return;
        audioManager.abandonAudioFocusRequest(request);
        held = false;
    }
}
