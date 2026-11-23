package com.deviceagent.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** The app's design system: palette, type scale and the few view builders every screen uses. */
final class UiKit {
    final Context context;
    final boolean dark;
    final int canvas, card, stroke, ink, muted, green, greenInk, greenSoft, danger, dangerSoft, amber;
    private final float density;

    UiKit(Context context) {
        this.context = context;
        dark = (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        density = context.getResources().getDisplayMetrics().density;
        canvas = Color.parseColor(dark ? "#0E1318" : "#F6F7F9");
        card = Color.parseColor(dark ? "#171F27" : "#FFFFFF");
        stroke = Color.parseColor(dark ? "#26323E" : "#E3E7EC");
        ink = Color.parseColor(dark ? "#F2F5F8" : "#111820");
        muted = Color.parseColor(dark ? "#94A3B3" : "#5A6572");
        green = Color.parseColor(dark ? "#66BB6A" : "#2E7D32");
        greenInk = Color.parseColor(dark ? "#0B1F0D" : "#FFFFFF");
        greenSoft = Color.parseColor(dark ? "#1A2E1D" : "#E8F5E9");
        danger = Color.parseColor(dark ? "#EF5350" : "#C62828");
        dangerSoft = Color.parseColor(dark ? "#3A1C1C" : "#FDECEA");
        amber = Color.parseColor(dark ? "#FFB74D" : "#E08A00");
    }

    int dp(float value) { return Math.round(value * density); }

    GradientDrawable shape(int color, float radius, int strokeColor) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color); bg.setCornerRadius(dp(radius));
        if (strokeColor != 0) bg.setStroke(dp(1), strokeColor);
        return bg;
    }

    Drawable ripple(Drawable content, float radius) {
        int tint = (green & 0x00FFFFFF) | 0x26000000;
        return new RippleDrawable(ColorStateList.valueOf(tint), content, shape(Color.WHITE, radius, 0));
    }

    LinearLayout column() {
        LinearLayout box = new LinearLayout(context); box.setOrientation(LinearLayout.VERTICAL); return box;
    }

    LinearLayout row() {
        LinearLayout box = new LinearLayout(context); box.setOrientation(LinearLayout.HORIZONTAL); box.setGravity(Gravity.CENTER_VERTICAL); return box;
    }

    /** A white (or dark slate) card with a hairline stroke, added to parent. */
    LinearLayout card(LinearLayout parent) {
        LinearLayout box = column();
        box.setPadding(dp(18), dp(16), dp(18), dp(16));
        box.setBackground(shape(card, 20, stroke));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(12);
        if (parent != null) parent.addView(box, lp);
        return box;
    }

    /** A tappable row card; the click target fills the card. */
    LinearLayout rowCard(LinearLayout parent, Runnable onClick) {
        LinearLayout box = row();
        box.setPadding(dp(16), dp(14), dp(14), dp(14));
        box.setBackground(shape(card, 20, stroke));
        box.setForeground(ripple(null, 20));
        box.setClickable(true); box.setFocusable(true); box.setMinimumHeight(dp(64));
        box.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(10);
        parent.addView(box, lp);
        return box;
    }

    TextView text(LinearLayout parent, CharSequence value, float size, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setLineSpacing(dp(2), 1);
        if (bold) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        if (parent != null) parent.addView(view, new LinearLayout.LayoutParams(-1, -2));
        return view;
    }

    TextView title(LinearLayout parent, CharSequence value) { return text(parent, value, 17, ink, true); }
    TextView body(LinearLayout parent, CharSequence value) {
        TextView v = text(parent, value, 14, muted, false); v.setPadding(0, dp(4), 0, 0); return v;
    }

    /** Small uppercase-free section label above a group of cards. */
    TextView section(LinearLayout parent, CharSequence value) {
        TextView v = text(parent, value, 13, muted, true);
        v.setLetterSpacing(0.04f); v.setPadding(dp(4), dp(22), 0, dp(2));
        v.setAccessibilityHeading(true);
        return v;
    }

    void space(LinearLayout parent, int height) {
        parent.addView(new View(context), new LinearLayout.LayoutParams(1, dp(height)));
    }

    View divider(LinearLayout parent) {
        View line = new View(context); line.setBackgroundColor(stroke);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, dp(0.75f)));
        lp.topMargin = dp(12); lp.bottomMargin = dp(4);
        parent.addView(line, lp); return line;
    }

    static final int PRIMARY = 0, SECONDARY = 1, QUIET = 2, DANGER = 3;

    Button buttonView(CharSequence label, int kind) {
        Button b = new Button(context);
        b.setText(label); b.setAllCaps(false); b.setTextSize(15);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setStateListAnimator(null); b.setElevation(0);
        b.setMinHeight(dp(48)); b.setMinimumHeight(dp(48)); b.setMinWidth(0); b.setMinimumWidth(0);
        b.setPadding(dp(18), 0, dp(18), 0);
        int fill, text, line;
        switch (kind) {
            case PRIMARY: fill = green; text = greenInk; line = 0; break;
            case DANGER: fill = card; text = danger; line = stroke; break;
            case QUIET: fill = Color.TRANSPARENT; text = green; line = 0; break;
            default: fill = card; text = ink; line = stroke;
        }
        b.setTextColor(text);
        b.setBackground(ripple(shape(fill, 14, line), 14));
        return b;
    }

    /** Full-width button under a card's content. */
    Button button(LinearLayout parent, CharSequence label, int kind, Runnable action) {
        Button b = buttonView(label, kind);
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(10);
        parent.addView(b, lp);
        return b;
    }

    /** A row of equal-width buttons. */
    LinearLayout buttonRow(LinearLayout parent) {
        LinearLayout r = row();
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(12);
        parent.addView(r, lp); return r;
    }

    Button rowButton(LinearLayout row, CharSequence label, int kind, Runnable action) {
        Button b = buttonView(label, kind);
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        if (row.getChildCount() > 0) lp.setMarginStart(dp(8));
        row.addView(b, lp); return b;
    }

    /** A small rounded badge such as "Recording" or "This phone". */
    TextView pill(LinearLayout parent, CharSequence value, int textColor, int fill) {
        TextView v = new TextView(context);
        v.setText(value); v.setTextSize(12); v.setTextColor(textColor);
        v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        v.setPadding(dp(9), dp(3), dp(9), dp(3));
        v.setBackground(shape(fill, 99, 0));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.setMarginEnd(dp(6));
        if (parent != null) parent.addView(v, lp);
        return v;
    }

    /** A solid status dot. */
    View dot(LinearLayout parent, int color, int size) {
        View v = new View(context);
        GradientDrawable d = new GradientDrawable(); d.setShape(GradientDrawable.OVAL); d.setColor(color);
        v.setBackground(d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(size), dp(size)); lp.setMarginEnd(dp(10));
        if (parent != null) parent.addView(v, lp);
        return v;
    }

    void setDot(View dot, int color) {
        if (dot.getBackground() instanceof GradientDrawable) ((GradientDrawable) dot.getBackground()).setColor(color);
    }

    EditText field(LinearLayout parent, CharSequence label, CharSequence hint, String value) {
        TextView title = text(parent, label, 13, muted, true); title.setPadding(dp(2), dp(12), 0, dp(6));
        EditText input = new EditText(context);
        input.setId(View.generateViewId()); title.setLabelFor(input.getId());
        input.setText(value); input.setHint(hint); input.setTextSize(16); input.setSingleLine(true);
        input.setTextColor(ink); input.setHintTextColor(muted);
        input.setPadding(dp(14), dp(12), dp(14), dp(12)); input.setMinimumHeight(dp(52));
        input.setBackground(shape(canvas, 12, stroke));
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        parent.addView(input, new LinearLayout.LayoutParams(-1, -2));
        return input;
    }

    interface Change { void changed(String value); }
    static void watch(EditText field, Change change) {
        field.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) { change.changed(s.toString()); }
            public void afterTextChanged(Editable e) {}
        });
    }

    /** Padding for dialog bodies built from views. */
    LinearLayout form() {
        LinearLayout box = column(); box.setPadding(dp(24), dp(8), dp(24), dp(8)); return box;
    }

    /** Centered muted text for empty lists. */
    TextView empty(LinearLayout parent, CharSequence value) {
        LinearLayout box = card(parent);
        box.setGravity(Gravity.CENTER); box.setPadding(dp(18), dp(22), dp(18), dp(22));
        TextView v = text(box, value, 14, muted, false); v.setGravity(Gravity.CENTER);
        return v;
    }
}
