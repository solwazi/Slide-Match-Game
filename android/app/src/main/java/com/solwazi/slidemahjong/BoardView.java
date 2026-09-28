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
    // Beat after a slide lands before the first matches clear, so the
    // player can see what matched.
    private static final long FIRST_CLEAR_DELAY_MS = 500;
    // Pause between chain-reaction clear waves.
    private static final long MATCH_CHAIN_DELAY_MS = 650;
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
        }
    };
    private final Runnable firstClearRunnable = new Runnable() {
        @Override
        public void run() {
            firstClear();
        }
    };
    private final Runnable chainCheckRunnable = new Runnable() {
        @Override
        public void run() {
            chainCheck();
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
        handler.removeCallbacks(firstClearRunnable);
        handler.removeCallbacks(chainCheckRunnable);
        handler.removeCallbacks(reshuffleRunnable);
        reshufflePending = false;
        activeHint = null;
        board.newBoard();
        afterBoardChanged();
    }

    /** Highlights one legal move, or reshuffles (with notice) if none exists. */
    public void showHint() {
        if (board == null) {
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

    /** Runs after a committed slide: pauses, then clears matches in waves. */
    private void afterMove() {
        handler.removeCallbacks(clearHintRunnable);
        activeHint = null;
        // Let the player see the landed tiles before anything vanishes.
        handler.postDelayed(firstClearRunnable, FIRST_CLEAR_DELAY_MS);
    }

    private void firstClear() {
        if (board == null) {
            return;
        }
        boolean matched = board.checkMatches();
        afterBoardChanged();
        if (matched) {
            handler.postDelayed(chainCheckRunnable, MATCH_CHAIN_DELAY_MS);
        } else {
            afterClearsSettled();
        }
    }

    private void chainCheck() {
        if (board == null) {
            return;
        }
        boolean matched = board.checkMatches();
        afterBoardChanged();
        if (matched) {
            handler.postDelayed(chainCheckRunnable, MATCH_CHAIN_DELAY_MS);
        } else {
            afterClearsSettled();
        }
    }

    /** Called once all match waves are done: reshuffle with notice when stuck. */
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
