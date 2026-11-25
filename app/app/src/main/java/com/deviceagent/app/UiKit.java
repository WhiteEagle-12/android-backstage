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
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** The app's design system: matches android-backstage.com (obsidian surfaces, crisp hairlines, #8FDC4E accent). */
final class UiKit {
    final Context context;
    final boolean dark;
    final int canvas, elevated, card, raised, stroke, strokeStrong, ink, muted, dim,
            green, greenInk, greenSoft, danger, dangerSoft, amber, amberSoft;
    private final float density;

    UiKit(Context context) {
        this.context = context;
        dark = true;
        density = context.getResources().getDisplayMetrics().density;
        canvas = Color.parseColor("#000000");
        elevated = Color.parseColor("#0A0A0A");
        card = Color.parseColor("#0D0D0D");
        raised = Color.parseColor("#141414");
        stroke = Color.parseColor("#1F1F1F");
        strokeStrong = Color.parseColor("#2E2E2E");
        ink = Color.parseColor("#F5F5F5");
        muted = Color.parseColor("#A3A3A3");
        dim = Color.parseColor("#737373");
        green = Color.parseColor("#8FDC4E");
        greenInk = Color.parseColor("#0C1A04");
        greenSoft = Color.parseColor("#1B2A10");
        danger = Color.parseColor("#F0565C");
        dangerSoft = Color.parseColor("#2A1315");
        amber = Color.parseColor("#F2B441");
        amberSoft = Color.parseColor("#2B210E");
    }

    int dp(float value) { return Math.round(value * density); }

    GradientDrawable shape(int color, float radius, int strokeColor) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color); bg.setCornerRadius(dp(radius));
        if (strokeColor != 0) bg.setStroke(Math.max(1, dp(1)), strokeColor);
        return bg;
    }

    Drawable ripple(Drawable content, float radius) {
        int tint = (green & 0x00FFFFFF) | 0x28000000;
        return new RippleDrawable(ColorStateList.valueOf(tint), content, shape(Color.WHITE, radius, 0));
    }

    LinearLayout column() {
        LinearLayout box = new LinearLayout(context); box.setOrientation(LinearLayout.VERTICAL); return box;
    }

    LinearLayout row() {
        LinearLayout box = new LinearLayout(context); box.setOrientation(LinearLayout.HORIZONTAL); box.setGravity(Gravity.CENTER_VERTICAL); return box;
    }

    /** An obsidian card with a crisp hairline stroke matching .tile on android-backstage.com. */
    LinearLayout card(LinearLayout parent) {
        LinearLayout box = column();
        box.setPadding(dp(16), dp(16), dp(16), dp(16));
        box.setBackground(shape(card, 14, stroke));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(12);
        if (parent != null) parent.addView(box, lp);
        return box;
    }

    /** A tappable row card; the click target fills the card. */
    LinearLayout rowCard(LinearLayout parent, Runnable onClick) {
        LinearLayout box = row();
        box.setPadding(dp(16), dp(14), dp(14), dp(14));
        box.setBackground(shape(card, 14, stroke));
        box.setForeground(ripple(null, 14));
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

    TextView title(LinearLayout parent, CharSequence value) {
        TextView v = text(parent, value, 16, ink, true);
        v.setLetterSpacing(-0.015f);
        return v;
    }
    TextView body(LinearLayout parent, CharSequence value) {
        TextView v = text(parent, value, 14, muted, false); v.setPadding(0, dp(4), 0, 0); return v;
    }

    /** Clean title-case section heading without all-caps eyebrow clutter. */
    TextView section(LinearLayout parent, CharSequence value) {
        TextView v = text(parent, value == null ? "" : value, 13.5f, muted, true);
        v.setPadding(dp(4), dp(20), 0, dp(4));
        v.setAccessibilityHeading(true);
        return v;
    }

    /** Crisp vector icon button with ripple feedback and centered 24-unit AppIcons drawable. */
    FrameLayout iconButton(
            int iconKind, int iconColor, int fillColor, int strokeColor,
            int sizeDp, int iconDp, float radiusDp, CharSequence contentDesc, Runnable action) {
        FrameLayout btn = new FrameLayout(context);
        btn.setMinimumWidth(dp(sizeDp)); btn.setMinimumHeight(dp(sizeDp));
        btn.setBackground(ripple(shape(fillColor, radiusDp, strokeColor), radiusDp));
        btn.setClickable(true); btn.setFocusable(true);
        if (contentDesc != null) btn.setContentDescription(contentDesc);
        AppIcons icon = new AppIcons(iconKind, iconColor);
        ImageView iv = new ImageView(context);
        iv.setImageDrawable(icon);
        iv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(dp(iconDp), dp(iconDp), Gravity.CENTER);
        btn.addView(iv, ip);
        btn.setTag(icon);
        if (action != null) btn.setOnClickListener(v -> action.run());
        return btn;
    }

    void styleIconButton(FrameLayout btn, int iconColor, int fillColor, int strokeColor, float radiusDp) {
        if (btn == null) return;
        btn.setBackground(ripple(shape(fillColor, radiusDp, strokeColor), radiusDp));
        if (btn.getTag() instanceof AppIcons) ((AppIcons) btn.getTag()).setColor(iconColor);
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

    static final int PRIMARY = 0, SECONDARY = 1, QUIET = 2, DANGER = 3, AMBER = 4;

    Button buttonView(CharSequence label, int kind) {
        Button b = new Button(context);
        b.setText(label); b.setAllCaps(false); b.setTextSize(14);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setStateListAnimator(null); b.setElevation(0);
        b.setMinHeight(dp(44)); b.setMinimumHeight(dp(44)); b.setMinWidth(0); b.setMinimumWidth(0);
        b.setPadding(dp(16), 0, dp(16), 0);
        int fill, text, line;
        switch (kind) {
            case PRIMARY: fill = green; text = greenInk; line = 0; break;
            case DANGER: fill = dangerSoft; text = danger; line = Color.parseColor("#4A1E21"); break;
            case AMBER: fill = amber; text = Color.parseColor("#1A1102"); line = 0; break;
            case QUIET: fill = Color.TRANSPARENT; text = green; line = 0; break;
            default: fill = raised; text = ink; line = strokeStrong;
        }
        b.setTextColor(text);
        b.setBackground(ripple(shape(fill, 10, line), 10));
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
