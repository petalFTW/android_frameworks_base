package com.android.server.vibrator;

import static android.os.VibrationEffect.Composition.*;

import android.hardware.vibrator.IVibrator;
import android.content.res.Resources;
import android.os.VibrationEffect;
import android.os.VibratorInfo;
import android.os.Vibrator;
import android.os.vibrator.Flags;
import android.os.vibrator.PrebakedSegment;
import android.os.vibrator.PrimitiveSegment;
import android.os.vibrator.StepSegment;
import android.os.vibrator.VibrationEffectSegment;
import android.view.HapticFeedbackConstants;
import android.util.Slog;
import android.util.SparseArray;

import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import java.util.ArrayList;
import java.util.List;

final class PetalHapticsEngine implements VibrationSegmentsAdapter {
    private static final int DEFAULT_TAP = 9;
    private static final SparseArray<List<StepSegment>> PATTERNS = loadPatterns();

    private static SparseArray<List<StepSegment>> loadPatterns() {
        SparseArray<List<StepSegment>> patterns = new SparseArray<>();
        try (InputStream input = Resources.getSystem().openRawResource(
                com.android.internal.R.raw.petal_richtap_patterns)) {
            byte[] bytes = input.readNBytes(262145);
            if (bytes.length > 262144) {
                throw new IllegalArgumentException("Haptic profile too large");
            }
            JSONObject definitions = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            for (int id = PRIMITIVE_CLICK; id <= DEFAULT_TAP; id++) {
                List<StepSegment> steps = new ArrayList<>();
                for (PetalRichTapPattern.Sample sample : PetalRichTapPattern.parse(
                        definitions.getJSONObject(Integer.toString(id)))) {
                    steps.add(new StepSegment(sample.amplitude, 0, sample.durationMs));
                }
                patterns.put(id, List.copyOf(steps));
            }
        } catch (Exception e) {
            patterns.clear();
            Slog.w("PetalHaptics", "Using built-in haptic fallback", e);
        }
        return patterns;
    }

    static VibrationEffect feedback(int effectId) {
        return switch (effectId) {
            case HapticFeedbackConstants.VIRTUAL_KEY,
                    HapticFeedbackConstants.CONTEXT_CLICK,
                    HapticFeedbackConstants.CALENDAR_DATE -> defaultTap();
            case HapticFeedbackConstants.TOGGLE_ON -> primitive(PRIMITIVE_TICK, 0.65f);
            case HapticFeedbackConstants.TOGGLE_OFF -> primitive(PRIMITIVE_LOW_TICK, 0.4f);
            case HapticFeedbackConstants.SEGMENT_FREQUENT_TICK,
                    HapticFeedbackConstants.TEXT_HANDLE_MOVE,
                    HapticFeedbackConstants.CLOCK_TICK -> primitive(PRIMITIVE_LOW_TICK, 0.25f);
            case HapticFeedbackConstants.SCROLL_TICK,
                    HapticFeedbackConstants.SEGMENT_TICK,
                    HapticFeedbackConstants.DRAG_CROSSING -> primitive(PRIMITIVE_TICK, 0.4f);
            case HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE ->
                    primitive(PRIMITIVE_TICK, 0.75f);
            case HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE ->
                    primitive(PRIMITIVE_LOW_TICK, 0.3f);
            case HapticFeedbackConstants.SCROLL_LIMIT -> primitive(PRIMITIVE_THUD, 0.6f);
            case HapticFeedbackConstants.CONFIRM,
                    HapticFeedbackConstants.BIOMETRIC_CONFIRM ->
                    VibrationEffect.startComposition()
                            .addPrimitive(PRIMITIVE_TICK, 0.45f)
                            .addPrimitive(PRIMITIVE_CLICK, 0.75f, 35).compose();
            case HapticFeedbackConstants.REJECT,
                    HapticFeedbackConstants.BIOMETRIC_REJECT ->
                    VibrationEffect.startComposition()
                            .addPrimitive(PRIMITIVE_THUD, 0.65f)
                            .addPrimitive(PRIMITIVE_THUD, 0.45f, 55).compose();
            default -> null;
        };
    }

    static VibrationEffect defaultTap() {
        return new VibrationEffect.Composed(new ArrayList<>(pattern(DEFAULT_TAP)), -1);
    }

    private static VibrationEffect primitive(int id, float scale) {
        return VibrationEffect.startComposition().addPrimitive(id, scale).compose();
    }

