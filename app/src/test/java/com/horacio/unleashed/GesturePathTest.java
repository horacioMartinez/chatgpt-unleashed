package com.horacio.unleashed;

import static org.junit.Assert.*;

import org.junit.Test;

public class GesturePathTest {
  @Test
  public void tapsRespectWindowEdgesIncludingTransparentTouchMargins() {
    assertTrue(GesturePath.intersects(100, 200, 300, 260, 100, 200, 100, 200));
    assertTrue(GesturePath.intersects(100, 200, 300, 260, 299, 259, 299, 259));
    assertFalse(GesturePath.intersects(100, 200, 300, 260, 300, 230, 300, 230));
    assertFalse(GesturePath.intersects(100, 200, 300, 260, 150, 260, 150, 260));
  }

  @Test
  public void swipeCanCrossWindowWithBothEndpointsOutside() {
    assertTrue(GesturePath.intersects(100, 200, 300, 260, 0, 230, 400, 230));
    assertTrue(GesturePath.intersects(100, 200, 300, 260, 150, 400, 150, 0));
  }

  @Test
  public void diagonalBoundingBoxOverlapDoesNotMeanPathOverlap() {
    assertFalse(GesturePath.intersects(100, 100, 200, 200, 0, 150, 150, 0));
    assertTrue(GesturePath.intersects(100, 100, 200, 200, 0, 0, 300, 300));
    assertTrue(GesturePath.intersects(100, 100, 200, 200, 300, 300, 0, 0));
  }

  @Test
  public void outsideAndEmptyRegionsDoNotHideBadge() {
    assertFalse(GesturePath.intersects(100, 200, 300, 260, 0, 400, 500, 400));
    assertFalse(GesturePath.intersects(100, 200, 100, 260, 100, 200, 100, 200));
  }
}
