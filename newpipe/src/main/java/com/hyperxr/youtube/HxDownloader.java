package com.hyperxr.youtube;

import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** HTTP for the extractor with the platform's HttpURLConnection (no extra HTTP library). */
public final class HxDownloader extends Downloader {
    public static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0";

    @Override
    public Response execute(final Request request) throws IOException, ReCaptchaException {
        final HttpURLConnection c = (HttpURLConnection) new URL(request.url()).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestMethod(request.httpMethod());
        c.setRequestProperty("User-Agent", USER_AGENT);
        for (final Map.Entry<String, List<String>> h : request.headers().entrySet()) {
            c.setRequestProperty(h.getKey(), String.join(", ", h.getValue()));
        }
        final byte[] body = request.dataToSend();
        if (body != null) {
            c.setDoOutput(true);
            try (OutputStream o = c.getOutputStream()) { o.write(body); }
        }
        final int code = c.getResponseCode();
        if (code == 429) {
            c.disconnect();
            throw new ReCaptchaException("reCaptcha Challenge requested", request.url());
        }
        final InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String text = "";
        if (in != null) {
            try (InputStream s = in) {
                final ByteArrayOutputStream out = new ByteArrayOutputStream();
                final byte[] buf = new byte[16384];
                int n;
                while ((n = s.read(buf)) > 0) out.write(buf, 0, n);
                text = out.toString(StandardCharsets.UTF_8.name());
            }
        }
        final Response r = new Response(code, c.getResponseMessage(), c.getHeaderFields(), text, c.getURL().toString());
        c.disconnect();
        return r;
    }
}
