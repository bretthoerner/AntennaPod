package de.danoeh.antennapod.playback.service.internal;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.List;

import de.danoeh.antennapod.model.feed.Chapter;
import de.danoeh.antennapod.model.playback.Playable;

@RunWith(RobolectricTestRunner.class)
public class SkipUtilsTest {

    @Test
    public void testGetTargetPositionForUnselectedChapter_nullPlayable() {
        assertEquals(-1, SkipUtils.getTargetPositionForUnselectedChapter(null, 100));
    }

    @Test
    public void testGetTargetPositionForUnselectedChapter_allSelected() {
        Playable playable = mock(Playable.class);
        List<Chapter> chapters = new ArrayList<>();
        chapters.add(new Chapter(0, "Ch 1", "", ""));
        chapters.add(new Chapter(10000, "Ch 2", "", ""));
        when(playable.getChapters()).thenReturn(chapters);

        assertEquals(-1, SkipUtils.getTargetPositionForUnselectedChapter(playable, 5000));
    }

    @Test
    public void testGetTargetPositionForUnselectedChapter_unselectedChapter() {
        Playable playable = mock(Playable.class);
        List<Chapter> chapters = new ArrayList<>();
        Chapter ch1 = new Chapter(0, "Ch 1", "", "");
        Chapter ch2 = new Chapter(10000, "Ch 2", "", "");
        ch2.setUnselected(true);
        Chapter ch3 = new Chapter(20000, "Ch 3", "", "");
        chapters.add(ch1);
        chapters.add(ch2);
        chapters.add(ch3);
        when(playable.getChapters()).thenReturn(chapters);

        // Position in Ch 2 (unselected) should jump to start of Ch 3 (20000)
        assertEquals(20000, SkipUtils.getTargetPositionForUnselectedChapter(playable, 12000));
    }

    @Test
    public void testGetTargetPositionForUnselectedChapter_lastChapterUnselected() {
        Playable playable = mock(Playable.class);
        List<Chapter> chapters = new ArrayList<>();
        Chapter ch1 = new Chapter(0, "Ch 1", "", "");
        Chapter ch2 = new Chapter(10000, "Ch 2", "", "");
        ch2.setUnselected(true);
        chapters.add(ch1);
        chapters.add(ch2);
        when(playable.getChapters()).thenReturn(chapters);
        when(playable.getDuration()).thenReturn(30000);

        // Position in Ch 2 (unselected, last chapter) should jump to duration (30000)
        assertEquals(30000, SkipUtils.getTargetPositionForUnselectedChapter(playable, 15000));
    }
}
