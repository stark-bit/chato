package com.whisperonnx.utils;

/**
 * Turns a drag on the space key into cursor steps. Once the finger has moved past the slop,
 * every {@code stepX} pixels sideways is one character and every {@code stepY} pixels up or down
 * is one line. The two axes are counted separately, so the cursor can move freely.
 */
public final class CursorDrag {
    private final float slop, stepX, stepY;
    private float startX, startY, anchorX, anchorY;
    private boolean dragging;

    public CursorDrag(float slop, float stepX, float stepY) {
        this.slop = slop;
        this.stepX = stepX;
        this.stepY = stepY;
    }

    public void start(float x, float y) {
        startX = anchorX = x;
        startY = anchorY = y;
        dragging = false;
    }

    /** True once the finger has moved past the slop: the key no longer types a space. */
    public boolean isDragging() {
        return dragging;
    }

    /**
     * Steps to move since the last call: {@code {columns, lines}}. Negative is left or up.
     */
    public int[] move(float x, float y) {
        if (!dragging) {
            if (Math.hypot(x - startX, y - startY) < slop) return new int[]{0, 0};
            dragging = true;
        }
        int cols = (int) ((x - anchorX) / stepX);
        int lines = (int) ((y - anchorY) / stepY);
        anchorX += cols * stepX;
        anchorY += lines * stepY;
        return new int[]{cols, lines};
    }
}
