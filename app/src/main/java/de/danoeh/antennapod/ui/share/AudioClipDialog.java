package de.danoeh.antennapod.ui.share;

import android.content.Context;
import android.graphics.Bitmap;
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
import androidx.media3.session.MediaController;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestBuilder;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.playback.service.PlaybackController;
import de.danoeh.antennapod.databinding.AudioClipDialogBinding;
import de.danoeh.antennapod.model.MediaMetadataRetrieverCompat;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.ui.common.Converter;
import de.danoeh.antennapod.ui.episodes.ImageResourceUtils;

public class AudioClipDialog extends BottomSheetDialogFragment {
    private static final String TAG = "AudioClipDialog";
    private static final String ARGUMENT_FEED_ITEM = "feedItem";
    private static final int WINDOW_BACK_SEC = 2 * 60;
    private static final int WINDOW_FORWARD_SEC = 60;
    private static final int WINDOW_INCREMENT_SEC = 60;

    private static final String ARGUMENT_FEED_ID = "feedId";
    private static final String ARGUMENT_FEED_TITLE = "feedTitle";
    private static final String ARGUMENT_FEED_AUTHOR = "feedAuthor";
    private static final String ARGUMENT_FEED_IMAGE_URL = "feedImageUrl";

    private AudioClipDialogBinding viewBinding;
    private MediaPlayer mediaPlayer;
    private Handler previewHandler = new Handler(Looper.getMainLooper());
    private Runnable previewRunnable;
    private boolean isPreviewPlaying = false;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private int totalDurationSec = 0;
    private int currentWindowStartSec = 0;
    private int currentWindowEndSec = 0;

    public AudioClipDialog() {
        // Empty constructor required for DialogFragment
    }

