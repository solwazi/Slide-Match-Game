# Slide Match Game

A slide-and-match puzzle game. Slide rows and columns of tiles to line up
matching pairs and clear the board.

## How to Play

**Goal:** Clear the board by eliminating pairs of matching tiles.

**Sliding tiles:** Drag any row or column. The tiles in that line slide
together as a block in the direction you drag — release to commit the move.
A longer drag moves the block further.

**Matching:** Two tiles with the same symbol form a match when they can
"see" each other: same row or column with no tiles between them. After
every slide, matches clear automatically, one pair at a time with a short
pause (about 1.5 seconds) between each.

**Legal moves:** A slide only counts if at least one of the tiles you moved
ends up part of a match. Just moving a blocker out of the way to expose an
untouched match doesn't count.

**Starting matches:** A new board sometimes already contains matches. When
it does, you'll be invited to tap two matching tiles to clear them yourself
before you start sliding — tap one tile to select it, then tap its match.
The Hint button will point at a waiting pair for you.

**Difficulty levels:** Pick Easy, Medium, or Hard before or during play.
Changing difficulty deals a fresh board immediately.

- **Easy** — 24 tiles; every symbol appears exactly twice.
- **Medium** — 30 tiles; symbols may repeat.
- **Hard** — 34 tiles; symbols may repeat.

On Medium and Hard, three or more of a kind can line up — pairs always
clear two at a time, so the board stays fully clearable.

**Hints:** The Hint button highlights a legal slide (or a waiting pair at
the start of a game). If no legal move exists, the board reshuffles instead.

**Stuck?** When no legal slide remains, you'll see
"No moves left — shuffling the board…" and the tiles reshuffle into a
solvable layout.

**Winning:** Clear every tile to win the round.

## Building (Android)

Open the `android/` folder in Android Studio and run the app on an
emulator or a physical device.

## Branches

- `convert-game-java-android` — the Android version (this branch).
- `convert-game-java-swing` — the Java Swing desktop version.
