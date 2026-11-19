#ifndef TRAINER_AI_H
#define TRAINER_AI_H

#include "game_data.h"

/*
 * Wyrm's original local AI trainer.
 *
 * Refines the player's current bot target by scoring a small set of forward
 * paths against food, other snakes, the player's own body and the arena edge.
 * It is deliberately deterministic, offline and cheap enough to run per frame.
 */
void trainer_ai_adjust_goal(game_data* gdata, int player_id,
                            double target_x, double target_y,
                            double* out_x, double* out_y);

#endif
