package com.github.surfstation.cammer;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.DateTime;
import com.google.api.client.util.store.DataStoreFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.youtube.YouTube;
import com.google.api.services.youtube.model.LiveBroadcast;
import com.google.api.services.youtube.model.LiveBroadcastContentDetails;
import com.google.api.services.youtube.model.LiveBroadcastListResponse;
import com.google.api.services.youtube.model.LiveBroadcastSnippet;
import com.google.api.services.youtube.model.LiveBroadcastStatus;
import com.google.api.services.youtube.model.PlaylistItem;
import com.google.api.services.youtube.model.PlaylistItemListResponse;
import com.google.api.services.youtube.model.PlaylistItemSnippet;
import com.google.api.services.youtube.model.ResourceId;

// 12 hour archive limit: https://support.google.com/youtube/answer/6247592?hl=en
// https://developers.google.com/youtube/v3/
// https://developers.google.com/youtube/v3/code_samples/java
// https://developers.google.com/youtube/v3/docs/playlists/update
// https://developers.google.com/youtube/v3/live/getting-started
// https://developers.google.com/api-client-library/java/
// https://github.com/youtube/api-samples
// https://developers.google.com/identity/protocols/OAuth2ServiceAccount
// https://developers.google.com/identity/protocols/googlescopes#youtubev3
// https://github.com/youtube/api-samples/blob/master/java/pom.xml
// https://developers.google.com/api-client-library/java/apis/youtube/v3
// https://developers.google.com/api-client-library/java/google-api-java-client/oauth2
// https://developers.google.com/youtube/registering_an_application

// cd /opt/surfstation/cammer && /usr/bin/java -cp .:lib/animal-sniffer-annotations-1.14.jar:lib/google-api-services-youtube-v3-rev20180511-1.28.0.jar:lib/google-oauth-client-java6-1.28.0.jar:lib/httpcore-4.4.9.jar:lib/opencensus-api-0.18.0.jar:lib/checker-compat-qual-2.5.2.jar:lib/google-http-client-1.28.0.jar:lib/google-oauth-client-jetty-1.28.0.jar:lib/j2objc-annotations-1.1.jar:lib/opencensus-contrib-http-util-0.18.0.jar:lib/commons-codec-1.10.jar:lib/google-http-client-apache-2.0.0.jar:lib/grpc-context-1.14.0.jar:lib/jackson-core-2.9.6.jar:lib/servlet-api-2.5-20081211.jar:lib/commons-logging-1.2.jar:lib/google-http-client-gson-1.28.0.jar:lib/gson-2.1.jar:lib/jetty-6.1.26.jar:lib/error_prone_annotations-2.1.3.jar:lib/google-http-client-jackson2-1.28.0.jar:lib/guava-26.0-android.jar:lib/jetty-util-6.1.26.jar:lib/google-api-client-1.28.0.jar:lib/google-oauth-client-1.28.0.jar:lib/httpclient-4.5.5.jar:lib/jsr305-3.0.2.jar com.github.surfstation.cammer.App /opt/surfstation/cammer/surfstationcam /opt/surfstation/cammer/client_secrets.json /opt/surfstation/cammer/generated/surfstationcam.js
public class App {
  public static void main(final String[] args) throws Exception {
    // authorize
    final HttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();
    final JsonFactory jsonFactory = GsonFactory.getDefaultInstance();
    final DataStoreFactory surfstationcamDatastore = new FileDataStoreFactory(new File(args[0]));
    final GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(jsonFactory, new InputStreamReader(new FileInputStream(new File(args[1]))));
    final GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(httpTransport, jsonFactory, clientSecrets, Collections.singleton("https://www.googleapis.com/auth/youtube")).setDataStoreFactory(surfstationcamDatastore).build();
    final Credential credential = new AuthorizationCodeInstalledApp(flow, new LocalServerReceiver()).authorize("user");
    // can now interact with the user's youtube objects.
    // user=surfstationcam for the 3rd st wavecam
    final YouTube youtube_surfstationcam = new YouTube.Builder(httpTransport, jsonFactory, credential).setApplicationName("cammer").build();
    transitionActiveBroadcastsToCompleted(youtube_surfstationcam);
    final String broadcastId = createBroadcast(youtube_surfstationcam);
    Files.write(Paths.get(args[2]), ("SurfStation.youtube_video_callback_surfstationcam({ \"videoId\": \"" + broadcastId + "\" });\n").getBytes(StandardCharsets.UTF_8));
    //new ProcessBuilder("systemctl", "stop", "youtube-live-stream.service").start().waitFor(60, TimeUnit.SECONDS);
    //Thread.sleep(5000L);
    //new ProcessBuilder("systemctl", "start", "youtube-live-stream.service").start().waitFor(60, TimeUnit.SECONDS);
    deletePriorLiveBroadcasts(youtube_surfstationcam); // this deletes the archived videos and we want to keep those for later viewing for some amount of time
    // will stop doing this later because i am not going to support using the playlist anymore
    clearPlaylist(youtube_surfstationcam, "PLJyLRh0ulkUdaPUMV3J_pA_OLphbxDGvJ");
    addBroadcastToPlaylist(youtube_surfstationcam, broadcastId, "PLJyLRh0ulkUdaPUMV3J_pA_OLphbxDGvJ");
  }

