#include "trainer_ai.h"

#include <math.h>
#include <stddef.h>

#include "body_part.h"
#include "food.h"
#include "snake.h"

#define TRAINER_PI 3.14159265358979323846
#define TRAINER_CANDIDATES 13
#define TRAINER_LOOKAHEADS 3

static double trainer_dist2(double ax, double ay, double bx, double by) {
  double dx = ax - bx;
  double dy = ay - by;
  return dx * dx + dy * dy;
}

static double trainer_wrap_angle(double angle) {
  while (angle > TRAINER_PI) angle -= 2.0 * TRAINER_PI;
  while (angle < -TRAINER_PI) angle += 2.0 * TRAINER_PI;
  return angle;
}

static double trainer_clearance_penalty(double clearance) {
  if (clearance < 45.0) return 1800.0;
  if (clearance < 90.0) return 650.0;
  if (clearance < 160.0) return 180.0;
  if (clearance < 240.0) return 55.0;
  return 0.0;
}

/*
 * This is a second opinion, not a replacement for Wyrm's collision response.
 * The existing bot first chooses a target and handles immediate danger. The
 * trainer then compares nearby routes and nudges that target toward the
 * safest useful corridor. ring_steer() runs afterwards and remains the final
 * authority for arenas with a lethal inner ring.
 */
void trainer_ai_adjust_goal(game_data* gdata, int player_id,
                            double target_x, double target_y,
                            double* out_x, double* out_y) {
  if (!gdata || !out_x || !out_y) return;

  snake* me = NULL;
  int snake_count = tdarray_length(gdata->data.snakes);
  for (int i = 0; i < snake_count; ++i) {
    snake* candidate = gdata->data.snakes + i;
    if (candidate->id == player_id && candidate->local_player &&
        !candidate->dead) {
      me = candidate;
      break;
    }
  }
  if (!me) return;

  double dx = target_x - me->xx;
  double dy = target_y - me->yy;
  double base_angle = (dx * dx + dy * dy > 1.0)
                          ? atan2(dy, dx)
                          : me->ang;
  double arena_radius = gdata->data.real_flux_grd > 0.0f
                            ? gdata->data.real_flux_grd
                            : gdata->data.grd;
  double centre = gdata->data.grd;
  double own_width = 14.6 * fmax(1.0, me->sc);
  double best_score = -1.0e30;
  double best_angle = base_angle;

  static const double lookahead[TRAINER_LOOKAHEADS] = {
      190.0, 390.0, 640.0};

  for (int candidate = 0; candidate < TRAINER_CANDIDATES; ++candidate) {
    double offset = ((double)candidate - (TRAINER_CANDIDATES - 1) / 2.0) * 0.14;
    double angle = base_angle + offset;
    double ca = cos(angle);
    double sa = sin(angle);
    double score = -fabs(offset) * 34.0;

    /* Arena edge: penalise routes that run out of turning room. */
    for (int step = 0; step < TRAINER_LOOKAHEADS; ++step) {
      double distance = lookahead[step];
      double px = me->xx + ca * distance;
      double py = me->yy + sa * distance;
      double from_centre = sqrt(trainer_dist2(px, py, centre, centre));
      double edge_clearance = arena_radius - from_centre - own_width;
      score -= trainer_clearance_penalty(edge_clearance) * (step + 1) * 0.55;
    }

    /* Other snakes: heads and sampled body points along the predicted route. */
    for (int si = 0; si < snake_count; ++si) {
      snake* other = gdata->data.snakes + si;
      if (other == me || other->dead) continue;
      double other_width = 14.6 * fmax(1.0, other->sc);
      double head_x = other->xx + other->fx;
      double head_y = other->yy + other->fy;
      double head_speed = fmax(0.0, fmin(1.0, other->sp / 10.0));
      head_x += cos(other->ang) * 100.0 * head_speed;
      head_y += sin(other->ang) * 100.0 * head_speed;

      for (int step = 0; step < TRAINER_LOOKAHEADS; ++step) {
        double distance = lookahead[step];
        double px = me->xx + ca * distance;
        double py = me->yy + sa * distance;
        double clearance = sqrt(trainer_dist2(px, py, head_x, head_y)) -
                           own_width - other_width;
        score -= trainer_clearance_penalty(clearance) * (1.0 + 0.25 * step);
      }

      int body_count = tdarray_length(other->pts);
      for (int pi = 0; pi < body_count; pi += 4) {
        body_part* part = other->pts + pi;
        if (part->dying) continue;
        double bx = part->xx + part->fx;
        double by = part->yy + part->fy;
        double along = (bx - me->xx) * ca + (by - me->yy) * sa;
        if (along < 80.0 || along > 720.0) continue;
        double lateral = fabs(-(bx - me->xx) * sa + (by - me->yy) * ca);
        if (lateral > 230.0) continue;
        double clearance = sqrt(along * along + lateral * lateral) -
                           own_width - other_width;
        score -= trainer_clearance_penalty(clearance) * 0.85;
      }
    }

    /* Own body: reject a route that curls back across the player's tail. */
    int own_count = tdarray_length(me->pts);
    for (int pi = 0; pi < own_count; pi += 3) {
      body_part* part = me->pts + pi;
      if (part->dying) continue;
      double bx = part->xx + part->fx;
      double by = part->yy + part->fy;
      double along = (bx - me->xx) * ca + (by - me->yy) * sa;
      if (along < 120.0 || along > 680.0) continue;
      double lateral = fabs(-(bx - me->xx) * sa + (by - me->yy) * ca);
      if (lateral > 170.0) continue;
      score -= trainer_clearance_penalty(lateral - own_width) * 1.2;
    }

    /* Food is useful only when it lies ahead and close to this corridor. */
    int food_count = tdarray_length(gdata->data.foods);
    for (int fi = 0; fi < food_count; ++fi) {
      food* item = gdata->data.foods + fi;
      if (item->eaten) continue;
      double fx = item->xx - me->xx;
      double fy = item->yy - me->yy;
      double along = fx * ca + fy * sa;
      if (along < 90.0 || along > 900.0) continue;
      double lateral = fabs(-fx * sa + fy * ca);
      if (lateral > 170.0) continue;
      double proximity = 1.0 - lateral / 170.0;
      double distance_value = 1.0 - along / 1100.0;
      score += fmin(16.0, fmax(0.0, item->sz)) *
               proximity * distance_value * 1.8;
    }

    if (score > best_score) {
      best_score = score;
      best_angle = angle;
    }
  }

  /* Keep the bot's target length and only refine its heading. */
  double target_length = sqrt(dx * dx + dy * dy);
  if (target_length < 450.0) target_length = 650.0;
  if (target_length > 900.0) target_length = 900.0;
  *out_x = me->xx + cos(best_angle) * target_length;
  *out_y = me->yy + sin(best_angle) * target_length;
}
