package com.solwazi.slidemahjong;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

/**
 * Hosts the board: title, tile counter, difficulty buttons, BoardView,
 * and the two buttons.
 */
public class MainActivity extends Activity {

    private Board board;
    private BoardView boardView;
    private TextView tileCountView;
    private Button easyButton;
    private Button mediumButton;
    private Button hardButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        board = new Board();
        boardView = findViewById(R.id.board_view);
        tileCountView = findViewById(R.id.tile_count);

        boardView.setBoard(board);
        boardView.setBoardListener(new BoardView.BoardListener() {
            @Override
            public void onTileCountChanged(int remaining) {
                tileCountView.setText(getString(R.string.tiles_remaining, remaining));
            }

            @Override
            public void onBoardCleared() {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle(R.string.win_title)
                        .setMessage(R.string.win_message)
                        .setPositiveButton(R.string.win_ok, null)
                        .show();
            }
        });

        Button newBoardButton = findViewById(R.id.btn_new_board);
        newBoardButton.setOnClickListener(v -> boardView.newBoard());

        Button hintButton = findViewById(R.id.btn_hint);
        hintButton.setOnClickListener(v -> boardView.showHint());

        easyButton = findViewById(R.id.btn_easy);
        mediumButton = findViewById(R.id.btn_medium);
        hardButton = findViewById(R.id.btn_hard);
        easyButton.setOnClickListener(v -> setDifficulty(Board.Difficulty.EASY));
        mediumButton.setOnClickListener(v -> setDifficulty(Board.Difficulty.MEDIUM));
        hardButton.setOnClickListener(v -> setDifficulty(Board.Difficulty.HARD));
        updateDifficultyButtons(Board.Difficulty.EASY);

        boardView.newBoard();
    }

    private void setDifficulty(Board.Difficulty difficulty) {
        boardView.setDifficulty(difficulty);
        updateDifficultyButtons(difficulty);
    }

    private void updateDifficultyButtons(Board.Difficulty selected) {
        styleDifficultyButton(easyButton, selected == Board.Difficulty.EASY);
        styleDifficultyButton(mediumButton, selected == Board.Difficulty.MEDIUM);
        styleDifficultyButton(hardButton, selected == Board.Difficulty.HARD);
    }

    private void styleDifficultyButton(Button button, boolean selected) {
        button.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(
                        getColor(selected ? R.color.accent : R.color.button_inactive)));
    }
}
