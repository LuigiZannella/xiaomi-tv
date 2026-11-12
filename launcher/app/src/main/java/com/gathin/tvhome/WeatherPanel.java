package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The weather add-on's turns in the race panel's place under the clock, drawn from a "{...}" game_day word instead of
 * the one-line headline. Two shapes, both text only and rebuilt once per turn:
 *
 * - {"main": "⛈️  19° · Temporale", "side": [{"icon": "💧", "label": "Umidità", "value": "81%"}, ...]}: the line in the
 *   headline's place, a thin divider, and the side rows in a column in the top line's style.
 * - {"hours": [{"time": "19:00", "main": "⛈️ 20°", "icon": "☔", "label": "Pioggia", "value": "83%"}, ...]}: one block
 *   per hour (time, sky and degrees, chance of rain), with a divider between blocks.
 */
final class WeatherPanel {
    private static final String TAG = "weatherPanel";
    private static final int GREY = 0xB3FFFFFF;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int DIVIDER = 0x5AFFFFFF;

    private WeatherPanel() {}

    /** Shows the word (a JSON object) in place of the headline; false when it is not one, so the caller shows text. */
    static boolean show(Context ctx, View panel, TextView headline, String json) {
        if (!(panel instanceof LinearLayout) || !json.startsWith("{")) {
            hide(panel, headline);
            return false;
        }
        JSONObject data;
        try {
            data = new JSONObject(json);
        } catch (JSONException broken) {
            hide(panel, headline);
            return false;
        }
        LinearLayout box = box((LinearLayout) panel, headline);
        box.removeAllViews();
        float density = ctx.getResources().getDisplayMetrics().density;
        Typeface light = UiFont.get(ctx, R.string.font_light);
        Typeface medium = UiFont.get(ctx, R.string.font_medium);
        JSONArray hours = data.optJSONArray("hours");
        if (hours != null) {
            for (int i = 0; i < hours.length(); i++) {
                JSONObject h = hours.optJSONObject(i);
                if (h == null) continue;
                if (box.getChildCount() > 0) box.addView(divider(ctx, density, 64));
                LinearLayout block = new LinearLayout(ctx);
                block.setOrientation(LinearLayout.VERTICAL);
                block.addView(text(ctx, h.optString("time"), 13, medium, WHITE, 0.08f, false));
                TextView main = text(ctx, h.optString("main"), 24, light, WHITE, -0.01f, false);
                main.setPadding(0, Math.round(4 * density), 0, 0);
                block.addView(main);
                LinearLayout rain = sideRow(ctx, h, density, medium);
                rain.setPadding(0, Math.round(6 * density), 0, 0);
                block.addView(rain);
                box.addView(block);
            }
        } else {
            TextView main = text(ctx, data.optString("main"), 24, light, WHITE, -0.01f, false);
            box.addView(main);
            JSONArray side = data.optJSONArray("side");
            if (side != null && side.length() > 0) {
                box.addView(divider(ctx, density, 30));
                LinearLayout column = new LinearLayout(ctx);
                column.setOrientation(LinearLayout.VERTICAL);
                for (int i = 0; i < side.length(); i++) {
                    JSONObject row = side.optJSONObject(i);
                    if (row == null) continue;
                    LinearLayout r = sideRow(ctx, row, density, medium);
                    if (column.getChildCount() > 0) r.setPadding(0, Math.round(3 * density), 0, 0);
                    column.addView(r);
                }
                box.addView(column);
            }
        }
        headline.setVisibility(View.GONE);
        box.setVisibility(View.VISIBLE);
        return true;
    }

    /** Back to the plain headline (F1, games, a text line). */
    static void hide(View panel, TextView headline) {
        headline.setVisibility(View.VISIBLE);
        if (!(panel instanceof LinearLayout)) return;
        View box = panel.findViewWithTag(TAG);
        if (box != null) box.setVisibility(View.GONE);
    }

    /** The box right under the headline, made once. */
    private static LinearLayout box(LinearLayout panel, TextView headline) {
        View found = panel.findViewWithTag(TAG);
        if (found instanceof LinearLayout) return (LinearLayout) found;
        LinearLayout box = new LinearLayout(panel.getContext());
        box.setTag(TAG);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(8 * panel.getResources().getDisplayMetrics().density);
        panel.addView(box, panel.indexOfChild(headline) + 1, lp);
        return box;
    }

    /** icon, LABEL in the top line's style, value in white: "💧 UMIDITÀ 81%". */
    private static LinearLayout sideRow(Context ctx, JSONObject row, float density, Typeface medium) {
        LinearLayout r = new LinearLayout(ctx);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextView icon = text(ctx, row.optString("icon"), 14, medium, WHITE, 0f, false);
        icon.setMinWidth(Math.round(20 * density));
        r.addView(icon);
        TextView label = text(ctx, row.optString("label"), 12, medium, GREY, 0.22f, true);
        label.setPadding(Math.round(4 * density), 0, Math.round(5 * density), 0);
        r.addView(label);
        r.addView(text(ctx, row.optString("value"), 13, medium, WHITE, 0.02f, false));
        return r;
    }

    private static View divider(Context ctx, float density, int heightDp) {
        View v = new View(ctx);
        v.setBackgroundColor(DIVIDER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Math.max(1, Math.round(density)),
                Math.round(heightDp * density));
        lp.leftMargin = Math.round(16 * density);
        lp.rightMargin = Math.round(16 * density);
        v.setLayoutParams(lp);
        return v;
    }

    private static TextView text(Context ctx, String s, int sp, Typeface face, int colour, float spacing, boolean caps) {
        TextView t = new TextView(ctx);
        // Upper case in the text itself: setSingleLine below replaces an all-caps transformation.
        t.setText(caps ? s.toUpperCase(java.util.Locale.getDefault()) : s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(face);
        t.setTextColor(colour);
        t.setLetterSpacing(spacing);
        t.setSingleLine(true);
        t.setIncludeFontPadding(false);
        return t;
    }
}
