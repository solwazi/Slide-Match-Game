package com.solwazi.slidemahjong;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;

import java.util.List;

/**
 * Renders the 6x6 board and handles touch-drag input.
 *
 * <p>All game rules live in {@link Board}; this view only draws the board and
 * forwards drags. A drag is recognized once the pointer travels ~30dp; the
 * dominant axis decides the direction and the drag length (in cell pitches)
 * decides how far the block tries to slide.
 */
public class BoardView extends View {

    /** UI callbacks for the hosting activity. */
    public interface BoardListener {
        void onTileCountChanged(int remaining);
        void onBoardCleared();
    }

    private static final long HINT_DURATION_MS = 1500;
    // Beat after a slide lands before the first pair clears, so the
    // player can see what matched.
    private static final long FIRST_CLEAR_DELAY_MS = 500;
    // Pause between individual pair clears: matches vanish one pair at a
    // time so the player can follow each one.
    private static final long PAIR_CLEAR_DELAY_MS = 3000;
    private static final long WIN_DIALOG_DELAY_MS = 300;
    // How long the "shuffling" notice stays up before the reshuffle happens.
    private static final long RESHUFFLE_NOTICE_DELAY_MS = 1600;
    private static final float DRAG_THRESHOLD_DP = 30f;

    private Board board;
    private BoardListener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // Board geometry in px, computed in onSizeChanged().
    private float boardSize;
    private float boardLeft;
    private float boardTop;
    private float padPx;
    private float cellPitch;
    private float tileSize;

    private final RectF tmpRect = new RectF();

    private final Paint boardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tilePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint emptyFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint emptyStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintSourcePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintTargetPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintTargetFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float touchSlopPx;

    // Touch state for the in-progress drag.
    private float downX;
    private float downY;
    private int downIndex = -1;

    // Tap-to-clear: a fresh deal sometimes already holds matches. While
    // true, taps select tiles and a valid second tap clears the pair,
    // instead of the first slide sweeping those matches away.
    private boolean tapToClearMode = false;
    private int selectedTapIndex = -1;

    // Active hint highlight.
    private Board.Hint activeHint;
    private final Runnable clearHintRunnable = new Runnable() {
        @Override
        public void run() {
            activeHint = null;
            invalidate();
        }
    };

    // True while a "shuffling the board" notice is on screen; guards
    // against scheduling the reshuffle twice.
    private boolean reshufflePending = false;
    private final Runnable reshuffleRunnable = new Runnable() {
        @Override
        public void run() {
            reshufflePending = false;
            if (board == null) {
                return;
            }
            board.reshuffle();
            afterBoardChanged();
            maybeEnterTapMode();
        }
    };
    private final Runnable pairClearRunnable = new Runnable() {
        @Override
        public void run() {
            clearNextPair();
        }
    };

    public BoardView(Context context) {
        super(context);
        init(context);
    }

    public BoardView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public BoardView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        boardPaint.setColor(0xFF0F172A);

        glyphPaint.setColor(0xFF0F172A);
        glyphPaint.setTextAlign(Paint.Align.CENTER);

        emptyFillPaint.setColor(0xFF1E293B);
        emptyFillPaint.setAlpha(38); // ~0.15 opacity, like the web version

        emptyStrokePaint.setStyle(Paint.Style.STROKE);
        emptyStrokePaint.setStrokeWidth(dpToPx(context, 1));
        emptyStrokePaint.setColor(0xFFFFFFFF);
        emptyStrokePaint.setAlpha(26); // ~0.1 opacity
        emptyStrokePaint.setPathEffect(new DashPathEffect(
                new float[]{dpToPx(context, 6), dpToPx(context, 4)}, 0));

        hintSourcePaint.setStyle(Paint.Style.STROKE);
        hintSourcePaint.setStrokeWidth(dpToPx(context, 3));
        hintSourcePaint.setColor(0xFFFBBF24);

        hintTargetPaint.setStyle(Paint.Style.STROKE);
        hintTargetPaint.setStrokeWidth(dpToPx(context, 2));
        hintTargetPaint.setColor(0xFFFBBF24);
        hintTargetPaint.setPathEffect(new DashPathEffect(
                new float[]{dpToPx(context, 8), dpToPx(context, 5)}, 0));

        hintTargetFillPaint.setColor(0xFFFBBF24);
        hintTargetFillPaint.setAlpha(64); // 0.25 opacity

