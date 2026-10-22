package com.horacio.unleashed;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.*;
import android.view.*;
import android.widget.*;

/** Two setup requirements, checked against live Accessibility and tunnel availability. */
public class MainActivity extends Activity {
  static final int BG = 0xFF0B141B, CARD = 0xFF16232D, INK = 0xFFF1F6F8, MINT = 0xFF8BE3C5;
  private static final int MUTED = 0xFF9AADB9, LINE = 0xFF2A3A46, AMBER = 0xFFF0C784;
  private final Handler ui = new Handler(Looper.getMainLooper());
  private LinearLayout backendCard, chatCard;
  private TextView setupProgress, accessibilityStep, connectionStep;
  private TextView backendDetail, backendBadge, backendHint, chatBadge, chatDetail;
  private Button backendAction, chatAction;
  private TunnelVault vault;
  private ConnectionState.Backend backendState;
  private ConnectionState.ChatGPT chatState;
  private int completedSteps = -1;
  private final Runnable tick =
      new Runnable() {
        public void run() {
          refresh();
          ui.postDelayed(this, 1000);
        }
      };

  int dp(int n) {
    return Math.round(n * getResources().getDisplayMetrics().density);
  }

  private TextView text(String s, int size, int color) {
    TextView v = new TextView(this);
    v.setText(s);
    v.setTextSize(size);
    v.setTextColor(color);
    return v;
  }

  private void medium(TextView v) {
    v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
  }

