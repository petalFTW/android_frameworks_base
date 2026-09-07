package com.android.server.vibrator;

import static android.os.VibrationEffect.Composition.*;
import static org.junit.Assert.*;

import android.hardware.vibrator.IVibrator;
import android.os.VibrationEffect;
import android.os.VibratorInfo;
import android.os.vibrator.Flags;
import android.os.vibrator.PrebakedSegment;
import android.os.vibrator.PrimitiveSegment;
import android.os.vibrator.StepSegment;
import android.os.vibrator.VibrationEffectSegment;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.view.HapticFeedbackConstants;

import org.junit.Rule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class PetalHapticsEngineTest {
    @Rule public final SetFlagsRule mFlags = new SetFlagsRule();

    private final PetalHapticsEngine mEngine = new PetalHapticsEngine();
    private final VibratorInfo mMacanc = new VibratorInfo.Builder(0)
            .setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL)
            .setSupportedEffects(VibrationEffect.EFFECT_CLICK, VibrationEffect.EFFECT_TICK,
                    VibrationEffect.EFFECT_DOUBLE_CLICK, VibrationEffect.EFFECT_THUD,
                    VibrationEffect.EFFECT_POP, VibrationEffect.EFFECT_HEAVY_CLICK).build();

    @Test
    public void preservesNativeEffectsAndRepeatingAlertWaveforms() {
        List<VibrationEffectSegment> segments = new ArrayList<>(List.of(
                new PrebakedSegment(VibrationEffect.EFFECT_CLICK, true,
                        VibrationEffect.EFFECT_STRENGTH_MEDIUM),
                new StepSegment(0, 0, 250), new StepSegment(1, 0, 500)));
        List<VibrationEffectSegment> original = new ArrayList<>(segments);
        assertEquals(1, mEngine.adaptToVibrator(mMacanc, segments, 1));
        assertEquals(original, segments);
    }

    @Test
    public void preservesSupportedPrimitives() {
        VibratorInfo nativeInfo = new VibratorInfo.Builder(0)
                .setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS)
                .setSupportedPrimitive(PRIMITIVE_TICK, 8).build();
        List<VibrationEffectSegment> segments = new ArrayList<>(List.of(
                new PrimitiveSegment(PRIMITIVE_TICK, 0.3f, 20)));
        List<VibrationEffectSegment> original = new ArrayList<>(segments);
        assertEquals(0, mEngine.adaptToVibrator(nativeInfo, segments, 0));
        assertEquals(original, segments);
    }

    @Test
    public void adjustsRepeatIndexAndPreservesDelay() {
        List<VibrationEffectSegment> segments = new ArrayList<>(List.of(
                new PrimitiveSegment(PRIMITIVE_TICK, 0.5f, 20),
                new StepSegment(1, 0, 500)));
        int repeat = mEngine.adaptToVibrator(mMacanc, segments, 1);
        assertEquals(segments.size() - 1, repeat);
        assertEquals(new StepSegment(0, 0, 20), segments.get(0));
        assertEquals(new StepSegment(1, 0, 500), segments.get(repeat));
        assertEquals(535, duration(segments));
    }

    @Test
    public void repeatAtPrimitiveIncludesItsDelay() {
        List<VibrationEffectSegment> segments = new ArrayList<>(List.of(
                new StepSegment(1, 0, 500), new PrimitiveSegment(PRIMITIVE_TICK, 1, 25)));
        assertEquals(1, mEngine.adaptToVibrator(mMacanc, segments, 1));
        assertEquals(new StepSegment(0, 0, 25), segments.get(1));
    }

    @Test
    public void zeroScaleStaysSilentWithoutAmplitudeControl() {
        List<VibrationEffectSegment> segments = new ArrayList<>(List.of(
                new PrimitiveSegment(PRIMITIVE_THUD, 0, 10)));
        mEngine.adaptToVibrator(VibratorInfo.EMPTY_VIBRATOR_INFO, segments, -1);
        assertEquals(55, duration(segments));
        for (VibrationEffectSegment segment : segments) {
            assertEquals(0f, ((StepSegment) segment).getAmplitude(), 0f);
        }
    }

    @Test
    public void textureTickRespectsFallbackOptOut() {
        PrebakedSegment optional = new PrebakedSegment(VibrationEffect.EFFECT_TEXTURE_TICK,
                false, VibrationEffect.EFFECT_STRENGTH_MEDIUM);
        List<VibrationEffectSegment> segments = new ArrayList<>(List.of(optional));
        mEngine.adaptToVibrator(mMacanc, segments, -1);
        assertEquals(List.of(optional), segments);
    }

    @Test
    public void textureTickGetsDetailedFallback() {
        List<VibrationEffectSegment> segments = new ArrayList<>(List.of(
                new PrebakedSegment(VibrationEffect.EFFECT_TEXTURE_TICK, true,
                        VibrationEffect.EFFECT_STRENGTH_LIGHT)));
        mEngine.adaptToVibrator(mMacanc, segments, -1);
        assertEquals(15, duration(segments));
        for (VibrationEffectSegment segment : segments) {
            float amplitude = ((StepSegment) segment).getAmplitude();
            assertTrue(amplitude >= 0 && amplitude <= 0.25f);
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_PRIMITIVE_COMPOSITION_ABSOLUTE_DELAY)
    public void relativeDelayUsesFallbackDurationAndDropsOverlap() {
        List<VibrationEffectSegment> segments = new ArrayList<>(List.of(
                new PrimitiveSegment(PRIMITIVE_TICK, 1, 0),
                new PrimitiveSegment(PRIMITIVE_CLICK, 1, 10, DELAY_TYPE_RELATIVE_START_OFFSET),
                new PrimitiveSegment(PRIMITIVE_TICK, 1, 10, DELAY_TYPE_RELATIVE_START_OFFSET)));
        mEngine.adaptToVibrator(mMacanc, segments, -1);
        assertEquals(35, duration(segments));
        assertTrue(segments.contains(new StepSegment(0, 0, 5)));
    }

    @Test
    public void allPrimitiveFallbacksAreValid() {
        for (int id = PRIMITIVE_CLICK; id <= PRIMITIVE_LOW_TICK; id++) {
            List<VibrationEffectSegment> segments = new ArrayList<>(List.of(
                    new PrimitiveSegment(id, 0.7f, 12)));
            mEngine.adaptToVibrator(mMacanc, segments, -1);
            VibrationEffect.Composed effect = new VibrationEffect.Composed(segments, -1);
            effect.validate();
            assertTrue(effect.getDuration() > 12);
        }
    }

    @Test
    public void semanticEffectsAreDistinctAndMacancPlayable() {
        assertNotEquals(PetalHapticsEngine.feedback(HapticFeedbackConstants.TOGGLE_ON),
                PetalHapticsEngine.feedback(HapticFeedbackConstants.TOGGLE_OFF));
        for (int id : new int[] {HapticFeedbackConstants.TOGGLE_ON,
                HapticFeedbackConstants.TOGGLE_OFF, HapticFeedbackConstants.CONFIRM,
                HapticFeedbackConstants.REJECT, HapticFeedbackConstants.SCROLL_LIMIT}) {
            VibrationEffect.Composed effect =
                    (VibrationEffect.Composed) PetalHapticsEngine.feedback(id);
            List<VibrationEffectSegment> segments = new ArrayList<>(effect.getSegments());
            mEngine.adaptToVibrator(mMacanc, segments, -1);
            new VibrationEffect.Composed(segments, -1).validate();
            for (VibrationEffectSegment segment : segments) {
                assertTrue(segment instanceof StepSegment);
            }
        }
    }

    private static long duration(List<VibrationEffectSegment> segments) {
        return segments.stream().mapToLong(VibrationEffectSegment::getDuration).sum();
    }
}
