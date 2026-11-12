package io.github.sumyabazar1.weather;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The weather as a short line under the Clean Your TV launcher's clock, through its game_day panel
 * (docs/modules.md, "Each team's next game on Home"): three turns, now, the next hours and tomorrow, for the place
 * chosen on the page. The owner turns it on by naming this package in the launcher's game_day key.
 *
 * Nothing runs in the background. The launcher queries while Home is on screen; a query older than
 * {@link Forecast#REFRESH_MS} starts one read of Open-Meteo, and its end notifies the launcher, which queries again.
 * The rows hold only what Home shows anyway (the place name and the weather), as the provider is exported.
 */
public final class TurnsProvider extends ContentProvider {
    static final String AUTHORITY = "io.github.sumyabazar1.weather.turns";
    static final Uri TURNS = Uri.parse("content://" + AUTHORITY + "/turns");
    private static final String[] COLUMNS = {"top_line", "word", "start_ms", "end_ms", "colour", "calendar", "turn_seconds"};
    /** Seconds each turn stays under the clock. */
    static final int TURN_SECONDS = 12;
    /** A row ends this long after the query; the launcher then asks again, which reads anew when due. */
    static final long SHOW_MS = 60 * 60_000L;
    /** A reading older than this is not shown at all. */
    static final long KEEP_MS = 3 * 60 * 60_000L;
    /** The launcher's game_day panel shows a row's word as written when it starts with this (launcher, MainActivity). */
    static final String AS_IS = "=";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final AtomicBoolean reading = new AtomicBoolean();

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        Context ctx = getContext();
        MatrixCursor out = new MatrixCursor(COLUMNS);
        if (ctx == null) return out;
        out.setNotificationUri(ctx.getContentResolver(), TURNS);
        SharedPreferences place = ctx.getSharedPreferences("place", Context.MODE_PRIVATE);
        if (!place.contains("name")) return out;
        SharedPreferences cache = ctx.getSharedPreferences("turns", Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        String key = placeKey(place);
        long readAt = key.equals(cache.getString("place", "")) ? cache.getLong("read_at", 0) : 0;
        if (now - readAt >= Forecast.REFRESH_MS && now - cache.getLong("tried_at", 0) >= Forecast.RETRY_MS) {
            read(ctx, place, key, cache);
        }
        if (readAt > 0 && now - readAt < KEEP_MS) {
            try {
                JSONArray rows = new JSONArray(cache.getString("rows", "[]"));
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject r = rows.getJSONObject(i);
                    out.addRow(new Object[]{r.getString("top"), AS_IS + r.getString("line"),
                            now - 60_000L, now + SHOW_MS, r.getInt("colour"), "weather", TURN_SECONDS});
                }
            } catch (JSONException broken) {
                cache.edit().remove("rows").remove("read_at").apply();
            }
        }
        return out;
    }

    private static String placeKey(SharedPreferences place) {
        // The leading version makes a new build read anew instead of showing lines in the old format.
        return "2|" + place.getString("name", "") + "|" + place.getLong("lat", 0) + "|" + place.getLong("lon", 0);
    }

    /** One read off the caller's thread; at most one at a time. */
    private void read(Context ctx, SharedPreferences place, String key, SharedPreferences cache) {
        if (!reading.compareAndSet(false, true)) return;
        cache.edit().putLong("tried_at", System.currentTimeMillis()).apply();
        String name = place.getString("name", "");
        double lat = Double.longBitsToDouble(place.getLong("lat", 0));
        double lon = Double.longBitsToDouble(place.getLong("lon", 0));
        boolean fahrenheit = ctx.getSharedPreferences("options", Context.MODE_PRIVATE).getBoolean("fahrenheit",
                "f".equalsIgnoreCase(ctx.getString(R.string.temperature_unit).trim()));
        io.execute(() -> {
            try {
                JSONArray rows = rows(ctx, name, lat, lon, fahrenheit);
                cache.edit().putString("rows", rows.toString()).putLong("read_at", System.currentTimeMillis())
                        .putString("place", key).apply();
                ctx.getContentResolver().notifyChange(TURNS, null);
            } catch (Exception offline) {
                // Tried again RETRY_MS later, on the launcher's next query.
            } finally {
                reading.set(false);
            }
        });
    }

    /** Now, the next three hours and tomorrow, as top line, line and bar colour. */
    static JSONArray rows(Context ctx, String name, double lat, double lon, boolean fahrenheit)
            throws IOException, JSONException {
        String where = Forecast.where(lat, lon);
        JSONObject reply = Forecast.now(where, 6);
        JSONObject chances;
        try {
            chances = Forecast.rainChances(where, 6);
        } catch (IOException | JSONException noChances) {
            chances = null; // the lines go without the ☔ part
        }
        JSONObject current = reply.getJSONObject("current");
        double temp = current.getDouble("temperature_2m");
        double humidity = current.optDouble("relative_humidity_2m", Double.NaN);
        int code = Forecast.snowIfCold(current.getInt("weather_code"), temp, Double.isNaN(humidity) ? 100 : humidity);
        boolean day = current.optInt("is_day", 1) == 1;
        String nowTime = current.optString("time");
        JSONArray rows = new JSONArray();
        StringBuilder now = new StringBuilder(Forecast.emoji(code, day)).append("  ")
                .append(Forecast.degrees(temp, fahrenheit)).append("° · ").append(ctx.getString(Forecast.sky(code, day)));
        if (!Double.isNaN(humidity)) now.append("   ").append(HUMIDITY).append(' ').append(Math.round(humidity)).append('%');
        int rainNow = hourChance(chances, nowTime);
        if (rainNow >= 0) now.append("   ").append(RAIN).append(' ').append(rainNow).append('%');
        rows.put(row(ctx.getString(R.string.turn_now, name), now.toString(), Forecast.barColour(code, day)));

        JSONObject hourly = reply.optJSONObject("hourly");
        if (hourly != null) {
            JSONArray times = hourly.getJSONArray("time");
            JSONArray temps = hourly.getJSONArray("temperature_2m");
            JSONArray codes = hourly.getJSONArray("weather_code");
            JSONArray days = hourly.optJSONArray("is_day");
            StringBuilder line = new StringBuilder();
            int shown = 0;
            int firstCode = code;
            boolean firstDay = day;
            for (int i = 0; i < times.length() && shown < 3; i++) {
                String time = times.getString(i);
                if (time.compareTo(nowTime) <= 0 || temps.isNull(i) || codes.isNull(i) || time.length() < 16) continue;
                double t = temps.getDouble(i);
                int c = Forecast.snowIfCold(codes.getInt(i), t, 100);
                boolean d = days == null || days.optInt(i, 1) == 1;
                if (shown == 0) {
                    firstCode = c;
                    firstDay = d;
                }
                if (shown > 0) line.append("   ");
                line.append(time, 11, 16).append(' ').append(Forecast.emoji(c, d)).append(' ')
                        .append(Forecast.degrees(t, fahrenheit)).append('°');
                int rain = hourChance(chances, time);
                if (rain >= 0) line.append(' ').append(RAIN).append(rain).append('%');
                shown++;
            }
            if (shown > 0) rows.put(row(ctx.getString(R.string.turn_hours), line.toString(), Forecast.barColour(firstCode, firstDay)));
        }

        try {
            List<Forecast.Day> next = Forecast.daysAt(where, Forecast.MIN_DAYS);
            if (!next.isEmpty()) {
                Forecast.Day d = next.get(0);
                String tomorrow = Forecast.emoji(d.code, true) + "  " + ctx.getString(Forecast.sky(d.code, true)) + " · "
                        + ctx.getString(R.string.high_low, Forecast.degrees(d.maxC, fahrenheit),
                        Forecast.degrees(d.minC, fahrenheit));
                int rain = dayChance(chances, 1);
                if (rain >= 0) tomorrow += "   " + RAIN + " " + rain + "%";
                rows.put(row(ctx.getString(R.string.turn_tomorrow), tomorrow, Forecast.barColour(d.code, true)));
            }
        } catch (IOException | JSONException noDays) {
            // Now and the next hours are still worth showing.
        }
        return rows;
    }

    /** 💧 relative humidity, ☔ chance of rain. */
    static final String HUMIDITY = "💧";
    static final String RAIN = "☔";

    /** The chance of rain in the hour that contains this local time ("2026-11-12T17:15"), or -1. */
    static int hourChance(JSONObject chances, String time) {
        JSONObject hourly = chances == null ? null : chances.optJSONObject("hourly");
        if (hourly == null || time == null || time.length() < 13) return -1;
        JSONArray times = hourly.optJSONArray("time");
        JSONArray values = hourly.optJSONArray("precipitation_probability");
        if (times == null || values == null) return -1;
        String hour = time.substring(0, 13);
        for (int i = 0; i < times.length() && i < values.length(); i++) {
            if (times.optString(i).startsWith(hour)) return values.isNull(i) ? -1 : values.optInt(i, -1);
        }
        return -1;
    }

    /** The day's highest chance of rain (0 today, 1 tomorrow), or -1. */
    static int dayChance(JSONObject chances, int index) {
        JSONObject daily = chances == null ? null : chances.optJSONObject("daily");
        JSONArray values = daily == null ? null : daily.optJSONArray("precipitation_probability_max");
        if (values == null || index >= values.length() || values.isNull(index)) return -1;
        return values.optInt(index, -1);
    }

    private static JSONObject row(String top, String line, int colour) throws JSONException {
        return new JSONObject().put("top", top).put("line", line).put("colour", colour);
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only");
    }
}
