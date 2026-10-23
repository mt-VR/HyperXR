package org.schabi.newpipe.extractor.services.youtube;

final class ClientsConstants {
    private ClientsConstants() {
    }

    // Common client fields

    static final String DESKTOP_CLIENT_PLATFORM = "DESKTOP";
    static final String MOBILE_CLIENT_PLATFORM = "MOBILE";
    static final String WATCH_CLIENT_SCREEN = "WATCH";

    // WEB (YouTube desktop) client fields

    static final String WEB_CLIENT_ID = "1";
    static final String WEB_CLIENT_NAME = "WEB";
    /**
     * The client version for InnerTube requests with the {@code WEB} client, used as the last
     * fallback if the extraction of the real one failed.
     */
    static final String WEB_HARDCODED_CLIENT_VERSION = "2.20260805.01.00";

    // WEB_REMIX (YouTube Music) client fields

    static final String WEB_REMIX_CLIENT_ID = "67";
    static final String WEB_REMIX_CLIENT_NAME = "WEB_REMIX";
    static final String WEB_REMIX_HARDCODED_CLIENT_VERSION = "1.20260804.16.00";

    // WEB_MUSIC_ANALYTICS (YouTube charts)

    static final String WEB_MUSIC_ANALYTICS_CLIENT_ID = "31";
    static final String WEB_MUSIC_ANALYTICS_CLIENT_NAME = "WEB_MUSIC_ANALYTICS";
    static final String WEB_MUSIC_ANALYTICS_CLIENT_VERSION = "2.0";

    // visionOS client fields

    static final String VISIONOS_CLIENT_ID = "101";
    static final String VISIONOS_CLIENT_NAME = "VISIONOS";
    // See https://apps.apple.com/us/app/youtube-for-visionos/id6745572359 for the latest version
    static final String VISIONOS_CLIENT_VERSION = "1.04";
    // See https://theapplewiki.com/wiki/Apple_Vision_Pro_(M5)
    static final String VISIONOS_DEVICE_MODEL = "RealityDevice17,1";
    // See https://theapplewiki.com/wiki/Firmware/Apple_Vision/26.x
    static final String VISIONOS_VERSION = "26.6.0.23O770";
    static final String VISIONOS_USER_AGENT_VERSION = "26_6_0";
}
