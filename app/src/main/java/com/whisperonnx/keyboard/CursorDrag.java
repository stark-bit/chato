package com.whisperonnx.keyboard;

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

    public boolean isDragging() {
        return dragging;
    }

    public int[] move(float x, float y) {
        if (!dragging) {
            if (Math.hypot(x - startX, y - startY) < slop) return new int[] {0, 0};
            dragging = true;
        }
        int cols = (int) ((x - anchorX) / stepX);
        int lines = (int) ((y - anchorY) / stepY);
        anchorX += cols * stepX;
        anchorY += lines * stepY;
        return new int[] {cols, lines};
    }
}
