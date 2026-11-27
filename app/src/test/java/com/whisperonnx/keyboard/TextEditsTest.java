package com.whisperonnx.keyboard;

import static org.junit.Assert.assertEquals;

import android.view.inputmethod.EditorInfo;
import org.junit.Test;

public class TextEditsTest {
    private static int del(String before) {
        return TextEdits.wordDeleteLength(before);
    }

    @Test
    public void deletesLastWord() {
        assertEquals(5, del("hello world"));
        assertEquals(4, del("word"));
    }

    @Test
    public void deletesTrailingWhitespaceWithTheWord() {
        assertEquals(8, del("hello world   "));
        assertEquals(6, del("hello\tthere\n"));
    }

    @Test
    public void onlyWhitespaceDeletesIt() {
        assertEquals(3, del("   "));
    }

    @Test
    public void punctuationRunIsItsOwnWord() {
        assertEquals(3, del("wait..."));
        assertEquals(2, del("hello, "));
        assertEquals(3, del("a ?!;"));
    }

    @Test
    public void digitsCountAsWordCharacters() {
        assertEquals(6, del("call 555123"));
        assertEquals(5, del("x item2"));
    }

    @Test
    public void emojiIsDeletedWhole() {
        assertEquals(2, del("nice 😀"));
        assertEquals(4, del("nice 😀😀"));
    }

    @Test
    public void stopsAtApostrophe() {
        assertEquals(1, del("it's"));
    }

    @Test
    public void nothingBeforeCursor() {
        assertEquals(0, del(""));
        assertEquals(0, TextEdits.wordDeleteLength(null));
    }

    @Test
    public void bullet_atStartOfFieldOrLine() {
        assertEquals("- ", TextEdits.bulletInsert(null));
        assertEquals("- ", TextEdits.bulletInsert(""));
        assertEquals("- ", TextEdits.bulletInsert("\n"));
    }

    @Test
    public void bullet_midLineStartsNewLine() {
        assertEquals("\n- ", TextEdits.bulletInsert("a"));
        assertEquals("\n- ", TextEdits.bulletInsert(" "));
    }

    @Test
    public void enterRunsTheFieldsAction() {
        assertEquals(EditorInfo.IME_ACTION_SEARCH, TextEdits.enterAction(EditorInfo.IME_ACTION_SEARCH));
        assertEquals(
                EditorInfo.IME_ACTION_GO,
                TextEdits.enterAction(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI));
    }

    @Test
    public void enterIsANewLineWhenTheFieldSaysSo() {
        assertEquals(EditorInfo.IME_ACTION_NONE, TextEdits.enterAction(0));
        assertEquals(EditorInfo.IME_ACTION_NONE, TextEdits.enterAction(EditorInfo.IME_ACTION_NONE));
        assertEquals(
                EditorInfo.IME_ACTION_NONE,
                TextEdits.enterAction(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_ENTER_ACTION));
    }
}
