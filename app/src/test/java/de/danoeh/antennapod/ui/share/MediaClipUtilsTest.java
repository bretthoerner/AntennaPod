package de.danoeh.antennapod.ui.share;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.assertFalse;

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
}
