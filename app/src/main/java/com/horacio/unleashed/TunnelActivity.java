package com.horacio.unleashed;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Focused tunnel setup; the live status does not claim to verify plugin installation. */
public class TunnelActivity extends Activity {
  private static final int MUTED = 0xFF9AADB9, LINE = 0xFF2A3A46, AMBER = 0xFFF0C784;
  private final Handler ui = new Handler(Looper.getMainLooper());
  private TunnelVault vault;
  private EditText tunnel, secret;
  private LinearLayout statusCard;
  private TextView statusMark, statusTitle, statusDetail, error;
  private Button connectionAction, saveAction;
  private boolean shownActive, shownConfigured;
  private ConnectionState.ChatGPT shownState;
  private final Runnable refresh =
      new Runnable() {
        @Override
        public void run() {
          refreshState();
          ui.postDelayed(this, 1000);
        }
      };

  private int dp(int n) {
    return Math.round(n * getResources().getDisplayMetrics().density);
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
    parent.addView(new View(this), new LinearLayout.LayoutParams(1, dp(height)));
  }

  private LinearLayout card(LinearLayout parent) {
    LinearLayout layout = column();
    layout.setPadding(dp(18), dp(18), dp(18), dp(18));
    layout.setBackground(shape(MainActivity.CARD, LINE, 24));
    parent.addView(layout, new LinearLayout.LayoutParams(-1, -2));
    return layout;
  }

  private Button button(String label, Runnable click) {
    Button view = new Button(this);
    view.setText(label);
    view.setTextSize(14);
    view.setAllCaps(false);
    medium(view);
    view.setMinWidth(0);
    view.setMinimumWidth(0);
    view.setMinHeight(dp(48));
    view.setMinimumHeight(dp(48));
    view.setGravity(Gravity.CENTER);
    view.setPadding(dp(14), dp(10), dp(14), dp(10));
    view.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
    styleButton(view, false);
    view.setOnClickListener(v -> click.run());
    return view;
  }

  private void styleButton(Button view, boolean primary) {
    view.setTextColor(primary ? MainActivity.BG : MainActivity.INK);
    view.setBackground(
        new RippleDrawable(
            ColorStateList.valueOf(primary ? 0x22F0C784 : 0x228BE3C5),
            shape(primary ? AMBER : 0, primary ? 0 : LINE, 14),
            shape(0xFFFFFFFF, 0, 14)));
  }

