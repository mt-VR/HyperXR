package com.hyperxr.youtube;

import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.kiosk.KioskExtractor;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.search.SearchExtractor;
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper;
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * HyperXR's YouTube: categories, search, video details and playable streams, straight from
 * YouTube through the NewPipe Extractor (no web page, no YouTube app, no Google account). The
 * XR app draws everything itself and plays the streams with its own player.
 */
public final class XrYouTube {
    private XrYouTube() {}

    private static volatile boolean initialised;

    public static synchronized void init(final String language, final String country) {
        if (initialised) return;
        NewPipe.init(new HxDownloader(), new Localization(language, country), new ContentCountry(country));
        initialised = true;
    }

    /** A video in a list (category, search, related). */
    public static final class Video {
        public final String url, title, channel, thumbnail;
        public final long durationS, views;
        public final boolean live;
        Video(final String url, final String title, final String channel, final String thumbnail, final long durationS, final long views, final boolean live) {
            this.url = url; this.title = title; this.channel = channel; this.thumbnail = thumbnail; this.durationS = durationS; this.views = views; this.live = live;
        }
    }

    /** One page of videos and how to get the next one (null when there is none). */
    public static final class Results {
        public final List<Video> videos;
        final Object source; final Page next;
        Results(final List<Video> videos, final Object source, final Page next) { this.videos = videos; this.source = source; this.next = next; }
        public boolean hasMore() { return next != null; }
    }

    /** A playable quality: the video stream and the audio to play with it (null when muxed). */
    public static final class Quality {
        public final String label, videoUrl, audioUrl, mime;
        public final int height, fps;
        Quality(final String label, final String videoUrl, final String audioUrl, final String mime, final int height, final int fps) {
            this.label = label; this.videoUrl = videoUrl; this.audioUrl = audioUrl; this.mime = mime; this.height = height; this.fps = fps;
        }
    }

    public static final class Details {
        public String title = "", channel = "", description = "", hlsUrl = "", dashUrl = "";
        public long views, likes, durationS;
        public boolean live, spherical;
        public final List<Quality> qualities = new ArrayList<>();
        public final List<Video> related = new ArrayList<>();
    }

    /** Category ids with their labels (YouTube's own categories). */
    public static List<String[]> categories() throws Exception {
        final List<String[]> out = new ArrayList<>();
        for (final String id : ServiceList.YouTube.getKioskList().getAvailableKiosks()) out.add(new String[]{id, categoryLabel(id)});
        return out;
    }

    static String categoryLabel(final String id) {
        final String l = id.toLowerCase(Locale.ROOT);
        if (l.contains("music")) return "Música";
        if (l.contains("gaming")) return "Jogos";
        if (l.contains("live")) return "Ao vivo";
        if (l.contains("movie")) return "Filmes e trailers";
        if (l.contains("podcast")) return "Podcasts";
        if (l.contains("trending")) return "Em alta";
        return id;
    }

    public static Results category(final String id) throws Exception {
        final KioskExtractor<? extends InfoItem> k = ServiceList.YouTube.getKioskList().getExtractorById(id, null);
        k.fetchPage();
        final ListExtractor.InfoItemsPage<? extends InfoItem> page = k.getInitialPage();
        return new Results(videos(page.getItems()), k, page.getNextPage());
    }

    public static Results search(final String query) throws Exception {
        final SearchExtractor s = ServiceList.YouTube.getSearchExtractor(query, Collections.singletonList(YoutubeSearchQueryHandlerFactory.VIDEOS), "");
        s.fetchPage();
        final ListExtractor.InfoItemsPage<InfoItem> page = s.getInitialPage();
        return new Results(videos(page.getItems()), s, page.getNextPage());
    }

    @SuppressWarnings("unchecked")
    public static Results more(final Results r) throws Exception {
        if (r.next == null) return new Results(Collections.emptyList(), r.source, null);
        final ListExtractor<InfoItem> e = (ListExtractor<InfoItem>) r.source;
        final ListExtractor.InfoItemsPage<InfoItem> page = e.getPage(r.next);
        return new Results(videos(page.getItems()), e, page.getNextPage());
    }

    private static List<Video> videos(final List<? extends InfoItem> items) {
        final List<Video> out = new ArrayList<>();
        for (final InfoItem i : items) {
            if (!(i instanceof StreamInfoItem)) continue;
            final StreamInfoItem s = (StreamInfoItem) i;
            final boolean live = s.getStreamType() == StreamType.LIVE_STREAM;
            out.add(new Video(s.getUrl(), s.getName(), s.getUploaderName() == null ? "" : s.getUploaderName(), best(s.getThumbnails(), 360),
                    Math.max(0, s.getDuration()), s.getViewCount(), live));
        }
        return out;
    }

