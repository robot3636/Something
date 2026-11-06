package com.miku.something;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/**
 * A single full-screen view that draws every bouncing image and keeps their state.
 *
 * Moving one canvas drawing is far cheaper than moving several separate system
 * windows: no inter-process call per image per frame, and everything is rendered
 * together by the GPU in one pass.
 */
class BouncingView extends View {

    // Pre-scaled image shared by every bouncing item (decoded only once)
    private final Bitmap bitmap;

    // Size of the square box in which each image bounces, in pixels
    private final int cellSize;

    // Offsets that center the (possibly non-square) bitmap inside its square box
    private final float offsetX;
    private final float offsetY;

    // Position (top-left corner of the box) and direction of each item.
    // Direction: +1 = right/down, -1 = left/up
    private final float[] x;
    private final float[] y;
    private final int[] xDirection;
    private final int[] yDirection;

    // Bilinear filtering keeps the image smooth when it sits on fractional pixel positions
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);

    // True after the starting positions were spread over the screen
    private boolean positionsInitialized = false;

    BouncingView(Context context, Bitmap bitmap, int cellSize, int count) {
        super(context);
        this.bitmap = bitmap;
        this.cellSize = cellSize;
        this.offsetX = (cellSize - bitmap.getWidth()) / 2f;
        this.offsetY = (cellSize - bitmap.getHeight()) / 2f;

        x = new float[count];
        y = new float[count];
        xDirection = new int[count];
        yDirection = new int[count];
        for (int i = 0; i < count; i++) {
            xDirection[i] = 1;
            yDirection[i] = 1;
        }
    }

    /** Called when the view gets its size, and again after a rotation. */
    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (width <= 0 || height <= 0) return;

        // First time: spread the items diagonally so they do not start on top of each other
        if (!positionsInitialized) {
            for (int i = 0; i < x.length; i++) {
                x[i] = (width  / (float) x.length) * i;
                y[i] = (height / (float) x.length) * i;
            }
            positionsInitialized = true;
        }

        // Make sure every item is still inside the new screen size
        for (int i = 0; i < x.length; i++) {
            bounceOffEdges(i);
        }
    }

    /**
     * Advances every item by the given distance (pixels) and bounces it off the edges.
     * The caller must call invalidate() afterwards to redraw.
     */
    void step(float distancePx) {
        if (!positionsInitialized) return;

        for (int i = 0; i < x.length; i++) {
            x[i] += xDirection[i] * distancePx;
            y[i] += yDirection[i] * distancePx;
            bounceOffEdges(i);
        }
    }

    /** Keeps item i inside the screen and reverses its direction on contact. */
    private void bounceOffEdges(int i) {
        float maxX = Math.max(0, getWidth()  - cellSize);
        float maxY = Math.max(0, getHeight() - cellSize);

        // Right edge
        if (x[i] >= maxX) {
            x[i] = maxX;
            xDirection[i] = -1;
        }
        // Left edge
        if (x[i] <= 0) {
            x[i] = 0;
            xDirection[i] = 1;
        }

        // Bottom edge
        if (y[i] >= maxY) {
            y[i] = maxY;
            yDirection[i] = -1;
        }
        // Top edge
        if (y[i] <= 0) {
            y[i] = 0;
            yDirection[i] = 1;
        }
    }

    /** Draws every item. No objects are allocated here to avoid garbage-collector pauses. */
    @Override
    protected void onDraw(Canvas canvas) {
        for (int i = 0; i < x.length; i++) {
            canvas.drawBitmap(bitmap, x[i] + offsetX, y[i] + offsetY, paint);
        }
    }
}
