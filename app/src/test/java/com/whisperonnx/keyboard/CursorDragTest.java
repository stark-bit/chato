package com.whisperonnx.keyboard;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CursorDragTest {

    private final CursorDrag drag = new CursorDrag(10, 20, 40);

    @Test public void smallMovesStayATap() {
        drag.start(100, 100);
        assertArrayEquals(new int[]{0, 0}, drag.move(106, 105));
        assertFalse(drag.isDragging());
    }

    @Test public void sidewaysMovesByCharacters() {
        drag.start(100, 100);
        assertArrayEquals(new int[]{2, 0}, drag.move(145, 100));
        assertTrue(drag.isDragging());
        assertArrayEquals(new int[]{0, 0}, drag.move(150, 100));  // 50 px: still the 2nd step
        assertArrayEquals(new int[]{-2, 0}, drag.move(85, 100));  // back to the start
    }

    @Test public void upAndDownMovesByLines() {
        drag.start(100, 100);
        assertArrayEquals(new int[]{0, -1}, drag.move(100, 55));
        assertArrayEquals(new int[]{1, 2}, drag.move(125, 145));
    }

    @Test public void staysADragAfterReturningToTheStart() {
        drag.start(100, 100);
        drag.move(130, 100);
        drag.move(100, 100);
        assertTrue(drag.isDragging());
    }
}
