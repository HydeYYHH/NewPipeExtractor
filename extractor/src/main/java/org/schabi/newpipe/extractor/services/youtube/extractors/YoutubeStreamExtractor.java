/*
 * Created by Christian Schabesberger on 06.08.15.
 *
 * Copyright (C) 2019 Christian Schabesberger <chris.schabesberger@mailbox.org>
 * YoutubeStreamExtractor.java is part of NewPipe Extractor.
 *
 * NewPipe Extractor is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NewPipe Extractor is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with NewPipe Extractor. If not, see <https://www.gnu.org/licenses/>.
 */

package org.schabi.newpipe.extractor.services.youtube.extractors;

import static org.schabi.newpipe.extractor.services.youtube.ItagItem.APPROX_DURATION_MS_UNKNOWN;
import static org.schabi.newpipe.extractor.services.youtube.ItagItem.CONTENT_LENGTH_UNKNOWN;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeDescriptionHelper.attributedDescriptionToHtml;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.CONTENT_CHECK_OK;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.CPN;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.RACY_CHECK_OK;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.VIDEO_ID;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.fixThumbnailUrl;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.generateContentPlaybackNonce;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getImagesFromThumbnailsArray;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getJsonPostResponse;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getTextFromObject;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.prepareDesktopJsonBuilder;
import static org.schabi.newpipe.extractor.utils.Utils.isNullOrEmpty;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonWriter;

