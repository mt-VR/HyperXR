package com.hyperxr.youtube;

/** Manual check against the live service: ./gradlew :newpipe:ytSmoke */
public final class Smoke {
    public static void main(final String[] args) throws Exception {
        XrYouTube.init("pt", "BR");
        for (final String[] c : XrYouTube.categories()) System.out.println("category " + c[0] + " = " + c[1]);
        final XrYouTube.Results r = XrYouTube.search("4k 60fps nature");
        System.out.println("search: " + r.videos.size() + " videos, more=" + r.hasMore());
        for (int i = 0; i < Math.min(3, r.videos.size()); i++) System.out.println("  " + r.videos.get(i).title + " | " + r.videos.get(i).channel + " | " + r.videos.get(i).durationS + "s");
        final XrYouTube.Details d = XrYouTube.details(r.videos.get(0).url);
        System.out.println("details: " + d.title + " views=" + d.views + " related=" + d.related.size());
        for (final XrYouTube.Quality q : d.qualities) System.out.println("  " + q.label + " " + q.mime + " audio=" + (q.audioUrl != null) + " ua=" + XrYouTube.userAgentFor(q.videoUrl).substring(0, 20));
        for (final XrYouTube.Quality q : new XrYouTube.Quality[]{d.qualities.get(0), d.qualities.get(2)}) {
            for (final String u : new String[]{q.videoUrl, q.audioUrl}) {
                if (u == null) continue;
                for (final int mode : new int[]{0, 1}) {
                    final String url = mode == 0 ? u : u + "&range=0-65535&rn=1";
                    final java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                    c.setRequestProperty("User-Agent", XrYouTube.userAgentFor(u));
                    if (mode == 0) c.setRequestProperty("Range", "bytes=0-65535");
                    int code = c.getResponseCode();
                    System.out.println("  fetch " + q.label + (mode == 0 ? " Range header" : " range param") + " -> HTTP " + code);
                }
            }
        }
    }
}
