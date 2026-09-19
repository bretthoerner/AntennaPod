package de.danoeh.antennapod.ui.share;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.nio.ByteBuffer;

public class MediaClipUtils {
    private static final String TAG = "MediaClipUtils";
    private static final int DEFAULT_BUFFER_SIZE = 1024 * 1024;
    private static final long TIMEOUT_US = 5000L;

    private MediaClipUtils() {
    }

    public static String cleanFilePath(String path) {
        if (path != null && path.startsWith("file://")) {
            return Uri.parse(path).getPath();
        }
        return path;
    }

    public static boolean cropAudioFile(String inputPath, String outputPath, long startMs, long endMs) {
        inputPath = cleanFilePath(inputPath);
        outputPath = cleanFilePath(outputPath);

        if (inputPath == null || outputPath == null || startMs < 0 || endMs <= startMs) {
            Log.e(TAG, "Invalid parameters for cropping audio file");
            return false;
        }

        File inputFile = new File(inputPath);
        if (!inputFile.exists()) {
            Log.e(TAG, "Input file does not exist: " + inputPath);
            return false;
        }

        long startUs = startMs * 1000;
        long endUs = endMs * 1000;

        boolean directCopySuccess = tryDirectSampleCopy(inputPath, outputPath, startUs, endUs);
        if (directCopySuccess) {
            return true;
        }

        Log.i(TAG, "Direct copy not possible or failed. Transcoding clip to AAC...");
        return transcodeAudioClip(inputPath, outputPath, startUs, endUs);
    }

