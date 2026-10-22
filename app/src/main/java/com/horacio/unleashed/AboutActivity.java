package com.horacio.unleashed;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Basic app and device details, with technical diagnostics kept outside setup. */
public final class AboutActivity extends Activity {
  private static final int MUTED = 0xFF9AADB9, LINE = 0xFF2A3A46;

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  private TextView text(String value, int size, int color) {
    TextView view = new TextView(this);
    view.setText(value);
    view.setTextSize(size);
    view.setTextColor(color);
    return view;
  }

  private void medium(TextView view) {
    view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
  }

  private GradientDrawable shape(int color, int stroke, int radius) {
    GradientDrawable drawable = new GradientDrawable();
    drawable.setColor(color);
    drawable.setCornerRadius(dp(radius));
    if (stroke != 0) drawable.setStroke(dp(1), stroke);
    return drawable;
  }

  private LinearLayout column() {
    LinearLayout layout = new LinearLayout(this);
    layout.setOrientation(LinearLayout.VERTICAL);
    return layout;
  }

  private LinearLayout row() {
    LinearLayout layout = new LinearLayout(this);
    layout.setGravity(Gravity.CENTER_VERTICAL);
    return layout;
  }

  private void gap(LinearLayout parent, int height) {
    parent.addView(new android.view.View(this), new LinearLayout.LayoutParams(1, dp(height)));
  }

  private Button button(String label, Runnable clicked) {
    Button view = new Button(this);
    view.setText(label);
    view.setTextSize(14);
    view.setTextColor(MainActivity.INK);
    view.setAllCaps(false);
    medium(view);
    view.setMinWidth(0);
    view.setMinimumWidth(0);
    view.setMinHeight(dp(48));
    view.setMinimumHeight(dp(48));
    view.setPadding(dp(14), dp(10), dp(14), dp(10));
    view.setGravity(Gravity.CENTER);
    view.setBackground(
        new RippleDrawable(
            ColorStateList.valueOf(0x228BE3C5), shape(0, LINE, 14), shape(0xFFFFFFFF, 0, 14)));
    view.setOnClickListener(v -> clicked.run());
    return view;
  }

  private void detail(LinearLayout parent, String label, String value) {
    LinearLayout detail = row();
    detail.setPadding(0, dp(8), 0, dp(8));
    detail.addView(text(label, 13, MUTED), new LinearLayout.LayoutParams(0, -2, 1));
    TextView content = text(value, 14, MainActivity.INK);
    content.setGravity(Gravity.END);
    content.setTextIsSelectable(true);
    detail.addView(content, new LinearLayout.LayoutParams(0, -2, 2));
    parent.addView(detail);
  }

  @Override
  public void onCreate(Bundle saved) {
    super.onCreate(saved);
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.setBackground(
        new GradientDrawable(
            GradientDrawable.Orientation.TL_BR, new int[] {MainActivity.BG, 0xFF102127}));
    scroll.setOnApplyWindowInsetsListener(
        (view, insets) -> {
          android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
          view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
          return insets;
        });
    LinearLayout body = column();
    body.setPadding(dp(20), dp(16), dp(20), dp(24));
    scroll.addView(body);
    LinearLayout header = row();
    Button back = button("‹", this::finish);
    back.setTextSize(28);
    back.setPadding(0, 0, 0, 0);
    back.setContentDescription("Back");
    header.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
    TextView title = text("About", 24, MainActivity.INK);
    medium(title);
    title.setAccessibilityHeading(true);
    title.setPadding(dp(14), 0, 0, 0);
    header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    body.addView(header);
    gap(body, 22);

    LinearLayout brand = row();
    ImageView mark = new ImageView(this);
    mark.setImageResource(R.drawable.app_icon);
    mark.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    brand.addView(mark, new LinearLayout.LayoutParams(dp(40), dp(40)));
    TextView name = text("unleashed", 22, MainActivity.INK);
    medium(name);
    name.setPadding(dp(14), 0, 0, 0);
    brand.addView(name);
    body.addView(brand);
    gap(body, 18);
    detail(body, "Version", BuildConfig.VERSION_NAME);
    detail(body, "Build", BuildConfig.VERSION_CODE + " · " + BuildConfig.BUILD_TYPE);
    detail(body, "App ID", getPackageName());
    gap(body, 14);

    detail(body, "Device", Build.MANUFACTURER + " " + Build.MODEL);
    detail(body, "Android", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
    gap(body, 18);
    body.addView(
        button("Diagnostics", () -> startActivity(new Intent(this, DiagnosticsActivity.class))),
        new LinearLayout.LayoutParams(-1, -2));
    setContentView(scroll);
  }
}
