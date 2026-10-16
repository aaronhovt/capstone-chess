/*
 * Derived from BlackWidow-Chess by Amir Afghani (https://github.com/amir650/BlackWidow-Chess).
 * Copyright (c) 2014-2023 Amir Afghani
 * Modified by Aaron Ho, 2023-2026. Modifications copyright (c) 2023-2026 Aaron Ho
 *
 * This file is licensed under the GNU Lesser General Public License, version 2.1, not under the
 * MIT License that covers the rest of this project. See LICENSE-LGPL-2.1 and NOTICE.
 *
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package engine.forPlayer.forAI;

import engine.forBoard.Board;

/**
 * The BoardEvaluator interface defines the contract for evaluating chess board positions.
 * Implementations of this interface provide strategic assessment of board states by analyzing
 * piece placement, material balance, positional factors, and tactical opportunities to generate
 * numerical scores that guide AI decision-making.
 * <p>
 * Different evaluator implementations may focus on specific game phases (opening, middlegame, endgame)
 * or employ varying evaluation criteria and weights to assess position strength. The evaluation score
 * is typically positive for positions favoring white and negative for positions favoring black.
 *
 * @author Aaron Ho
 */
public interface BoardEvaluator {

  /**
   * Evaluates the given chess board position and returns a numerical score representing
   * the position's strength from white's perspective. Higher positive values indicate
   * positions favorable to white, while negative values indicate positions favorable to black.
   * The evaluation considers factors such as material balance, piece activity, king safety,
   * pawn structure, and positional advantages.
   *
   * @param board The current chess board position to evaluate.
   * @return A numerical evaluation score where positive values favor white and negative values favor black.
   */
  double evaluate(Board board);
}