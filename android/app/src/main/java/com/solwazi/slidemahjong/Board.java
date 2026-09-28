package com.solwazi.slidemahjong;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * UI-agnostic game logic for Daily Slide Mahjong.
 *
 * <p>A 6x6 board holding a difficulty-dependent number of tiles
 * (see {@link Difficulty}): 12 mahjong symbol pairs on easy, plus extra
 * duplicate pairs on medium and hard. A tile is
 * dragged in one of the four directions; the contiguous block of tiles ahead
 * of it slides along, but the move is only committed when at least one of
 * the moved tiles is part of a line-of-sight match (two equal symbols with
 * no other tile between them in the same row or column, gaps allowed) on
 * the resulting board. Sliding a tile merely out of the way so two untouched
 * tiles match does not count. Matching pairs are removed automatically,
 * chain reactions keep clearing, and the board is reshuffled whenever no
 * legal move remains.
 *
 * <p>Operates on a {@code Tile[36]} array; has no Android dependencies so it
 * can be unit-tested on a plain JVM.
 */
public class Board {

    public static final int GRID_SIZE = 6;
    public static final int CELL_COUNT = GRID_SIZE * GRID_SIZE;
    private static final int MAX_SOLVE_ATTEMPTS = 500;

    /** Difficulty level: harder levels pack more tiles onto the board. */
    public enum Difficulty {
        /** 24 tiles (12 pairs): roomy board, relaxed play. */
        EASY(24),
        /** 30 tiles: 3 extra duplicate pairs, noticeably more crowded. */
        MEDIUM(30),
        /** 34 tiles: 5 extra duplicate pairs, very little room to maneuver. */
        HARD(34);

        /** Total tiles dealt at this difficulty; always even and <= 36. */
        public final int tileCount;

        Difficulty(int tileCount) {
            this.tileCount = tileCount;
        }
    }

    private Difficulty difficulty = Difficulty.EASY;

    /** Current difficulty; applies to the next dealt board. */
    public Difficulty getDifficulty() {
        return difficulty;
    }

    public void setDifficulty(Difficulty difficulty) {
        this.difficulty = difficulty;
    }

    /** The 12 mahjong symbols, in palette order (matches the original game). */
    public static final String[] SYMBOLS = {
            "🀀", "🀁", "🀂", "🀃", "🀄", "🀅",
            "🀆", "🀇", "🀏", "🀕", "🀟", "🀤"
    };

    /** Per-symbol face colors, in the same order as {@link #SYMBOLS}. */
    public static final String[] SYMBOL_COLOR_HEX = {
            "#f87171", "#fb923c", "#fbbf24", "#fde047",
            "#a3e635", "#4ade80", "#2dd4bf", "#38bdf8",
            "#60a5fa", "#818cf8", "#c084fc", "#f472b6"
    };

    /** A legal slide move, as returned by {@link #findHint()}. */
    public static final class Hint {
        /** Cells of the block that would move, in slide order. */
        public final List<Integer> blockIndices;
        /** Slide step: +/-1 (horizontal) or +/-GRID_SIZE (vertical). */
        public final int step;
        /** How many cells the block would travel. */
        public final int distance;

        public Hint(List<Integer> blockIndices, int step, int distance) {
            this.blockIndices = blockIndices;
            this.step = step;
            this.distance = distance;
        }
    }

    /** The contiguous block starting at a cell plus its open runway. */
    public static final class SlideInfo {
        /** Contiguous non-null cells starting at the touched cell, in order. */
        public final List<Integer> blockIndices;
        /** Consecutive empty cells immediately ahead of the block. */
        public final int maxDistance;

        public SlideInfo(List<Integer> blockIndices, int maxDistance) {
            this.blockIndices = blockIndices;
            this.maxDistance = maxDistance;
        }
    }

    private final Tile[] cells = new Tile[CELL_COUNT];
    private final Random random = new Random();

    /** Tile at a cell, or null when the cell is empty. */
    public Tile getTile(int index) {
        return cells[index];
    }

    /** Number of tiles currently on the board. */
    public int remainingTiles() {
        int count = 0;
        for (Tile tile : cells) {
            if (tile != null) {
                count++;
            }
        }
        return count;
    }

    /** Shallow copy of the cell array, for read-only inspection (e.g. tests). */
    public Tile[] copyCells() {
        Tile[] copy = new Tile[CELL_COUNT];
        System.arraycopy(cells, 0, copy, 0, CELL_COUNT);
        return copy;
    }