        touchSlopPx = dpToPx(context, DRAG_THRESHOLD_DP);
    }

    private static float dpToPx(Context context, float dp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                context.getResources().getDisplayMetrics());
    }

    public void setBoard(Board board) {
        this.board = board;
    }

    /** Switches difficulty and immediately deals a fresh board at that level. */
    public void setDifficulty(Board.Difficulty difficulty) {
        if (board == null) {
            return;
        }
        board.setDifficulty(difficulty);
        newBoard();
    }

    public void setBoardListener(BoardListener listener) {
        this.listener = listener;
    }

    /** Starts a fresh game. */
    public void newBoard() {
        if (board == null) {
            return;
        }
        handler.removeCallbacks(clearHintRunnable);
        handler.removeCallbacks(pairClearRunnable);
        handler.removeCallbacks(reshuffleRunnable);
        reshufflePending = false;
        activeHint = null;
        board.newBoard();
        afterBoardChanged();
        maybeEnterTapMode();
    }

    /**
     * A fresh deal (or reshuffle) sometimes already holds matches. When it
     * does, let the player tap the pairs away instead of sweeping them in
     * the first slide's clear wave.
     */
    private void maybeEnterTapMode() {
        selectedTapIndex = -1;
        tapToClearMode = board != null && board.hasPairs();
        if (tapToClearMode) {
            Toast.makeText(getContext(), "Tap two matching tiles to clear them",
                    Toast.LENGTH_SHORT).show();
        }
        invalidate();
    }

    /** Tap-to-clear: select tiles; a valid second tap clears the pair. */
    private void handleTap(int index) {
        if (board == null || board.getTile(index) == null) {
            selectedTapIndex = -1;
            invalidate();
            return;
        }
        if (selectedTapIndex == -1) {
            selectedTapIndex = index;
        } else if (selectedTapIndex == index) {
            selectedTapIndex = -1; // tapping the same tile deselects it
        } else if (board.clearPair(selectedTapIndex, index)) {
            selectedTapIndex = -1;
            afterBoardChanged();
            if (!board.hasPairs()) {
                // Opening matches are gone: back to sliding. If no slide
                // exists either, offer a reshuffle instead of stranding.
                tapToClearMode = false;
                if (board.remainingTiles() > 0 && board.findHint() == null) {
                    notifyReshuffling();
                } else {
                    checkWin();
                }
            } else {
                checkWin();
            }
        } else {
            // Not a pair: move the selection to the newly tapped tile.
            selectedTapIndex = index;
        }
        invalidate();
    }

    /** Highlights one legal move, or reshuffles (with notice) if none exists. */
    public void showHint() {
        if (board == null) {
            return;
        }
        if (tapToClearMode) {
            // Point at one tile of an available pair; the player taps its partner.
            List<int[]> pairs = board.findPairs();
            if (!pairs.isEmpty()) {
                selectedTapIndex = pairs.get(0)[0];
                invalidate();
            }
            return;
        }
        Board.Hint hint = board.findHint();
        if (hint == null) {
            notifyReshuffling();
            return;
        }
        activeHint = hint;
        invalidate();
        handler.removeCallbacks(clearHintRunnable);
        handler.postDelayed(clearHintRunnable, HINT_DURATION_MS);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // The board is always square.
        int size = Math.min(MeasureSpec.getSize(widthMeasureSpec),
                MeasureSpec.getSize(heightMeasureSpec));
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        boardSize = Math.min(w, h);
        padPx = dpToPx(getContext(), 10);
        float gapPx = dpToPx(getContext(), 6);
        tileSize = (boardSize - 2 * padPx - 5 * gapPx) / Board.GRID_SIZE;
        cellPitch = tileSize + gapPx;
        boardLeft = (w - boardSize) / 2f;
        boardTop = (h - boardSize) / 2f;
        glyphPaint.setTextSize(tileSize * 0.58f);
    }

    private void cellRect(int index, RectF out) {
        int row = index / Board.GRID_SIZE;
        int col = index % Board.GRID_SIZE;
        float left = boardLeft + padPx + col * cellPitch;
        float top = boardTop + padPx + row * cellPitch;
        out.set(left, top, left + tileSize, top + tileSize);
    }

    private int cellIndexAt(float x, float y) {
        float x0 = boardLeft + padPx;
        float y0 = boardTop + padPx;
        float inner = boardSize - 2 * padPx;
        if (x < x0 || y < y0 || x > x0 + inner || y > y0 + inner) {
            return -1;
        }
        int col = (int) ((x - x0) / cellPitch);
        int row = (int) ((y - y0) / cellPitch);
        if (col < 0 || col >= Board.GRID_SIZE || row < 0 || row >= Board.GRID_SIZE) {
            return -1;
        }
        return row * Board.GRID_SIZE + col;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float corner = dpToPx(getContext(), 12);
        canvas.drawRoundRect(boardLeft, boardTop, boardLeft + boardSize, boardTop + boardSize,
                corner, corner, boardPaint);
        if (board == null) {
            return;
        }

        float tileCorner = dpToPx(getContext(), 6);
        for (int i = 0; i < Board.CELL_COUNT; i++) {
            cellRect(i, tmpRect);
            Tile tile = board.getTile(i);
            if (tile != null) {
                tilePaint.setColor(tile.color);
                canvas.drawRoundRect(tmpRect, tileCorner, tileCorner, tilePaint);
                float cx = tmpRect.centerX();
                float cy = tmpRect.centerY()
                        - (glyphPaint.descent() + glyphPaint.ascent()) / 2f;
                canvas.drawText(tile.symbol, cx, cy, glyphPaint);
            } else {
                canvas.drawRoundRect(tmpRect, tileCorner, tileCorner, emptyFillPaint);
                canvas.drawRoundRect(tmpRect, tileCorner, tileCorner, emptyStrokePaint);
            }
        }

        if (activeHint != null) {
            List<Integer> block = activeHint.blockIndices;
            for (int index : block) {
                cellRect(index, tmpRect);
                canvas.drawRoundRect(tmpRect, tileCorner, tileCorner, hintSourcePaint);
            }
            for (int index : block) {
                int dest = index + activeHint.step * activeHint.distance;
                if (dest < 0 || dest >= Board.CELL_COUNT) {
                    continue;
                }
                cellRect(dest, tmpRect);
                canvas.drawRoundRect(tmpRect, tileCorner, tileCorner, hintTargetFillPaint);
                canvas.drawRoundRect(tmpRect, tileCorner, tileCorner, hintTargetPaint);
            }
        }

        if (selectedTapIndex >= 0 && board.getTile(selectedTapIndex) != null) {
            cellRect(selectedTapIndex, tmpRect);
            canvas.drawRoundRect(tmpRect, tileCorner, tileCorner, hintSourcePaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (board == null) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                int index = cellIndexAt(event.getX(), event.getY());
                if (index >= 0 && board.getTile(index) != null) {
                    downX = event.getX();
                    downY = event.getY();
                    downIndex = index;
                    return true;
                }
                return false;
            }
            case MotionEvent.ACTION_UP: {
                // Commit on release using the total drag distance, like the
                // original game and the Swing version: a longer drag moves
                // the block further instead of always moving a single cell.
                if (downIndex < 0) {
                    break;
                }
                int startIndex = downIndex;
                downIndex = -1;
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                float absDx = Math.abs(dx);
                float absDy = Math.abs(dy);
                if (Math.max(absDx, absDy) < touchSlopPx) {
                    if (tapToClearMode) {
                        handleTap(startIndex);
                    }
                    break; // treated as a tap, not a drag
                }
                boolean horizontal = absDx > absDy;
                int step;
                if (horizontal) {
                    step = dx > 0 ? 1 : -1;
                } else {
                    step = dy > 0 ? Board.GRID_SIZE : -Board.GRID_SIZE;
                }
                float dragPx = horizontal ? absDx : absDy;
                int requestedDistance = Math.max(1, Math.round(dragPx / cellPitch));
                if (board.trySlide(startIndex, step, horizontal, requestedDistance)) {
                    // Sliding ends tap-to-clear: the clear wave sweeps the rest.
                    tapToClearMode = false;
                    selectedTapIndex = -1;
                    afterMove();
                }
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                downIndex = -1;
                break;
        }
        return true;
    }

    private void afterBoardChanged() {
        invalidate();
        if (listener != null) {
            listener.onTileCountChanged(board.remainingTiles());
        }
    }

    /** Runs after a committed slide: pauses, then clears one pair at a time. */
    private void afterMove() {
        handler.removeCallbacks(clearHintRunnable);
        activeHint = null;
        // Let the player see the landed tiles before anything vanishes.
        handler.postDelayed(pairClearRunnable, FIRST_CLEAR_DELAY_MS);
    }

    /**
     * Clears a single pair, then schedules the next one after a pause so
     * each match vanishes on its own. Chain reactions resolve naturally:
     * clearing a pair can open line of sight for the next one.
     */
    private void clearNextPair() {
        if (board == null) {
            return;
        }
        boolean cleared = board.clearOnePair();
        afterBoardChanged();
        if (cleared) {
            handler.postDelayed(pairClearRunnable, PAIR_CLEAR_DELAY_MS);
        } else {
            afterClearsSettled();
        }
    }

    /** Called once all pairs are cleared: reshuffle with notice when stuck. */
    private void afterClearsSettled() {
        if (board.remainingTiles() > 0 && board.findHint() == null) {
            notifyReshuffling();
        } else {
            checkWin();
        }
    }

    /**
     * Shows a "shuffling the board" notice, pauses, then reshuffles.
     * Used instead of reshuffling silently when no legal move remains.
     */
    private void notifyReshuffling() {
        if (reshufflePending || board == null) {
            return;
        }
        reshufflePending = true;
        Toast.makeText(getContext(), "No moves left \u2014 shuffling the board\u2026",
                Toast.LENGTH_LONG).show();
        handler.postDelayed(reshuffleRunnable, RESHUFFLE_NOTICE_DELAY_MS);
    }

    private void checkWin() {
        if (board.remainingTiles() == 0 && listener != null) {
            handler.postDelayed(() -> {
                if (listener != null) {
                    listener.onBoardCleared();
                }
            }, WIN_DIALOG_DELAY_MS);
        }
    }
}
