package com.whisperonnx.keyboard;

import android.view.inputmethod.EditorInfo;

public final class TextEdits {
    private TextEdits() {}

    public static int wordDeleteLength(CharSequence before) {
        if (before == null) return 0;
        int i = before.length();
        while (i > 0 && Character.isWhitespace(before.charAt(i - 1))) i--;
        if (i > 0 && Character.isLetterOrDigit(before.charAt(i - 1))) {
            while (i > 0 && Character.isLetterOrDigit(before.charAt(i - 1))) i--;
        } else {
            while (i > 0
                    && !Character.isWhitespace(before.charAt(i - 1))
                    && !Character.isLetterOrDigit(before.charAt(i - 1))) i--;
        }
        return before.length() - i;
    }

    public static String bulletInsert(CharSequence charBefore) {
        boolean atLineStart =
                charBefore == null || charBefore.length() == 0 || charBefore.charAt(charBefore.length() - 1) == '\n';
        return atLineStart ? "- " : "\n- ";
    }

    public static int enterAction(int imeOptions) {
        if ((imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return EditorInfo.IME_ACTION_NONE;
        int action = imeOptions & EditorInfo.IME_MASK_ACTION;
        return action == EditorInfo.IME_ACTION_UNSPECIFIED ? EditorInfo.IME_ACTION_NONE : action;
    }
}