    /** Deals a fresh shuffled board and guarantees it is solvable. */
    public void newBoard() {
        for (int i = 0; i < CELL_COUNT; i++) {
            cells[i] = null;
        }

        List<Tile> deck = new ArrayList<>(difficulty.tileCount);
        for (int s = 0; s < SYMBOLS.length; s++) {
            int color = Tile.parseColor(SYMBOL_COLOR_HEX[s]);
            deck.add(new Tile(SYMBOLS[s], color));
            deck.add(new Tile(SYMBOLS[s], color));
        }
        // Harder levels add extra pairs of random symbols (duplicates
        // allowed). Pairs keep every symbol count even so the board stays
        // fully clearable.
        int extraPairs = (difficulty.tileCount - deck.size()) / 2;
        for (int i = 0; i < extraPairs; i++) {
            int s = random.nextInt(SYMBOLS.length);
            int color = Tile.parseColor(SYMBOL_COLOR_HEX[s]);
            deck.add(new Tile(SYMBOLS[s], color));
            deck.add(new Tile(SYMBOLS[s], color));
        }
        Collections.shuffle(deck, random);

        List<Integer> positions = allPositionsShuffled();
        for (int i = 0; i < deck.size(); i++) {
            cells[positions.get(i)] = deck.get(i);
        }

        ensureSolvable();
    }

    /**
     * Attempts a slide starting at {@code startIndex}.
     *
     * @param step               +1/-1 for horizontal, +GRID_SIZE/-GRID_SIZE for vertical
     * @param horizontal         true when sliding along a row
     * @param requestedDistance  cells requested by the drag length
     * @return true when the move was committed (a moved tile landed in a match)
     */
    public boolean trySlide(int startIndex, int step, boolean horizontal, int requestedDistance) {
        if (startIndex < 0 || startIndex >= CELL_COUNT || cells[startIndex] == null) {
            return false;
        }
        SlideInfo info = getSlideInfo(cells, startIndex, step, horizontal);
        int distance = Math.min(requestedDistance, info.maxDistance);
        if (distance <= 0) {
            return false;
        }

        // Try on a scratch copy first; the real board is only touched on success.
        Tile[] test = deepCopy(cells);
        applySlide(test, info.blockIndices, step, distance);
        if (movedBlockCreatesMatch(test, info.blockIndices, step, distance)) {
            System.arraycopy(test, 0, cells, 0, CELL_COUNT);
            return true;
        }
        return false;
    }

    /**
     * For a tile at {@code startIndex}, returns the contiguous block of tiles
     * that would move together and how many open cells lie ahead of that block
     * before it is stopped by another tile or the board edge.
     */
    public static SlideInfo getSlideInfo(Tile[] board, int startIndex, int step, boolean horizontal) {
        int row = startIndex / GRID_SIZE;

        List<Integer> blockIndices = new ArrayList<>();
        blockIndices.add(startIndex);
        int current = startIndex;
        while (true) {
            current += step;
            if (current < 0 || current >= CELL_COUNT) {
                break;
            }
            if (horizontal && current / GRID_SIZE != row) {
                break;
            }
            if (board[current] != null) {
                blockIndices.add(current);
            } else {
                break;
            }
        }

        int lastBlockIndex = blockIndices.get(blockIndices.size() - 1);
        int maxDistance = 0;
        int cursor = lastBlockIndex + step;
        while (cursor >= 0 && cursor < CELL_COUNT
                && !(horizontal && cursor / GRID_SIZE != row)
                && board[cursor] == null) {
            maxDistance++;
            cursor += step;
        }

        return new SlideInfo(blockIndices, maxDistance);
    }

    /** Moves a block far-end-first so tiles don't overwrite each other. */
    private static void applySlide(Tile[] board, List<Integer> blockIndices, int step, int distance) {
        for (int i = blockIndices.size() - 1; i >= 0; i--) {
            int index = blockIndices.get(i);
            board[index + step * distance] = board[index];
            board[index] = null;
        }
    }

    /**
     * Read-only check: does the board contain a matching pair anywhere in
     * line of sight (same row or column, no other tile between the two,
     * gaps allowed)?
     */
    public static boolean hasLineOfSightMatch(Tile[] board) {
        for (int r = 0; r < GRID_SIZE; r++) {
            int lastTileIndex = -1;
            for (int c = 0; c < GRID_SIZE; c++) {
                int index = r * GRID_SIZE + c;
                if (board[index] != null) {
                    if (lastTileIndex != -1
                            && board[index].symbol.equals(board[lastTileIndex].symbol)) {
                        return true;
                    }
                    lastTileIndex = index;
                }
            }
        }
        for (int c = 0; c < GRID_SIZE; c++) {
            int lastTileIndex = -1;
            for (int r = 0; r < GRID_SIZE; r++) {
                int index = r * GRID_SIZE + c;
                if (board[index] != null) {
                    if (lastTileIndex != -1
                            && board[index].symbol.equals(board[lastTileIndex].symbol)) {
                        return true;
                    }
                    lastTileIndex = index;
                }
            }
        }
        return false;
    }

