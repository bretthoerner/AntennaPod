package de.danoeh.antennapod.ui.share;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class MediaClipUtilsTest {

    @Test
    public void testCropAudioFileInvalidParams() {
        assertFalse(MediaClipUtils.cropAudioFile(null, "/tmp/out.m4a", 0, 1000));
        assertFalse(MediaClipUtils.cropAudioFile("/tmp/in.mp3", null, 0, 1000));
        assertFalse(MediaClipUtils.cropAudioFile("/tmp/in.mp3", "/tmp/out.m4a", -1, 1000));
        assertFalse(MediaClipUtils.cropAudioFile("/tmp/in.mp3", "/tmp/out.m4a", 1000, 1000));
        assertFalse(MediaClipUtils.cropAudioFile("/tmp/in.mp3", "/tmp/out.m4a", 2000, 1000));
    }

    @Test
    public void testCropAudioFileNonExistentFile() {
        assertFalse(MediaClipUtils.cropAudioFile("/non_existent_path/file.mp3", "/tmp/out.m4a", 0, 1000));
    }

    @Test
    public void testEmbedMetadataInvalidFile() {
        assertFalse(MediaClipUtils.embedMetadata(null, null, "Title", "Artist", "Album"));
        assertFalse(MediaClipUtils.embedMetadata(new File("/non_existent_path/file.m4a"),
                null, "Title", "Artist", "Album"));
    }

    @Test
    public void testEmbedMetadataSuccess() throws Exception {
        File src = new File("../parser/media/src/test/resources/ffmpeg.m4a");
        if (!src.exists()) {
            src = new File("parser/media/src/test/resources/ffmpeg.m4a");
        }
        if (!src.exists()) {
            return;
        }

        File temp = File.createTempFile("test_clip", ".m4a");
        temp.deleteOnExit();
        Files.copy(src.toPath(), temp.toPath(), StandardCopyOption.REPLACE_EXISTING);

        byte[] pngBytes = new byte[]{
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
                0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
                0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, (byte) 0xC4, (byte) 0x89,
                0x00, 0x00, 0x00, 0x0D, 0x49, 0x44, 0x41, 0x54,
                0x78, (byte) 0x9C, 0x63, 0x60, 0x60, 0x60, 0x60,
                0x00, 0x00, 0x00, 0x05, 0x00, 0x01, (byte) 0xA7, (byte) 0xDF, (byte) 0x93, 0x0C,
                0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44,
                (byte) 0xAE, 0x42, 0x60, (byte) 0x82
        };

        boolean result = MediaClipUtils.embedMetadata(temp, pngBytes, "Test Clip", "Podcast Show", "Episode Album");
        assertTrue(result);
        assertTrue(temp.exists() && temp.length() > 0);
    }
}
