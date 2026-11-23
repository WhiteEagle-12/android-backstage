package com.deviceagent.app;

import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * One bottom-bar tab. Its content is a list of keyed sections; each render passes a signature per
 * section, and only sections whose signature changed are rebuilt. Scroll position, focus and live
 * previews in untouched sections stay as they are.
 */
abstract class TabPage {
    final AgentActivity a;
    final UiKit k;
    final ScrollView scroll;
    final LinearLayout content;
    private final Map<String, LinearLayout> sections = new LinkedHashMap<>();
    private final Map<String, String> signatures = new HashMap<>();
    private final Set<String> used = new HashSet<>();
    private int cursor;

    TabPage(AgentActivity activity, UiKit kit, String title) {
        a = activity; k = kit;
        scroll = new ScrollView(a); scroll.setFillViewport(true); scroll.setBackgroundColor(k.canvas); scroll.setClipToPadding(false);
        LinearLayout wrap = k.column(); wrap.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout page = k.column(); page.setPadding(k.dp(16), k.dp(8), k.dp(16), k.dp(24));
        int width = Math.min(a.getResources().getDisplayMetrics().widthPixels, k.dp(600));
        wrap.addView(page, new LinearLayout.LayoutParams(width, -2));
        scroll.addView(wrap);
        TextView heading = k.text(page, title, 28, k.ink, true);
        heading.setPadding(k.dp(4), k.dp(12), 0, k.dp(4)); heading.setAccessibilityHeading(true);
        content = k.column();
        page.addView(content, new LinearLayout.LayoutParams(-1, -2));
    }

    /** Called on every tick while visible, and after any state change. */
    abstract void render();
    /** The activity's 1.5 s tick while this tab is visible: render, then fetch what the tab shows. */
    void tick() { render(); }
    void onShow() {}
    void onHide() {}

    final void begin() { cursor = 0; used.clear(); }

    final LinearLayout put(String key, String signature, Consumer<LinearLayout> build) {
        LinearLayout box = sections.get(key);
        if (box == null) { box = k.column(); sections.put(key, box); signatures.remove(key); }
        int index = content.indexOfChild(box);
        if (index != cursor) {
            if (index >= 0) content.removeView(box);
            content.addView(box, cursor, new LinearLayout.LayoutParams(-1, -2));
        }
        cursor++; used.add(key);
        if (!signature.equals(signatures.get(key))) {
            box.removeAllViews(); build.accept(box); signatures.put(key, signature);
        }
        return box;
    }

    final void end() {
        for (Iterator<Map.Entry<String, LinearLayout>> it = sections.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, LinearLayout> e = it.next();
            if (used.contains(e.getKey())) continue;
            content.removeView(e.getValue()); signatures.remove(e.getKey()); it.remove();
        }
    }

    /** Forces every section to rebuild on the next render. */
    final void invalidate() { signatures.clear(); }

    final Runnable guard(Runnable r) { return () -> { if (!a.session.busy) r.run(); }; }
}