    @Override
    public int adaptToVibrator(VibratorInfo info, List<VibrationEffectSegment> segments,
            int repeatIndex) {
        for (int i = 0; i < segments.size(); i++) {
            if (segments.get(i) instanceof PrebakedSegment prebaked
                    && prebaked.getEffectId() == VibrationEffect.EFFECT_TEXTURE_TICK
                    && prebaked.shouldFallback()
                    && info.isEffectSupported(prebaked.getEffectId())
                            != Vibrator.VIBRATION_EFFECT_SUPPORT_YES) {
                float scale = switch (prebaked.getEffectStrength()) {
                    case VibrationEffect.EFFECT_STRENGTH_LIGHT -> 0.25f;
                    case VibrationEffect.EFFECT_STRENGTH_STRONG -> 0.65f;
                    default -> 0.4f;
                };
                segments.set(i, new PrimitiveSegment(PRIMITIVE_LOW_TICK, scale, 0));
            }
        }
        boolean needsFallback = false;
        for (VibrationEffectSegment segment : segments) {
            if (segment instanceof PrimitiveSegment primitive
                    && !info.isPrimitiveSupported(primitive.getPrimitiveId())) {
                needsFallback = true;
                break;
            }
        }
        if (!needsFallback) {
            return repeatIndex;
        }

        List<VibrationEffectSegment> result = new ArrayList<>();
        int newRepeatIndex = -1;
        long previousStartOffset = 0;
        for (int i = 0; i < segments.size(); i++) {
            if (i == repeatIndex) {
                newRepeatIndex = result.size();
                previousStartOffset = 0;
            }
            VibrationEffectSegment segment = segments.get(i);
            if (!(segment instanceof PrimitiveSegment primitive)) {
                result.add(segment);
                previousStartOffset = -Math.max(0, segment.getDuration(info));
                continue;
            }
            int id = primitive.getPrimitiveId();
            long pause = primitive.getDelay();
            if (primitive.getDelayType() == DELAY_TYPE_RELATIVE_START_OFFSET) {
                if (!Flags.primitiveCompositionAbsoluteDelay()) {
                    return repeatIndex;
                }
                pause += previousStartOffset;
            }
            if (pause < 0) {
                previousStartOffset = pause;
                continue;
            }
            if (info.isPrimitiveSupported(id)) {
                result.add(new PrimitiveSegment(id, primitive.getScale(), (int) pause));
                previousStartOffset = -info.getPrimitiveDuration(id);
                continue;
            }
            if (pause > 0) {
                result.add(new StepSegment(0, 0, (int) pause));
            }
            int duration = 0;
            for (StepSegment step : pattern(id)) {
                float amplitude = step.getAmplitude() * primitive.getScale();
                if (!info.hasCapability(IVibrator.CAP_AMPLITUDE_CONTROL) && amplitude > 0) {
                    amplitude = 1;
                }
                result.add(new StepSegment(amplitude, 0, (int) step.getDuration()));
                duration += step.getDuration();
            }
            previousStartOffset = -duration;
        }
        if (result.isEmpty() || newRepeatIndex == result.size()) {
            return repeatIndex;
        }
        segments.clear();
        segments.addAll(result);
        return newRepeatIndex;
    }

    private static List<StepSegment> pattern(int id) {
        List<StepSegment> pattern = PATTERNS.get(id);
        if (pattern != null) {
            return pattern;
        }
        int[] timings = timings(id);
        float[] amplitudes = amplitudes(id);
        List<StepSegment> fallback = new ArrayList<>();
        for (int i = 0; i < timings.length; i++) {
            fallback.add(new StepSegment(amplitudes[i], 0, timings[i]));
        }
        return fallback;
    }

    private static int[] timings(int primitive) {
        return switch (primitive) {
            case DEFAULT_TAP -> new int[] {3, 14, 5};
            case PRIMITIVE_NOOP -> new int[0];
            case PRIMITIVE_CLICK -> new int[] {5, 5, 5};
            case PRIMITIVE_THUD -> new int[] {10, 15, 20};
            case PRIMITIVE_SPIN -> new int[] {15, 20, 20, 15, 10};
            case PRIMITIVE_QUICK_RISE -> new int[] {5, 10, 15};
            case PRIMITIVE_SLOW_RISE -> new int[] {20, 30, 30, 30};
            case PRIMITIVE_QUICK_FALL -> new int[] {10, 10, 10};
            case PRIMITIVE_TICK, PRIMITIVE_LOW_TICK -> new int[] {5, 5, 5};
            default -> throw new IllegalArgumentException("Unknown primitive " + primitive);
        };
    }

    private static float[] amplitudes(int primitive) {
        return switch (primitive) {
            case DEFAULT_TAP -> new float[] {0.5f, 1f, 0.5f};
            case PRIMITIVE_NOOP -> new float[0];
            case PRIMITIVE_CLICK -> new float[] {0.7f, 1f, 0.25f};
            case PRIMITIVE_THUD -> new float[] {0.5f, 0.85f, 0.35f};
            case PRIMITIVE_SPIN -> new float[] {0.2f, 0.65f, 1f, 0.65f, 0.2f};
            case PRIMITIVE_QUICK_RISE -> new float[] {0.15f, 0.5f, 1f};
            case PRIMITIVE_SLOW_RISE -> new float[] {0.1f, 0.3f, 0.65f, 1f};
            case PRIMITIVE_QUICK_FALL -> new float[] {1f, 0.5f, 0.15f};
            case PRIMITIVE_TICK -> new float[] {0.6f, 0.8f, 0.15f};
            case PRIMITIVE_LOW_TICK -> new float[] {0.2f, 0.4f, 0.1f};
            default -> throw new IllegalArgumentException("Unknown primitive " + primitive);
        };
    }
}