  public static List<String> clearPlaylist(final YouTube youtube, final String playlistId) throws IOException {
    final List<String> result = new LinkedList<String>();
    PlaylistItemListResponse items = null;
    do {
      final String pageToken = (null == items) ? null : items.getNextPageToken();
      items = youtube.playlistItems().list("id").setMaxResults(50L).setPlaylistId(playlistId).setPageToken(pageToken).execute();
      for (final PlaylistItem item : items.getItems()) {
        try {
          youtube.playlistItems().delete(item.getId()).execute();
          result.add(item.getId());
        } catch (final Exception e) {
          System.out.println(e.getMessage());
        }
      }
    } while (items.getNextPageToken() != null);
    return result;
  }

  public static List<String> transitionActiveBroadcastsToCompleted(final YouTube youtube) throws IOException {
    final List<String> result = new LinkedList<String>();
    LiveBroadcastListResponse broadcasts = null;
    do {
      final String pageToken = (null == broadcasts) ? null : broadcasts.getNextPageToken();
      broadcasts = youtube.liveBroadcasts().list("id").setBroadcastStatus("active").setMaxResults(50L).setPageToken(pageToken).execute();
      for (final LiveBroadcast broadcast : broadcasts.getItems()) {
        try {
          result.add(youtube.liveBroadcasts().transition("complete", broadcast.getId(), "id").execute().getId());
        } catch (final Exception e) {
          System.out.println(e.getMessage());
        }
      }
    } while (broadcasts.getNextPageToken() != null);
    return result;
  }

  public static List<String> deletePriorLiveBroadcasts(final YouTube youtube) throws IOException {
    final List<String> result = new LinkedList<String>();
    LiveBroadcastListResponse broadcasts = null;
    do {
      final String pageToken = (null == broadcasts) ? null : broadcasts.getNextPageToken();
      broadcasts = youtube.liveBroadcasts().list("id,snippet").setBroadcastStatus("completed").setMaxResults(50L).setPageToken(pageToken).execute();
      for (final LiveBroadcast broadcast : broadcasts.getItems()) {
        try {
          final long now = new Date().getTime();
          final long diff = now - broadcast.getSnippet().getActualEndTime().getValue();
          if (diff > (7L * 24L * 60L * 60L * 1000L)) { // delete videos more than a week old
            youtube.liveBroadcasts().delete("id").setId(broadcast.getId()).execute();
            result.add(broadcast.getId());
          }
        } catch (final Exception e) {
          System.out.println(e.getMessage());
        }
      }
    } while (broadcasts.getNextPageToken() != null);
    return result;
  }

  public static String createBroadcast(final YouTube youtube) throws IOException {
    final LiveBroadcastContentDetails details = new LiveBroadcastContentDetails();
    details.setEnableAutoStart(true);
    details.setEnableDvr(true);
    details.setEnableEmbed(true);
    details.setEnableLowLatency(true);
    details.setLatencyPreference("low");
    details.setEnableClosedCaptions(false);
    final LiveBroadcastSnippet snippet = new LiveBroadcastSnippet();
    final Instant now = Instant.now();
    final String datetime = DateTimeFormatter.ofPattern("EEE LLL dd hh:mma").withZone(ZoneId.of("America/New_York")).format(now);
    snippet.setTitle("The Surf Station 3rd St Wavecam - " + datetime);
    snippet.setDescription("St. Augustine, FL 32080. Recording started at about " + datetime + ".");
    snippet.setScheduledStartTime(new DateTime(now.getEpochSecond() * 1000L));
    snippet.setScheduledEndTime(new DateTime(now.getEpochSecond() * 1000L));
    final LiveBroadcastStatus status = new LiveBroadcastStatus();
    status.setPrivacyStatus("public");
    final LiveBroadcast broadcast = new LiveBroadcast();
    broadcast.setKind("youtube#liveBroadcast");
    broadcast.setSnippet(snippet);
    broadcast.setStatus(status);
    broadcast.setContentDetails(details);
    final LiveBroadcast result = youtube.liveBroadcasts().insert("snippet,contentDetails,status", broadcast).execute();
    return youtube.liveBroadcasts().bind(result.getId(), "id").setStreamId("GKOoJhHZiQhDyfvaZ8aAYw1548094394731553").execute().getId();
  }

  public static void addBroadcastToPlaylist(final YouTube youtube, final String broadcastId, final String playlistId) throws IOException {
    final ResourceId resourceId = new ResourceId();
    resourceId.set("kind", "youtube#video");
    resourceId.set("videoId", broadcastId);
    final PlaylistItemSnippet snippet = new PlaylistItemSnippet();
    snippet.setResourceId(resourceId);
    snippet.setPlaylistId(playlistId);
    final PlaylistItem playlistItem = new PlaylistItem();
    playlistItem.setSnippet(snippet);
    youtube.playlistItems().insert("snippet", playlistItem).execute();
  }
}