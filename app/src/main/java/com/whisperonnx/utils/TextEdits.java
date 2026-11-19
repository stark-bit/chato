package com.whisperonnx.utils;

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
}
