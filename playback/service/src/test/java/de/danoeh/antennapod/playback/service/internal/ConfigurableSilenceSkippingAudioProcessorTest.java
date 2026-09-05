package de.danoeh.antennapod.playback.service.internal;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor.AudioFormat;
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import de.danoeh.antennapod.storage.preferences.UserPreferences;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class ConfigurableSilenceSkippingAudioProcessorTest {
    private static final AudioFormat AUDIO_FORMAT =
            new AudioFormat(1000, 2, C.ENCODING_PCM_16BIT);

    private ConfigurableSilenceSkippingAudioProcessor processor;

    @Before
    public void setUp() {
        UserPreferences.init(RuntimeEnvironment.getApplication());
        processor = new ConfigurableSilenceSkippingAudioProcessor();
    }

    @Test
    public void testStrengthDurationsAndRatios() {
        assertEquals(300_000L, ConfigurableSilenceSkippingAudioProcessor.getMinimumSilenceDurationUs(1));
        assertEquals(0.60f, ConfigurableSilenceSkippingAudioProcessor.getSilenceRetentionRatio(1), 0.001f);

        assertEquals(200_000L, ConfigurableSilenceSkippingAudioProcessor.getMinimumSilenceDurationUs(2));
        assertEquals(0.45f, ConfigurableSilenceSkippingAudioProcessor.getSilenceRetentionRatio(2), 0.001f);

        assertEquals(150_000L, ConfigurableSilenceSkippingAudioProcessor.getMinimumSilenceDurationUs(3));
        assertEquals(0.30f, ConfigurableSilenceSkippingAudioProcessor.getSilenceRetentionRatio(3), 0.001f);

        assertEquals(SilenceSkippingAudioProcessor.DEFAULT_MINIMUM_SILENCE_DURATION_US,
                ConfigurableSilenceSkippingAudioProcessor.getMinimumSilenceDurationUs(4));
        assertEquals(SilenceSkippingAudioProcessor.DEFAULT_SILENCE_RETENTION_RATIO,
                ConfigurableSilenceSkippingAudioProcessor.getSilenceRetentionRatio(4), 0.001f);

        assertEquals(60_000L, ConfigurableSilenceSkippingAudioProcessor.getMinimumSilenceDurationUs(5));
        assertEquals(0.05f, ConfigurableSilenceSkippingAudioProcessor.getSilenceRetentionRatio(5), 0.001f);
    }

    @Test
    public void testActivationState() throws Exception {
        processor.configure(AUDIO_FORMAT);
        assertFalse(processor.isActive());

        processor.setEnabled(true);
        assertTrue(processor.isActive());

        processor.setEnabled(false);
        assertFalse(processor.isActive());
    }

    @Test
    public void testDynamicStrengthUpdateDuringPlayback() throws Exception {
        processor.configure(AUDIO_FORMAT);
        processor.setEnabled(true);
        processor.flush();

        ByteBuffer silenceBuffer = ByteBuffer.allocateDirect(4000).order(ByteOrder.nativeOrder());
        processor.queueInput(silenceBuffer);

        processor.setStrength(2);
        ByteBuffer silenceBuffer2 = ByteBuffer.allocateDirect(4000).order(ByteOrder.nativeOrder());
        processor.queueInput(silenceBuffer2);

        assertTrue(processor.isActive());
    }
}
