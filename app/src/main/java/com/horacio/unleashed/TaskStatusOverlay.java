package com.horacio.unleashed;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Main-thread-only compact task badge with a 48 dp Cancel touch target. */
final class TaskStatusOverlay {
  private final Context context;
  private WindowManager windows;
  private Context windowOwner;
  private final Runnable stopTask;
  private View badge;
  private TextView message, stop;
  private View statusDot;
  private PhoneTaskState.Status displayed = PhoneTaskState.Status.IDLE;

  TaskStatusOverlay(Context context, Runnable stopTask) {
    this.context = context;
    this.stopTask = stopTask;
  }

  static boolean enabled(Context context) {
    return context
        .getSharedPreferences("task-indicator", Context.MODE_PRIVATE)
        .getBoolean("enabled", true);
  }

  static void setEnabled(Context context, boolean enabled) {
    context
        .getSharedPreferences("task-indicator", Context.MODE_PRIVATE)
        .edit()
        .putBoolean("enabled", enabled)
        .apply();
    TunnelService.taskStateChanged();
  }

  static String label(Context context, PhoneTaskState.Status status) {
    return context.getString(
        status == PhoneTaskState.Status.WAITING_FOR_AUTHENTICATION
            ? R.string.chatgpt_waiting_for_authentication
            : R.string.chatgpt_working);
  }

  private int dp(int value) {
    return Math.round(value * context.getResources().getDisplayMetrics().density);
  }

  void render(PhoneTaskState.Status status) {
    Context owner = PhoneAccessibilityService.current();
    if (status == PhoneTaskState.Status.IDLE || !enabled(context) || owner == null) {
      close();
      return;
    }
    if (badge != null && (owner != windowOwner || !badge.isAttachedToWindow())) close();
    if (badge == null) {
      windowOwner = owner;
      windows = owner.getSystemService(WindowManager.class);
      LinearLayout view = new LinearLayout(owner);
      view.setOrientation(LinearLayout.HORIZONTAL);
      view.setGravity(Gravity.CENTER_VERTICAL);
      view.setPadding(dp(14), 0, 0, 0);
      statusDot = new View(owner);
      LinearLayout.LayoutParams dot = new LinearLayout.LayoutParams(dp(7), dp(7));
      dot.rightMargin = dp(8);
      view.addView(statusDot, dot);
      message = new TextView(owner);
      message.setTextSize(13);
      message.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
      message.setGravity(Gravity.CENTER);
      message.setMaxLines(1);
      message.setEllipsize(TextUtils.TruncateAt.END);
      int screenWidth = windows.getCurrentWindowMetrics().getBounds().width();
      message.setMaxWidth(Math.max(dp(80), screenWidth - dp(150)));
      message.setPadding(0, dp(9), 0, dp(9));
      view.addView(message, new LinearLayout.LayoutParams(-2, -2));
      View divider = new View(owner);
      divider.setBackgroundColor(Color.argb(60, 255, 255, 255));
      LinearLayout.LayoutParams line = new LinearLayout.LayoutParams(dp(1), dp(14));
      line.leftMargin = dp(12);
      line.rightMargin = dp(2);
      view.addView(divider, line);
      stop = new TextView(owner);
      stop.setText(R.string.cancel_current_phone_task);
      stop.setContentDescription(context.getString(R.string.cancel_current_phone_task_description));
      stop.setTextSize(13);
      stop.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
      stop.setGravity(Gravity.CENTER);
      stop.setMinHeight(dp(48));
      stop.setOnClickListener(v -> stopTask.run());
      view.addView(stop, new LinearLayout.LayoutParams(dp(64), -1));
      view.setMinimumHeight(dp(48));
      // A slim 36 dp visible pill sits inside a transparent 48 dp touch area.
      FrameLayout touchArea = new FrameLayout(owner);
      GradientDrawable fill = new GradientDrawable();
      fill.setColor(Color.rgb(24, 26, 30));
      fill.setCornerRadius(dp(18));
      touchArea.setBackground(new InsetDrawable(fill, 0, dp(6), 0, dp(6)));
      touchArea.setPadding(0, 0, 0, 0);
      touchArea.addView(view, new FrameLayout.LayoutParams(-2, dp(48)));
      View root = touchArea;
      // Keep the badge out of UI hierarchies used by the phone agent.
      root.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
      WindowManager.LayoutParams position =
          new WindowManager.LayoutParams(
              WindowManager.LayoutParams.WRAP_CONTENT,
              WindowManager.LayoutParams.WRAP_CONTENT,
              WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
              WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                  | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
              PixelFormat.TRANSLUCENT);
      position.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
      position.y = dp(8);
      position.setTitle("ChatGPT task status");
      // Keep the existing unobtrusive appearance. Only the small badge bounds consume touches.
      position.alpha = .75f;
      try {
        windows.addView(root, position);
        badge = root;
      } catch (RuntimeException unavailable) {
        // A revoked permission or disconnected service can invalidate the window token.
        close();
        return;
      }
    }
    if (displayed == status) return;
    GradientDrawable dot = new GradientDrawable();
    dot.setShape(GradientDrawable.OVAL);
    dot.setColor(
        status == PhoneTaskState.Status.WAITING_FOR_AUTHENTICATION
            ? Color.rgb(243, 184, 82)
            : Color.rgb(76, 211, 156));
    statusDot.setBackground(dot);
    message.setTextColor(Color.rgb(236, 238, 242));
    stop.setTextColor(Color.WHITE);
    message.setText(
        status == PhoneTaskState.Status.WAITING_FOR_AUTHENTICATION
            ? R.string.compact_bubble_waiting
            : R.string.compact_bubble_working);
    displayed = status;
  }

  boolean obstructsGesture(int x, int y, int endX, int endY) {
    if (badge == null || !badge.isAttachedToWindow() || badge.getVisibility() != View.VISIBLE)
      return false;
    // A newly attached window may not have completed layout yet. Do not risk hitting Cancel.
    if (!badge.isLaidOut() || badge.getWidth() == 0 || badge.getHeight() == 0) return true;
    int[] location = new int[2];
    badge.getLocationOnScreen(location);
    // Include the transparent margins: they belong to the touchable window too.
    return GesturePath.intersects(
        location[0],
        location[1],
        location[0] + badge.getWidth(),
        location[1] + badge.getHeight(),
        x,
        y,
        endX,
        endY);
  }

  void close() {
    if (badge != null) {
      try {
        windows.removeViewImmediate(badge);
      } catch (RuntimeException alreadyRemoved) {
        /* Window/permission may already be gone. */
      }
    }
    badge = null;
    message = null;
    stop = null;
    statusDot = null;
    windows = null;
    windowOwner = null;
    displayed = PhoneTaskState.Status.IDLE;
  }
}
