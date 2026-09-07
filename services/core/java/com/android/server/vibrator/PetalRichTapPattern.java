package com.android.server.vibrator;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class PetalRichTapPattern {
    public static final class Sample {
        public final float amplitude;
        public final int durationMs;

        Sample(float amplitude, int durationMs) {
            this.amplitude = amplitude;
            this.durationMs = durationMs;
        }
    }
    private static final int STEP_MS = 5;

    public static List<Sample> parse(JSONObject pattern) throws JSONException {
        if (pattern.getJSONObject("Metadata").getInt("Version") != 1) {
            throw new JSONException("Expected HE version 1");
        }
        JSONArray events = pattern.getJSONArray("Pattern");
        if (events.length() < 1 || events.length() > 16) {
            throw new JSONException("Invalid event count");
        }
        List<Sample> result = new ArrayList<>();
        int end = 0;
        for (int i = 0; i < events.length(); i++) {
            JSONObject event = events.getJSONObject(i).getJSONObject("Event");
            if (!"continuous".equals(event.getString("Type"))) {
                throw new JSONException("Petal profiles require continuous events");
            }
            int start = event.getInt("RelativeTime");
            int duration = event.getInt("Duration");
            if (start < end || start > 50000 || duration < 1 || duration > 5000
                    || start + duration > 50000) {
                throw new JSONException("Invalid event timing");
            }
            JSONObject parameters = event.getJSONObject("Parameters");
            double intensity = bounded(parameters.getDouble("Intensity"), 0, 100) / 100;
            bounded(parameters.getDouble("Frequency"), 0, 100);
            JSONArray curve = parameters.getJSONArray("Curve");
            if (curve.length() < 2 || curve.length() > 16) {
                throw new JSONException("Invalid curve size");
            }
            int[] times = new int[curve.length()];
            double[] amplitudes = new double[curve.length()];
            for (int j = 0; j < curve.length(); j++) {
                JSONObject point = curve.getJSONObject(j);
                times[j] = point.getInt("Time");
                amplitudes[j] = bounded(point.getDouble("Intensity"), 0, 1);
                bounded(point.getDouble("Frequency"), -100, 100);
                if (times[j] < 0 || times[j] > duration
                        || (j > 0 && times[j] <= times[j - 1])) {
                    throw new JSONException("Invalid curve timing");
                }
            }
            int last = times.length - 1;
            if (times[0] != 0 || times[last] != duration
                    || amplitudes[0] != 0 || amplitudes[last] != 0) {
                throw new JSONException("Curve must start and end at rest");
            }
            if (start > end) {
                result.add(new Sample(0, start - end));
            }
            int point = 0;
            for (int time = 0; time < duration;) {
                int next = Math.min(duration, Math.min(time + STEP_MS, times[point + 1]));
                double midpoint = (time + next) / 2.0;
                double fraction = (midpoint - times[point])
                        / (times[point + 1] - times[point]);
                float amplitude = (float) (intensity * (amplitudes[point]
                        + fraction * (amplitudes[point + 1] - amplitudes[point])));
                result.add(new Sample(amplitude, next - time));
                time = next;
                if (time == times[point + 1] && point + 1 < last) {
                    point++;
                }
            }
            end = start + duration;
        }
        return List.copyOf(result);
    }

    private static double bounded(double value, double min, double max) throws JSONException {
        if (!Double.isFinite(value) || value < min || value > max) {
            throw new JSONException("Curve value out of range");
        }
        return value;
    }
}