    public static AudioClipDialog newInstance(FeedItem item) {
        Bundle arguments = new Bundle();
        arguments.putSerializable(ARGUMENT_FEED_ITEM, item);
        if (item != null) {
            arguments.putLong(ARGUMENT_FEED_ID, item.getFeedId());
            if (item.getFeed() != null) {
                arguments.putString(ARGUMENT_FEED_TITLE, item.getFeed().getTitle());
                arguments.putString(ARGUMENT_FEED_AUTHOR, item.getFeed().getAuthor());
                arguments.putString(ARGUMENT_FEED_IMAGE_URL, item.getFeed().getImageUrl());
            }
        }
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
            try (MediaMetadataRetrieverCompat retriever = new MediaMetadataRetrieverCompat()) {
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

        totalDurationSec = Math.max(1, durationMs / 1000);
        int currentSec = Math.max(0, media.getPosition() / 1000);

        currentWindowStartSec = Math.max(0, currentSec - WINDOW_BACK_SEC);
        currentWindowEndSec = Math.min(totalDurationSec, currentSec + WINDOW_FORWARD_SEC);
        if (currentWindowEndSec <= currentWindowStartSec) {
            currentWindowEndSec = Math.min(totalDurationSec, currentWindowStartSec + 1);
        }
        if (currentWindowStartSec == 0 && currentWindowEndSec == totalDurationSec) {
            viewBinding.layoutWindowControls.setVisibility(View.GONE);
        } else {
            viewBinding.layoutWindowControls.setVisibility(View.VISIBLE);
        }

        int startSec = Math.max(currentWindowStartSec, currentSec - 30);
        int endSec = Math.min(currentWindowEndSec, currentSec);
        if (endSec <= startSec) {
            endSec = Math.min(currentWindowEndSec, startSec + 30);
        }
        if (endSec <= startSec) {
            endSec = Math.min(currentWindowEndSec, startSec + 1);
        }

        viewBinding.rangeSlider.setStepSize(1f);
        viewBinding.rangeSlider.setMinSeparationValue(1f);
        viewBinding.rangeSlider.setLabelFormatter(value ->
                Converter.getDurationStringLong((int) (value * 1000)));

        updateSliderAndWindow(currentWindowStartSec, currentWindowEndSec, startSec, endSec);

        viewBinding.btnWindowPrev.setOnClickListener(v -> {
            if (isPreviewPlaying) {
                stopPreview();
            }
            int newStart = Math.max(0, currentWindowStartSec - WINDOW_INCREMENT_SEC);
            shiftWindow(newStart, currentWindowEndSec);
        });

        viewBinding.btnWindowNext.setOnClickListener(v -> {
            if (isPreviewPlaying) {
                stopPreview();
            }
            int newEnd = Math.min(totalDurationSec, currentWindowEndSec + WINDOW_INCREMENT_SEC);
            shiftWindow(currentWindowStartSec, newEnd);
        });

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
            PlaybackController.bindToMedia3Service(getContext(), MediaController::pause);
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

        Context appContext = getContext().getApplicationContext();
        Bundle args = getArguments();
        long feedIdArg = args != null ? args.getLong(ARGUMENT_FEED_ID, 0) : 0;
        String feedTitleArg = args != null ? args.getString(ARGUMENT_FEED_TITLE) : null;
        String feedAuthorArg = args != null ? args.getString(ARGUMENT_FEED_AUTHOR) : null;
        String feedImageUrlArg = args != null ? args.getString(ARGUMENT_FEED_IMAGE_URL) : null;
        FeedItem item = args != null ? (FeedItem) args.getSerializable(ARGUMENT_FEED_ITEM) : null;
        File cacheDir = getContext().getCacheDir();
        File outputFile = new File(cacheDir, "clip_" + System.currentTimeMillis() + ".m4a");

        executor.execute(() -> {
            boolean success = MediaClipUtils.cropAudioFile(inputPath, outputFile.getAbsolutePath(), startMs, endMs);
            if (success && outputFile.exists() && item != null) {
                FeedItem exportItem = item;
                if (exportItem.getFeed() == null && exportItem.getId() > 0) {
                    FeedItem dbItem = DBReader.getFeedItem(exportItem.getId());
                    if (dbItem != null) {
                        exportItem = dbItem;
                    }
                }
                if (exportItem.getFeed() == null) {
                    long feedId = exportItem.getFeedId() > 0 ? exportItem.getFeedId() : feedIdArg;
                    if (feedId > 0) {
                        Feed feed = DBReader.getFeed(feedId, false, 0, 1);
                        if (feed != null) {
                            exportItem.setFeed(feed);
                        }
                    }
                }
                String fallbackImageUrl = feedImageUrlArg;
                if (fallbackImageUrl == null && exportItem.getFeed() != null) {
                    fallbackImageUrl = exportItem.getFeed().getImageUrl();
                }
                byte[] imageBytes = loadEpisodeImage(appContext, exportItem, inputPath, fallbackImageUrl);
                String title = exportItem.getTitle();
                String artist = exportItem.getFeed() != null ? exportItem.getFeed().getAuthor() : feedAuthorArg;
                String album = exportItem.getFeed() != null ? exportItem.getFeed().getTitle() : feedTitleArg;
                MediaClipUtils.embedMetadata(outputFile, imageBytes, title, artist, album);
            }
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (!isAdded() || viewBinding == null) {
                        return;
                    }
                    setExportUiState(false);
                    if (success && outputFile.exists()) {
                        Context context = getContext();
                        dismiss();
                        if (context != null) {
                            ShareUtils.shareAudioClipFile(context, outputFile);
                        }
                    } else {
                        Toast.makeText(getContext(), R.string.audio_clip_error, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    @Nullable
    private byte[] loadEpisodeImage(Context context, FeedItem item, String localAudioPath,
                                    @Nullable String fallbackImageUrl) {
        String imageLocation = ImageResourceUtils.getEpisodeListImageLocation(item);
        String fallbackLocation = ImageResourceUtils.getFallbackImageLocation(item);
        if (imageLocation == null) {
            imageLocation = fallbackLocation;
            fallbackLocation = null;
        }
        if (imageLocation == null) {
            imageLocation = fallbackImageUrl;
        }
        if (imageLocation != null) {
            try {
                RequestBuilder<Bitmap> request = Glide.with(context)
                        .asBitmap()
                        .load(imageLocation);
                if (fallbackLocation != null) {
                    request = request.error(Glide.with(context)
                            .asBitmap()
                            .load(fallbackLocation));
                }
                Bitmap bitmap = request.submit(1024, 1024).get(5, TimeUnit.SECONDS);
                if (bitmap != null) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, baos);
                    return baos.toByteArray();
                }
            } catch (Exception e) {
                Log.d(TAG, "Failed to load image via Glide", e);
            }
        }
        if (localAudioPath != null) {
            try (MediaMetadataRetrieverCompat retriever = new MediaMetadataRetrieverCompat()) {
                retriever.setDataSource(localAudioPath);
                byte[] pic = retriever.getEmbeddedPicture();
                if (pic != null) {
                    return pic;
                }
            } catch (Exception e) {
                Log.d(TAG, "Failed to get embedded picture", e);
            }
        }
        return null;
    }

    private void setExportUiState(boolean exporting) {
        if (viewBinding == null) {
            return;
        }
        viewBinding.progressBar.setVisibility(exporting ? View.VISIBLE : View.GONE);
        viewBinding.btnExport.setEnabled(!exporting);
        viewBinding.btnExport.setText(exporting ? R.string.audio_clip_exporting : R.string.audio_clip_export);
        viewBinding.btnPreview.setEnabled(!exporting);
        viewBinding.btnCancel.setEnabled(!exporting);
        viewBinding.rangeSlider.setEnabled(!exporting);
        viewBinding.btnWindowPrev.setEnabled(!exporting && currentWindowStartSec > 0);
        viewBinding.btnWindowNext.setEnabled(!exporting && currentWindowEndSec < totalDurationSec);
    }

    private void shiftWindow(int newStart, int newEnd) {
        if (viewBinding == null) {
            return;
        }
        List<Float> values = viewBinding.rangeSlider.getValues();
        int curStart = Math.round(values.get(0));
        int curEnd = Math.round(values.get(1));
        int curDuration = Math.max(1, curEnd - curStart);

        if (curStart < newStart) {
            curStart = newStart;
            curEnd = Math.min(newEnd, curStart + curDuration);
        } else if (curEnd > newEnd) {
            curEnd = newEnd;
            curStart = Math.max(newStart, curEnd - curDuration);
        }
        updateSliderAndWindow(newStart, newEnd, curStart, curEnd);
    }

    private void updateSliderAndWindow(int winStart, int winEnd, int startSec, int endSec) {
        if (viewBinding == null) {
            return;
        }
        currentWindowStartSec = winStart;
        currentWindowEndSec = winEnd;

        float minBound = Math.min(viewBinding.rangeSlider.getValueFrom(), winStart);
        float maxBound = Math.max(viewBinding.rangeSlider.getValueTo(), winEnd);
        viewBinding.rangeSlider.setValueFrom(minBound);
        viewBinding.rangeSlider.setValueTo(maxBound);
        viewBinding.rangeSlider.setValues((float) startSec, (float) endSec);
        viewBinding.rangeSlider.setValueFrom((float) winStart);
        viewBinding.rangeSlider.setValueTo((float) winEnd);

        updateWindowRangeLabel();
        updateTimeLabels(startSec * 1000L, endSec * 1000L);
    }

    private void updateWindowRangeLabel() {
        if (viewBinding == null) {
            return;
        }
        viewBinding.txtvWindowRange.setText(getString(R.string.audio_clip_window_range,
                Converter.getDurationStringLong(currentWindowStartSec * 1000),
                Converter.getDurationStringLong(currentWindowEndSec * 1000)));
        viewBinding.btnWindowPrev.setEnabled(currentWindowStartSec > 0);
        viewBinding.btnWindowNext.setEnabled(currentWindowEndSec < totalDurationSec);
    }

    @Override
    public void onStop() {
        stopPreview();
        super.onStop();
    }

    @Override
    public void onDestroyView() {
        stopPreview();
        viewBinding = null;
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        executor.shutdown();
        super.onDestroy();
    }
}
