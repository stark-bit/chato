package com.whisperonnx.keyboard;

import android.view.inputmethod.EditorInfo;

/** Pure text rules behind the keyboard's editing keys. */
public final class TextEdits {
    private TextEdits() {}

    /**
     * Number of characters to delete before the cursor to remove the previous word: trailing
     * whitespace, then either a run of letters/digits or a run of punctuation/symbols/emoji.
     */
    public static int wordDeleteLength(CharSequence before) {
        if (before == null) return 0;
        int i = before.length();
        while (i > 0 && Character.isWhitespace(before.charAt(i - 1))) i--;
        if (i > 0 && Character.isLetterOrDigit(before.charAt(i - 1))) {
            while (i > 0 && Character.isLetterOrDigit(before.charAt(i - 1))) i--;
        } else {
            while (i > 0 && !Character.isWhitespace(before.charAt(i - 1))
                    && !Character.isLetterOrDigit(before.charAt(i - 1))) i--;
        }
        return before.length() - i;
    }

    /**
     * Text the bullet key inserts, given the character before the cursor (null or empty if
     * there is none): a new "- " item, on a new line unless the cursor is already at a line start.
     */
    public static String bulletInsert(CharSequence charBefore) {
        boolean atLineStart = charBefore == null || charBefore.length() == 0
                || charBefore.charAt(charBefore.length() - 1) == '\n';
        return atLineStart ? "- " : "\n- ";
    }

    /**
     * The editor action the enter key should run for a field's {@code imeOptions}, e.g.
     * {@link EditorInfo#IME_ACTION_SEARCH} in a search box, or {@link EditorInfo#IME_ACTION_NONE}
     * to send a plain Enter (a new line). Fields that set {@link EditorInfo#IME_FLAG_NO_ENTER_ACTION},
     * like multi-line messages, get a new line.
     */
    public static int enterAction(int imeOptions) {
        if ((imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return EditorInfo.IME_ACTION_NONE;
        int action = imeOptions & EditorInfo.IME_MASK_ACTION;
        return action == EditorInfo.IME_ACTION_UNSPECIFIED ? EditorInfo.IME_ACTION_NONE : action;
    }
}
