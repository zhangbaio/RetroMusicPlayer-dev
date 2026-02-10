/*
 * Copyright (c) 2019 Hemanth Savarala.
 *
 * Licensed under the GNU General Public License v3
 *
 * This is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by
 *  the Free Software Foundation either version 3 of the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 */
package code.name.monkey.retromusic.providers;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.provider.BaseColumns;
import android.provider.MediaStore.Audio.AudioColumns;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import code.name.monkey.retromusic.Constants;
import code.name.monkey.retromusic.model.SourceType;
import code.name.monkey.retromusic.model.Song;

/**
 * @author Andrew Neal, modified for Phonograph by Karim Abou Zeid
 *     <p>This keeps track of the music playback and history state of the playback service
 */
public class MusicPlaybackQueueStore extends SQLiteOpenHelper {

  public static final String DATABASE_NAME = "music_playback_state.db";

  public static final String PLAYING_QUEUE_TABLE_NAME = "playing_queue";

  public static final String ORIGINAL_PLAYING_QUEUE_TABLE_NAME = "original_playing_queue";

  private static final int VERSION = 13;

  private static final String COLUMN_QUEUE_POSITION = "queue_position";
  private static final String COLUMN_ALBUM_ARTIST = "album_artist";
  private static final String COLUMN_SOURCE_TYPE = "source_type";
  private static final String COLUMN_REMOTE_PATH = "remote_path";
  private static final String COLUMN_WEBDAV_CONFIG_ID = "webdav_config_id";
  private static final String COLUMN_WEBDAV_ALBUM_ART_PATH = "webdav_album_art_path";

  @Nullable private static MusicPlaybackQueueStore sInstance = null;

  /**
   * Constructor of <code>MusicPlaybackState</code>
   *
   * @param context The {@link Context} to use
   */
  public MusicPlaybackQueueStore(final @NonNull Context context) {
    super(context, DATABASE_NAME, null, VERSION);
  }

  /**
   * @param context The {@link Context} to use
   * @return A new instance of this class.
   */
  @NonNull
  public static synchronized MusicPlaybackQueueStore getInstance(@NonNull final Context context) {
    if (sInstance == null) {
      sInstance = new MusicPlaybackQueueStore(context.getApplicationContext());
    }
    return sInstance;
  }

  @Override
  public void onCreate(@NonNull final SQLiteDatabase db) {
    createTable(db, PLAYING_QUEUE_TABLE_NAME);
    createTable(db, ORIGINAL_PLAYING_QUEUE_TABLE_NAME);
  }

  @NonNull
  public List<Song> getSavedOriginalPlayingQueue() {
    return getQueue(ORIGINAL_PLAYING_QUEUE_TABLE_NAME);
  }

  @NonNull
  public List<Song> getSavedPlayingQueue() {
    return getQueue(PLAYING_QUEUE_TABLE_NAME);
  }

  @Override
  public void onDowngrade(@NonNull SQLiteDatabase db, int oldVersion, int newVersion) {
    // If we ever have downgrade, drop the table to be safe
    db.execSQL("DROP TABLE IF EXISTS " + PLAYING_QUEUE_TABLE_NAME);
    db.execSQL("DROP TABLE IF EXISTS " + ORIGINAL_PLAYING_QUEUE_TABLE_NAME);
    onCreate(db);
  }

  @Override
  public void onUpgrade(
      @NonNull final SQLiteDatabase db, final int oldVersion, final int newVersion) {
    // not necessary yet
    db.execSQL("DROP TABLE IF EXISTS " + PLAYING_QUEUE_TABLE_NAME);
    db.execSQL("DROP TABLE IF EXISTS " + ORIGINAL_PLAYING_QUEUE_TABLE_NAME);
    onCreate(db);
  }

  public synchronized void saveQueues(
      @NonNull final List<Song> playingQueue, @NonNull final List<Song> originalPlayingQueue) {
    saveQueue(PLAYING_QUEUE_TABLE_NAME, playingQueue);
    saveQueue(ORIGINAL_PLAYING_QUEUE_TABLE_NAME, originalPlayingQueue);
  }

