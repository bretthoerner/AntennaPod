package de.danoeh.antennapod.ui.share;

import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.databinding.AudioClipDialogBinding;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.ui.common.Converter;

public class AudioClipDialog extends BottomSheetDialogFragment {
    private static final String TAG = "AudioClipDialog";
    private static final String ARGUMENT_FEED_ITEM = "feedItem";

    private AudioClipDialogBinding viewBinding;
    private MediaPlayer mediaPlayer;
    private Handler previewHandler = new Handler(Looper.getMainLooper());
    private Runnable previewRunnable;
    private boolean isPreviewPlaying = false;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public AudioClipDialog() {
        // Empty constructor required for DialogFragment
    }

    public static AudioClipDialog newInstance(FeedItem item) {
        Bundle arguments = new Bundle();
        arguments.putSerializable(ARGUMENT_FEED_ITEM, item);
        AudioClipDialog dialog = new AudioClipDialog();
        dialog.setArguments(arguments);
        return dialog;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        if (getArguments() == null) {
            return null;
        }
        FeedItem item = (FeedItem) getArguments().getSerializable(ARGUMENT_FEED_ITEM);
        if (item == null || item.getMedia() == null) {
            return null;
        }

        viewBinding = AudioClipDialogBinding.inflate(inflater, container, false);
        viewBinding.txtvEpisodeTitle.setText(item.getTitle());

        FeedMedia media = item.getMedia();
        int durationMs = media.getDuration();
        if (durationMs <= 0 && media.getLocalFileUrl() != null) {
            try (MediaMetadataRetriever retriever = new MediaMetadataRetriever()) {
                retriever.setDataSource(media.getLocalFileUrl());
                String durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (durStr != null) {
                    durationMs = Integer.parseInt(durStr);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to retrieve duration via MediaMetadataRetriever", e);
            }
        }
        if (durationMs <= 0) {
            durationMs = 300000; // Fallback 5 minutes
        }

        int totalSec = Math.max(1, durationMs / 1000);
        int startSec = Math.max(0, media.getPosition() / 1000);
        if (startSec >= totalSec) {
            startSec = Math.max(0, totalSec - 30);
        }
        int endSec = Math.min(totalSec, startSec + 30);
        if (endSec <= startSec) {
            endSec = Math.min(totalSec, startSec + 1);
        }

        viewBinding.rangeSlider.setValueFrom(0f);
        viewBinding.rangeSlider.setValueTo((float) totalSec);
        viewBinding.rangeSlider.setValues((float) startSec, (float) endSec);
        viewBinding.rangeSlider.setStepSize(1f);
        viewBinding.rangeSlider.setLabelFormatter(value ->
                Converter.getDurationStringLong((int) (value * 1000)));

        updateTimeLabels(startSec * 1000L, endSec * 1000L);

        viewBinding.rangeSlider.addOnChangeListener((slider, value, fromUser) -> {
            List<Float> values = slider.getValues();
            long curStartMs = (long) (values.get(0) * 1000);
            long curEndMs = (long) (values.get(1) * 1000);
            updateTimeLabels(curStartMs, curEndMs);
            if (isPreviewPlaying) {
                stopPreview();
            }
        });

        viewBinding.btnPreview.setOnClickListener(v -> {
            if (isPreviewPlaying) {
                stopPreview();
            } else {
                List<Float> values = viewBinding.rangeSlider.getValues();
                long curStartMs = (long) (values.get(0) * 1000);
                long curEndMs = (long) (values.get(1) * 1000);
                startPreview(media.getLocalFileUrl(), curStartMs, curEndMs);
            }
        });

        viewBinding.btnCancel.setOnClickListener(v -> dismiss());

        viewBinding.btnExport.setOnClickListener(v -> {
            if (isPreviewPlaying) {
                stopPreview();
            }
            List<Float> values = viewBinding.rangeSlider.getValues();
            long curStartMs = (long) (values.get(0) * 1000);
            long curEndMs = (long) (values.get(1) * 1000);
            exportClip(media.getLocalFileUrl(), curStartMs, curEndMs);
        });

        return viewBinding.getRoot();
    }

    private void updateTimeLabels(long startMs, long endMs) {
        if (viewBinding == null) {
            return;
        }
        viewBinding.txtvStartTime.setText(getString(R.string.audio_clip_start_time,
                Converter.getDurationStringLong((int) startMs)));
        viewBinding.txtvEndTime.setText(getString(R.string.audio_clip_end_time,
                Converter.getDurationStringLong((int) endMs)));
        viewBinding.txtvDuration.setText(getString(R.string.audio_clip_duration,
                Converter.getDurationStringLong((int) (endMs - startMs))));
    }

    private void startPreview(String filePath, long startMs, long endMs) {
        if (filePath == null) {
            return;
        }
        try {
            stopPreview();
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(filePath);
            mediaPlayer.prepare();
            mediaPlayer.seekTo((int) startMs);
            mediaPlayer.start();
            isPreviewPlaying = true;
            viewBinding.btnPreview.setIconResource(R.drawable.ic_pause);

            previewRunnable = new Runnable() {
                @Override
                public void run() {
                    if (mediaPlayer != null && isPreviewPlaying) {
                        try {
                            if (mediaPlayer.getCurrentPosition() >= endMs || !mediaPlayer.isPlaying()) {
                                stopPreview();
                            } else {
                                previewHandler.postDelayed(this, 100);
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error checking preview position", e);
                            stopPreview();
                        }
                    }
                }
            };
            previewHandler.post(previewRunnable);
        } catch (Exception e) {
            Log.e(TAG, "Error starting preview", e);
            stopPreview();
        }
    }

    private void stopPreview() {
        isPreviewPlaying = false;
        if (previewRunnable != null) {
            previewHandler.removeCallbacks(previewRunnable);
            previewRunnable = null;
        }
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }
                mediaPlayer.release();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping media player", e);
            }
            mediaPlayer = null;
        }
        if (viewBinding != null) {
            viewBinding.btnPreview.setIconResource(R.drawable.ic_play_24dp);
        }
    }

    private void exportClip(String inputPath, long startMs, long endMs) {
        if (getContext() == null || inputPath == null) {
            return;
        }
        setExportUiState(true);

        File cacheDir = getContext().getCacheDir();
        File outputFile = new File(cacheDir, "clip_" + System.currentTimeMillis() + ".m4a");

        executor.execute(() -> {
            boolean success = MediaClipUtils.cropAudioFile(inputPath, outputFile.getAbsolutePath(), startMs, endMs);
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (!isAdded() || viewBinding == null) {
                        return;
                    }
                    setExportUiState(false);
                    if (success && outputFile.exists()) {
                        dismiss();
                        ShareUtils.shareAudioClipFile(getContext(), outputFile);
                    } else {
                        Toast.makeText(getContext(), R.string.audio_clip_error, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    private void setExportUiState(boolean exporting) {
        if (viewBinding == null) {
            return;
        }
        viewBinding.progressBar.setVisibility(exporting ? View.VISIBLE : View.GONE);
        viewBinding.btnExport.setEnabled(!exporting);
        viewBinding.btnPreview.setEnabled(!exporting);
        viewBinding.btnCancel.setEnabled(!exporting);
        viewBinding.rangeSlider.setEnabled(!exporting);
    }

    @Override
    public void onDestroyView() {
        stopPreview();
        viewBinding = null;
        super.onDestroyView();
    }
}