    /** The thumbnail closest to (and at least) [height] px, else the largest. */
    static String best(final List<Image> images, final int height) {
        Image pick = null;
        for (final Image i : images) {
            if (pick == null) { pick = i; continue; }
            final int h = i.getHeight(), ph = pick.getHeight();
            final boolean better = (h >= height && (ph < height || h < ph)) || (ph < height && h > ph);
            if (better) pick = i;
        }
        return pick == null ? "" : pick.getUrl();
    }

    /** Everything needed to play [url]: qualities from 144p to the highest (4K/8K), best audio. */
    public static Details details(final String url) throws Exception {
        final StreamInfo info = StreamInfo.getInfo(ServiceList.YouTube, url);
        final Details d = new Details();
        d.title = info.getName();
        d.channel = info.getUploaderName() == null ? "" : info.getUploaderName();
        d.description = info.getDescription() == null ? "" : info.getDescription().content();
        d.views = info.getViewCount(); d.likes = info.getLikeCount(); d.durationS = info.getDuration();
        d.live = info.getStreamType() == StreamType.LIVE_STREAM;
        d.hlsUrl = info.getHlsUrl() == null ? "" : info.getHlsUrl();
        d.dashUrl = info.getDashMpdUrl() == null ? "" : info.getDashMpdUrl();
        for (final String t : info.getTags()) {
            final String l = t.toLowerCase(Locale.ROOT);
            if (l.equals("360") || l.contains("360 video") || l.contains("vr360") || l.contains("360°")) d.spherical = true;
        }
        if (d.title.contains("360°") || d.title.toLowerCase(Locale.ROOT).contains("360 video") || d.title.contains("VR 360")) d.spherical = true;

        // best audio: AAC/MP4 first (decodes everywhere), highest bitrate
        AudioStream audio = null;
        for (final AudioStream a : info.getAudioStreams()) {
            if (a.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP) continue;
            if (audio == null || score(a) > score(audio)) audio = a;
        }
        // one stream per height and fps: video-only + audio, else the muxed one
        final List<Quality> q = new ArrayList<>();
        for (final VideoStream v : info.getVideoOnlyStreams()) {
            if (v.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP || audio == null) continue;
            addOrReplace(q, new Quality(label(v), v.getContent(), audio.getContent(), v.getFormat() == null ? "" : v.getFormat().getMimeType(), v.getHeight(), v.getFps()), v);
        }
        for (final VideoStream v : info.getVideoStreams()) {
            if (v.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP) continue;
            addOrReplace(q, new Quality(label(v), v.getContent(), null, v.getFormat() == null ? "" : v.getFormat().getMimeType(), v.getHeight(), v.getFps()), v);
        }
        q.sort((a, b) -> a.height != b.height ? Integer.compare(b.height, a.height) : Integer.compare(b.fps, a.fps));
        d.qualities.addAll(q);
        d.related.addAll(videos(info.getRelatedItems()));
        return d;
    }

    private static int score(final AudioStream a) {
        final String mime = a.getFormat() == null ? "" : a.getFormat().getMimeType();
        return (mime.contains("mp4") ? 1_000_000 : 0) + a.getAverageBitrate();
    }

    private static String label(final VideoStream v) {
        final int h = v.getHeight();
        final String base = h >= 4320 ? "8K" : h >= 2160 ? "4K" : h >= 1440 ? "1440p" : h > 0 ? h + "p" : v.getResolution();
        return v.getFps() > 30 ? base + v.getFps() : base;
    }

    /** Keeps one quality per height/fps, preferring MP4 (H.264/AV1) over WebM (VP9) when both exist. */
    private static void addOrReplace(final List<Quality> list, final Quality q, final VideoStream v) {
        for (int i = 0; i < list.size(); i++) {
            final Quality o = list.get(i);
            if (o.height == q.height && o.fps == q.fps) {
                if (o.audioUrl == null && q.audioUrl != null) list.set(i, q);  // separate streams reach higher bitrates
                return;
            }
        }
        list.add(q);
    }

    /** The User-Agent YouTube expects when fetching a stream URL from this client. */
    public static String userAgentFor(final String streamUrl) {
        if (YoutubeParsingHelper.isVisionOsStreamingUrl(streamUrl)) return YoutubeParsingHelper.getVisionOsUserAgent(null);
        return HxDownloader.USER_AGENT;
    }
}
