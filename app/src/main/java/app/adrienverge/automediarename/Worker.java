/*
 * Copyright 2022 Adrien Vergé
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package app.adrienverge.automediarename;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.ListenableWorker.Result;
import androidx.work.WorkerParameters;

import com.android.camera.exif.ExifInterface;

public class Worker extends androidx.work.Worker {

  private static final String TAG = "automediarename";
  private static final int NOTIFICATION_ID = 1;
  private static final String FILE_TEMP_SUFFIX = "_automediarename_temp.jpg";
  private static final String FILE_BACKUP_SUFFIX = "_automediarename_backup.jpg";

  private Context context;
  private ContentResolver contentResolver;
  private Config config;

  private long minimumTimestampFilterInMillis;
  private long maximumTimestampFilterInMillis;

  public Worker(@NonNull Context context,
      @NonNull WorkerParameters workerParams) {
    super(context, workerParams);
    this.context = context;
    this.contentResolver = context.getContentResolver();
    this.config = Config.getInstance(context);
  }

  @NonNull
  @Override
  public Result doWork() {
    Log.i(TAG, "Starting work…");
    sendNotification("Auto Media Rename", "Looking for new images…");
    Logger.getInstance(context).addLine("Starting worker…");

    Uri uri = Uri.parse(config.getMediaDirectory());
    minimumTimestampFilterInMillis = config.getMinimumTimestamp();
    // Set maximumTimestampFilterInMillis in the past to make sure we don't
    // touch a picture that has just been saved and is potentially still beeing
    // processed by another app.
    Calendar calendar = Calendar.getInstance();
    calendar.setTime(new Date());
    calendar.add(Calendar.MINUTE, -10);
    maximumTimestampFilterInMillis = calendar.getTimeInMillis();

    int noProcessedFiles = traverseDirectoryEntries(uri);
    Logger.getInstance(context).addLine("Worker found " + noProcessedFiles + " images to process.");

    Log.i(TAG, "Finished work.");
    removeNotification();

    // Now that we've processed all files, reset the minimum timestamp, to avoid
    // processing old files on next run. But let a 24-hour window, in case of
    // time zone shift.
    calendar.setTime(new Date());
    calendar.add(Calendar.DAY_OF_MONTH, -1);
    long newMinimumTimestampFilterInMillis = calendar.getTimeInMillis();
    newMinimumTimestampFilterInMillis = Math.max(
        newMinimumTimestampFilterInMillis, minimumTimestampFilterInMillis);
    config.setMinimumTimestamp(newMinimumTimestampFilterInMillis);
    config.save();

    return Result.success();
  }

  private void sendNotification(String title, String message) {
    NotificationManager notificationManager =
        (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

    notificationManager.createNotificationChannel(
        new NotificationChannel("default", "Default",
            NotificationManager.IMPORTANCE_LOW));

    NotificationCompat.Builder notification =
        new NotificationCompat.Builder(context, "default")
        .setContentTitle(title)
        .setContentText(message)
        .setSmallIcon(R.mipmap.ic_launcher);

    notificationManager.notify(NOTIFICATION_ID, notification.build());
  }

  private void removeNotification() {
    NotificationManager notificationManager =
        (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

    notificationManager.cancel(NOTIFICATION_ID);
  }

  private int traverseDirectoryEntries(Uri rootUri) {
    int ret = 0;

    Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
        rootUri, DocumentsContract.getTreeDocumentId(rootUri));

    // Keep track of our directory hierarchy
    List<Uri> dirNodes = new LinkedList<>();
    dirNodes.add(childrenUri);

    while (!dirNodes.isEmpty()) {
      childrenUri = dirNodes.remove(0); // get the item from top

      final String[] projection = {
          Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
          Document.COLUMN_MIME_TYPE,
          Document.COLUMN_LAST_MODIFIED};
      Cursor c = contentResolver.query(
          childrenUri, projection,
          // Here, it would be great for performance to filter the SQL selection
          // based on MIME types and last modified dates, e.g.
          //     Document.COLUMN_LAST_MODIFIED + " > ?",
          // but unfortunately it's not possible to filter with
          // DocumentsProvider: https://stackoverflow.com/a/61214849
          // So we need to get a big batch of results and filter them ourselves.
          null, null, null);

      try {
        while (c.moveToNext()) {
          final String docId = c.getString(0);
          final String name = c.getString(1);
          final String mimeType = c.getString(2);
          final long lastModified = c.getLong(3);
          if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
            dirNodes.add(DocumentsContract.buildChildDocumentsUriUsingTree(
                rootUri, docId));
          } else if (lastModified < minimumTimestampFilterInMillis) {
            continue;
          } else if (lastModified > maximumTimestampFilterInMillis) {
            continue;
          } else if (name.endsWith(FILE_TEMP_SUFFIX) ||
              name.endsWith(FILE_BACKUP_SUFFIX)) {
            continue;
          } else {
            for (Config.Selection selection : config.getSelections()) {
              if (selection.pattern.matcher(name).matches()) {
                Log.d(TAG, "Found matching document: docId: " + docId +
                    ", name: " + name + ", mimeType: " + mimeType +
                    ", lastModified: " + Long.toString(lastModified));
                String newName = selection.prefix + name;
                processFile(rootUri, docId, name, newName, mimeType);
                ret++;
                break; // make sure we don't apply two rules on the same file
              }
            }
          }
        }
      } finally {
        if (c != null) {
          try {
            c.close();
          } catch (RuntimeException re) {
            throw re;
          } catch (Exception ignore) {
            // ignore exception
          }
        }
      }
    }

    return ret;
  }

  private void processFile(Uri rootUri, String docId, String name,
      String newName, String mimeType) {
    Uri originalUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, docId);
    Uri compressedUri = null;

    if ("image/jpeg".equals(mimeType)) {
      byte[] compressedJpeg = compressJpegFile(originalUri, name);

      if (compressedJpeg != null) {
        OutputStream outputStream = null;
        try {
          Uri parentDocumentUri = DocumentsContract.buildDocumentUriUsingTree(
              rootUri, new File(docId).getParent());
          compressedUri = DocumentsContract.createDocument(contentResolver,
              parentDocumentUri, mimeType, name + FILE_TEMP_SUFFIX);
          outputStream = contentResolver.openOutputStream(compressedUri);
          outputStream.write(compressedJpeg, 0, compressedJpeg.length);
          outputStream.close();
        } catch (FileNotFoundException e) {
          e.printStackTrace();
        } catch (IOException e) {
          Log.e(TAG, "IOException: " + e.toString());
          e.printStackTrace();
        } finally {
          if (outputStream != null) {
            try {
              outputStream.close();
            } catch (IOException e) {}
          }
        }

        if (config.getJpegCompressionCopyTimestamps()) {
          try {
            // Thanks to this post: https://stackoverflow.com/a/66681306
            Path originalPath = Paths.get(
                FileUtil.getFullDocIdPathFromTreeUri(originalUri, context));
            Path newPath = Paths.get(
                FileUtil.getFullDocIdPathFromTreeUri(compressedUri, context));
            BasicFileAttributes attrs = Files.readAttributes(
              originalPath, BasicFileAttributes.class);
            Files.getFileAttributeView(newPath, BasicFileAttributeView.class)
              .setTimes(attrs.lastModifiedTime(), attrs.lastAccessTime(),
                  attrs.creationTime());
          } catch (Exception e) {
            Log.e(TAG, "Exception: " + e.toString());
            Logger.getInstance(context).addLine(
                "Could not set file creation and last modification dates for " +
                "\"" + name + "\": " + e.toString());
          }
        }
      }
    }

    // If the file wasn't recompressed or if the compressed version isn't small
    // enough, simply rename the file. Otherwise, make a backup of the original
    // file and give its name to the new compressed one. For safety, steps are
    // run in this order:
    // - mv original.jpg original_automediarename_backup.jpg
    // - mv _automediarename_temp.jpg original.jpg
    // - rm original_automediarename_backup.jpg
    if (compressedUri != null) {
        try {
          Uri backupUri = DocumentsContract.renameDocument(contentResolver,
              originalUri, name + FILE_BACKUP_SUFFIX);
          DocumentsContract.renameDocument(contentResolver, compressedUri, newName);
          if (!config.getJpegCompressionKeepBackup()) {
            DocumentsContract.deleteDocument(contentResolver, backupUri);
          }
        } catch (FileNotFoundException e) {
          Log.e(TAG, "FileNotFoundException: " + originalUri);
        }
    } else if (!name.equals(newName)) {
      Logger.getInstance(context).addLine("Renaming \"" + name + "\"…");
      try {
        DocumentsContract.renameDocument(contentResolver, originalUri, newName);
      } catch (FileNotFoundException e) {
        Log.e(TAG, "FileNotFoundException: " + originalUri);
      }
    }
  }

  private byte[] compressJpegFile(Uri originalUri, String name) {
    InputStream inputStream = null;
    ByteArrayOutputStream tempStream = null;

    try {
      inputStream = contentResolver.openInputStream(originalUri);
      tempStream = new ByteArrayOutputStream();
      byte[] buf = new byte[8192];
      int length;
      while ((length = inputStream.read(buf)) != -1) {
        tempStream.write(buf, 0, length);
      }
      byte[] originalBytes = tempStream.toByteArray();
      int originalFileSize = originalBytes.length;

      Bitmap bitmap = BitmapFactory.decodeByteArray(
          originalBytes, 0, originalBytes.length);
      inputStream.close();

      tempStream = new ByteArrayOutputStream();
      bitmap.compress(Bitmap.CompressFormat.JPEG,
          config.getJpegCompressionQuality(), tempStream);
      tempStream.close();

      inputStream = contentResolver.openInputStream(originalUri);
      ExifInterface originalExif = new ExifInterface();
      originalExif.readExif(inputStream);
      inputStream.close();
      inputStream = new ByteArrayInputStream(tempStream.toByteArray());
      tempStream = new ByteArrayOutputStream();
      originalExif.writeExif(inputStream, tempStream);
      inputStream.close();
      tempStream.close();

      byte[] compressedBytes = tempStream.toByteArray();
      float ratio = (float) compressedBytes.length / (float) originalFileSize;

      if (ratio < config.getJpegCompressionOverwriteRatio()) {
        Logger.getInstance(context).addLine(
            "Compressing \"" + name + "\": " + Math.round(100 * ratio) + "% " +
            "→ keep");
        return compressedBytes;
      } else {
        Logger.getInstance(context).addLine(
            "Compressing \"" + name + "\": " + Math.round(100 * ratio) + "% " +
            "→ discard");
        return null;
      }

    } catch (FileNotFoundException e) {
      Log.e(TAG, "Cannot open " + originalUri);
      e.printStackTrace();
    } catch (IOException e) {
      Log.e(TAG, "IOException: " + e.toString());
      e.printStackTrace();
    } finally {
      if (inputStream != null) {
        try {
          inputStream.close();
        } catch (IOException e) {}
      }
      if (tempStream != null) {
        try {
          tempStream.close();
        } catch (IOException e) {}
      }
    }

    Logger.getInstance(context).addLine(
        "Error compressing \"" + name + "\"");
    return null;
  }
}

class FileUtil {
  private static final String PRIMARY_VOLUME_NAME = "primary";

  static boolean hasAccessToFullPaths(String testUri, Context context) {
    try {
      String fullPath = rootUriToFullPath(testUri, context);
      Calendar calendar = Calendar.getInstance();
      calendar.setTime(new Date());
      File file = new File(
          fullPath + "/automediarename_test_" + calendar.getTimeInMillis());
      calendar.add(Calendar.MINUTE, -10);
      FileTime timestamp = FileTime.fromMillis(calendar.getTimeInMillis());
      try {
        file.createNewFile();
        Files.getFileAttributeView(file.toPath(), BasicFileAttributeView.class)
          .setTimes(timestamp, timestamp, timestamp);
      } finally {
        file.delete();
      }
      return fullPath != null && fullPath.startsWith("/");
    } catch (Exception e) {
      return false;
    }
  }

  static String rootUriToFullPath(String rootUri, Context context) {
    Uri uri = Uri.parse(rootUri);
    uri = DocumentsContract.buildDocumentUriUsingTree(
        uri, DocumentsContract.getTreeDocumentId(uri));
    return FileUtil.getFullDocIdPathFromTreeUri(uri, context);
  }

  static String getFullDocIdPathFromTreeUri(final Uri treeUri,
      Context context) {
    if (treeUri == null) return null;
    String volumePath = getVolumePath(getVolumeIdFromTreeUri(treeUri), context);
    if (volumePath == null) return File.separator;
    if (volumePath.endsWith(File.separator))
      volumePath = volumePath.substring(0, volumePath.length() - 1);

    String documentPath = getDocumentPathFromTreeUri(treeUri);
    if (documentPath.endsWith(File.separator))
      documentPath = documentPath.substring(0, documentPath.length() - 1);

    if (documentPath.length() > 0) {
      if (documentPath.startsWith(File.separator))
        return volumePath + documentPath;
      else
        return volumePath + File.separator + documentPath;
    }
    else return volumePath;
  }

  private static String getVolumePath(final String volumeId, Context context) {
    try {
      StorageManager mStorageManager =
        (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
      for (StorageVolume vol : mStorageManager.getStorageVolumes()) {
        // primary volume?
        if (vol.isPrimary() && PRIMARY_VOLUME_NAME.equals(volumeId))
          return vol.getDirectory().toString();

        // other volumes?
        String uuid = vol.getUuid();
        if (uuid != null && uuid.equals(volumeId))
          return vol.getDirectory().toString();
      }
      return null;
    } catch (Exception e) {
      return null;
    }
  }

  private static String getVolumeIdFromTreeUri(final Uri treeUri) {
    final String docId = DocumentsContract.getTreeDocumentId(treeUri);
    final String[] split = docId.split(":");
    if (split.length > 0) return split[0];
    else return null;
  }

  private static String getDocumentPathFromTreeUri(final Uri treeUri) {
    final String docId = DocumentsContract.getDocumentId(treeUri);
    final String[] split = docId.split(":");
    if ((split.length >= 2) && (split[1] != null)) return split[1];
    else return File.separator;
  }
}