  private GradientDrawable shape(int color, int stroke, int radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(radius));
    if (stroke != 0) d.setStroke(dp(1), stroke);
    return d;
  }

  private Drawable ripple(int color, int stroke, int radius) {
    return new RippleDrawable(
        ColorStateList.valueOf(0x228BE3C5),
        shape(color, stroke, radius),
        shape(Color.WHITE, 0, radius));
  }

  private void gap(LinearLayout p, int h) {
    p.addView(new View(this), new LinearLayout.LayoutParams(1, dp(h)));
  }

  private LinearLayout column() {
    LinearLayout v = new LinearLayout(this);
    v.setOrientation(LinearLayout.VERTICAL);
    return v;
  }

  private LinearLayout row() {
    LinearLayout v = new LinearLayout(this);
    v.setGravity(Gravity.CENTER_VERTICAL);
    return v;
  }

  private LinearLayout card(LinearLayout p, int padding) {
    LinearLayout v = column();
    v.setPadding(dp(padding), dp(padding), dp(padding), dp(padding));
    v.setBackground(shape(CARD, LINE, 24));
    p.addView(v, new LinearLayout.LayoutParams(-1, -2));
    return v;
  }

  private TextView badge() {
    TextView v = text("", 11, MINT);
    medium(v);
    v.setGravity(Gravity.CENTER);
    v.setPadding(dp(10), dp(5), dp(10), dp(5));
    return v;
  }

  private void paintBadge(TextView v, String s, int color) {
    v.setText(s);
    v.setTextColor(color);
    v.setBackground(shape((color & 0xFFFFFF) | 0x18000000, 0, 20));
  }

  private Button action(LinearLayout p, Runnable clicked) {
    Button v = new Button(this);
    v.setAllCaps(false);
    v.setTextSize(14);
    medium(v);
    v.setMinWidth(0);
    v.setMinHeight(dp(48));
    v.setMinimumHeight(dp(48));
    v.setPadding(dp(16), dp(8), dp(16), dp(8));
    v.setOnClickListener(w -> clicked.run());
    p.addView(v, new LinearLayout.LayoutParams(-1, -2));
    return v;
  }

  private void paintAction(Button v, String s, boolean ready) {
    v.setText(s);
    v.setTextColor(ready ? INK : BG);
    v.setBackground(
        new RippleDrawable(
            ColorStateList.valueOf(ready ? 0x228BE3C5 : 0x22F0C784),
            shape(ready ? 0 : AMBER, ready ? LINE : 0, 14),
            shape(Color.WHITE, 0, 14)));
  }

  private TextView stepHeading(LinearLayout card, String title, TextView status) {
    LinearLayout heading = row();
    TextView step = text("", 15, AMBER);
    medium(step);
    step.setGravity(Gravity.CENTER);
    step.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    heading.addView(step, new LinearLayout.LayoutParams(dp(30), dp(30)));
    TextView label = text(title, 18, INK);
    medium(label);
    label.setPadding(dp(10), 0, dp(8), 0);
    heading.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
    heading.addView(status);
    card.addView(heading);
    return step;
  }

  private void paintStep(LinearLayout card, TextView step, boolean ready) {
    int color = ready ? MINT : AMBER;
    step.setText(ready ? "✓" : "!");
    step.setTextColor(color);
    step.setBackground(shape((color & 0xFFFFFF) | 0x18000000, 0, 15));
    card.setBackground(shape(ready ? 0xFF142B28 : 0xFF2A241A, ready ? 0xFF2B5046 : 0xFF80663A, 24));
  }

  @Override
  public void onCreate(Bundle saved) {
    super.onCreate(saved);
    BackendController.init(this);
    vault = new TunnelVault(this);
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.setClipToPadding(false);
    scroll.setBackground(
        new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] {BG, 0xFF102127}));
    scroll.setOnApplyWindowInsetsListener(
        (v, i) -> {
          Insets b = i.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
          v.setPadding(b.left, b.top, b.right, b.bottom);
          return i;
        });
    LinearLayout body = column();
    body.setPadding(dp(20), dp(22), dp(20), dp(24));
    scroll.addView(body);
    LinearLayout brand = row();
    ImageView mark = new ImageView(this);
    mark.setImageResource(R.drawable.app_icon);
    mark.setLayoutParams(new LinearLayout.LayoutParams(dp(32), dp(32)));
    mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    mark.setBackground(shape(getColor(R.color.icon_background), 0, 10));
    brand.addView(mark);
    TextView name = text("unleashed", 14, INK);
    medium(name);
    name.setPadding(dp(10), 0, 0, 0);
    brand.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
    Button more = new Button(this);
    more.setText("⋮");
    more.setTextSize(24);
    more.setTextColor(INK);
    more.setGravity(Gravity.CENTER);
    more.setPadding(0, 0, 0, 0);
    more.setContentDescription("More options");
    more.setBackground(ripple(0, 0, 12));
    more.setOnClickListener(
        v -> {
          PopupMenu menu = new PopupMenu(this, more, Gravity.END);
          menu.getMenu()
              .add("About")
              .setOnMenuItemClickListener(
                  item -> {
                    startActivity(new Intent(this, AboutActivity.class));
                    return true;
                  });
          menu.show();
        });
    brand.addView(more, new LinearLayout.LayoutParams(dp(48), dp(48)));
    body.addView(brand);
    gap(body, 24);
    LinearLayout setup = row();
    TextView title = text("Set up", 26, INK);
    medium(title);
    setup.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    setupProgress = badge();
    setupProgress.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    setup.addView(setupProgress);
    body.addView(setup);
    gap(body, 22);
    backendCard = card(body, 18);
    backendBadge = badge();
    accessibilityStep = stepHeading(backendCard, "Accessibility", backendBadge);
    gap(backendCard, 12);
    backendDetail = text("", 14, MUTED);
    backendCard.addView(backendDetail);
    gap(backendCard, 14);
    backendAction = action(backendCard, this::backendClicked);
    backendHint = text("", 12, MUTED);
    backendHint.setPadding(0, dp(10), 0, 0);
    backendCard.addView(backendHint);
    gap(body, 14);
    chatCard = card(body, 18);
    chatBadge = badge();
    connectionStep = stepHeading(chatCard, "ChatGPT connection", chatBadge);
    gap(chatCard, 12);
    chatDetail = text("", 13, MUTED);
    chatCard.addView(chatDetail);
    gap(chatCard, 14);
    chatAction = action(chatCard, this::chatClicked);
    gap(body, 24);
    nav(
        body,
        "Full setup guide",
        () ->
            startActivity(
                new Intent(
                    Intent.ACTION_VIEW,
                    android.net.Uri.parse(
                        "https://github.com/horacioMartinez/chatgpt-unleashed"))));
    setContentView(scroll);
    refresh();
  }

  private void nav(LinearLayout parent, String title, Runnable clicked) {
    LinearLayout link = row();
    link.setPadding(dp(4), dp(10), dp(4), dp(10));
    link.setMinimumHeight(dp(48));
    TextView label = text(title + " ↗", 16, INK);
    medium(label);
    link.addView(label, new LinearLayout.LayoutParams(-2, -2));
    link.setBackground(ripple(0, 0, 12));
    link.setFocusable(true);
    link.setContentDescription(title + ". Opens website.");
    link.setOnClickListener(view -> clicked.run());
    parent.addView(link, new LinearLayout.LayoutParams(-2, -2));
  }

  private void refresh() {
    if (setupProgress == null) return;
    ConnectionState.Backend next = BackendController.state();
    if (next != backendState) {
      backendState = next;
      String detail, button, hint, status;
      boolean ready = next == ConnectionState.Backend.RUNNING;
      switch (next) {
        case RUNNING:
          status = "Enabled";
          detail =
              "unleashed can read screens, take screenshots, tap and type. Screen content is sent"
                  + " to ChatGPT.";
          button = "Open Accessibility settings";
          hint = "You can turn off phone control in Android settings at any time.";
          break;
        case WAITING:
          status = "Connecting";
          detail = "Accessibility is enabled. Waiting for Android to connect the service…";
          button = "Open Accessibility settings";
          hint = "If this continues, turn unleashed off and on in Accessibility settings.";
          break;
        case PAUSED:
          status = "Paused";
          detail = "Accessibility is enabled, but phone control is paused.";
          button = "Resume phone control";
          hint = "Resume to let ChatGPT use the enabled accessibility service.";
          break;
        default:
          status = "Not enabled";
          detail =
              "Let unleashed read screens, take screenshots, tap and type. Screen content is sent"
                  + " to ChatGPT.";
          button = "Open Accessibility settings";
          hint =
              "Access restricted? Settings → Apps → unleashed → ⋮ → Allow restricted settings."
                  + " Then enable Accessibility.";
      }
      backendDetail.setText(detail);
      backendHint.setText(hint);
      backendHint.setVisibility(hint.isEmpty() ? View.GONE : View.VISIBLE);
      paintBadge(backendBadge, status, ready ? MINT : AMBER);
      paintAction(backendAction, button, ready);
      paintStep(backendCard, accessibilityStep, ready);
    }
    ConnectionState.ChatGPT nextChat =
        ConnectionState.chatGPT(
            vault.configured(),
            PhoneAccessPreferences.enabled(this),
            TunnelService.active,
            TunnelService.connectedToChatGPT,
            TunnelService.connectionRejected);
    if (nextChat != chatState) {
      chatState = nextChat;
      String status, detail, button;
      boolean ready = nextChat == ConnectionState.ChatGPT.CONNECTED;
      switch (nextChat) {
        case CONNECTED:
          status = "Connected";
          detail = "Your tunnel is connected and ready for ChatGPT requests.";
          button = "Connection settings";
          break;
        case CONNECTING:
          status = "Connecting";
          detail = "Connecting your tunnel to ChatGPT. This step turns green once connected.";
          button = "Connection settings";
          break;
        case NEEDS_ATTENTION:
          status = "Needs attention";
          detail = "The tunnel could not connect. Check your saved tunnel and key.";
          button = "Review connection settings";
          break;
        case PAUSED:
          status = "Stopped";
          detail = "Your tunnel is saved, but the connection is stopped.";
          button = "Start connection";
          break;
        default:
          status = "Not connected";
          detail = "Link this phone to your ChatGPT account using a private tunnel.";
          button = "Connect ChatGPT";
      }
      paintBadge(chatBadge, status, ready ? MINT : AMBER);
      chatDetail.setText(detail);
      paintAction(chatAction, button, ready);
      paintStep(chatCard, connectionStep, ready);
    }
    int completed = ConnectionState.completedSetupSteps(next, nextChat);
    if (completed != completedSteps) {
      completedSteps = completed;
      paintBadge(setupProgress, completed + "/2", completed == 2 ? MINT : AMBER);
      setupProgress.setContentDescription(completed + " of 2 setup steps ready");
    }
  }

  private void backendClicked() {
    if (BackendController.state() == ConnectionState.Backend.PAUSED) {
      BackendController.start(this);
    } else {
      BackendController.openSettings(this);
    }
    refresh();
  }

  private void chatClicked() {
    try {
      if (chatState == ConnectionState.ChatGPT.PAUSED) TunnelService.startAccess(this);
      else openChatGPT();
    } catch (RuntimeException error) {
      Toast.makeText(this, "Could not connect. Check connection settings.", Toast.LENGTH_LONG)
          .show();
    }
    refresh();
  }

  private void openChatGPT() {
    startActivity(new Intent(this, TunnelActivity.class));
  }

  @Override
  protected void onResume() {
    super.onResume();
    ui.post(tick);
  }

  @Override
  protected void onPause() {
    ui.removeCallbacks(tick);
    super.onPause();
  }

  @Override
  public void onDestroy() {
    ui.removeCallbacksAndMessages(null);
    super.onDestroy();
  }
}
