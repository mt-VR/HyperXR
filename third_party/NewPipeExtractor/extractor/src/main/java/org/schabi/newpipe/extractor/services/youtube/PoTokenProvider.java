package org.schabi.newpipe.extractor.services.youtube;

import javax.annotation.Nullable;

/**
 * Interface to provide {@code poToken}s to YouTube player requests.
 *
 * <p>
 * On some major clients, YouTube requires that the integrity of the device passes some checks to
 * allow playback.
 * </p>
 *
 * <p>
 * These checks involve running codes to verify the integrity and using their result to generate
 * one or multiple {@code poToken}(s) (which stands for proof of origin token(s)).
 * </p>
 *
 * <p>
 * These tokens may have a role in triggering the sign in requirement.
 * </p>
 *
 * <p>
 * If an implementation does not want to return a {@code poToken} for a specific client, it <b>must
 * return {@code null}</b>.
 * </p>
 *
 * <p>
 * <b>Implementations of this interface are expected to be thread-safe, as they may be accessed by
 * multiple threads.</b>
 * </p>
 */
public interface PoTokenProvider {

    /**
     * Get a {@link PoTokenResult} specific to the desktop website, a.k.a. the WEB InnerTube client.
     *
     * <p>
     * To be generated and valid, {@code poToken}s from this client must be generated using Google's
     * BotGuard machine, which requires a JavaScript engine with a good DOM implementation. They
     * must be added to adaptive/DASH streaming URLs with the {@code pot} parameter.
     * </p>
     *
     * <p>
     * Note that YouTube desktop website generates two {@code poToken}s:
     * - one for the player requests {@code poToken}s, using the videoId as the minter value;
     * - one for the streaming URLs, using a visitor data for logged-out users as the minter value.
     * </p>
     *
     * @return a {@link PoTokenResult} specific to the WEB InnerTube client or null if it cannot
     * get one
     */
    @Nullable
    PoTokenResult getWebClientPoToken(String videoId);
}
