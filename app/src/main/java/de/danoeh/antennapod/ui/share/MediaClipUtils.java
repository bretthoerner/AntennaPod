package de.danoeh.antennapod.ui.share;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class MediaClipUtils {
    private static final String TAG = "MediaClipUtils";
    private static final int DEFAULT_BUFFER_SIZE = 1024 * 1024;
    private static final long TIMEOUT_US = 5000L;

    private static final int ATOM_MOOV = 0x6D6F6F76;
    private static final int ATOM_MDAT = 0x6D646174;
    private static final int ATOM_UDTA = 0x75647461;
    private static final int ATOM_STCO = 0x7374636F;
    private static final int ATOM_CO64 = 0x636F3634;
    private static final int ATOM_TRAK = 0x7472616B;
    private static final int ATOM_MDIA = 0x6D646961;
    private static final int ATOM_MINF = 0x6D696E66;
    private static final int ATOM_STBL = 0x7374626C;
    private static final int ATOM_EDTS = 0x65647473;

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

            if (firstSampleTimeUs < 0) {
                return false;
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

    private static MediaCodec createEncoder(int sampleRate, int channelCount) throws IOException {
        MediaFormat outputFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,
                sampleRate, channelCount);
        outputFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        outputFormat.setInteger(MediaFormat.KEY_BIT_RATE, 128000);
        outputFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024);

        MediaCodec encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoder.start();
        return encoder;
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

            final int defaultSampleRate = inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            final int defaultChannelCount = inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;

            int currentSampleRate = defaultSampleRate;
            int currentChannelCount = defaultChannelCount;

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
            int pendingDecoderIndex = -1;
            long currentPcmPtsUs = 0;
            boolean encoderInputEos = false;

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

                if (!decoderEos && pendingDecoderIndex < 0) {
                    int decoderStatus = decoder.dequeueOutputBuffer(decoderBufferInfo, TIMEOUT_US);
                    if (decoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat decFormat = decoder.getOutputFormat();
                        currentSampleRate = decFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                                ? decFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) : defaultSampleRate;
                        currentChannelCount = decFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                                ? decFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : defaultChannelCount;
                        if (encoder == null) {
                            encoder = createEncoder(currentSampleRate, currentChannelCount);
                        }
                    } else if (decoderStatus >= 0) {
                        if ((decoderBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            decoderEos = true;
                        }
                        pendingDecoderIndex = decoderStatus;
                        ByteBuffer pcmBuf = decoder.getOutputBuffer(pendingDecoderIndex);
                        if (pcmBuf != null) {
                            pcmBuf.position(decoderBufferInfo.offset);
                            pcmBuf.limit(decoderBufferInfo.offset + decoderBufferInfo.size);
                        }
                        currentPcmPtsUs = decoderBufferInfo.presentationTimeUs;
                    }
                }

                if (pendingDecoderIndex >= 0) {
                    ByteBuffer pcmBuf = decoder.getOutputBuffer(pendingDecoderIndex);
                    boolean withinRange = pcmBuf != null && decoderBufferInfo.size > 0
                            && decoderBufferInfo.presentationTimeUs >= startUs
                            && decoderBufferInfo.presentationTimeUs <= endUs;

                    if (withinRange) {
                        if (encoder == null) {
                            encoder = createEncoder(currentSampleRate, currentChannelCount);
                        }
                        int encoderInputIndex = encoder.dequeueInputBuffer(TIMEOUT_US);
                        if (encoderInputIndex >= 0) {
                            ByteBuffer encoderInputBuf = encoder.getInputBuffer(encoderInputIndex);
                            if (encoderInputBuf != null) {
                                encoderInputBuf.clear();
                                int toTransfer = Math.min(pcmBuf.remaining(), encoderInputBuf.remaining());
                                int bytesPerFrame = currentChannelCount * 2;
                                if (bytesPerFrame > 0 && toTransfer < pcmBuf.remaining()) {
                                    toTransfer = (toTransfer / bytesPerFrame) * bytesPerFrame;
                                }
                                if (toTransfer > 0) {
                                    int oldLimit = pcmBuf.limit();
                                    pcmBuf.limit(pcmBuf.position() + toTransfer);
                                    encoderInputBuf.put(pcmBuf);
                                    pcmBuf.limit(oldLimit);

                                    encoder.queueInputBuffer(encoderInputIndex, 0,
                                            toTransfer, currentPcmPtsUs, 0);

                                    int frames = bytesPerFrame > 0 ? toTransfer / bytesPerFrame : 0;
                                    currentPcmPtsUs += (currentSampleRate > 0)
                                            ? (frames * 1_000_000L / currentSampleRate) : 0;
                                }
                            }
                            if (!pcmBuf.hasRemaining()) {
                                decoder.releaseOutputBuffer(pendingDecoderIndex, false);
                                pendingDecoderIndex = -1;
                            }
                        }
                    } else {
                        decoder.releaseOutputBuffer(pendingDecoderIndex, false);
                        pendingDecoderIndex = -1;
                    }
                }

                if (decoderEos && pendingDecoderIndex < 0 && !encoderInputEos) {
                    if (encoder == null) {
                        encoder = createEncoder(currentSampleRate, currentChannelCount);
                    }
                    int encoderInputIndex = encoder.dequeueInputBuffer(TIMEOUT_US);
                    if (encoderInputIndex >= 0) {
                        encoder.queueInputBuffer(encoderInputIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        encoderInputEos = true;
                    }
                }

                if (encoder != null) {
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
                } else if (decoderEos && pendingDecoderIndex < 0) {
                    break;
                }
            }

            if (muxerStarted) {
                muxer.stop();
            }
            return muxerStarted;
        } catch (IOException | RuntimeException e) {
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

    public static boolean embedMetadata(File file, @Nullable byte[] imageBytes,
                                        @Nullable String title, @Nullable String artist,
                                        @Nullable String album) {
        if (file == null || !file.exists()) {
            return false;
        }
        byte[] udtaAtom = buildUdtaAtom(imageBytes, title, artist, album);
        if (udtaAtom == null) {
            return true;
        }

        File tempFile = new File(file.getAbsolutePath() + ".tmp");
        try (RandomAccessFile in = new RandomAccessFile(file, "r");
                RandomAccessFile out = new RandomAccessFile(tempFile, "rw")) {

            long fileLen = in.length();
            long pos = 0;
            long moovPos = -1;
            long moovSize = -1;
            long mdatPos = -1;

            while (pos + 8 <= fileLen) {
                in.seek(pos);
                int size = in.readInt();
                int tag = in.readInt();
                long actualSize;
                if (size == 1) {
                    actualSize = in.readLong();
                } else if (size == 0) {
                    actualSize = fileLen - pos;
                } else {
                    actualSize = size & 0xFFFFFFFFL;
                }
                if (actualSize <= 0) {
                    break;
                }
                if (tag == ATOM_MOOV) {
                    moovPos = pos;
                    moovSize = actualSize;
                } else if (tag == ATOM_MDAT) {
                    mdatPos = pos;
                }
                pos += actualSize;
            }

            if (moovPos < 0 || moovSize <= 8 || moovSize > 50 * 1024 * 1024) {
                Log.w(TAG, "Valid moov atom not found");
                return false;
            }

            byte[] moovData = new byte[(int) moovSize];
            in.seek(moovPos);
            in.readFully(moovData);

            ByteBuffer moovBb = ByteBuffer.wrap(moovData);
            int childPos = 8;
            int existingUdtaPos = -1;
            int existingUdtaSize = 0;

            while (childPos + 8 <= moovData.length) {
                int s = moovBb.getInt(childPos);
                if (s <= 0 || childPos + s > moovData.length) {
                    break;
                }
                int t = moovBb.getInt(childPos + 4);
                if (t == ATOM_UDTA) {
                    existingUdtaPos = childPos;
                    existingUdtaSize = s;
                    break;
                }
                childPos += s;
            }

            int delta = udtaAtom.length - existingUdtaSize;
            if (moovPos < mdatPos && delta != 0) {
                updateChunkOffsets(moovData, 8, moovData.length, delta);
            }

            byte[] newMoovData = new byte[moovData.length + delta];
            if (existingUdtaPos >= 0) {
                System.arraycopy(moovData, 0, newMoovData, 0, existingUdtaPos);
                System.arraycopy(udtaAtom, 0, newMoovData, existingUdtaPos, udtaAtom.length);
                System.arraycopy(moovData, existingUdtaPos + existingUdtaSize, newMoovData,
                        existingUdtaPos + udtaAtom.length, moovData.length - (existingUdtaPos + existingUdtaSize));
            } else {
                System.arraycopy(moovData, 0, newMoovData, 0, moovData.length);
                System.arraycopy(udtaAtom, 0, newMoovData, moovData.length, udtaAtom.length);
            }
            ByteBuffer.wrap(newMoovData).putInt(0, newMoovData.length);

            copyFileRange(in, out, 0, moovPos);
            out.write(newMoovData);
            copyFileRange(in, out, moovPos + moovSize, fileLen - (moovPos + moovSize));

        } catch (IOException e) {
            Log.e(TAG, "Failed to embed metadata in audio clip", e);
            if (tempFile.exists() && !tempFile.delete()) {
                Log.w(TAG, "Could not delete temporary metadata file: " + tempFile.getAbsolutePath());
            }
            return false;
        }

        if (tempFile.exists() && tempFile.length() > 0) {
            if (file.delete() && tempFile.renameTo(file)) {
                return true;
            } else if (tempFile.renameTo(file)) {
                return true;
            }
        }
        return false;
    }

    private static void copyFileRange(RandomAccessFile in, RandomAccessFile out, long start, long length)
            throws IOException {
        if (length <= 0) {
            return;
        }
        in.seek(start);
        byte[] buf = new byte[64 * 1024];
        long remaining = length;
        while (remaining > 0) {
            int toRead = (int) Math.min(buf.length, remaining);
            int read = in.read(buf, 0, toRead);
            if (read < 0) {
                break;
            }
            out.write(buf, 0, read);
            remaining -= read;
        }
    }

    private static void updateChunkOffsets(byte[] buf, int start, int end, int delta) {
        int p = start;
        ByteBuffer bb = ByteBuffer.wrap(buf);
        while (p + 8 <= end) {
            int s = bb.getInt(p);
            if (s <= 0 || p + s > end) {
                break;
            }
            int tag = bb.getInt(p + 4);
            if (tag == ATOM_STCO) {
                int count = bb.getInt(p + 12);
                for (int i = 0; i < count; i++) {
                    int idx = p + 16 + i * 4;
                    if (idx + 4 <= end) {
                        int val = bb.getInt(idx);
                        bb.putInt(idx, val + delta);
                    }
                }
            } else if (tag == ATOM_CO64) {
                int count = bb.getInt(p + 12);
                for (int i = 0; i < count; i++) {
                    int idx = p + 16 + i * 8;
                    if (idx + 8 <= end) {
                        long val = bb.getLong(idx);
                        bb.putLong(idx, val + delta);
                    }
                }
            } else if (tag == ATOM_TRAK || tag == ATOM_MDIA || tag == ATOM_MINF
                    || tag == ATOM_STBL || tag == ATOM_EDTS) {
                updateChunkOffsets(buf, p + 8, p + s, delta);
            }
            p += s;
        }
    }

    @Nullable
    private static byte[] buildUdtaAtom(@Nullable byte[] imageBytes,
                                        @Nullable String title,
                                        @Nullable String artist,
                                        @Nullable String album) {
        ByteArrayOutputStream ilstItems = new ByteArrayOutputStream();
        try {
            if (title != null && !title.isEmpty()) {
                ilstItems.write(buildTextAtom(new byte[]{(byte) 0xA9, 'n', 'a', 'm'}, title));
            }
            if (artist != null && !artist.isEmpty()) {
                ilstItems.write(buildTextAtom(new byte[]{(byte) 0xA9, 'A', 'R', 'T'}, artist));
            }
            if (album != null && !album.isEmpty()) {
                ilstItems.write(buildTextAtom(new byte[]{(byte) 0xA9, 'a', 'l', 'b'}, album));
            }
            if (imageBytes != null && imageBytes.length > 0) {
                byte[] covrAtom = buildCoverAtom(imageBytes);
                if (covrAtom != null) {
                    ilstItems.write(covrAtom);
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Error building ilst items", e);
            return null;
        }

        byte[] ilstPayload = ilstItems.toByteArray();
        if (ilstPayload.length == 0) {
            return null;
        }
        byte[] ilstAtom = buildAtom(new byte[]{'i', 'l', 's', 't'}, ilstPayload);

        ByteBuffer hdlr = ByteBuffer.allocate(33);
        hdlr.putInt(33);
        hdlr.put(new byte[]{'h', 'd', 'l', 'r'});
        hdlr.putInt(0);
        hdlr.putInt(0);
        hdlr.put(new byte[]{'m', 'd', 'i', 'r'});
        hdlr.put(new byte[]{'a', 'p', 'p', 'l'});
        hdlr.putInt(0);
        hdlr.putInt(0);
        hdlr.put((byte) 0);
        byte[] hdlrAtom = hdlr.array();

        ByteBuffer metaPayload = ByteBuffer.allocate(4 + hdlrAtom.length + ilstAtom.length);
        metaPayload.putInt(0);
        metaPayload.put(hdlrAtom);
        metaPayload.put(ilstAtom);

        byte[] metaAtom = buildAtom(new byte[]{'m', 'e', 't', 'a'}, metaPayload.array());
        return buildAtom(new byte[]{'u', 'd', 't', 'a'}, metaAtom);
    }

    @Nullable
    private static byte[] buildCoverAtom(byte[] imageBytes) {
        int type = 13;
        if (imageBytes.length >= 8 && imageBytes[0] == (byte) 0x89 && imageBytes[1] == (byte) 0x50
                && imageBytes[2] == (byte) 0x4E && imageBytes[3] == (byte) 0x47) {
            type = 14;
        } else if (!(imageBytes.length >= 2 && imageBytes[0] == (byte) 0xFF && imageBytes[1] == (byte) 0xD8)) {
            try {
                Bitmap bm = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
                if (bm == null) {
                    return null;
                }
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                bm.compress(Bitmap.CompressFormat.JPEG, 90, baos);
                bm.recycle();
                imageBytes = baos.toByteArray();
                type = 13;
            } catch (Throwable t) {
                Log.w(TAG, "Failed to decode cover image", t);
                return null;
            }
        }
        byte[] dataAtom = buildDataAtom(type, imageBytes);
        return buildAtom(new byte[]{'c', 'o', 'v', 'r'}, dataAtom);
    }

    private static byte[] buildTextAtom(byte[] tagBytes, String text) {
        byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
        byte[] dataAtom = buildDataAtom(1, textBytes);
        return buildAtom(tagBytes, dataAtom);
    }

    private static byte[] buildDataAtom(int dataType, byte[] data) {
        ByteBuffer buf = ByteBuffer.allocate(8 + data.length);
        buf.putInt(dataType);
        buf.putInt(0);
        buf.put(data);
        return buildAtom(new byte[]{'d', 'a', 't', 'a'}, buf.array());
    }

    private static byte[] buildAtom(byte[] tagBytes, byte[] payload) {
        ByteBuffer buf = ByteBuffer.allocate(8 + payload.length);
        buf.putInt(8 + payload.length);
        buf.put(tagBytes);
        buf.put(payload);
        return buf.array();
    }
}
