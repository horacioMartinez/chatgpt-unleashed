package com.horacio.unleashed;

/** Pixel-coordinate segment intersection with an exclusive-right/bottom window rectangle. */
final class GesturePath {
  static boolean intersects(
      int left, int top, int right, int bottom, int x, int y, int endX, int endY) {
    if (left >= right || top >= bottom) return false;
    double dx = (double) endX - x, dy = (double) endY - y;
    double[] direction = {-dx, dx, -dy, dy};
    double[] distance = {(double) x - left, right - 1.0 - x, (double) y - top, bottom - 1.0 - y};
    double enter = 0, exit = 1;
    for (int i = 0; i < direction.length; i++) {
      if (direction[i] == 0) {
        if (distance[i] < 0) return false;
      } else {
        double crossing = distance[i] / direction[i];
        if (direction[i] < 0) enter = Math.max(enter, crossing);
        else exit = Math.min(exit, crossing);
        if (enter > exit) return false;
      }
    }
    return true;
  }
}