  private void createTable(@NonNull final SQLiteDatabase db, final String tableName) {
    //noinspection StringBufferReplaceableByString
    StringBuilder builder = new StringBuilder();
    builder.append("CREATE TABLE IF NOT EXISTS ");
    builder.append(tableName);
    builder.append("(");

    builder.append(BaseColumns._ID);
    builder.append(" INT NOT NULL,");

    builder.append(COLUMN_QUEUE_POSITION);
    builder.append(" INT NOT NULL,");

    builder.append(AudioColumns.TITLE);
    builder.append(" STRING NOT NULL,");

    builder.append(AudioColumns.TRACK);
    builder.append(" INT NOT NULL,");

    builder.append(AudioColumns.YEAR);
    builder.append(" INT NOT NULL,");

    builder.append(AudioColumns.DURATION);
    builder.append(" LONG NOT NULL,");

    builder.append(Constants.DATA);
    builder.append(" STRING NOT NULL,");

    builder.append(AudioColumns.DATE_MODIFIED);
    builder.append(" LONG NOT NULL,");

    builder.append(AudioColumns.ALBUM_ID);
    builder.append(" INT NOT NULL,");

    builder.append(AudioColumns.ALBUM);
    builder.append(" STRING NOT NULL,");

    builder.append(AudioColumns.ARTIST_ID);
    builder.append(" INT NOT NULL,");

    builder.append(AudioColumns.ARTIST);
    builder.append(" STRING NOT NULL,");

    builder.append(AudioColumns.COMPOSER);
    builder.append(" STRING,");

    builder.append(COLUMN_ALBUM_ARTIST);
    builder.append(" STRING,");

    builder.append(COLUMN_SOURCE_TYPE);
    builder.append(" STRING NOT NULL,");

    builder.append(COLUMN_REMOTE_PATH);
    builder.append(" STRING,");

    builder.append(COLUMN_WEBDAV_CONFIG_ID);
    builder.append(" LONG,");

    builder.append(COLUMN_WEBDAV_ALBUM_ART_PATH);
    builder.append(" STRING);");

    db.execSQL(builder.toString());
  }

  @NonNull
  private List<Song> getQueue(@NonNull final String tableName) {
    final ArrayList<Song> queue = new ArrayList<>();
    Cursor cursor =
        getReadableDatabase()
            .query(tableName, null, null, null, null, null, COLUMN_QUEUE_POSITION + " ASC");
    if (cursor == null) {
      return queue;
    }
    try {
      if (cursor.moveToFirst()) {
        do {
          queue.add(songFromCursor(cursor));
        } while (cursor.moveToNext());
      }
    } finally {
      cursor.close();
    }
    return queue;
  }

  /**
   * Clears the existing database and saves the queue into the db so that when the app is restarted,
   * the tracks you were listening to is restored
   *
   * @param queue the queue to save
   */
  private synchronized void saveQueue(final String tableName, @NonNull final List<Song> queue) {
    final SQLiteDatabase database = getWritableDatabase();
    database.beginTransaction();

    try {
      database.delete(tableName, null, null);
      database.setTransactionSuccessful();
    } finally {
      database.endTransaction();
    }

    final int NUM_PROCESS = 20;
    int position = 0;
    while (position < queue.size()) {
      database.beginTransaction();
      try {
        for (int i = position; i < queue.size() && i < position + NUM_PROCESS; i++) {
          Song song = queue.get(i);
          ContentValues values = new ContentValues(18);

          values.put(BaseColumns._ID, song.getId());
          values.put(COLUMN_QUEUE_POSITION, i);
          values.put(AudioColumns.TITLE, song.getTitle());
          values.put(AudioColumns.TRACK, song.getTrackNumber());
          values.put(AudioColumns.YEAR, song.getYear());
          values.put(AudioColumns.DURATION, song.getDuration());
          values.put(Constants.DATA, song.getData());
          values.put(AudioColumns.DATE_MODIFIED, song.getDateModified());
          values.put(AudioColumns.ALBUM_ID, song.getAlbumId());
          values.put(AudioColumns.ALBUM, song.getAlbumName());
          values.put(AudioColumns.ARTIST_ID, song.getArtistId());
          values.put(AudioColumns.ARTIST, song.getArtistName());
          values.put(AudioColumns.COMPOSER, song.getComposer());
          values.put(COLUMN_ALBUM_ARTIST, song.getAlbumArtist());
          values.put(COLUMN_SOURCE_TYPE, song.getSourceType().name());
          values.put(COLUMN_REMOTE_PATH, song.getRemotePath());
          if (song.getWebDavConfigId() != null) {
            values.put(COLUMN_WEBDAV_CONFIG_ID, song.getWebDavConfigId());
          } else {
            values.putNull(COLUMN_WEBDAV_CONFIG_ID);
          }
          values.put(COLUMN_WEBDAV_ALBUM_ART_PATH, song.getWebDavAlbumArtPath());

          database.insert(tableName, null, values);
        }
        database.setTransactionSuccessful();
      } finally {
        database.endTransaction();
        position += NUM_PROCESS;
      }
    }
  }