  private EditText field(LinearLayout parent, String label, String hint, boolean password) {
    TextView caption = text(label, 13, MUTED);
    parent.addView(caption);
    gap(parent, 6);
    EditText input = new EditText(this);
    input.setId(View.generateViewId());
    caption.setLabelFor(input.getId());
    input.setTextSize(14);
    input.setTextColor(MainActivity.INK);
    input.setHintTextColor(MUTED);
    input.setHint(hint);
    input.setSingleLine(true);
    input.setMinimumHeight(dp(52));
    input.setPadding(dp(12), dp(12), dp(12), dp(12));
    input.setBackground(shape(MainActivity.BG, LINE, 12));
    input.setInputType(
        InputType.TYPE_CLASS_TEXT
            | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            | (password ? InputType.TYPE_TEXT_VARIATION_PASSWORD : 0));
    input.setImeOptions(
        (password ? EditorInfo.IME_ACTION_DONE : EditorInfo.IME_ACTION_NEXT)
            | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
    input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
    input.setSaveEnabled(false);
    parent.addView(input, new LinearLayout.LayoutParams(-1, -2));
    return input;
  }

  @Override
  public void onCreate(Bundle saved) {
    super.onCreate(saved);
    vault = new TunnelVault(this);
    BackendController.init(this);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.setBackground(
        new GradientDrawable(
            GradientDrawable.Orientation.TL_BR, new int[] {MainActivity.BG, 0xFF102127}));
    scroll.setOnApplyWindowInsetsListener(
        (view, insets) -> {
          android.graphics.Insets bars =
              insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
          view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
          return insets;
        });
    LinearLayout body = column();
    body.setPadding(dp(20), dp(16), dp(20), dp(24));
    body.setFocusableInTouchMode(true);
    scroll.addView(body);
    LinearLayout header = row();
    Button back = button("‹", this::finish);
    back.setTextSize(28);
    back.setPadding(0, 0, 0, 0);
    back.setContentDescription("Back");
    header.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
    TextView title = text("Connect ChatGPT", 22, MainActivity.INK);
    medium(title);
    title.setAccessibilityHeading(true);
    title.setPadding(dp(14), 0, 0, 0);
    header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    body.addView(header);
    gap(body, 22);

    statusCard = card(body);
    LinearLayout statusRow = row();
    statusMark = text("", 20, AMBER);
    medium(statusMark);
    statusMark.setGravity(Gravity.CENTER);
    statusMark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    statusRow.addView(statusMark, new LinearLayout.LayoutParams(dp(40), dp(40)));
    statusTitle = text("", 20, MainActivity.INK);
    medium(statusTitle);
    statusTitle.setPadding(dp(12), 0, 0, 0);
    statusTitle.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    statusRow.addView(statusTitle, new LinearLayout.LayoutParams(0, -2, 1));
    statusCard.addView(statusRow);
    gap(statusCard, 12);
    statusDetail = text("", 14, MainActivity.INK);
    statusCard.addView(statusDetail);
    connectionAction = button("", this::connectionClicked);
    LinearLayout.LayoutParams connectionParams = new LinearLayout.LayoutParams(-1, -2);
    connectionParams.topMargin = dp(14);
    statusCard.addView(connectionAction, connectionParams);
    gap(body, 14);

    tunnel = field(body, "Tunnel ID", "tunnel_…", false);
    tunnel.setText(
        saved == null ? vault.tunnel() : saved.getString("tunnel_draft", vault.tunnel()));
    gap(body, 14);
    secret = field(body, "Secret key", "sk-…", true);
    saveAction = button("Connect", this::saveConnection);
    styleButton(saveAction, true);
    LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(-1, -2);
    saveParams.topMargin = dp(12);
    body.addView(saveAction, saveParams);
    error = text("", 13, AMBER);
    error.setPadding(0, dp(12), 0, 0);
    error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    error.setVisibility(View.GONE);
    body.addView(error);
    secret.setOnEditorActionListener(
        (view, action, event) -> {
          if (action != EditorInfo.IME_ACTION_DONE || !saveAction.isEnabled()) return false;
          saveConnection();
          return true;
        });
    body.requestFocus();
    setContentView(scroll);
    refreshState();
  }

  private void refreshState() {
    boolean configured = vault.configured();
    boolean active = TunnelService.active;
    ConnectionState.ChatGPT state =
        ConnectionState.chatGPT(
            configured,
            PhoneAccessPreferences.enabled(this),
            active,
            TunnelService.connectedToChatGPT,
            TunnelService.connectionRejected);
    if (state == shownState && active == shownActive && configured == shownConfigured) return;
    shownState = state;
    shownActive = active;
    shownConfigured = configured;
    boolean connected = state == ConnectionState.ChatGPT.CONNECTED;
    int color = connected ? MainActivity.MINT : AMBER;
    statusCard.setBackground(
        shape(connected ? 0xFF142B28 : 0xFF2A241A, connected ? 0xFF2B5046 : 0xFF80663A, 24));
    statusMark.setText(connected ? "✓" : "!");
    statusMark.setTextColor(color);
    statusMark.setBackground(shape((color & 0xFFFFFF) | 0x18000000, 0, 20));
    statusTitle.setTextColor(color);
    switch (state) {
      case CONNECTED:
        statusTitle.setText("Connected");
        statusDetail.setText("Your tunnel is online.");
        connectionAction.setText("Disconnect");
        break;
      case CONNECTING:
        statusTitle.setText("Connecting");
        statusDetail.setText("Connecting your tunnel to ChatGPT…");
        connectionAction.setText("Stop connecting");
        break;
      case NEEDS_ATTENTION:
        statusTitle.setText("Needs attention");
        statusDetail.setText("Check your tunnel ID, key and permissions.");
        connectionAction.setText("Disconnect");
        break;
      case PAUSED:
        statusTitle.setText("Disconnected");
        statusDetail.setText("Your saved connection is stopped.");
        connectionAction.setText("Disconnecting…");
        break;
      default:
        statusTitle.setText("Not connected");
        statusDetail.setText("Add your tunnel ID and secret key below.");
    }
    connectionAction.setVisibility(active ? View.VISIBLE : View.GONE);
    connectionAction.setEnabled(state != ConnectionState.ChatGPT.PAUSED);
    connectionAction.setAlpha(connectionAction.isEnabled() ? 1f : .55f);
    styleButton(connectionAction, false);
    tunnel.setEnabled(!active);
    secret.setEnabled(!active);
    secret.setHint(configured ? "Saved key · leave blank to keep" : "sk-…");
    saveAction.setVisibility(active ? View.GONE : View.VISIBLE);
    saveAction.setEnabled(!active);
  }

  private void connectionClicked() {
    try {
      TunnelService.stopAccess(this);
      refreshState();
    } catch (RuntimeException failure) {
      showError("Could not change the connection. Try again.");
    }
  }

  private void saveConnection() {
    if (TunnelService.active) return;
    try {
      vault.save(tunnel.getText().toString().strip(), secret.getText().toString().strip());
      secret.setText("");
      TunnelService.startAccess(this);
      error.setVisibility(View.GONE);
      shownState = null;
      refreshState();
      getSystemService(android.view.inputmethod.InputMethodManager.class)
          .hideSoftInputFromWindow(secret.getWindowToken(), 0);
    } catch (Exception failure) {
      showError(
          failure instanceof IllegalArgumentException
              ? failure.getMessage()
              : "Could not save and connect. Try again.");
    }
  }

  private void showError(String message) {
    error.setText(message);
    error.setVisibility(View.VISIBLE);
  }

  @Override
  protected void onSaveInstanceState(Bundle state) {
    state.putString("tunnel_draft", tunnel.getText().toString());
    super.onSaveInstanceState(state);
  }

  @Override
  protected void onResume() {
    super.onResume();
    ui.post(refresh);
  }

  @Override
  protected void onPause() {
    ui.removeCallbacks(refresh);
    super.onPause();
  }

  @Override
  protected void onDestroy() {
    ui.removeCallbacksAndMessages(null);
    super.onDestroy();
  }
}
