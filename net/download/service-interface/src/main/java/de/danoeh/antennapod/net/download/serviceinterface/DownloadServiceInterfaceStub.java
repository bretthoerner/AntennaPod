package de.danoeh.antennapod.net.download.serviceinterface;

import android.content.Context;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;

import java.util.Collections;
import java.util.Set;

public class DownloadServiceInterfaceStub extends DownloadServiceInterface {

    @Override
    public void downloadNow(Context context, FeedItem item, boolean ignoreConstraints) {
    }

    @Override
    public void download(Context context, FeedItem item) {
    }

    @Override
    public void cancel(Context context, FeedMedia media) {
    }

    @Override
    public void cancelAll(Context context) {
    }

    @Override
    public int getNumberOfActiveDownloads(Context context) {
        return 0;
    }

    @Override
    public Set<String> getActiveDownloads(Context context) {
        return Collections.emptySet();
    }
}