import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.MetaInfo;
import org.schabi.newpipe.extractor.MultiInfoItemsCollector;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.exceptions.AccountTerminatedException;
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException;
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException;
import org.schabi.newpipe.extractor.exceptions.PaidContentException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.exceptions.PrivateContentException;
import org.schabi.newpipe.extractor.exceptions.YoutubeMusicPremiumContentException;
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException;
import org.schabi.newpipe.extractor.linkhandler.LinkHandler;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.DateWrapper;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.localization.TimeAgoParser;
import org.schabi.newpipe.extractor.localization.TimeAgoPatternsManager;
import org.schabi.newpipe.extractor.services.youtube.ItagItem;
import org.schabi.newpipe.extractor.services.youtube.PoTokenProvider;
import org.schabi.newpipe.extractor.services.youtube.PoTokenResult;
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager;
import org.schabi.newpipe.extractor.services.youtube.YoutubeMetaInfoHelper;
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper;
import org.schabi.newpipe.extractor.services.youtube.YoutubeStreamHelper;
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeChannelLinkHandlerFactory;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.Description;
import org.schabi.newpipe.extractor.stream.Frameset;
import org.schabi.newpipe.extractor.stream.Stream;
import org.schabi.newpipe.extractor.stream.StreamExtractor;
import org.schabi.newpipe.extractor.stream.StreamSegment;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.SubtitlesStream;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.extractor.utils.JsonUtils;
import org.schabi.newpipe.extractor.utils.LocaleCompat;
import org.schabi.newpipe.extractor.utils.Pair;
import org.schabi.newpipe.extractor.utils.Parser;
import org.schabi.newpipe.extractor.utils.Utils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class YoutubeStreamExtractor extends StreamExtractor {
    private enum Client { ANDROID_VR, ANDROID, WEB, IOS }

    private enum ManifestKind { DASH, HLS }

    private enum StreamChoiceKind { AUDIO, VIDEO_ONLY, MUXED }

    private enum AuthScene { ANON, LOGGED_IN, PREMIUM }

    private static final class ClientState {
        @Nullable
        private JsonObject streamingData;
        @Nullable
        private String contentPlaybackNonce;
        @Nullable
        private String streamingUrlsPoToken;
        private boolean fetched;

        private void clear() {
            streamingData = null;
            contentPlaybackNonce = null;
            streamingUrlsPoToken = null;
            fetched = false;
        }
    }

    public interface ClientProfileProvider {
        boolean isLoggedIn();

        boolean isPremium();
    }

    private static final String PREMIERED = "Premiered ";
    private static final String PREMIERED_ON = "Premiered on ";
    private static final String FORMATS = "formats";
    private static final String ADAPTIVE_FORMATS = "adaptiveFormats";
    private static final String STREAMING_DATA = "streamingData";
    private static final String NEXT = "next";
    private static final String SIGNATURE_CIPHER = "signatureCipher";
    private static final String CIPHER = "cipher";
    private static final String PLAYER_CAPTIONS_TRACKLIST_RENDERER
            = "playerCaptionsTracklistRenderer";
    private static final String CAPTIONS = "captions";
    private static final String PLAYABILITY_STATUS = "playabilityStatus";
    private static final String THUMBNAIL = "thumbnail";
    private static final String THUMBNAILS = "thumbnails";
    private static final String VIDEO_DETAILS = "videoDetails";
    private static final String TITLE = "title";

    @Nullable
    private static PoTokenProvider poTokenProvider;
    @Nullable
    private static ClientProfileProvider clientProfileProvider;
    private static boolean fetchIosClient = true;

    private JsonObject playerResponse;
    private JsonObject nextResponse;

    @Nonnull
    private final Map<Client, ClientState> clientStates = new EnumMap<>(Client.class);

    private JsonObject videoPrimaryInfoRenderer;
    private JsonObject videoSecondaryInfoRenderer;
    private JsonObject playerMicroFormatRenderer;
    private JsonObject playerCaptionsTracklistRenderer;
    private JsonArray thumbnailsArray;
    private int ageLimit = -1;
    private StreamType streamType;

    @Nonnull
    private List<ManifestChoice> dashChoices = new ArrayList<>();
    @Nonnull
    private List<ManifestChoice> hlsChoices = new ArrayList<>();
    @Nonnull
    private List<ItagChoice<AudioStream>> audioChoices = new ArrayList<>();
    @Nonnull
    private List<ItagChoice<VideoStream>> videoOnlyChoices = new ArrayList<>();
    @Nonnull
    private List<ItagChoice<VideoStream>> muxedChoices = new ArrayList<>();
    @Nullable
    private Localization reqLocalization;
    @Nullable
    private ContentCountry reqContentCountry;
    @Nullable
    private String reqVideoId;
    @Nullable
    private PoTokenResult reqAndroidPoToken;
    @Nullable
    private PoTokenResult reqIosPoToken;
    @Nullable
    private PoTokenResult reqWebPoToken;
    private boolean webPoTokenKnown;
    @Nonnull
    private List<Client> clients = List.of(Client.ANDROID_VR, Client.ANDROID, Client.WEB);

    public YoutubeStreamExtractor(final StreamingService service, final LinkHandler linkHandler) {
        super(service, linkHandler);
    }

    @Nonnull
    public List<ManifestChoice> getDashManifestChoices() {
        return Collections.unmodifiableList(dashChoices);
    }

    @Nonnull
    public List<ManifestChoice> getHlsManifestChoices() {
        return Collections.unmodifiableList(hlsChoices);
    }

    @Nonnull
    public List<ItagChoice<AudioStream>> getAudioStreamChoices() {
        return Collections.unmodifiableList(audioChoices);
    }

    @Nonnull
    public List<ItagChoice<VideoStream>> getVideoOnlyStreamChoices() {
        return Collections.unmodifiableList(videoOnlyChoices);
    }

    @Nonnull
    public List<ItagChoice<VideoStream>> getMuxedStreamChoices() {
        return Collections.unmodifiableList(muxedChoices);
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Impl
    //////////////////////////////////////////////////////////////////////////*/

    @Nonnull
    @Override
    public String getName() throws ParsingException {
        assertPageFetched();
        String title;

        // Try to get the video's original title, which is untranslated
        title = playerResponse.getObject(VIDEO_DETAILS)
                .getString(TITLE);

        if (isNullOrEmpty(title)) {
            title = getTextFromObject(getVideoPrimaryInfoRenderer().getObject(TITLE));

            if (isNullOrEmpty(title)) {
                throw new ParsingException("Could not get name");
            }
        }

        return title;
    }

    @Nullable
    @Override
    public String getTextualUploadDate() {
        String timestamp = playerMicroFormatRenderer.getString("uploadDate", "");
        if (timestamp.isEmpty()) {
            timestamp = playerMicroFormatRenderer.getString("publishDate", "");
        }
        if (!timestamp.isEmpty()) {
            return timestamp;
        }

        final var liveDetails = playerMicroFormatRenderer.getObject("liveBroadcastDetails");
        timestamp = liveDetails.getString("endTimestamp", ""); // an ended live stream
        if (timestamp.isEmpty()) {
            // a running live stream
            timestamp = liveDetails.getString("startTimestamp", "");
        }
        if (!timestamp.isEmpty()) {
            return timestamp;
        } else if (getStreamType() == StreamType.LIVE_STREAM) {
            // this should never be reached, but a live stream without upload date is valid
            return null;
        }

        final var textObject = getVideoPrimaryInfoRenderer().getObject("dateText");
        final String rendererDateText = getTextFromObject(textObject);
        if (rendererDateText == null) {
            return null;
        } else if (rendererDateText.startsWith(PREMIERED_ON)) { // Premiered on 21 Feb 2020
            return rendererDateText.substring(PREMIERED_ON.length());
        } else if (rendererDateText.startsWith(PREMIERED)) {
            // Premiered 20 hours ago / Premiered Feb 21, 2020
            return rendererDateText.substring(PREMIERED.length());
        } else {
            return rendererDateText;
        }
    }

    @Override
    public DateWrapper getUploadDate() throws ParsingException {
        final String dateText = getTextualUploadDate();
        try {
            return DateWrapper.fromOffsetDateTime(dateText);
        } catch (final ParsingException e) {
            // Try other patterns first
        }

        try { // Premiered 20 hours ago
            final var localization = new Localization("en");
            return TimeAgoPatternsManager.getTimeAgoParserFor(localization).parse(dateText);
        } catch (final ParsingException e) {
            // Try other patterns first
        }

        return parseOptionalDate(dateText, "MMM dd, yyyy")
                .or(() -> parseOptionalDate(dateText, "dd MMM yyyy"))
                .map(date -> new DateWrapper(date.atStartOfDay(), true))
                .orElseThrow(() ->
                    new ParsingException("Could not parse upload date \"" + dateText + "\""));
    }

    private Optional<LocalDate> parseOptionalDate(final String date, final String pattern) {
        try {
            // TODO: this parses English formatted dates only, we need a better approach to parse
            // the textual date
            final var formatter = DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH);
            return Optional.of(LocalDate.parse(date, formatter));
        } catch (final DateTimeParseException e) {
            return Optional.empty();
        }
    }

    @Nonnull
    @Override
    public List<Image> getThumbnails() throws ParsingException {
        assertPageFetched();
        try {
            return getImagesFromThumbnailsArray(thumbnailsArray);
        } catch (final Exception e) {
            throw new ParsingException("Could not get thumbnails");
        }
    }

    @Nonnull
    @Override
    public Description getDescription() throws ParsingException {
        assertPageFetched();
        // Description with more info on links
        final String videoSecondaryInfoRendererDescription = getTextFromObject(
                getVideoSecondaryInfoRenderer().getObject("description"),
                true);
        if (!isNullOrEmpty(videoSecondaryInfoRendererDescription)) {
            return new Description(videoSecondaryInfoRendererDescription, Description.HTML);
        }

        final String attributedDescription = attributedDescriptionToHtml(
                getVideoSecondaryInfoRenderer().getObject("attributedDescription"));
        if (!isNullOrEmpty(attributedDescription)) {
            return new Description(attributedDescription, Description.HTML);
        }

        String description = playerResponse.getObject(VIDEO_DETAILS)
                .getString("shortDescription");
        if (description == null) {
            final JsonObject descriptionObject = playerMicroFormatRenderer.getObject("description");
            description = getTextFromObject(descriptionObject);
        }

        // Raw non-html description
        return new Description(description, Description.PLAIN_TEXT);
    }

    @Override
    public int getAgeLimit() throws ParsingException {
        if (ageLimit != -1) {
            return ageLimit;
        }
        assertPageFetched();

        final boolean ageRestricted = getVideoSecondaryInfoRenderer()
                .getObject("metadataRowContainer")
                .getObject("metadataRowContainerRenderer")
                .getArray("rows")
                .stream()
                // Only JsonObjects allowed
                .filter(JsonObject.class::isInstance)
                .map(JsonObject.class::cast)
                .flatMap(metadataRow -> metadataRow
                        .getObject("metadataRowRenderer")
                        .getArray("contents")
                        .stream()
                        // Only JsonObjects allowed
                        .filter(JsonObject.class::isInstance)
                        .map(JsonObject.class::cast))
                .flatMap(content -> content
                        .getArray("runs")
                        .stream()
                        // Only JsonObjects allowed
                        .filter(JsonObject.class::isInstance)
                        .map(JsonObject.class::cast))
                .map(run -> run.getString("text", ""))
                .anyMatch(rowText -> rowText.contains("Age-restricted"));

        ageLimit = ageRestricted ? 18 : NO_AGE_LIMIT;
        return ageLimit;
    }

    @Override
    public long getLength() throws ParsingException {
        assertPageFetched();

        try {
            final String duration = playerResponse.getObject(VIDEO_DETAILS)
                    .getString("lengthSeconds");
            return Long.parseLong(duration);
        } catch (final Exception e) {
            return getDurationFromFirstAdaptiveFormat(clientStates.values().stream()
                    .map(state -> state.streamingData)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList()));
        }
    }

    private int getDurationFromFirstAdaptiveFormat(@Nonnull final List<JsonObject> streamingDatas)
            throws ParsingException {
        for (final JsonObject streamingData : streamingDatas) {
            final JsonArray adaptiveFormats = streamingData.getArray(ADAPTIVE_FORMATS);
            if (adaptiveFormats.isEmpty()) {
                continue;
            }

            final String durationMs = adaptiveFormats.getObject(0)
                    .getString("approxDurationMs");
            try {
                return Math.round(Long.parseLong(durationMs) / 1000f);
            } catch (final NumberFormatException ignored) {
            }
        }

        throw new ParsingException("Could not get duration");
    }

    /**
     * Attempts to parse (and return) the offset to start playing the video from.
     *
     * @return the offset (in seconds), or 0 if no timestamp is found.
     */
    @Override
    public long getTimeStamp() throws ParsingException {
        final long timestamp =
                getTimestampSeconds("((#|&|\\?)t=\\d*h?\\d*m?\\d+s?)");

        if (timestamp == -2) {
            // Regex for timestamp was not found
            return 0;
        }
        return timestamp;
    }

    @Override
    public long getViewCount() throws ParsingException {
        String views = getTextFromObject(getVideoPrimaryInfoRenderer().getObject("viewCount")
                .getObject("videoViewCountRenderer").getObject("viewCount"));

        if (isNullOrEmpty(views)) {
            views = playerResponse.getObject(VIDEO_DETAILS)
                    .getString("viewCount");

            if (isNullOrEmpty(views)) {
                throw new ParsingException("Could not get view count");
            }
        }

        if (views.toLowerCase().contains("no views")) {
            return 0;
        }

        return Long.parseLong(Utils.removeNonDigitCharacters(views));
    }

    @Override
    public long getLikeCount() throws ParsingException {
        assertPageFetched();

        // If ratings are not allowed, there is no like count available
        if (!playerResponse.getObject(VIDEO_DETAILS)
                .getBoolean("allowRatings")) {
            return -1L;
        }

        final JsonArray topLevelButtons = getVideoPrimaryInfoRenderer()
                .getObject("videoActions")
                .getObject("menuRenderer")
                .getArray("topLevelButtons");

        try {
            return parseLikeCountFromLikeButtonViewModel(topLevelButtons);
        } catch (final ParsingException ignored) {
            // A segmentedLikeDislikeButtonRenderer could be returned instead of a
            // segmentedLikeDislikeButtonViewModel, so ignore extraction errors relative to
            // segmentedLikeDislikeButtonViewModel object
        }

        try {
            return parseLikeCountFromLikeButtonRenderer(topLevelButtons);
        } catch (final ParsingException e) {
            throw new ParsingException("Could not get like count", e);
        }
    }

    private static long parseLikeCountFromLikeButtonRenderer(
            @Nonnull final JsonArray topLevelButtons) throws ParsingException {
        String likesString = null;
        final JsonObject likeToggleButtonRenderer = topLevelButtons.stream()
                .filter(JsonObject.class::isInstance)
                .map(JsonObject.class::cast)
                .map(button -> button.getObject("segmentedLikeDislikeButtonRenderer")
                        .getObject("likeButton")
                        .getObject("toggleButtonRenderer"))
                .filter(toggleButtonRenderer -> !isNullOrEmpty(toggleButtonRenderer))
                .findFirst()
                .orElse(null);

        if (likeToggleButtonRenderer != null) {
            // Use one of the accessibility strings available (this one has the same path as the
            // one used for comments' like count extraction)
            likesString = likeToggleButtonRenderer.getObject("accessibilityData")
                    .getObject("accessibilityData")
                    .getString("label");

            // Use the other accessibility string available which contains the exact like count
            if (likesString == null) {
                likesString = likeToggleButtonRenderer.getObject("accessibility")
                        .getString("label");
            }

            // Last method: use the defaultText's accessibility data, which contains the exact like
            // count too, except when it is equal to 0, where a localized string is returned instead
            if (likesString == null) {
                likesString = likeToggleButtonRenderer.getObject("defaultText")
                        .getObject("accessibility")
                        .getObject("accessibilityData")
                        .getString("label");
            }

            // This check only works with English localizations!
            if (likesString != null && likesString.toLowerCase().contains("no likes")) {
                return 0;
            }
        }

        // If ratings are allowed and the likes string is null, it means that we couldn't extract
        // the full like count from accessibility data
        if (likesString == null) {
            throw new ParsingException("Could not get like count from accessibility data");
        }

        try {
            return Long.parseLong(Utils.removeNonDigitCharacters(likesString));
        } catch (final NumberFormatException e) {
            throw new ParsingException("Could not parse \"" + likesString + "\" as a long", e);
        }
    }

    private static long parseLikeCountFromLikeButtonViewModel(
            @Nonnull final JsonArray topLevelButtons) throws ParsingException {
        // Try first with the current video actions buttons data structure
        final JsonObject likeToggleButtonViewModel = topLevelButtons.stream()
                .filter(JsonObject.class::isInstance)
                .map(JsonObject.class::cast)
                .map(button -> button.getObject("segmentedLikeDislikeButtonViewModel")
                        .getObject("likeButtonViewModel")
                        .getObject("likeButtonViewModel")
                        .getObject("toggleButtonViewModel")
                        .getObject("toggleButtonViewModel")
                        .getObject("defaultButtonViewModel")
                        .getObject("buttonViewModel"))
                .filter(buttonViewModel -> !isNullOrEmpty(buttonViewModel))
                .findFirst()
                .orElse(null);

        if (likeToggleButtonViewModel == null) {
            throw new ParsingException("Could not find buttonViewModel object");
        }

        final String accessibilityText = likeToggleButtonViewModel.getString("accessibilityText");
        if (accessibilityText == null) {
            throw new ParsingException("Could not find buttonViewModel's accessibilityText string");
        }

        // The like count is always returned as a number in this element, even for videos with no
        // likes
        try {
            return Long.parseLong(Utils.removeNonDigitCharacters(accessibilityText));
        } catch (final NumberFormatException e) {
            throw new ParsingException(
                    "Could not parse \"" + accessibilityText + "\" as a long", e);
        }
    }

    @Nonnull
    @Override
    public String getUploaderUrl() throws ParsingException {
        assertPageFetched();

        // Don't use the id in the videoSecondaryRenderer object to get real id of the uploader
        // The difference between the real id of the channel and the displayed id is especially
        // visible for music channels and autogenerated channels.
        final String uploaderId = playerResponse.getObject(VIDEO_DETAILS)
                .getString("channelId");
        if (!isNullOrEmpty(uploaderId)) {
            return YoutubeChannelLinkHandlerFactory.getInstance().getUrl("channel/" + uploaderId);
        }

        throw new ParsingException("Could not get uploader url");
    }

    @Nonnull
    @Override
    public String getUploaderName() throws ParsingException {
        assertPageFetched();

        // Don't use the name in the videoSecondaryRenderer object to get real name of the uploader
        // The difference between the real name of the channel and the displayed name is especially
        // visible for music channels and autogenerated channels.
        final String uploaderName = playerResponse.getObject(VIDEO_DETAILS)
                .getString("author");
        if (isNullOrEmpty(uploaderName)) {
            throw new ParsingException("Could not get uploader name");
        }

        return uploaderName;
    }

    @Override
    public boolean isUploaderVerified() throws ParsingException {
        final JsonObject videoOwnerRenderer = getVideoSecondaryInfoRenderer()
                        .getObject("owner")
                        .getObject("videoOwnerRenderer");

        if (videoOwnerRenderer.has("badges")) {
            return YoutubeParsingHelper.isVerified(videoOwnerRenderer
                .getArray("badges"));
        }


        final JsonObject channel = YoutubeParsingHelper.getFirstCollaborator(
            videoOwnerRenderer.getObject("navigationEndpoint"));
        if (channel == null) {
            return false;
        }

        return YoutubeParsingHelper.hasArtistOrVerifiedIconBadgeAttachment(
            channel.getObject(TITLE)
                    .getArray("attachmentRuns"));
    }

    @Nonnull
    @Override
    public List<Image> getUploaderAvatars() throws ParsingException {
        assertPageFetched();
        final JsonObject owner = getVideoSecondaryInfoRenderer().getObject("owner")
                        .getObject("videoOwnerRenderer");

        final List<Image> imageList;
        if (owner.has("avatarStack")) {
            imageList = getImagesFromThumbnailsArray(
                owner.getObject("avatarStack").getObject("avatarStackViewModel")
                    .getArray("avatars")
                    // only consider the first collaborator, which is the video owner
                    .getObject(0)
                    .getObject("avatarViewModel")
                    .getObject("image")
                    .getArray("sources"));
        } else {
            imageList = getImagesFromThumbnailsArray(owner.getObject(THUMBNAIL)
                    .getArray(THUMBNAILS));
        }

        if (imageList.isEmpty() && ageLimit == NO_AGE_LIMIT) {
            throw new ParsingException("Could not get uploader avatars");
        }

        return imageList;
    }

    @Override
    public long getUploaderSubscriberCount() throws ParsingException {
        final JsonObject videoOwnerRenderer = JsonUtils.getObject(videoSecondaryInfoRenderer,
                "owner.videoOwnerRenderer");

        String subscriberCountText = null;
        if (videoOwnerRenderer.has("subscriberCountText")) {
            subscriberCountText = getTextFromObject(videoOwnerRenderer
                .getObject("subscriberCountText"));
        } else {
            final String content = YoutubeParsingHelper.getFirstCollaborator(
                videoOwnerRenderer.getObject("navigationEndpoint")
            ).getObject("subtitle").getString("content");
            subscriberCountText = content.split("•")[1];
        }

        if (isNullOrEmpty(subscriberCountText)) {
            return UNKNOWN_SUBSCRIBER_COUNT;
        }

        try {
            return Utils.mixedNumberWordToLong(subscriberCountText);
        } catch (final NumberFormatException e) {
            throw new ParsingException("Could not get uploader subscriber count", e);
        }
    }

    @Nonnull
    @Override
    public String getDashMpdUrl() throws ParsingException {
        assertPageFetched();
        return getManifestUrl(ManifestKind.DASH, "mpd_version=7");
    }
    @Nonnull
    @Override
    public String getHlsUrl() throws ParsingException {
        assertPageFetched();
        return getManifestUrl(ManifestKind.HLS, "");
    }
    @Nonnull
    private String getManifestUrl(@Nonnull final ManifestKind manifestKind,
                                  @Nonnull final String query) {
        final String manifestKey = manifestKind == ManifestKind.DASH
                ? "dashManifestUrl" : "hlsManifestUrl";
        final List<ManifestChoice> candidates = new ArrayList<>();
        for (final Client client : clients) {
            ensureClientForManifest(client, manifestKey);
            final Pair<JsonObject, String> pair = getStreamingDataPair(client);
            final JsonObject data = pair.getFirst();
            if (data == null) {
                continue;
            }
            final String url = data.getString(manifestKey);
            if (isNullOrEmpty(url)) {
                continue;
            }
            final String poToken = pair.getSecond();
            final String result = appendManifestQuery(url, poToken, query);
            candidates.add(new ManifestChoice(
                    client,
                    result,
                    hasPlayerPoToken(client),
                    !isNullOrEmpty(poToken)));
        }
        setManifestChoices(manifestKind, candidates);
        if (candidates.isEmpty()) {
            return "";
        }
        return candidates.get(0).getUrl();
    }

    private void setManifestChoices(@Nonnull final ManifestKind manifestKind,
                                    @Nonnull final List<ManifestChoice> candidates) {
        candidates.sort(manifestComparator(manifestKind));
        switch (manifestKind) {
            case DASH:
                dashChoices = new ArrayList<>(candidates);
                break;
            case HLS:
                hlsChoices = new ArrayList<>(candidates);
                break;
            default:
                break;
        }
    }
    @Nonnull
    private static String appendManifestQuery(@Nonnull final String manifestUrl,
                                              @Nullable final String poToken,
                                              @Nonnull final String extraQuery) {
        final StringBuilder url = new StringBuilder(manifestUrl);
        final String separator = manifestUrl.contains("?") ? "&" : "?";
        boolean hasQuery = manifestUrl.contains("?");

        if (!isNullOrEmpty(poToken)) {
            url.append(hasQuery ? "&" : separator)
                    .append("pot=")
                    .append(poToken);
            hasQuery = true;
        }
        if (!isNullOrEmpty(extraQuery)) {
            url.append(hasQuery ? "&" : separator)
                    .append(extraQuery);
        }
        return url.toString();
    }

    @Override
    public List<AudioStream> getAudioStreams() throws ExtractionException {
        assertPageFetched();
        return getItags(ADAPTIVE_FORMATS, ItagItem.ItagType.AUDIO,
                getAudioStreamBuilderHelper(), StreamChoiceKind.AUDIO);
    }

    @Override
    public List<VideoStream> getVideoStreams() throws ExtractionException {
        assertPageFetched();
        return getItags(FORMATS, ItagItem.ItagType.VIDEO,
                getVideoStreamBuilderHelper(false), StreamChoiceKind.MUXED);
    }

    @Override
    public List<VideoStream> getVideoOnlyStreams() throws ExtractionException {
        assertPageFetched();
        return getItags(ADAPTIVE_FORMATS, ItagItem.ItagType.VIDEO_ONLY,
                getVideoStreamBuilderHelper(true), StreamChoiceKind.VIDEO_ONLY);
    }

    @Override
    @Nonnull
    public List<SubtitlesStream> getSubtitlesDefault() throws ParsingException {
        return getSubtitles(MediaFormat.TTML);
    }

    @Override
    @Nonnull
    public List<SubtitlesStream> getSubtitles(final MediaFormat format) throws ParsingException {
        assertPageFetched();

        // We cannot store the subtitles list because the media format may change
        final List<SubtitlesStream> subtitlesToReturn = new ArrayList<>();
        final JsonArray captionsArray = playerCaptionsTracklistRenderer.getArray("captionTracks");
        // TODO: use this to apply auto translation to different language from a source language
        // final JsonArray autoCaptionsArray = renderer.getArray("translationLanguages");

        for (int i = 0; i < captionsArray.size(); i++) {
            final String languageCode = captionsArray.getObject(i).getString("languageCode");
            final String baseUrl = captionsArray.getObject(i).getString("baseUrl");
            final String vssId = captionsArray.getObject(i).getString("vssId");

            if (languageCode != null && baseUrl != null && vssId != null) {
                final boolean isAutoGenerated = vssId.startsWith("a.");
                final String cleanUrl = baseUrl
                        // Remove preexisting format if exists
                        .replaceAll("&fmt=[^&]*", "")
                        // Remove translation language
                        .replaceAll("&tlang=[^&]*", "");

                subtitlesToReturn.add(new SubtitlesStream.Builder()
                        .setContent(cleanUrl + "&fmt=" + format.getSuffix(), true)
                        .setMediaFormat(format)
                        .setLanguageCode(languageCode)
                        .setAutoGenerated(isAutoGenerated)
                        .build());
            }
        }

        return subtitlesToReturn;
    }

    @Override
    public StreamType getStreamType() {
        assertPageFetched();

        return streamType;
    }

    private void setStreamType() {
        if (playerResponse.getObject(PLAYABILITY_STATUS).has("liveStreamability")) {
            streamType = StreamType.LIVE_STREAM;
        } else if (playerResponse.getObject(VIDEO_DETAILS)
                .getBoolean("isPostLiveDvr", false)) {
            streamType = StreamType.POST_LIVE_STREAM;
        } else {
            streamType = StreamType.VIDEO_STREAM;
        }
    }

    @Nullable
    @Override
    public MultiInfoItemsCollector getRelatedItems() throws ExtractionException {
        assertPageFetched();

        if (getAgeLimit() != NO_AGE_LIMIT) {
            return null;
        }

        try {
            final MultiInfoItemsCollector collector = new MultiInfoItemsCollector(getServiceId());

            final JsonArray results = nextResponse
                    .getObject("contents")
                    .getObject("twoColumnWatchNextResults")
                    .getObject("secondaryResults")
                    .getObject("secondaryResults")
                    .getArray("results");

            final TimeAgoParser timeAgoParser = getTimeAgoParser();
            results.stream()
                    .filter(JsonObject.class::isInstance)
                    .map(JsonObject.class::cast)
                    .map(result -> {
                        if (result.has("compactVideoRenderer")) {
                            return new YoutubeStreamInfoItemExtractor(
                                    result.getObject("compactVideoRenderer"), timeAgoParser);
                        } else if (result.has("compactRadioRenderer")) {
                            return new YoutubeMixOrPlaylistInfoItemExtractor(
                                    result.getObject("compactRadioRenderer"));
                        } else if (result.has("compactPlaylistRenderer")) {
                            return new YoutubeMixOrPlaylistInfoItemExtractor(
                                    result.getObject("compactPlaylistRenderer"));
                        } else if (result.has("lockupViewModel")) {
                            final JsonObject lockupViewModel = result.getObject("lockupViewModel");
                            final String contentType = lockupViewModel.getString("contentType");
                            if ("LOCKUP_CONTENT_TYPE_PLAYLIST".equals(contentType)
                                    || "LOCKUP_CONTENT_TYPE_PODCAST".equals(contentType)) {
                                return new YoutubeMixOrPlaylistLockupInfoItemExtractor(
                                        lockupViewModel);
                            } else if ("LOCKUP_CONTENT_TYPE_VIDEO".equals(contentType)) {
                                return new YoutubeStreamInfoItemLockupExtractor(
                                        lockupViewModel, timeAgoParser);
                            }
                        }
                        return null;
                    })
                    .filter(Objects::nonNull)
                    .forEach(collector::commit);

            return collector;
        } catch (final Exception e) {
            throw new ParsingException("Could not get related videos", e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getErrorMessage() {
        try {
            return getTextFromObject(playerResponse.getObject(PLAYABILITY_STATUS)
                    .getObject("errorScreen").getObject("playerErrorMessageRenderer")
                    .getObject("reason"));
        } catch (final NullPointerException e) {
            return null; // No error message
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Fetch page
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void onFetchPage(@Nonnull final Downloader downloader)
            throws IOException, ExtractionException {
        reqVideoId = getId();
        reqLocalization = getExtractorLocalization();
        reqContentCountry = getExtractorContentCountry();

        final PoTokenProvider provider = poTokenProvider;
        final boolean noPo = provider == null;
        reqAndroidPoToken = noPo ? null : provider.getAndroidClientPoToken(reqVideoId);
        reqWebPoToken = null;
        webPoTokenKnown = noPo;
        reqIosPoToken = !fetchIosClient || noPo ? null : provider.getIosClientPoToken(reqVideoId);
        dashChoices = new ArrayList<>();
        hlsChoices = new ArrayList<>();
        audioChoices = new ArrayList<>();
        videoOnlyChoices = new ArrayList<>();
        muxedChoices = new ArrayList<>();
        clients = buildClients();

        Exception clientException = null;
        Client mainClient = null;
        for (final Client client : clients) {
            try {
                fetchClient(client, true);
                mainClient = client;
                break;
            } catch (final IOException | ExtractionException e) {
                if (shouldAbortWebFallback(e)) {
                    throw e;
                }
                if (clientException != null) {
                    e.addSuppressed(clientException);
                }
                clientException = e;
                resetClient(client);
                playerResponse = null;
            }
        }

        if (mainClient == null) {
            if (clientException instanceof IOException) {
                throw (IOException) clientException;
            }
            if (clientException instanceof ExtractionException) {
                throw (ExtractionException) clientException;
            }
            throw new ExtractionException("No valid player response available");
        }

        setStreamType();

        if (isStreamOnlyRequest()) {
            final List<Thread> prefetch = prefetchRemainingClients(mainClient);
            joinPrefetch(prefetch);
            return;
        }

        final byte[] nextBody = JsonWriter.string(
                prepareDesktopJsonBuilder(reqLocalization, reqContentCountry)
                        .value(VIDEO_ID, reqVideoId)
                        .value(CONTENT_CHECK_OK, true)
                        .value(RACY_CHECK_OK, true)
                        .done())
                .getBytes(StandardCharsets.UTF_8);
        if (mainClient == Client.WEB) {
            setMetadataFromPlayerResponse(playerResponse);
            nextResponse = getJsonPostResponse(NEXT, nextBody, reqLocalization);
        } else {
            final JsonObject[] next = new JsonObject[1];
            final Throwable[] error = new Throwable[1];
            final Thread more = new Thread(() -> {
                try {
                    next[0] = getJsonPostResponse(NEXT, nextBody, reqLocalization);
                } catch (final Throwable e) {
                    error[0] = e;
                }
            });
            more.start();
            fetchWebClientMetadataAndSetThumbnails(
                    reqLocalization, reqContentCountry, reqVideoId);
            try {
                more.join();
            } catch (final InterruptedException e) {
                more.interrupt();
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while fetching YouTube metadata", e);
            }
            if (error[0] instanceof IOException) {
                throw (IOException) error[0];
            }
            if (error[0] instanceof ExtractionException) {
                throw (ExtractionException) error[0];
            }
            if (error[0] instanceof RuntimeException) {
                throw (RuntimeException) error[0];
            }
            if (error[0] instanceof Error) {
                throw (Error) error[0];
            }
            if (error[0] != null) {
                throw new IOException("Failed to fetch YouTube next response", error[0]);
            }
            nextResponse = next[0];
        }
        final List<Thread> prefetch = prefetchRemainingClients(mainClient);
        joinPrefetch(prefetch);
    }

    @Nonnull
    private List<Client> buildClients() {
        final List<Client> result = new ArrayList<>(baseClientOrder());
        if (fetchIosClient) {
            result.add(Client.IOS);
        }
        return result;
    }

    @Nonnull
    private List<Client> baseClientOrder() {
        if (isLiveScene()) {
            return List.of(Client.ANDROID, Client.WEB);
        }
        switch (authScene()) {
            case PREMIUM:
            case LOGGED_IN:
                return List.of(Client.WEB, Client.ANDROID_VR, Client.ANDROID);
            case ANON:
            default:
                return List.of(Client.ANDROID_VR, Client.ANDROID, Client.WEB);
        }
    }

    @Nonnull
    private List<Client> manifestClientOrder(final boolean hls) {
        if (hls) {
            if (isLiveScene()) {
                return fetchIosClient
                        ? List.of(Client.IOS, Client.WEB, Client.ANDROID)
                        : List.of(Client.WEB, Client.ANDROID);
            }
            switch (authScene()) {
                case PREMIUM:
                case LOGGED_IN:
                    return fetchIosClient
                            ? List.of(Client.WEB, Client.IOS, Client.ANDROID_VR, Client.ANDROID)
                            : List.of(Client.WEB, Client.ANDROID_VR, Client.ANDROID);
                case ANON:
                default:
                    return fetchIosClient
                            ? List.of(Client.WEB, Client.IOS, Client.ANDROID_VR, Client.ANDROID)
                            : List.of(Client.WEB, Client.ANDROID_VR, Client.ANDROID);
            }
        }
        return buildClients();
    }

    @Nonnull
    private ClientState clientState(@Nonnull final Client client) {
        return clientStates.computeIfAbsent(client, key -> new ClientState());
    }

    private boolean isLiveScene() {
        return streamType == StreamType.LIVE_STREAM
                || streamType == StreamType.AUDIO_LIVE_STREAM
                || streamType == StreamType.POST_LIVE_STREAM;
    }

    @Nonnull
    private AuthScene authScene() {
        if (isPremiumContext()) {
            return AuthScene.PREMIUM;
        }
        if (isLoggedInContext()) {
            return AuthScene.LOGGED_IN;
        }
        return AuthScene.ANON;
    }

    private boolean isLoggedInContext() {
        final ClientProfileProvider provider = clientProfileProvider;
        return provider != null && provider.isLoggedIn();
    }

    private boolean isPremiumContext() {
        final ClientProfileProvider provider = clientProfileProvider;
        return provider != null && provider.isPremium();
    }

    private static void checkPlayabilityStatus(@Nonnull final JsonObject playabilityStatus)
            throws ParsingException {
        final String status = playabilityStatus.getString("status");
        if (status == null || status.equalsIgnoreCase("ok")) {
            return;
        }

        final String reason = playabilityStatus.getString("reason");

        if (reason != null) {
            if (status.equalsIgnoreCase("login_required")) {
                if (reason.contains("inappropriate for some users")) {
                    throw new AgeRestrictedContentException(
                            "This age-restricted video cannot be watched anonymously");
                }

                if (reason.contains("private")) {
                    throw new PrivateContentException("This video is private");
                }

                if (reason.contains("a bot")) {
                    throw new SignInConfirmNotBotException(
                            "YouTube probably temporarily blocked anonymous watch access with this"
                                    + " IP , got error " + status + ": \"" + reason + "\"");
                }
            }

            if (status.equalsIgnoreCase("unplayable") || status.equalsIgnoreCase("error")) {
                if (reason.contains("Music Premium")) {
                    throw new YoutubeMusicPremiumContentException();
                }

                if (reason.contains("payment")) {
                    throw new PaidContentException("This video is a paid video");
                }

                if (reason.contains("members")) {
                    throw new PaidContentException("This video is only available for members of "
                            + "the channel of this video");
                }

                if (reason.contains("country")) {
                    throw new GeographicRestrictionException(
                            "This video is not available in client's country.");
                }

                if (reason.contains("closed") || reason.contains("terminated")) {
                    throw new AccountTerminatedException(reason);
                }
            }
        }

        throw new ContentNotAvailableException("Got error " + status + ": \"" + reason + "\"");
    }

    private static boolean shouldAbortWebFallback(@Nonnull final Exception exception) {
        return exception instanceof AgeRestrictedContentException
                || exception instanceof PrivateContentException
                || exception instanceof GeographicRestrictionException
                || exception instanceof PaidContentException
                || exception instanceof YoutubeMusicPremiumContentException
                || exception instanceof AccountTerminatedException;
    }

    private void fetchWebClient(@Nonnull final Client client,
                                @Nonnull final Localization localization,
                                @Nonnull final ContentCountry contentCountry,
                                @Nonnull final String videoId,
                                @Nullable final PoTokenResult webPoTokenResult,
                                final int signatureTimestamp)
            throws IOException, ExtractionException {
        final ClientState state = clientState(client);
        state.contentPlaybackNonce = generateContentPlaybackNonce();

        playerResponse = YoutubeStreamHelper.getWebPlayerResponse(
                localization, contentCountry, videoId, state.contentPlaybackNonce,
                webPoTokenResult, signatureTimestamp);

        checkPlayabilityStatus(playerResponse.getObject(PLAYABILITY_STATUS));
        if (isPlayerResponseNotValid(playerResponse, videoId)) {
            throw new ExtractionException("WEB player response is not valid");
        }

        state.streamingData = playerResponse.getObject(STREAMING_DATA);
        if (!hasUsableStreamingData(state.streamingData)) {
            throw new ExtractionException("WEB player response has no usable streaming data");
        }

        playerCaptionsTracklistRenderer = playerResponse.getObject(CAPTIONS)
                .getObject(PLAYER_CAPTIONS_TRACKLIST_RENDERER);

        if (webPoTokenResult != null) {
            state.streamingUrlsPoToken = webPoTokenResult.streamingDataPoToken;
        }
        state.fetched = true;
    }

    private void prefetchWebClient(@Nonnull final Client client,
                                   @Nonnull final Localization localization,
                                   @Nonnull final ContentCountry contentCountry,
                                   @Nonnull final String videoId,
                                   @Nullable final PoTokenResult webPoTokenResult,
                                   final int signatureTimestamp)
            throws IOException, ExtractionException {
        final String cpn = generateContentPlaybackNonce();
        final JsonObject webPlayerResponse = YoutubeStreamHelper.getWebPlayerResponse(
                localization, contentCountry, videoId, cpn, webPoTokenResult, signatureTimestamp);

        checkPlayabilityStatus(webPlayerResponse.getObject(PLAYABILITY_STATUS));
        if (isPlayerResponseNotValid(webPlayerResponse, videoId)) {
            throw new ExtractionException("WEB player response is not valid");
        }

        final JsonObject streamingData = webPlayerResponse.getObject(STREAMING_DATA);
        if (!hasUsableStreamingData(streamingData)) {
            throw new ExtractionException("WEB player response has no usable streaming data");
        }

        final ClientState state = clientState(client);
        state.contentPlaybackNonce = cpn;
        state.streamingData = streamingData;
        if (webPoTokenResult != null) {
            state.streamingUrlsPoToken = webPoTokenResult.streamingDataPoToken;
        }
        if (isNullOrEmpty(playerCaptionsTracklistRenderer)) {
            playerCaptionsTracklistRenderer = webPlayerResponse.getObject(CAPTIONS)
                    .getObject(PLAYER_CAPTIONS_TRACKLIST_RENDERER);
        }
        state.fetched = true;
    }

    private void fetchAndroidClient(@Nonnull final Client client,
                                    @Nonnull final Localization localization,
                                    @Nonnull final ContentCountry contentCountry,
                                    @Nonnull final String videoId,
                                    @Nullable final PoTokenResult androidPoTokenResult)
            throws IOException, ExtractionException {
        final ClientState state = clientState(client);
        state.contentPlaybackNonce = generateContentPlaybackNonce();

        if (client == Client.ANDROID_VR) {
            playerResponse = YoutubeStreamHelper.getAndroidVrPlayerResponse(
                    contentCountry, localization, videoId, state.contentPlaybackNonce);
        } else if (androidPoTokenResult == null) {
            playerResponse = YoutubeStreamHelper.getAndroidReelPlayerResponse(
                    contentCountry, localization, videoId, state.contentPlaybackNonce);
        } else {
            playerResponse = YoutubeStreamHelper.getAndroidPlayerResponse(
                    contentCountry, localization, videoId, state.contentPlaybackNonce,
                    androidPoTokenResult);
        }

        checkPlayabilityStatus(playerResponse.getObject(PLAYABILITY_STATUS));
        if (isPlayerResponseNotValid(playerResponse, videoId)) {
            throw new ExtractionException("ANDROID player response is not valid");
        }

        state.streamingData = playerResponse.getObject(STREAMING_DATA);

        playerCaptionsTracklistRenderer = playerResponse.getObject(CAPTIONS)
                .getObject(PLAYER_CAPTIONS_TRACKLIST_RENDERER);

        if (androidPoTokenResult != null) {
            state.streamingUrlsPoToken = androidPoTokenResult.streamingDataPoToken;
        }
        state.fetched = true;
    }

    private void prefetchAndroidClient(@Nonnull final Client client,
                                       @Nonnull final Localization localization,
                                       @Nonnull final ContentCountry contentCountry,
                                       @Nonnull final String videoId,
                                       @Nullable final PoTokenResult androidPoTokenResult)
            throws IOException, ExtractionException {
        final String cpn = generateContentPlaybackNonce();
        final JsonObject androidPlayerResponse;
        if (client == Client.ANDROID_VR) {
            androidPlayerResponse = YoutubeStreamHelper.getAndroidVrPlayerResponse(
                    contentCountry, localization, videoId, cpn);
        } else if (androidPoTokenResult == null) {
            androidPlayerResponse = YoutubeStreamHelper.getAndroidReelPlayerResponse(
                    contentCountry, localization, videoId, cpn);
        } else {
            androidPlayerResponse = YoutubeStreamHelper.getAndroidPlayerResponse(
                    contentCountry, localization, videoId, cpn, androidPoTokenResult);
        }

        checkPlayabilityStatus(androidPlayerResponse.getObject(PLAYABILITY_STATUS));
        if (isPlayerResponseNotValid(androidPlayerResponse, videoId)) {
            throw new ExtractionException("ANDROID player response is not valid");
        }

        final ClientState state = clientState(client);
        state.contentPlaybackNonce = cpn;
        state.streamingData = androidPlayerResponse.getObject(STREAMING_DATA);
        if (androidPoTokenResult != null) {
            state.streamingUrlsPoToken = androidPoTokenResult.streamingDataPoToken;
        }
        if (isNullOrEmpty(playerCaptionsTracklistRenderer)) {
            playerCaptionsTracklistRenderer = androidPlayerResponse.getObject(CAPTIONS)
                    .getObject(PLAYER_CAPTIONS_TRACKLIST_RENDERER);
        }
        state.fetched = true;
    }

    private void fetchIosClient(@Nonnull final Client client,
                                @Nonnull final Localization localization,
                                @Nonnull final ContentCountry contentCountry,
                                @Nonnull final String videoId,
                                @Nullable final PoTokenResult iosPoTokenResult,
                                final boolean required)
            throws IOException, ExtractionException {
        final ClientState state = clientState(client);
        state.contentPlaybackNonce = generateContentPlaybackNonce();

        final JsonObject iosPlayerResponse = YoutubeStreamHelper.getIosPlayerResponse(
                contentCountry, localization, videoId, state.contentPlaybackNonce, iosPoTokenResult);

        if (required) {
            playerResponse = iosPlayerResponse;
            checkPlayabilityStatus(iosPlayerResponse.getObject(PLAYABILITY_STATUS));
            if (isPlayerResponseNotValid(iosPlayerResponse, videoId)) {
                throw new ExtractionException("IOS player response is not valid");
            }
        }

        if (isPlayerResponseNotValid(iosPlayerResponse, videoId)) {
            return;
        }

        state.streamingData = iosPlayerResponse.getObject(STREAMING_DATA);
        if (required && !hasUsableStreamingData(state.streamingData)) {
            throw new ExtractionException("IOS player response has no usable streaming data");
        }

        if (isNullOrEmpty(playerCaptionsTracklistRenderer)) {
            playerCaptionsTracklistRenderer = iosPlayerResponse.getObject(CAPTIONS)
                    .getObject(PLAYER_CAPTIONS_TRACKLIST_RENDERER);
        }

        if (iosPoTokenResult != null) {
            state.streamingUrlsPoToken = iosPoTokenResult.streamingDataPoToken;
        }
        state.fetched = true;
    }

    private void prefetchIosClient(@Nonnull final Client client,
                                   @Nonnull final Localization localization,
                                   @Nonnull final ContentCountry contentCountry,
                                   @Nonnull final String videoId,
                                   @Nullable final PoTokenResult iosPoTokenResult)
            throws IOException, ExtractionException {
        final String cpn = generateContentPlaybackNonce();
        final JsonObject iosPlayerResponse = YoutubeStreamHelper.getIosPlayerResponse(
                contentCountry, localization, videoId, cpn, iosPoTokenResult);

        if (isPlayerResponseNotValid(iosPlayerResponse, videoId)) {
            throw new ExtractionException("IOS player response is not valid");
        }

        final JsonObject streamingData = iosPlayerResponse.getObject(STREAMING_DATA);
        if (!hasUsableStreamingData(streamingData)) {
            throw new ExtractionException("IOS player response has no usable streaming data");
        }

        final ClientState state = clientState(client);
        state.contentPlaybackNonce = cpn;
        state.streamingData = streamingData;
        if (iosPoTokenResult != null) {
            state.streamingUrlsPoToken = iosPoTokenResult.streamingDataPoToken;
        }
        if (isNullOrEmpty(playerCaptionsTracklistRenderer)) {
            playerCaptionsTracklistRenderer = iosPlayerResponse.getObject(CAPTIONS)
                    .getObject(PLAYER_CAPTIONS_TRACKLIST_RENDERER);
        }
        state.fetched = true;
    }

    private void fetchWebClientMetadataAndSetThumbnails(
            @Nonnull final Localization localization,
            @Nonnull final ContentCountry contentCountry,
            @Nonnull final String videoId) {
        try {
            final JsonObject webPlayerResponse = YoutubeStreamHelper.getWebMetadataPlayerResponse(
                    localization, contentCountry, videoId);

            // Important note: we don't checkPlayabilityStatus() here, because we use this request
            // exclusively for metadata, not for extracting streams. It turns out that when
            // YouTube returns a playability status error, the metadata may still be there.

            if (!isPlayerResponseNotValid(webPlayerResponse, videoId)) {
                // WEB returns the microformat block and better thumbnails.
                playerMicroFormatRenderer = webPlayerResponse.getObject("microformat")
                        .getObject("playerMicroformatRenderer");
                final JsonObject thumbnailWebJsonObj = webPlayerResponse.getObject(VIDEO_DETAILS)
                        .getObject(THUMBNAIL);
                if (thumbnailWebJsonObj.containsKey(THUMBNAILS)) {
                    thumbnailsArray = thumbnailWebJsonObj.getArray(THUMBNAILS);
                } else {
                    thumbnailsArray = playerResponse.getObject(VIDEO_DETAILS)
                            .getObject(THUMBNAIL)
                            .getArray(THUMBNAILS);
                }
            }
        } catch (final Exception e) {
            playerMicroFormatRenderer = new JsonObject();
            thumbnailsArray = playerResponse.getObject(VIDEO_DETAILS)
                    .getObject(THUMBNAIL)
                    .getArray(THUMBNAILS);
        }
    }
    private void setMetadataFromPlayerResponse(
            @Nonnull final JsonObject sourcePlayerResponse) {
        try {
            final JsonObject thumbnailJsonObject = sourcePlayerResponse.getObject(VIDEO_DETAILS)
                    .getObject(THUMBNAIL);
            playerMicroFormatRenderer = sourcePlayerResponse.getObject("microformat")
                    .getObject("playerMicroformatRenderer");
            thumbnailsArray = thumbnailJsonObject.containsKey(THUMBNAILS)
                    ? thumbnailJsonObject.getArray(THUMBNAILS)
                    : playerResponse.getObject(VIDEO_DETAILS)
                            .getObject(THUMBNAIL)
                            .getArray(THUMBNAILS);
        } catch (final Exception e) {
            playerMicroFormatRenderer = new JsonObject();
            thumbnailsArray = playerResponse.getObject(VIDEO_DETAILS)
                    .getObject(THUMBNAIL)
                    .getArray(THUMBNAILS);
        }
    }

    private void fetchClient(@Nonnull final Client client, final boolean required)
            throws IOException, ExtractionException {
        final JsonObject mainPlayerResponse = playerResponse;
        try {
            switch (client) {
                case ANDROID_VR:
                    fetchAndroidClient(client, reqLocalization, reqContentCountry, reqVideoId,
                            null);
                    break;
                case ANDROID:
                    fetchAndroidClient(client, reqLocalization, reqContentCountry, reqVideoId,
                            reqAndroidPoToken);
                    break;
                case WEB:
                    if (!webPoTokenKnown) {
                        reqWebPoToken = poTokenProvider == null
                                ? null
                                : poTokenProvider.getWebClientPoToken(reqVideoId);
                        webPoTokenKnown = true;
                    }
                    fetchWebClient(client, reqLocalization, reqContentCountry, reqVideoId, reqWebPoToken,
                            YoutubeJavaScriptPlayerManager.getSignatureTimestamp(reqVideoId));
                    break;
                case IOS:
                    fetchIosClient(client, reqLocalization, reqContentCountry, reqVideoId, reqIosPoToken,
                            required);
                    break;
                default:
                    throw new ExtractionException("Unsupported client");
            }
        } finally {
            if (!required) {
                playerResponse = mainPlayerResponse;
            }
        }
    }

    private void prefetchClient(@Nonnull final Client client)
            throws IOException, ExtractionException {
        switch (client) {
            case ANDROID_VR:
                prefetchAndroidClient(client, reqLocalization, reqContentCountry, reqVideoId, null);
                break;
            case ANDROID:
                prefetchAndroidClient(client, reqLocalization, reqContentCountry, reqVideoId,
                        reqAndroidPoToken);
                break;
                case WEB:
                if (!webPoTokenKnown) {
                    reqWebPoToken = poTokenProvider == null
                            ? null
                            : poTokenProvider.getWebClientPoToken(reqVideoId);
                    webPoTokenKnown = true;
                }
                prefetchWebClient(client, reqLocalization, reqContentCountry, reqVideoId,
                        reqWebPoToken,
                        YoutubeJavaScriptPlayerManager.getSignatureTimestamp(reqVideoId));
                break;
            case IOS:
                prefetchIosClient(client, reqLocalization, reqContentCountry, reqVideoId, reqIosPoToken);
                break;
            default:
                throw new ExtractionException("Unsupported client");
        }
    }

    @Nonnull
    private List<Thread> prefetchRemainingClients(@Nonnull final Client mainClient) {
        final List<Thread> threads = new ArrayList<>();
        for (final Client client : clients) {
            if (client == mainClient || isClientFetched(client)) {
                continue;
            }
            final Thread thread = new Thread(() -> {
                try {
                    prefetchClient(client);
                } catch (final IOException | ExtractionException e) {
                    resetClient(client);
                }
            }, "yt-client-" + client.name().toLowerCase(Locale.ROOT));
            thread.start();
            threads.add(thread);
        }
        return threads;
    }

    private void joinPrefetch(@Nonnull final List<Thread> threads) throws IOException {
        for (final Thread thread : threads) {
            try {
                thread.join();
            } catch (final InterruptedException e) {
                thread.interrupt();
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while prefetching YouTube clients", e);
            }
        }
    }
    private void ensureClientForManifest(@Nonnull final Client client,
                                         @Nonnull final String manifestKey) {
        if (!isNullOrEmpty(getManifestFromClient(client, manifestKey))) {
            return;
        }
        if (isClientFetched(client)) {
            return;
        }
        try {
            prefetchClient(client);
        } catch (final IOException | ExtractionException e) {
            resetClient(client);
        }
    }
    private void ensureClientForStreams(@Nonnull final Client client,
                                        @Nonnull final String streamingDataKey,
                                        @Nonnull final ItagItem.ItagType itagTypeWanted) {
        if (hasUsableStreams(getStreamingData(client), streamingDataKey, itagTypeWanted)) {
            return;
        }
        if (isClientFetched(client)) {
            return;
        }
        try {
            prefetchClient(client);
        } catch (final IOException | ExtractionException e) {
            resetClient(client);
        }
    }
    private boolean hasUsableStreams(@Nullable final JsonObject streamingData,
                                     @Nonnull final String streamingDataKey,
                                     @Nonnull final ItagItem.ItagType itagTypeWanted) {
        return getStreamsFromStreamingDataKey(
                reqVideoId == null ? "" : reqVideoId,
                streamingData,
                streamingDataKey,
                itagTypeWanted,
                null,
                null).findAny().isPresent();
    }
    @Nullable
    private String getManifestFromClient(@Nonnull final Client client,
                                         @Nonnull final String manifestKey) {
        final JsonObject streamingData = getStreamingData(client);
        return streamingData == null ? null : streamingData.getString(manifestKey);
    }
    private boolean isClientFetched(@Nonnull final Client client) {
        return clientState(client).fetched;
    }
    @Nonnull
    private Pair<JsonObject, String> getStreamingDataPair(@Nonnull final Client client) {
        return new Pair<>(getStreamingData(client), getStreamingUrlsPoToken(client));
    }

    private boolean hasPlayerPoToken(@Nonnull final Client client) {
        return getPoToken(client) != null;
    }

    @Nullable
    private PoTokenResult getPoToken(@Nonnull final Client client) {
        switch (client) {
            case ANDROID:
                return reqAndroidPoToken;
            case ANDROID_VR:
                return null;
            case WEB:
                return reqWebPoToken;
            case IOS:
                return reqIosPoToken;
            default:
                return null;
        }
    }
    @Nullable
    private JsonObject getStreamingData(@Nonnull final Client client) {
        return clientState(client).streamingData;
    }
    @Nullable
    private String getContentPlaybackNonce(@Nonnull final Client client) {
        return clientState(client).contentPlaybackNonce;
    }
    @Nullable
    private String getStreamingUrlsPoToken(@Nonnull final Client client) {
        return clientState(client).streamingUrlsPoToken;
    }
    private void resetClient(@Nonnull final Client client) {
        clientState(client).clear();
    }

    /**
     * Checks whether a player response is invalid.
     *
     * <p>
     * If YouTube detects that requests come from a third party client, they may replace the real
     * player response by another one of a video saying that this content is not available on this
     * app and to watch it on the latest version of YouTube. This behavior has been observed on the
     * {@code ANDROID} client, see
     * <a href="https://github.com/TeamNewPipe/NewPipe/issues/8713">
     *     https://github.com/TeamNewPipe/NewPipe/issues/8713</a>.
     * </p>
     *
     * <p>
     * YouTube may also sometimes for currently unknown reasons rate-limit an IP, and replace the
     * real one by a player response with a video that says that the requested video is
     * unavailable. This behaviour has been observed in Piped on the InnerTube clients used by the
     * extractor ({@code ANDROID} and {@code WEB} clients) which should apply for all clients, see
     * <a href="https://github.com/TeamPiped/Piped/issues/2487">
     *     https://github.com/TeamPiped/Piped/issues/2487</a>.
     * </p>
     *
     * <p>
     * We can detect this by checking whether the video ID of the player response returned is the
     * same as the one requested by the extractor.
     * </p>
     *
     * @param playerResponse a player response from any client
     * @param videoId        the video ID of the content requested
     * @return whether the video ID of the player response is not equal to the one requested
     */
    private static boolean isPlayerResponseNotValid(
            @Nonnull final JsonObject playerResponse,
            @Nonnull final String videoId) {
        return !videoId.equals(playerResponse.getObject(VIDEO_DETAILS)
                .getString("videoId"));
    }

    private static boolean hasUsableStreamingData(@Nullable final JsonObject streamingData) {
        if (streamingData == null) {
            return false;
        }
        if (!isNullOrEmpty(streamingData.getString("dashManifestUrl"))
                || !isNullOrEmpty(streamingData.getString("hlsManifestUrl"))) {
            return true;
        }

        final JsonArray formats = streamingData.getArray(FORMATS);
        final JsonArray adaptiveFormats = streamingData.getArray(ADAPTIVE_FORMATS);
        return formats != null && !formats.isEmpty()
                || adaptiveFormats != null && !adaptiveFormats.isEmpty();
    }

    @Nonnull
    private JsonObject getVideoPrimaryInfoRenderer() {
        if (videoPrimaryInfoRenderer != null) {
            return videoPrimaryInfoRenderer;
        }

        videoPrimaryInfoRenderer = getVideoInfoRenderer("videoPrimaryInfoRenderer");
        return videoPrimaryInfoRenderer;
    }

    @Nonnull
    private JsonObject getVideoSecondaryInfoRenderer() {
        if (videoSecondaryInfoRenderer != null) {
            return videoSecondaryInfoRenderer;
        }

        videoSecondaryInfoRenderer = getVideoInfoRenderer("videoSecondaryInfoRenderer");
        return videoSecondaryInfoRenderer;
    }

    @Nonnull
    private JsonObject getVideoInfoRenderer(@Nonnull final String videoRendererName) {
        return nextResponse.getObject("contents")
                .getObject("twoColumnWatchNextResults")
                .getObject("results")
                .getObject("results")
                .getArray("contents")
                .stream()
                .filter(JsonObject.class::isInstance)
                .map(JsonObject.class::cast)
                .filter(content -> content.has(videoRendererName))
                .map(content -> content.getObject(videoRendererName))
                .findFirst()
                .orElse(new JsonObject());
    }
    @Nonnull
    private <T extends Stream> List<T> getItags(
            final String streamingDataKey,
            final ItagItem.ItagType itagTypeWanted,
            final java.util.function.Function<ItagInfo, T> builder,
            final StreamChoiceKind kind) throws ParsingException {
        try {
            final String videoId = getId();
            final List<ItagChoice<T>> candidates = new ArrayList<>();
            for (final Client client : clients) {
                ensureClientForStreams(client, streamingDataKey, itagTypeWanted);
                final String poToken = getStreamingUrlsPoToken(client);
                final boolean hasPlayerPoToken = hasPlayerPoToken(client);
                final List<T> streamList = getStreamsFromStreamingDataKey(
                        videoId,
                        getStreamingData(client),
                        streamingDataKey,
                        itagTypeWanted,
                        getContentPlaybackNonce(client),
                        poToken)
                        .map(builder)
                        .collect(Collectors.toCollection(ArrayList::new));
                if (!streamList.isEmpty()) {
                    candidates.add(new ItagChoice<>(
                            client,
                            streamList,
                            hasPlayerPoToken,
                            !isNullOrEmpty(poToken)));
                }
            }
            setItagChoices(kind, candidates);
            if (candidates.isEmpty()) {
                return new ArrayList<>();
            }
            return candidates.get(0).getStreams();
        } catch (final Exception e) {
            throw new ParsingException(
                    "Could not get " + kind.name().toLowerCase(Locale.ROOT).replace('_', '-')
                            + " streams", e);
        }
    }

    @SuppressWarnings("unchecked")
    private <T extends Stream> void setItagChoices(@Nonnull final StreamChoiceKind kind,
                                                   @Nonnull final List<ItagChoice<T>> candidates) {
        candidates.sort(itagComparator());
        switch (kind) {
            case AUDIO:
                audioChoices = (List<ItagChoice<AudioStream>>) (List<?>) new ArrayList<>(candidates);
                break;
            case VIDEO_ONLY:
                videoOnlyChoices = (List<ItagChoice<VideoStream>>) (List<?>) new ArrayList<>(candidates);
                break;
            case MUXED:
                muxedChoices = (List<ItagChoice<VideoStream>>) (List<?>) new ArrayList<>(candidates);
                break;
            default:
                break;
        }
    }

    @Nonnull
    private Comparator<ManifestChoice> manifestComparator(@Nonnull final ManifestKind manifestKind) {
        final List<Client> order = manifestClientOrder(manifestKind == ManifestKind.HLS);
        return Comparator
                .comparingInt((ManifestChoice choice) -> clientRank(choice.client, order))
                .thenComparing(ManifestChoice::hasStreamPoToken, Comparator.reverseOrder())
                .thenComparing(ManifestChoice::hasPlayerPoToken, Comparator.reverseOrder());
    }

    @Nonnull
    private <T extends Stream> Comparator<ItagChoice<T>> itagComparator() {
        final List<Client> order = buildClients();
        return Comparator
                .comparingInt((ItagChoice<T> choice) -> clientRank(choice.client, order))
                .thenComparing(ItagChoice::hasStreamPoToken, Comparator.reverseOrder())
                .thenComparing(ItagChoice::hasPlayerPoToken, Comparator.reverseOrder());
    }

    private int clientRank(@Nonnull final Client client, @Nonnull final List<Client> order) {
        final int index = order.indexOf(client);
        return index >= 0 ? index : order.size();
    }

    public static final class ManifestChoice {
        @Nonnull
        private final Client client;
        @Nonnull
        private final String url;
        private final boolean playerPoToken;
        private final boolean streamPoToken;

        private ManifestChoice(@Nonnull final Client client,
                               @Nonnull final String url,
                               final boolean playerPoToken,
                               final boolean streamPoToken) {
            this.client = client;
            this.url = url;
            this.playerPoToken = playerPoToken;
            this.streamPoToken = streamPoToken;
        }

        @Nonnull
        public String getClient() {
            return client.name();
        }

        @Nonnull
        public String getUrl() {
            return url;
        }

        public boolean hasPlayerPoToken() {
            return playerPoToken;
        }

        public boolean hasStreamPoToken() {
            return streamPoToken;
        }
    }

    public static final class ItagChoice<T extends Stream> {
        @Nonnull
        private final Client client;
        @Nonnull
        private final List<T> streams;
        private final boolean playerPoToken;
        private final boolean streamPoToken;

        private ItagChoice(@Nonnull final Client client,
                           @Nonnull final List<T> streams,
                           final boolean playerPoToken,
                           final boolean streamPoToken) {
            this.client = client;
            this.streams = streams;
            this.playerPoToken = playerPoToken;
            this.streamPoToken = streamPoToken;
        }

        @Nonnull
        public String getClient() {
            return client.name();
        }

        @Nonnull
        public List<T> getStreams() {
            return streams;
        }

        public boolean hasPlayerPoToken() {
            return playerPoToken;
        }

        public boolean hasStreamPoToken() {
            return streamPoToken;
        }
    }

    /**
     * Get the stream builder helper which will be used to build {@link AudioStream}s in
     * {@link #getItags(String, ItagItem.ItagType, java.util.function.Function, String)}
     *
     * <p>
     * The {@code StreamBuilderHelper} will set the following attributes in the
     * {@link AudioStream}s built:
     * <ul>
     *     <li>the {@link ItagItem}'s id of the stream as its id;</li>
     *     <li>{@link ItagInfo#getContent()} and {@link ItagInfo#getIsUrl()} as its content and
     *     as the value of {@code isUrl};</li>
     *     <li>the media format returned by the {@link ItagItem} as its media format;</li>
     *     <li>its average bitrate with the value returned by {@link
     *     ItagItem#getAverageBitrate()};</li>
     *     <li>the {@link ItagItem};</li>
     *     <li>the {@link DeliveryMethod#DASH DASH delivery method}, for OTF streams, live streams
     *     and ended streams.</li>
     * </ul>
     * </p>
     *
     * <p>
     * Note that the {@link ItagItem} comes from an {@link ItagInfo} instance.
     * </p>
     *
     * @return a stream builder helper to build {@link AudioStream}s
     */
    @Nonnull
    private java.util.function.Function<ItagInfo, AudioStream> getAudioStreamBuilderHelper() {
        return (itagInfo) -> {
            final ItagItem itagItem = itagInfo.getItagItem();
            final AudioStream.Builder builder = new AudioStream.Builder()
                    .setId(String.valueOf(itagItem.id))
                    .setContent(itagInfo.getContent(), itagInfo.getIsUrl())
                    .setMediaFormat(itagItem.getMediaFormat())
                    .setAverageBitrate(itagItem.getAverageBitrate())
                    .setAudioTrackId(itagItem.getAudioTrackId())
                    .setAudioTrackName(itagItem.getAudioTrackName())
                    .setAudioLocale(itagItem.getAudioLocale())
                    .setAudioTrackType(itagItem.getAudioTrackType())
                    .setItagItem(itagItem);

            if (streamType == StreamType.LIVE_STREAM
                    || streamType == StreamType.POST_LIVE_STREAM
                    || !itagInfo.getIsUrl()) {
                // For YouTube videos on OTF streams and for all streams of post-live streams
                // and live streams, only the DASH delivery method can be used.
                builder.setDeliveryMethod(DeliveryMethod.DASH);
            }

            return builder.build();
        };
    }

    /**
     * Get the stream builder helper which will be used to build {@link VideoStream}s in
     * {@link #getItags(String, ItagItem.ItagType, java.util.function.Function, String)}
     *
     * <p>
     * The {@code StreamBuilderHelper} will set the following attributes in the
     * {@link VideoStream}s built:
     * <ul>
     *     <li>the {@link ItagItem}'s id of the stream as its id;</li>
     *     <li>{@link ItagInfo#getContent()} and {@link ItagInfo#getIsUrl()} as its content and
     *     as the value of {@code isUrl};</li>
     *     <li>the media format returned by the {@link ItagItem} as its media format;</li>
     *     <li>whether it is video-only with the {@code areStreamsVideoOnly} parameter</li>
     *     <li>the {@link ItagItem};</li>
     *     <li>the resolution, by trying to use, in this order:
     *         <ol>
     *             <li>the height returned by the {@link ItagItem} + {@code p} + the frame rate if
     *             it is more than 30;</li>
     *             <li>the default resolution string from the {@link ItagItem};</li>
     *             <li>an empty string.</li>
     *         </ol>
     *     </li>
     *     <li>the {@link DeliveryMethod#DASH DASH delivery method}, for OTF streams, live streams
     *     and ended streams.</li>
     * </ul>
     *
     * <p>
     * Note that the {@link ItagItem} comes from an {@link ItagInfo} instance.
     * </p>
     *
     * @param areStreamsVideoOnly whether the stream builder helper will set the video
     *                            streams as video-only streams
     * @return a stream builder helper to build {@link VideoStream}s
     */
    @Nonnull
    private java.util.function.Function<ItagInfo, VideoStream> getVideoStreamBuilderHelper(
            final boolean areStreamsVideoOnly) {
        return (itagInfo) -> {
            final ItagItem itagItem = itagInfo.getItagItem();
            final VideoStream.Builder builder = new VideoStream.Builder()
                    .setId(String.valueOf(itagItem.id))
                    .setContent(itagInfo.getContent(), itagInfo.getIsUrl())
                    .setMediaFormat(itagItem.getMediaFormat())
                    .setIsVideoOnly(areStreamsVideoOnly)
                    .setItagItem(itagItem);

            final String resolutionString = itagItem.getResolutionString();
            builder.setResolution(resolutionString != null ? resolutionString
                    : "");

            if (streamType != StreamType.VIDEO_STREAM || !itagInfo.getIsUrl()) {
                // For YouTube videos on OTF streams and for all streams of post-live streams
                // and live streams, only the DASH delivery method can be used.
                builder.setDeliveryMethod(DeliveryMethod.DASH);
            }

            return builder.build();
        };
    }

    @Nonnull
    private java.util.stream.Stream<ItagInfo> getStreamsFromStreamingDataKey(
            final String videoId,
            final JsonObject streamingData,
            final String streamingDataKey,
            @Nonnull final ItagItem.ItagType itagTypeWanted,
            @Nonnull final String contentPlaybackNonce,
            @Nullable final String poToken) {
        if (streamingData == null || !streamingData.has(streamingDataKey)) {
            return java.util.stream.Stream.empty();
        }

        return streamingData.getArray(streamingDataKey).stream()
                .filter(JsonObject.class::isInstance)
                .map(JsonObject.class::cast)
                .map(formatData -> {
                    try {
                        final ItagItem itagItem = ItagItem.getItag(formatData.getInt("itag"));
                        if (itagItem.itagType == itagTypeWanted) {
                            return buildAndAddItagInfoToList(videoId, formatData, itagItem,
                                    itagItem.itagType, contentPlaybackNonce, poToken);
                        }
                    } catch (final ExtractionException ignored) {
                        // If the itag is not supported, the n parameter of HTML5 clients cannot be
                        // decoded or buildAndAddItagInfoToList fails, we end up here
                    }
                    return null;
                })
                .filter(Objects::nonNull);
    }

    private ItagInfo buildAndAddItagInfoToList(
            @Nonnull final String videoId,
            @Nonnull final JsonObject formatData,
            @Nonnull final ItagItem itagItem,
            @Nonnull final ItagItem.ItagType itagType,
            @Nonnull final String contentPlaybackNonce,
            @Nullable final String poToken) throws ExtractionException {
        String streamUrl;
        if (formatData.has("url")) {
            streamUrl = formatData.getString("url");
        } else {
            // This url has an obfuscated signature
            final String cipherString = formatData.getString(CIPHER,
                    formatData.getString(SIGNATURE_CIPHER));

            if (isNullOrEmpty(cipherString)) {
                return null;
            }

            final var cipher = Parser.compatParseMap(cipherString);
            final String signature = YoutubeJavaScriptPlayerManager.deobfuscateSignature(videoId,
                    cipher.getOrDefault("s", ""));
            streamUrl = cipher.get("url") + "&" + cipher.get("sp") + "=" + signature;
        }

        // Decode the n parameter if it is present
        // If it cannot be decoded, the stream cannot be used as streaming URLs return HTTP 403
        // responses if it has not the right value
        // Exceptions thrown by
        // YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated are so
        // propagated to the parent which ignores streams in this case
        streamUrl = YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(
                videoId, streamUrl);

        // Add the content playback nonce to the stream URL
        streamUrl += "&" + CPN + "=" + contentPlaybackNonce;

        // Add the poToken, if there is one
        if (poToken != null) {
            streamUrl += "&pot=" + poToken;
        }

        final JsonObject initRange = formatData.getObject("initRange");
        final JsonObject indexRange = formatData.getObject("indexRange");
        final String mimeType = formatData.getString("mimeType", "");
        final String codec = mimeType.contains("codecs")
                ? mimeType.split("\"")[1] : "";

        itagItem.setBitrate(formatData.getInt("bitrate"));
        itagItem.setWidth(formatData.getInt("width"));
        itagItem.setHeight(formatData.getInt("height"));
        itagItem.setInitStart(Integer.parseInt(initRange.getString("start", "-1")));
        itagItem.setInitEnd(Integer.parseInt(initRange.getString("end", "-1")));
        itagItem.setIndexStart(Integer.parseInt(indexRange.getString("start", "-1")));
        itagItem.setIndexEnd(Integer.parseInt(indexRange.getString("end", "-1")));
        itagItem.setQuality(formatData.getString("quality"));
        itagItem.setCodec(codec);
        itagItem.setIsDrc(formatData.getBoolean("isDrc", false));
        itagItem.setLastModified(Long.parseLong(formatData.getString("lastModified", "-1")));
        itagItem.setXtags(formatData.getString("xtags"));

        if (streamType == StreamType.LIVE_STREAM || streamType == StreamType.POST_LIVE_STREAM) {
            itagItem.setTargetDurationSec(formatData.getInt("targetDurationSec"));
        }

        if (itagType == ItagItem.ItagType.VIDEO || itagType == ItagItem.ItagType.VIDEO_ONLY) {
            itagItem.setFps(formatData.getInt("fps"));
        } else if (itagType == ItagItem.ItagType.AUDIO) {
            // YouTube return the audio sample rate as a string
            itagItem.setSampleRate(Integer.parseInt(formatData.getString("audioSampleRate")));
            itagItem.setAudioChannels(formatData.getInt("audioChannels",
                    // Most audio streams have two audio channels, so use this value if the real
                    // count cannot be extracted
                    // Doing this prevents an exception when generating the
                    // AudioChannelConfiguration element of DASH manifests of audio streams in
                    // YoutubeDashManifestCreatorUtils
                    2));

            final String audioTrackId = formatData.getObject("audioTrack")
                    .getString("id");
            if (!isNullOrEmpty(audioTrackId)) {
                itagItem.setAudioTrackId(audioTrackId);
                final int audioTrackIdLastLocaleCharacter = audioTrackId.indexOf(".");
                if (audioTrackIdLastLocaleCharacter != -1) {
                    // Audio tracks IDs are in the form LANGUAGE_CODE.TRACK_NUMBER
                    LocaleCompat.forLanguageTag(
                            audioTrackId.substring(0, audioTrackIdLastLocaleCharacter)
                    ).ifPresent(itagItem::setAudioLocale);
                }
                itagItem.setAudioTrackType(YoutubeParsingHelper.extractAudioTrackType(streamUrl));
            }

            itagItem.setAudioTrackName(formatData.getObject("audioTrack")
                    .getString("displayName"));
        }

        // YouTube return the content length and the approximate duration as strings
        itagItem.setContentLength(Long.parseLong(formatData.getString("contentLength",
                String.valueOf(CONTENT_LENGTH_UNKNOWN))));
        itagItem.setApproxDurationMs(Long.parseLong(formatData.getString("approxDurationMs",
                String.valueOf(APPROX_DURATION_MS_UNKNOWN))));

        final ItagInfo itagInfo = new ItagInfo(streamUrl, itagItem);

        if (streamType == StreamType.VIDEO_STREAM) {
            itagInfo.setIsUrl(!formatData.getString("type", "")
                    .equalsIgnoreCase("FORMAT_STREAM_TYPE_OTF"));
        } else {
            // We are currently not able to generate DASH manifests for running
            // livestreams, so because of the requirements of StreamInfo
            // objects, return these streams as DASH URL streams (even if they
            // are not playable).
            // Ended livestreams are returned as non URL streams
            itagInfo.setIsUrl(streamType != StreamType.POST_LIVE_STREAM);
        }

        return itagInfo;
    }

    /**
     * {@inheritDoc}
     * Should return a list of Frameset object that contains preview of stream frames
     *
     * <p><b>Warning:</b> When using this method be aware
     * that the YouTube API very rarely returns framesets,
     * that are slightly too small e.g. framesPerPageX = 5, frameWidth = 160, but the url contains
     * a storyboard that is only 795 pixels wide (5*160 &gt; 795). You will need to handle this
     * "manually" to avoid errors.</p>
     *
     * @see <a href="https://github.com/TeamNewPipe/NewPipe/pull/11596">
     *     TeamNewPipe/NewPipe#11596</a>
     */
    @Nonnull
    @Override
    public List<Frameset> getFrames() throws ExtractionException {
        try {
            final JsonObject storyboards = playerResponse.getObject("storyboards");
            final JsonObject storyboardsRenderer = storyboards.getObject(
                    storyboards.has("playerLiveStoryboardSpecRenderer")
                            ? "playerLiveStoryboardSpecRenderer"
                            : "playerStoryboardSpecRenderer"
            );

            if (storyboardsRenderer == null) {
                return Collections.emptyList();
            }

            final String storyboardsRendererSpec = storyboardsRenderer.getString("spec");
            if (storyboardsRendererSpec == null) {
                return Collections.emptyList();
            }

            final String[] spec = storyboardsRendererSpec.split("\\|");
            final String url = spec[0];
            final List<Frameset> result = new ArrayList<>(spec.length - 1);

            for (int i = 1; i < spec.length; ++i) {
                final String[] parts = spec[i].split("#");
                if (parts.length != 8 || Integer.parseInt(parts[5]) == 0) {
                    continue;
                }
                final int totalCount = Integer.parseInt(parts[2]);
                final int framesPerPageX = Integer.parseInt(parts[3]);
                final int framesPerPageY = Integer.parseInt(parts[4]);
                final String baseUrl = url.replace("$L", String.valueOf(i - 1))
                        .replace("$N", parts[6]) + "&sigh=" + parts[7];
                final List<String> urls;
                if (baseUrl.contains("$M")) {
                    final int totalPages = (int) Math.ceil(totalCount / (double)
                            (framesPerPageX * framesPerPageY));
                    urls = new ArrayList<>(totalPages);
                    for (int j = 0; j < totalPages; j++) {
                        urls.add(baseUrl.replace("$M", String.valueOf(j)));
                    }
                } else {
                    urls = Collections.singletonList(baseUrl);
                }
                result.add(new Frameset(
                        urls,
                        /*frameWidth=*/Integer.parseInt(parts[0]),
                        /*frameHeight=*/Integer.parseInt(parts[1]),
                        totalCount,
                        /*durationPerFrame=*/Integer.parseInt(parts[5]),
                        framesPerPageX,
                        framesPerPageY
                ));
            }
            return result;
        } catch (final Exception e) {
            throw new ExtractionException("Could not get frames", e);
        }
    }

    @Nonnull
    @Override
    public Privacy getPrivacy() {
        return playerMicroFormatRenderer.getBoolean("isUnlisted")
                || getVideoPrimaryInfoRenderer().getArray("badges")
                .streamAsJsonObjects()
                .anyMatch(badge ->
                        "PRIVACY_UNLISTED".equals(badge.getObject("metadataBadgeRenderer")
                                .getObject("icon")
                                .getString("iconType")))
                ? Privacy.UNLISTED
                : Privacy.PUBLIC;
    }

    @Nonnull
    @Override
    public String getCategory() {
        return playerMicroFormatRenderer.getString("category", "");
    }

    @Nonnull
    @Override
    public String getLicence() throws ParsingException {
        final JsonObject metadataRowRenderer = getVideoSecondaryInfoRenderer()
                .getObject("metadataRowContainer")
                .getObject("metadataRowContainerRenderer")
                .getArray("rows")
                .getObject(0)
                .getObject("metadataRowRenderer");

        final JsonArray contents = metadataRowRenderer.getArray("contents");
        final String license = getTextFromObject(contents.getObject(0));
        return license != null
                && "Licence".equals(getTextFromObject(metadataRowRenderer.getObject(TITLE)))
                ? license
                : "YouTube licence";
    }

    @Override
    public Locale getLanguageInfo() {
        return null;
    }

    @Nonnull
    @Override
    public List<String> getTags() {
        return JsonUtils.getStringListFromJsonArray(playerResponse.getObject(VIDEO_DETAILS)
                .getArray("keywords"));
    }

    @Nonnull
    @Override
    public List<StreamSegment> getStreamSegments() throws ParsingException {

        if (!nextResponse.has("engagementPanels")) {
            return Collections.emptyList();
        }

        final JsonArray segmentsArray = nextResponse.getArray("engagementPanels")
                .stream()
                // Check if object is a JsonObject
                .filter(JsonObject.class::isInstance)
                .map(JsonObject.class::cast)
                // Check if the panel is the correct one
                .filter(panel -> "engagement-panel-macro-markers-description-chapters".equals(
                        panel
                                .getObject("engagementPanelSectionListRenderer")
                                .getString("panelIdentifier")))
                // Extract the data
                .map(panel -> panel
                        .getObject("engagementPanelSectionListRenderer")
                        .getObject("content")
                        .getObject("macroMarkersListRenderer")
                        .getArray("contents"))
                .findFirst()
                .orElse(null);

        // If no data was found exit
        if (segmentsArray == null) {
            return Collections.emptyList();
        }

        final long duration = getLength();
        final List<StreamSegment> segments = new ArrayList<>();
        for (final JsonObject segmentJson : segmentsArray.stream()
                .filter(JsonObject.class::isInstance)
                .map(JsonObject.class::cast)
                .map(object -> object.getObject("macroMarkersListItemRenderer"))
                .collect(Collectors.toList())
        ) {
            final int startTimeSeconds = segmentJson.getObject("onTap")
                    .getObject("watchEndpoint").getInt("startTimeSeconds", -1);

            if (startTimeSeconds == -1) {
                throw new ParsingException("Could not get stream segment start time.");
            }
            if (startTimeSeconds > duration) {
                break;
            }

            final String title = getTextFromObject(segmentJson.getObject(TITLE));
            if (isNullOrEmpty(title)) {
                throw new ParsingException("Could not get stream segment title.");
            }

            final StreamSegment segment = new StreamSegment(title, startTimeSeconds);
            segment.setUrl(getUrl() + "?t=" + startTimeSeconds);
            if (segmentJson.has(THUMBNAIL)) {
                final JsonArray previewsArray = segmentJson.getObject(THUMBNAIL)
                        .getArray(THUMBNAILS);
                if (!previewsArray.isEmpty()) {
                    // Assume that the thumbnail with the highest resolution is at the last position
                    final String url = previewsArray
                            .getObject(previewsArray.size() - 1)
                            .getString("url");
                    segment.setPreviewUrl(fixThumbnailUrl(url));
                }
            }
            segments.add(segment);
        }
        return segments;
    }

    @Nonnull
    @Override
    public List<MetaInfo> getMetaInfo() throws ParsingException {
        return YoutubeMetaInfoHelper.getMetaInfo(nextResponse
                .getObject("contents")
                .getObject("twoColumnWatchNextResults")
                .getObject("results")
                .getObject("results")
                .getArray("contents"));
    }

    /**
     * Set the {@link PoTokenProvider} instance to be used for fetching {@code poToken}s.
     *
     * <p>
     * This method allows setting an implementation of {@link PoTokenProvider} which will be used
     * to obtain poTokens required for YouTube player requests and streaming URLs. These tokens
     * are used by YouTube to verify the integrity of the user's device or browser and are required
     * for playback with several clients.
     * </p>
     *
     * <p>
     * Without a {@link PoTokenProvider}, the extractor makes its best effort to fetch as many
     * streams as possible, but without {@code poToken}s, some formats may be not available or
     * fetching may be slower due to additional requests done to get streams.
     * </p>
     *
     * <p>
     * Note that any provider change will be only applied on the next {@link #fetchPage()} request.
     * </p>
     *
     * @param poTokenProvider the {@link PoTokenProvider} instance to set, which can be null to
     *                        remove a provider already passed
     * @see PoTokenProvider
     */
    @SuppressWarnings("unused")
    public static void setPoTokenProvider(@Nullable final PoTokenProvider poTokenProvider) {
        YoutubeStreamExtractor.poTokenProvider = poTokenProvider;
    }

    @SuppressWarnings("unused")
    public static void setClientProfileProvider(
            @Nullable final ClientProfileProvider clientProfileProvider) {
        YoutubeStreamExtractor.clientProfileProvider = clientProfileProvider;
    }

    /**
     * Set whether to fetch the iOS player responses.
     *
     * <p>
     * This method allows fetching the iOS player response, which can be useful in scenarios where
     * streams from the iOS player response are needed, especially HLS manifests and video-only
     * streams when YouTube returns SABR-only adaptive formats to Android and WEB clients.
     * </p>
     *
     * <p>
     * Note that at the time of writing, YouTube is rolling out a {@code poToken} requirement on
     * this client, formats from HLS manifests do not seem to be affected.
     * </p>
     *
     * @param fetchIosClient whether to fetch the iOS client
     */
    @SuppressWarnings("unused")
    public static void setFetchIosClient(final boolean fetchIosClient) {
        YoutubeStreamExtractor.fetchIosClient = fetchIosClient;
    }
}