    private static boolean tryDirectSampleCopy(String inputPath, String outputPath, long startUs, long endUs) {
        MediaExtractor extractor = null;
        MediaMuxer muxer = null;
        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(inputPath);

            int audioTrackIndex = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat trackFormat = extractor.getTrackFormat(i);
                String mime = trackFormat.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    if (!MediaFormat.MIMETYPE_AUDIO_AAC.equals(mime)) {
                        return false;
                    }
                    audioTrackIndex = i;
                    format = trackFormat;
                    break;
                }
            }

            if (audioTrackIndex < 0 || format == null) {
                return false;
            }

            extractor.selectTrack(audioTrackIndex);

            File outputFile = new File(outputPath);
            if (outputFile.exists() && !outputFile.delete()) {
                Log.w(TAG, "Could not delete existing file: " + outputPath);
            }

            muxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int muxerTrackIndex = muxer.addTrack(format);
            muxer.start();

            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);

            int bufferSize = DEFAULT_BUFFER_SIZE;
            if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                bufferSize = format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE);
            }
            ByteBuffer buffer = ByteBuffer.allocateDirect(bufferSize);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            long firstSampleTimeUs = -1;

            while (true) {
                info.offset = 0;
                info.size = extractor.readSampleData(buffer, 0);
                if (info.size < 0) {
                    break;
                }

                long sampleTimeUs = extractor.getSampleTime();
                if (sampleTimeUs > endUs) {
                    break;
                }

                if (sampleTimeUs >= startUs) {
                    if (firstSampleTimeUs < 0) {
                        firstSampleTimeUs = sampleTimeUs;
                    }
                    info.presentationTimeUs = sampleTimeUs - firstSampleTimeUs;

                    int sampleFlags = extractor.getSampleFlags();
                    int bufferFlags = 0;
                    if ((sampleFlags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                        bufferFlags |= MediaCodec.BUFFER_FLAG_KEY_FRAME;
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                            && (sampleFlags & MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0) {
                        bufferFlags |= MediaCodec.BUFFER_FLAG_PARTIAL_FRAME;
                    }
                    info.flags = bufferFlags;

                    muxer.writeSampleData(muxerTrackIndex, buffer, info);
                }

                extractor.advance();
            }

            muxer.stop();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Direct sample copy failed: " + e.getMessage());
            return false;
        } finally {
            if (extractor != null) {
                extractor.release();
            }
            if (muxer != null) {
                try {
                    muxer.release();
                } catch (Throwable e) {
                    Log.e(TAG, "Error releasing muxer", e);
                }
            }
        }
    }

    private static boolean transcodeAudioClip(String inputPath, String outputPath, long startUs, long endUs) {
        MediaExtractor extractor = null;
        MediaCodec decoder = null;
        MediaCodec encoder = null;
        MediaMuxer muxer = null;

        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(inputPath);

            int audioTrackIndex = -1;
            MediaFormat inputFormat = null;
            String inputMime = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat trackFormat = extractor.getTrackFormat(i);
                String mime = trackFormat.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audioTrackIndex = i;
                    inputFormat = trackFormat;
                    inputMime = mime;
                    break;
                }
            }

            if (audioTrackIndex < 0 || inputFormat == null || inputMime == null) {
                return false;
            }

            extractor.selectTrack(audioTrackIndex);
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);

            decoder = MediaCodec.createDecoderByType(inputMime);
            decoder.configure(inputFormat, null, null, 0);
            decoder.start();

            final int sampleRate = inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            final int channelCount = inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;

            MediaFormat outputFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,
                    sampleRate, channelCount);
            outputFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            outputFormat.setInteger(MediaFormat.KEY_BIT_RATE, 128000);

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();

            File outputFile = new File(outputPath);
            if (outputFile.exists() && !outputFile.delete()) {
                Log.w(TAG, "Could not delete existing output file: " + outputPath);
            }
            muxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            int muxerTrackIndex = -1;
            boolean muxerStarted = false;

            boolean extractorEos = false;
            boolean decoderEos = false;
            boolean encoderEos = false;

            MediaCodec.BufferInfo decoderBufferInfo = new MediaCodec.BufferInfo();
            MediaCodec.BufferInfo encoderBufferInfo = new MediaCodec.BufferInfo();

            long firstPresentationUs = -1;

            while (!encoderEos) {
                if (!extractorEos) {
                    int inputBufIndex = decoder.dequeueInputBuffer(TIMEOUT_US);
                    if (inputBufIndex >= 0) {
                        ByteBuffer inputBuf = decoder.getInputBuffer(inputBufIndex);
                        if (inputBuf != null) {
                            int sampleSize = extractor.readSampleData(inputBuf, 0);
                            long sampleTimeUs = extractor.getSampleTime();
                            if (sampleSize < 0 || sampleTimeUs > endUs) {
                                decoder.queueInputBuffer(inputBufIndex, 0, 0, 0,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                extractorEos = true;
                            } else {
                                decoder.queueInputBuffer(inputBufIndex, 0, sampleSize, sampleTimeUs, 0);
                                extractor.advance();
                            }
                        }
                    }
                }

                if (!decoderEos) {
                    int decoderStatus = decoder.dequeueOutputBuffer(decoderBufferInfo, TIMEOUT_US);
                    if (decoderStatus >= 0) {
                        ByteBuffer pcmBuf = decoder.getOutputBuffer(decoderStatus);
                        if ((decoderBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            decoderEos = true;
                        }

                        if (pcmBuf != null && decoderBufferInfo.size > 0
                                && decoderBufferInfo.presentationTimeUs >= startUs
                                && decoderBufferInfo.presentationTimeUs <= endUs) {

                            int encoderInputIndex = encoder.dequeueInputBuffer(TIMEOUT_US);
                            if (encoderInputIndex >= 0) {
                                ByteBuffer encoderInputBuf = encoder.getInputBuffer(encoderInputIndex);
                                if (encoderInputBuf != null) {
                                    encoderInputBuf.clear();
                                    pcmBuf.position(decoderBufferInfo.offset);
                                    pcmBuf.limit(decoderBufferInfo.offset + decoderBufferInfo.size);
                                    encoderInputBuf.put(pcmBuf);

                                    int flags = decoderEos ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0;
                                    encoder.queueInputBuffer(encoderInputIndex, 0,
                                            decoderBufferInfo.size, decoderBufferInfo.presentationTimeUs, flags);
                                }
                            }
                        }
                        decoder.releaseOutputBuffer(decoderStatus, false);
                    }
                }

                int encoderStatus = encoder.dequeueOutputBuffer(encoderBufferInfo, TIMEOUT_US);
                if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (!muxerStarted) {
                        MediaFormat newFormat = encoder.getOutputFormat();
                        muxerTrackIndex = muxer.addTrack(newFormat);
                        muxer.start();
                        muxerStarted = true;
                    }
                } else if (encoderStatus >= 0) {
                    if (muxerStarted) {
                        ByteBuffer encodedData = encoder.getOutputBuffer(encoderStatus);
                        if (encodedData != null && encoderBufferInfo.size > 0) {
                            if (firstPresentationUs < 0) {
                                firstPresentationUs = encoderBufferInfo.presentationTimeUs;
                            }
                            encoderBufferInfo.presentationTimeUs =
                                    Math.max(0, encoderBufferInfo.presentationTimeUs - firstPresentationUs);

                            encodedData.position(encoderBufferInfo.offset);
                            encodedData.limit(encoderBufferInfo.offset + encoderBufferInfo.size);
                            muxer.writeSampleData(muxerTrackIndex, encodedData, encoderBufferInfo);
                        }
                    }
                    if ((encoderBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        encoderEos = true;
                    }
                    encoder.releaseOutputBuffer(encoderStatus, false);
                }
            }

            if (muxerStarted) {
                muxer.stop();
            }
            return muxerStarted;
        } catch (Exception e) {
            Log.e(TAG, "Transcoding audio clip failed", e);
            return false;
        } finally {
            if (decoder != null) {
                try {
                    decoder.stop();
                } catch (Throwable e) {
                    Log.d(TAG, "Error stopping decoder", e);
                }
                try {
                    decoder.release();
                } catch (Throwable e) {
                    Log.d(TAG, "Error releasing decoder", e);
                }
            }
            if (encoder != null) {
                try {
                    encoder.stop();
                } catch (Throwable e) {
                    Log.d(TAG, "Error stopping encoder", e);
                }
                try {
                    encoder.release();
                } catch (Throwable e) {
                    Log.d(TAG, "Error releasing encoder", e);
                }
            }
            if (extractor != null) {
                try {
                    extractor.release();
                } catch (Throwable e) {
                    Log.d(TAG, "Error releasing extractor", e);
                }
            }
            if (muxer != null) {
                try {
                    muxer.release();
                } catch (Throwable e) {
                    Log.d(TAG, "Error releasing muxer", e);
                }
            }
        }
    }
}
