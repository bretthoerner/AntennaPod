package de.danoeh.antennapod.playback.service.internal;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.AudioProcessorChain;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.audio.SonicAudioProcessor;

@OptIn(markerClass = UnstableApi.class)
public class AntennaPodAudioProcessorChain implements AudioProcessorChain {
    private final AudioProcessor[] audioProcessors;
    private final ConfigurableSilenceSkippingAudioProcessor silenceSkippingAudioProcessor;
    private final SonicAudioProcessor sonicAudioProcessor;

    public AntennaPodAudioProcessorChain(
            ConfigurableSilenceSkippingAudioProcessor silenceSkippingAudioProcessor) {
        this.silenceSkippingAudioProcessor = silenceSkippingAudioProcessor;
        this.sonicAudioProcessor = new SonicAudioProcessor();
        this.audioProcessors = new AudioProcessor[] {
                silenceSkippingAudioProcessor,
                sonicAudioProcessor
        };
    }

    @Override
    @NonNull
    public AudioProcessor[] getAudioProcessors() {
        return audioProcessors;
    }

    @Override
    @NonNull
    public PlaybackParameters applyPlaybackParameters(@NonNull PlaybackParameters playbackParameters) {
        sonicAudioProcessor.setSpeed(playbackParameters.speed);
        sonicAudioProcessor.setPitch(playbackParameters.pitch);
        return playbackParameters;
    }

    @Override
    public boolean applySkipSilenceEnabled(boolean skipSilenceEnabled) {
        silenceSkippingAudioProcessor.setEnabled(skipSilenceEnabled);
        return skipSilenceEnabled;
    }

    @Override
    public long getMediaDuration(long playoutDuration) {
        return sonicAudioProcessor.isActive()
                ? sonicAudioProcessor.getMediaDuration(playoutDuration)
                : playoutDuration;
    }

    @Override
    public long getSkippedOutputFrameCount() {
        return silenceSkippingAudioProcessor.getSkippedFrames();
    }

    public ConfigurableSilenceSkippingAudioProcessor getSilenceSkippingAudioProcessor() {
        return silenceSkippingAudioProcessor;
    }
}