    /**
     * Gathers every line-of-sight pair on the given board into a set of
     * cell indices. Does not mutate the board.
     */
    public static Set<Integer> collectMatches(Tile[] board) {
        Set<Integer> toRemove = new HashSet<>();

        for (int r = 0; r < GRID_SIZE; r++) {
            int lastTileIndex = -1;
            for (int c = 0; c < GRID_SIZE; c++) {
                int index = r * GRID_SIZE + c;
                if (board[index] != null) {
                    if (lastTileIndex != -1
                            && board[index].symbol.equals(board[lastTileIndex].symbol)) {
                        toRemove.add(index);
                        toRemove.add(lastTileIndex);
                    }
                    lastTileIndex = index;
                }
            }
        }
        for (int c = 0; c < GRID_SIZE; c++) {
            int lastTileIndex = -1;
            for (int r = 0; r < GRID_SIZE; r++) {
                int index = r * GRID_SIZE + c;
                if (board[index] != null) {
                    if (lastTileIndex != -1
                            && board[index].symbol.equals(board[lastTileIndex].symbol)) {
                        toRemove.add(index);
                        toRemove.add(lastTileIndex);
                    }
                    lastTileIndex = index;
                }
            }
        }

        return toRemove;
    }

    /**
     * Strict legality check: after sliding {@code blockIndices} by
     * {@code distance} on {@code test}, is at least one of the moved
     * tiles (at its new position) part of a line-of-sight match?
     * Merely uncovering a match between untouched tiles does not count.
     */
    public static boolean movedBlockCreatesMatch(Tile[] test,
                                                 List<Integer> blockIndices,
                                                 int step, int distance) {
        Set<Integer> matches = collectMatches(test);
        for (int index : blockIndices) {
            if (matches.contains(index + step * distance)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Finds one tile whose block can slide some distance, in some direction,
     * to put a moved tile into a match — exactly the kind of move a drag is
     * allowed to make. Returns null when no such move exists anywhere on
     * the board.
     */
    public Hint findHint() {
        int[][] directions = {
                {1, 1}, {-1, 1},                 // step, horizontal=1
                {GRID_SIZE, 0}, {-GRID_SIZE, 0}  // step, horizontal=0
        };
        for (int index = 0; index < CELL_COUNT; index++) {
            if (cells[index] == null) {
                continue;
            }
            for (int[] direction : directions) {
                int step = direction[0];
                boolean horizontal = direction[1] == 1;
                SlideInfo info = getSlideInfo(cells, index, step, horizontal);
                for (int distance = 1; distance <= info.maxDistance; distance++) {
                    Tile[] test = deepCopy(cells);
                    applySlide(test, info.blockIndices, step, distance);
                    if (movedBlockCreatesMatch(test, info.blockIndices, step, distance)) {
                        return new Hint(new ArrayList<>(info.blockIndices), step, distance);
                    }
                }
            }
        }
        return null;
    }

    /**
     * Removes every line-of-sight pair on the board.
     *
     * <p>Never reshuffles on its own: when no legal move remains, the caller
     * (the UI) is responsible for telling the player and reshuffling.
     *
     * @return true when at least one pair was removed (caller should check
     *         again after a short delay for chain reactions)
     */
    public boolean checkMatches() {
        Set<Integer> toRemove = collectMatches(cells);

        boolean matched = !toRemove.isEmpty();
        for (int index : toRemove) {
            cells[index] = null;
        }
        return matched;
    }

    /**
     * Guarantees the board never has zero legal moves: while no slide can
     * put a moved tile into a match, the tiles are redistributed
     * (up to 500 attempts).
     */
    public void ensureSolvable() {
        int attempts = 0;
        while (findHint() == null && attempts < MAX_SOLVE_ATTEMPTS) {
            shufflePositionsInPlace();
            attempts++;
        }
    }

    /** Redistributes the current tiles across all 36 cells, then re-solves. */
    public void reshuffle() {
        shufflePositionsInPlace();
        ensureSolvable();
    }

    /**
     * Randomly redistributes the tiles currently on the board across all 36
     * cells. Tile values are kept; only their positions change.
     */
    private void shufflePositionsInPlace() {
        List<Tile> values = new ArrayList<>();
        for (Tile tile : cells) {
            if (tile != null) {
                values.add(tile);
            }
        }
        List<Integer> positions = allPositionsShuffled();
        for (int i = 0; i < CELL_COUNT; i++) {
            cells[i] = null;
        }
        for (int i = 0; i < values.size(); i++) {
            cells[positions.get(i)] = values.get(i);
        }
    }

    /** 0..35 in Fisher-Yates-shuffled order. */
    private List<Integer> allPositionsShuffled() {
        List<Integer> positions = new ArrayList<>(CELL_COUNT);
        for (int i = 0; i < CELL_COUNT; i++) {
            positions.add(i);
        }
        Collections.shuffle(positions, random);
        return positions;
    }

    private static Tile[] deepCopy(Tile[] source) {
        Tile[] copy = new Tile[CELL_COUNT];
        for (int i = 0; i < CELL_COUNT; i++) {
            copy[i] = source[i] == null ? null : source[i].copy();
        }
        return copy;
    }
}
