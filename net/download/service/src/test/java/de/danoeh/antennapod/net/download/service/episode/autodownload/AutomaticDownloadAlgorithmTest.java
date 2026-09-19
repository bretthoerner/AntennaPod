package de.danoeh.antennapod.net.download.service.episode.autodownload;

import android.content.Context;
import androidx.preference.PreferenceManager;
import androidx.test.platform.app.InstrumentationRegistry;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedPreferences;
import de.danoeh.antennapod.model.feed.VolumeAdaptionSetting;
import de.danoeh.antennapod.net.common.NetworkUtils;
import de.danoeh.antennapod.net.download.serviceinterface.DownloadServiceInterface;
import de.danoeh.antennapod.net.download.serviceinterface.DownloadServiceInterfaceStub;
import de.danoeh.antennapod.storage.database.DBWriter;
import de.danoeh.antennapod.storage.database.PodDBAdapter;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class AutomaticDownloadAlgorithmTest {

    private Context context;
    private TestDownloadServiceInterface testDownloadService;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PodDBAdapter.init(context);
        PodDBAdapter.deleteDatabase();
        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        adapter.close();

        UserPreferences.init(context);
        NetworkUtils.init(context);
        UserPreferences.setAllowMobileAutoDownload(true);

        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean(UserPreferences.PREF_AUTODL_QUEUE, true)
                .putBoolean(UserPreferences.PREF_AUTODL_GLOBAL, true)
                .putString(UserPreferences.PREF_EPISODE_CACHE_SIZE, "20")
                .apply();

        testDownloadService = new TestDownloadServiceInterface();
        DownloadServiceInterface.setImpl(testDownloadService);
    }

    @After
    public void tearDown() {
        PodDBAdapter.deleteDatabase();
        DBWriter.tearDownTests();
    }

    @Test
    public void testActiveDownloadsExcludedFromCandidates() {
        List<Feed> feeds = DbTestUtils.saveFeedlist(1, 2, true);
        Feed feed = feeds.get(0);
        FeedItem item1 = feed.getItems().get(0);
        FeedItem item2 = feed.getItems().get(1);

        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        adapter.setQueue(Arrays.asList(item1, item2));
        adapter.close();

        testDownloadService.activeDownloads.add(item1.getMedia().getDownloadUrl());

        new AutomaticDownloadAlgorithm().autoDownloadUndownloadedItems(context).run();

        assertEquals(1, testDownloadService.downloadedItems.size());
        assertEquals(item2.getId(), testDownloadService.downloadedItems.get(0).getId());
    }

    @Test
    public void testPrioritizeQueuedCandidates() {
        List<Feed> feeds = DbTestUtils.saveFeedlist(2, 1, true);
        Feed unqueuedFeed = feeds.get(0);
        FeedPreferences unqueuedPrefs = new FeedPreferences(unqueuedFeed.getId(),
                FeedPreferences.AutoDownloadSetting.ENABLED,
                FeedPreferences.AutoDeleteAction.GLOBAL, VolumeAdaptionSetting.OFF,
                FeedPreferences.NewEpisodesAction.GLOBAL, null, null);
        unqueuedFeed.setPreferences(unqueuedPrefs);
        Feed queuedFeed = feeds.get(1);
        FeedPreferences queuedPrefs = new FeedPreferences(queuedFeed.getId(),
                FeedPreferences.AutoDownloadSetting.DISABLED,
                FeedPreferences.AutoDeleteAction.GLOBAL, VolumeAdaptionSetting.OFF,
                FeedPreferences.NewEpisodesAction.GLOBAL, null, null);
        queuedFeed.setPreferences(queuedPrefs);

        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        unqueuedFeed.getItems().get(0).setNew();
        adapter.setCompleteFeed(unqueuedFeed);
        adapter.setCompleteFeed(queuedFeed);
        adapter.setQueue(Collections.singletonList(queuedFeed.getItems().get(0)));
        adapter.close();

        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putString(UserPreferences.PREF_EPISODE_CACHE_SIZE, "1")
                .apply();

        new AutomaticDownloadAlgorithm().autoDownloadUndownloadedItems(context).run();

        assertEquals(1, testDownloadService.downloadedItems.size());
        assertEquals(queuedFeed.getItems().get(0).getId(), testDownloadService.downloadedItems.get(0).getId());
    }

    @Test
    public void testClampedSpaceLeftNoCrashWhenSpaceNegative() {
        List<Feed> feeds = DbTestUtils.saveFeedlist(1, 1, true);
        FeedItem item = feeds.get(0).getItems().get(0);

        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        adapter.setQueue(Collections.singletonList(item));
        adapter.close();

        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putString(UserPreferences.PREF_EPISODE_CACHE_SIZE, "1")
                .apply();

        testDownloadService.activeDownloads.add("http://fake.url/1");
        testDownloadService.activeDownloads.add("http://fake.url/2");

        new AutomaticDownloadAlgorithm().autoDownloadUndownloadedItems(context).run();

        assertTrue(testDownloadService.downloadedItems.isEmpty());
    }

    private static class TestDownloadServiceInterface extends DownloadServiceInterfaceStub {
        final List<FeedItem> downloadedItems = new ArrayList<>();
        final Set<String> activeDownloads = new HashSet<>();

        @Override
        public void download(Context context, FeedItem item) {
            downloadedItems.add(item);
        }

        @Override
        public Set<String> getActiveDownloads(Context context) {
            return activeDownloads;
        }

        @Override
        public int getNumberOfActiveDownloads(Context context) {
            return activeDownloads.size();
        }
    }
}