  @NonNull
  private Song songFromCursor(@NonNull Cursor cursor) {
    final long id = getLong(cursor, BaseColumns._ID, -1L);
    final String title = getString(cursor, AudioColumns.TITLE, "");
    final int trackNumber = getInt(cursor, AudioColumns.TRACK, 0);
    final int year = getInt(cursor, AudioColumns.YEAR, 0);
    final long duration = getLong(cursor, AudioColumns.DURATION, 0L);
    final String data = getString(cursor, Constants.DATA, "");
    final long dateModified = getLong(cursor, AudioColumns.DATE_MODIFIED, 0L);
    final long albumId = getLong(cursor, AudioColumns.ALBUM_ID, 0L);
    final String albumName = getString(cursor, AudioColumns.ALBUM, "");
    final long artistId = getLong(cursor, AudioColumns.ARTIST_ID, 0L);
    final String artistName = getString(cursor, AudioColumns.ARTIST, "");
    final String composer = getNullableString(cursor, AudioColumns.COMPOSER);
    final String albumArtist = getNullableString(cursor, COLUMN_ALBUM_ARTIST);
    final SourceType sourceType =
        resolveSourceType(getNullableString(cursor, COLUMN_SOURCE_TYPE), data);
    final String remotePath =
        resolveRemotePath(sourceType, getNullableString(cursor, COLUMN_REMOTE_PATH), data);
    final Long webDavConfigId = getNullableLong(cursor, COLUMN_WEBDAV_CONFIG_ID);
    final String webDavAlbumArtPath = getNullableString(cursor, COLUMN_WEBDAV_ALBUM_ART_PATH);
    return new Song(
        id,
        title,
        trackNumber,
        year,
        duration,
        data,
        dateModified,
        albumId,
        albumName,
        artistId,
        artistName,
        composer,
        albumArtist,
        sourceType,
        remotePath,
        webDavConfigId,
        webDavAlbumArtPath);
  }

  @NonNull
  private SourceType resolveSourceType(@Nullable String rawValue, @NonNull String data) {
    if (rawValue != null) {
      try {
        return SourceType.valueOf(rawValue);
      } catch (IllegalArgumentException ignored) {
        // Fall back to URL sniffing for rows written before source_type existed.
      }
    }
    if (data.startsWith("http://") || data.startsWith("https://")) {
      return SourceType.SERVER;
    }
    return SourceType.LOCAL;
  }

  @Nullable
  private String resolveRemotePath(
      @NonNull SourceType sourceType, @Nullable String remotePath, @NonNull String data) {
    if (remotePath != null && !remotePath.trim().isEmpty()) {
      return remotePath;
    }
    if ((sourceType == SourceType.SERVER || sourceType == SourceType.WEBDAV)
        && (data.startsWith("http://") || data.startsWith("https://"))) {
      return data;
    }
    return null;
  }

  private int getInt(@NonNull Cursor cursor, @NonNull String columnName, int defaultValue) {
    final int index = cursor.getColumnIndex(columnName);
    if (index < 0 || cursor.isNull(index)) {
      return defaultValue;
    }
    return cursor.getInt(index);
  }

  private long getLong(@NonNull Cursor cursor, @NonNull String columnName, long defaultValue) {
    final int index = cursor.getColumnIndex(columnName);
    if (index < 0 || cursor.isNull(index)) {
      return defaultValue;
    }
    return cursor.getLong(index);
  }

  @Nullable
  private Long getNullableLong(@NonNull Cursor cursor, @NonNull String columnName) {
    final int index = cursor.getColumnIndex(columnName);
    if (index < 0 || cursor.isNull(index)) {
      return null;
    }
    return cursor.getLong(index);
  }

  @NonNull
  private String getString(
      @NonNull Cursor cursor, @NonNull String columnName, @NonNull String defaultValue) {
    final int index = cursor.getColumnIndex(columnName);
    if (index < 0 || cursor.isNull(index)) {
      return defaultValue;
    }
    final String value = cursor.getString(index);
    return value == null ? defaultValue : value;
  }

  @Nullable
  private String getNullableString(@NonNull Cursor cursor, @NonNull String columnName) {
    final int index = cursor.getColumnIndex(columnName);
    if (index < 0 || cursor.isNull(index)) {
      return null;
    }
    return cursor.getString(index);
  }
}
