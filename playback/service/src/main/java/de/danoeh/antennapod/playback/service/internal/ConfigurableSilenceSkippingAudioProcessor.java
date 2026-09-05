package de.danoeh.antennapod.playback.service.internal;

import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor;
import de.danoeh.antennapod.storage.preferences.UserPreferences;

import java.nio.ByteBuffer;

@OptIn(markerClass = UnstableApi.class)
public class ConfigurableSilenceSkippingAudioProcessor implements AudioProcessor {
    private static final String TAG = "ConfigurableSilence";
    private SilenceSkippingAudioProcessor delegate;
    private AudioFormat inputAudioFormat = AudioFormat.NOT_SET;
    private StreamMetadata streamMetadata = StreamMetadata.DEFAULT;
    private boolean enabled;
    private volatile int targetStrength = UserPreferences.getSkipSilenceStrength();
    private int currentStrength = -1;
    private long totalSkippedFrames;

    public ConfigurableSilenceSkippingAudioProcessor() {
    }

    public static long getMinimumSilenceDurationUs(int strength) {
        switch (strength) {
            case 1:
                return 300_000L;
            case 2:
                return 200_000L;
            case 3:
                return 150_000L;
            case 5:
                return 60_000L;
            case 4:
            default:
                return SilenceSkippingAudioProcessor.DEFAULT_MINIMUM_SILENCE_DURATION_US;
        }
    }

    public static float getSilenceRetentionRatio(int strength) {
        switch (strength) {
            case 1:
                return 0.60f;
            case 2:
                return 0.45f;
            case 3:
                return 0.30f;
            case 5:
                return 0.05f;
            case 4:
            default:
                return SilenceSkippingAudioProcessor.DEFAULT_SILENCE_RETENTION_RATIO;
        }
    }

    private void applyStrength(int strength) {
        currentStrength = strength;
        if (delegate != null) {
            totalSkippedFrames += delegate.getSkippedFrames();
        }
        delegate = new SilenceSkippingAudioProcessor(
                getMinimumSilenceDurationUs(strength),
                getSilenceRetentionRatio(strength),
                SilenceSkippingAudioProcessor.DEFAULT_MAX_SILENCE_TO_KEEP_DURATION_US,
                SilenceSkippingAudioProcessor.DEFAULT_MIN_VOLUME_TO_KEEP_PERCENTAGE,
                SilenceSkippingAudioProcessor.DEFAULT_SILENCE_THRESHOLD_LEVEL);
        delegate.setEnabled(enabled);
        if (inputAudioFormat != AudioFormat.NOT_SET) {
            try {
                delegate.configure(inputAudioFormat);
            } catch (UnhandledAudioFormatException e) {
                Log.e(TAG, "Failed to configure silence skipping audio processor", e);
            }
            delegate.flush(streamMetadata);
        }
    }

    public void setStrength(int strength) {
        targetStrength = strength;
    }

    public int getStrength() {
        return targetStrength;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (delegate != null) {
            delegate.setEnabled(enabled);
        }
    }

    @Override
    @NonNull
    public AudioFormat configure(@NonNull AudioFormat inputAudioFormat) throws UnhandledAudioFormatException {
        this.inputAudioFormat = inputAudioFormat;
        applyStrength(targetStrength);
        return delegate.configure(inputAudioFormat);
    }

    @Override
    public boolean isActive() {
        return enabled && delegate != null && delegate.isActive();
    }

    @Override
    public void queueInput(@NonNull ByteBuffer inputBuffer) {
        if (targetStrength != currentStrength) {
            applyStrength(targetStrength);
        }
        if (delegate != null) {
            delegate.queueInput(inputBuffer);
        }
    }

    @Override
    public void queueEndOfStream() {
        if (delegate != null) {
            delegate.queueEndOfStream();
        }
    }

    @Override
    @NonNull
    public ByteBuffer getOutput() {
        return delegate != null ? delegate.getOutput() : EMPTY_BUFFER;
    }

    @Override
    public boolean isEnded() {
        return delegate == null || delegate.isEnded();
    }

    @Override
    public void flush() {
        flush(StreamMetadata.DEFAULT);
    }

    @Override
    public void flush(@NonNull StreamMetadata streamMetadata) {
        this.streamMetadata = streamMetadata;
        totalSkippedFrames = 0;
        if (targetStrength != currentStrength) {
            applyStrength(targetStrength);
        } else if (delegate != null) {
            delegate.flush(streamMetadata);
        }
    }

    @Override
    public void reset() {
        if (delegate != null) {
            delegate.reset();
        }
        currentStrength = -1;
        totalSkippedFrames = 0;
        inputAudioFormat = AudioFormat.NOT_SET;
    }

    @Override
    public long getDurationAfterProcessorApplied(long durationUs) {
        return delegate != null ? delegate.getDurationAfterProcessorApplied(durationUs) : durationUs;
    }

    public long getSkippedFrames() {
        return totalSkippedFrames + (delegate != null ? delegate.getSkippedFrames() : 0);
    }
}
