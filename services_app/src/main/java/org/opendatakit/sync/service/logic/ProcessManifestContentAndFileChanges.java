/*
 * Copyright (C) 2016 University of Washington
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.opendatakit.sync.service.logic;

import android.os.RemoteException;
import org.opendatakit.aggregate.odktables.rest.entity.*;
import org.opendatakit.common.android.utilities.ODKFileUtils;
import org.opendatakit.common.android.utilities.WebLogger;
import org.opendatakit.common.android.utilities.WebLoggerIf;
import org.opendatakit.services.R;
import org.opendatakit.sync.service.SyncAttachmentState;
import org.opendatakit.sync.service.SyncExecutionContext;
import org.opendatakit.sync.service.SyncProgressState;
import org.opendatakit.sync.service.data.SyncRowPending;
import org.opendatakit.sync.service.exceptions.*;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.*;

/**
 * Extraction of manifest and file-update logic for app-level and table-level config files
 * and also for row-level attachments.
 *
 * @author the.dylan.price@gmail.com
 * @author sudar.sam@gmail.com
 * @author mitchellsundt@gmail.com
 *
 */
public class ProcessManifestContentAndFileChanges {

  private static final String LOGTAG = ProcessManifestContentAndFileChanges.class.getSimpleName();

  /**
   * Maximum number of bytes to put within one bulk upload/download request for
   * row-level instance files.
   */
  public static final long MAX_BATCH_SIZE = 10485760;


  private SyncExecutionContext sc;
  private final WebLoggerIf log;

  public ProcessManifestContentAndFileChanges(SyncExecutionContext sc) {
    this.sc = sc;
    this.log = WebLogger.getLogger(sc.getAppName());
  }

  /**********************************************************************************
   *
   * Complex interactions using the above simple interactions.
   **********************************************************************************/

  /**
   *
   * @return
   */
  private List<String> getAppLevelFiles() {
    File baseFolder = new File(ODKFileUtils.getAppFolder(sc.getAppName()));

    // Return an empty list of the folder doesn't exist or is not a directory
    if (!baseFolder.exists()) {
      return new ArrayList<String>();
    } else if (!baseFolder.isDirectory()) {
      log.e(LOGTAG, "[getAppLevelFiles] application folder is not a directory: " +
          baseFolder.getAbsolutePath());
      return new ArrayList<String>();
    }

    baseFolder = new File(ODKFileUtils.getConfigFolder(sc.getAppName()));
    // Return an empty list of the folder doesn't exist or is not a directory
    if (!baseFolder.exists()) {
      return new ArrayList<String>();
    } else if (!baseFolder.isDirectory()) {
      log.e(LOGTAG, "[getAppLevelFiles] config folder is not a directory: " +
          baseFolder.getAbsolutePath());
      return new ArrayList<String>();
    }

    LinkedList<File> unexploredDirs = new LinkedList<File>();
    List<String> relativePaths = new ArrayList<String>();
    
    unexploredDirs.add(baseFolder);

    boolean haveFilteredTablesDir = false;
    boolean haveFilteredAssetsCsvDir = false;
    boolean haveFilteredTableInitFile = false;
    
    while (!unexploredDirs.isEmpty()) {
      File exploring = unexploredDirs.removeFirst();
      File[] files = exploring.listFiles();
      for (File f : files) {
        if (f.isDirectory()) {

          // ignore the config/tables dir
          if ( !haveFilteredTablesDir ) {
            File tablesDir = new File(ODKFileUtils.getTablesFolder(sc.getAppName()));
            if ( f.equals(tablesDir) ) {
              haveFilteredTablesDir = true;
              continue;
            }
          }
          // ignore the config/assets/csv dir
          if ( !haveFilteredAssetsCsvDir ) {
            File csvDir = new File(ODKFileUtils.getAssetsCsvFolder(sc.getAppName()));
            if ( f.equals(csvDir) ) {
              haveFilteredAssetsCsvDir = true;
              continue;
            }
          }

          // we'll need to explore it
          unexploredDirs.add(f);
        } else {

          // ignore the config/assets/tables.init file -- never sync'd to server...
          if ( !haveFilteredTableInitFile ) {
            File tablesInitFile = new File(ODKFileUtils.getTablesInitializationFile(sc.getAppName()));
            if ( f.equals(tablesInitFile) ) {
              haveFilteredTableInitFile = true;
              continue;
            }
          }

          // we'll add it to our list of files.
          relativePaths.add(ODKFileUtils.asRelativePath(sc.getAppName(), f));
        }
      }
    }

    return relativePaths; 
  }

  private static List<String> filterInTableIdFiles(List<String> relativePaths, String tableId) {
    List<String> newList = new ArrayList<String>();
    for (String relativePath : relativePaths) {
      if (relativePath.startsWith("config/assets/csv/")) {
        // by convention, the files here begin with their identifying tableId
        // and the directory matches the tableId if there are media attachments for that tableId
        String[] parts = relativePath.split("/");
        if (parts.length >= 4) {
          if ( parts[3].equals(tableId) ) {
            // directory...
            newList.add(relativePath);
          } else {
            String[] nameElements = parts[3].split("\\.");
            // .csv or .qualifier.csv file for tableId
            if (nameElements[0].equals(tableId)) {
              newList.add(relativePath);
            }
          }
        }
      }
    }
    return newList;
  }

  /**
   * Get all the files under the given folder, excluding those directories that
   * are the concatenation of folder and a member of excluding. If the member of
   * excluding is a directory, none of its children will be reported.
   * <p>
   * If the baseFolder doesn't exist it returns an empty list.
   * <p>
   * If the baseFolder exists but is not a directory, logs an error and returns an
   * empty list.
   * 
   * @param baseFolder
   * @param excludingNamedItemsUnderFolder
   *          can be null--nothing will be excluded. Should be relative to the
   *          given folder.
   * @return list of app-relative paths of the files and directories that were found.
   */
  private List<String> getAllFilesUnderFolder(File baseFolder,
      final Set<String> excludingNamedItemsUnderFolder) {
    String appName = ODKFileUtils.extractAppNameFromPath(baseFolder);

    // Return an empty list of the folder doesn't exist or is not a directory
    if (!baseFolder.exists()) {
      return new ArrayList<String>();
    } else if (!baseFolder.isDirectory()) {
      log.e(LOGTAG, "[getAllFilesUnderFolder] folder is not a directory: " + baseFolder.getAbsolutePath());
      return new ArrayList<String>();
    }

    // construct the set of starting directories and files to process
    File[] partials = baseFolder.listFiles(new FileFilter() {
      @Override
      public boolean accept(File pathname) {
        if (excludingNamedItemsUnderFolder == null) {
          return true;
        } else {
          return !excludingNamedItemsUnderFolder.contains(pathname.getName());
        }
      }
    });

    if (partials == null) {
      return Collections.emptyList();
    }

    LinkedList<File> unexploredDirs = new LinkedList<File>();
    List<File> nondirFiles = new ArrayList<File>();

    // copy the starting set into a queue of unexploredDirs
    // and a list of files to be sync'd
    for (int i = 0; i < partials.length; ++i) {
      if (partials[i].isDirectory()) {
        unexploredDirs.add(partials[i]);
      } else {
        nondirFiles.add(partials[i]);
      }
    }

    while (!unexploredDirs.isEmpty()) {
      File exploring = unexploredDirs.removeFirst();
      File[] files = exploring.listFiles();
      for (File f : files) {
        if (f.isDirectory()) {
          // we'll need to explore it
          unexploredDirs.add(f);
        } else {
          // we'll add it to our list of files.
          nondirFiles.add(f);
        }
      }
    }

    List<String> relativePaths = new ArrayList<String>();
    // we want the relative path, so drop the necessary bets.
    for (File f : nondirFiles) {
      // +1 to exclude the separator.
      relativePaths.add(ODKFileUtils.asRelativePath(sc.getAppName(), f));
    }
    return relativePaths;
  }

  /**
   * This may complete (successfully) but leave config files on the SDCard that are not
   * being used with the new config due to activity stacks perhaps holding those files open.
   *
   * We do not care about these extraneous files and ignore deleteFile failures. The next
   * material update of the config will presumably clean these up.
   *
   * @param pushLocalFiles
   * @param serverReportedAppLevelETag
   * @param syncStatus
   * @throws HttpClientWebException
   * @throws IOException
   * @throws RemoteException
   */
  public void syncAppLevelFiles(boolean pushLocalFiles, String serverReportedAppLevelETag,
      Synchronizer.SynchronizerStatus syncStatus)
      throws HttpClientWebException, IOException, RemoteException {
    // Get the app-level files on the server.
    syncStatus.updateNotification(SyncProgressState.APP_FILES, R.string
            .sync_getting_app_level_manifest,
        null, 1.0, false);
    FileManifestDocument manifestDocument =
        sc.getSynchronizer().getAppLevelFileManifest(pushLocalFiles, serverReportedAppLevelETag);

    if (manifestDocument == null) {
      log.i(LOGTAG, "no change in app-level manifest -- skipping!");
      // short-circuited -- no change in manifest
      syncStatus.updateNotification(SyncProgressState.APP_FILES,
          R.string.sync_getting_app_level_manifest, null, 100.0, false);
      return;
    }

    // Get the app-level files on our device.
    List<String> relativePathsOnDevice = getAppLevelFiles();

    double stepSize = 100.0 / (1 + relativePathsOnDevice.size() + manifestDocument.entries.size());
    int stepCount = 1;

    boolean deviceAndServerEntirelyMatch = true;
    if (pushLocalFiles) {
      // if we are pushing, we want to push the local files that are different
      // up to the server, then remove the files on the server that are not
      // in the local set.
      List<File> serverFilesToDelete = new ArrayList<File>();

      for (OdkTablesFileManifestEntry entry : manifestDocument.entries) {
        File localFile = ODKFileUtils.asConfigFile(sc.getAppName(), entry.filename);
        if (!localFile.exists() || !localFile.isFile()) {
          // we need to delete this file from the server.
          serverFilesToDelete.add(localFile);
        } else if (ODKFileUtils.getMd5Hash(sc.getAppName(), localFile).equals(entry.md5hash)) {
          // we are ok -- no need to upload or delete
          relativePathsOnDevice.remove(ODKFileUtils.asRelativePath(sc.getAppName(), localFile));
        }
      }

      // this is the actual step size when we are pushing...
      stepSize = 100.0 / (1 + relativePathsOnDevice.size() + serverFilesToDelete.size());

      for (String relativePath : relativePathsOnDevice) {

        syncStatus.updateNotification(SyncProgressState.APP_FILES, R.string
                .sync_uploading_local_file,
            new Object[] { relativePath }, stepCount * stepSize, false);

        File localFile = ODKFileUtils.asAppFile(sc.getAppName(), relativePath);

        sc.getSynchronizer().uploadConfigFile(localFile);

        ++stepCount;
      }

      for (File localFile : serverFilesToDelete) {

        String relativePath = ODKFileUtils.asRelativePath(sc.getAppName(), localFile);
        syncStatus.updateNotification(SyncProgressState.APP_FILES,
            R.string.sync_deleting_file_on_server,
            new Object[] { relativePath }, stepCount * stepSize, false);

        sc.getSynchronizer().deleteConfigFile(localFile);

        ++stepCount;
      }

    } else {
      // if we are pulling, we want to pull the server files that are different
      // down from the server, then remove the local files that are not present
      // on the server.

      for (OdkTablesFileManifestEntry entry : manifestDocument.entries) {
        File localFile = ODKFileUtils.asConfigFile(sc.getAppName(), entry.filename);
        String relativePath = ODKFileUtils.asRelativePath(sc.getAppName(), localFile);

        syncStatus.updateNotification(SyncProgressState.APP_FILES,
            R.string.sync_verifying_local_file,
            new Object[] { relativePath }, stepCount * stepSize, false);

        // make sure our copy is current
        compareAndDownloadConfigFile(null, entry, localFile);
        // remove it from the set of app-level files we found before the sync
        relativePathsOnDevice.remove(relativePath);

        // this is the corrected step size based upon matching files
        stepSize = 100.0 / (1 + relativePathsOnDevice.size() + manifestDocument.entries.size());

        ++stepCount;
      }

      for (String relativePath : relativePathsOnDevice) {

        syncStatus.updateNotification(SyncProgressState.APP_FILES,
            R.string.sync_deleting_local_file,
            new Object[] { relativePath }, stepCount * stepSize, false);

        // and remove any remaining files, as these do not match anything on
        // the server.
        File localFile = ODKFileUtils.asAppFile(sc.getAppName(), relativePath);
        if (!localFile.delete()) {
          // this is a benign error. Hopefully on the next reload of the app,
          // whatever was referencing/holding this file handle will no longer
          // be holding it and we will be able to delete it.
          log.e(LOGTAG, "Unable to delete " + localFile.getAbsolutePath());
          deviceAndServerEntirelyMatch = false;
        }

        ++stepCount;
      }
    }

    if ( deviceAndServerEntirelyMatch ) {

      // Update the ETag for the manifest so that we can detect a no-file-changes state
      // and minimize the bytes across the wire.
      //
      // Use the matching of the ETag to indicate the device and server content exactly match.
      try {
        sc.getSynchronizer().updateManifestSyncETag(null, manifestDocument.eTag);
      } catch (RemoteException e) {
        log.e(LOGTAG, "Error while trying to update the manifest sync etag");
        log.printStackTrace(e);
      }

    }
  }

  /**
   * This may complete (successfully) but leave config files on the SDCard that are not
   * being used with the new config due to activity stacks perhaps holding those files open.
   *
   * We do not care about these extraneous files and ignore deleteFile failures. The next
   * material update of the config will presumably clean these up.
   *
   * @param tableId
   * @param serverReportedTableLevelETag
   * @param onChange
   * @param pushLocalFiles
   * @param syncStatus
   * @throws HttpClientWebException
   * @throws IOException
   * @throws RemoteException
   */
  public void syncTableLevelFiles(String tableId, String serverReportedTableLevelETag, Synchronizer.OnTablePropertiesChanged onChange,
      boolean pushLocalFiles, Synchronizer.SynchronizerStatus syncStatus) throws
      HttpClientWebException, IOException, RemoteException {

    syncStatus.updateNotification(SyncProgressState.TABLE_FILES,
        R.string.sync_getting_table_manifest,
        new Object[] { tableId }, 1.0, false);

    // get the table files on the server
    FileManifestDocument manifestDocument = sc.getSynchronizer().getTableLevelFileManifest(tableId,
        serverReportedTableLevelETag, pushLocalFiles);

    if (manifestDocument == null) {
      log.i(LOGTAG, "no change in table manifest -- skipping!");
      // short-circuit because our files should match those on the server
      syncStatus.updateNotification(SyncProgressState.TABLE_FILES,
          R.string.sync_getting_table_manifest,
          new Object[] { tableId }, 100.0, false);

      return;
    }
    String tableIdPropertiesFile = ODKFileUtils.asRelativePath(sc.getAppName(),
        new File(ODKFileUtils.getTablePropertiesCsvFile(sc.getAppName(), tableId)));

    boolean tablePropertiesChanged = false;

    // Get any config/assets/csv files that begin with tableId
    Set<String> dirsToExclude = new HashSet<String>();
    File assetsCsvFolder = new File(ODKFileUtils.getAssetsCsvFolder(sc.getAppName()));
    List<String> relativePathsToTableIdAssetsCsvOnDevice = getAllFilesUnderFolder(assetsCsvFolder,
        dirsToExclude);
    relativePathsToTableIdAssetsCsvOnDevice = filterInTableIdFiles(
        relativePathsToTableIdAssetsCsvOnDevice, tableId);

    // instance directory is now under the data tree, so we don't have to worry about it...
    File tableFolder = new File(ODKFileUtils.getTablesFolder(sc.getAppName(), tableId));
    List<String> relativePathsOnDevice = getAllFilesUnderFolder(tableFolder, dirsToExclude);

    // mix in the assets files for this tableId, if any...
    relativePathsOnDevice.addAll(relativePathsToTableIdAssetsCsvOnDevice);

    double stepSize = 100.0 / (1 + relativePathsOnDevice.size() + manifestDocument.entries.size());
    int stepCount = 1;

    boolean deviceAndServerEntirelyMatch = true;
    if (pushLocalFiles) {
      // if we are pushing, we want to push the local files that are different
      // up to the server, then remove the files on the server that are not
      // in the local set.
      List<File> serverFilesToDelete = new ArrayList<File>();

      for (OdkTablesFileManifestEntry entry : manifestDocument.entries) {
        File localFile = ODKFileUtils.asConfigFile(sc.getAppName(), entry.filename);
        if (!localFile.exists() || !localFile.isFile()) {
          // we need to delete this file from the server.
          serverFilesToDelete.add(localFile);
        } else if (ODKFileUtils.getMd5Hash(sc.getAppName(), localFile).equals(entry.md5hash)) {
          // we are ok -- no need to upload or delete
          relativePathsOnDevice.remove(ODKFileUtils.asRelativePath(sc.getAppName(), localFile));
        }
      }

      // this is the actual step size when we are pushing...
      stepSize = 100.0 / (1 + relativePathsOnDevice.size() + serverFilesToDelete.size());

      for (String relativePath : relativePathsOnDevice) {

        syncStatus.updateNotification(SyncProgressState.TABLE_FILES,
            R.string.sync_uploading_local_file,
            new Object[] { relativePath }, stepCount * stepSize, false);

        File localFile = ODKFileUtils.asAppFile(sc.getAppName(), relativePath);
        sc.getSynchronizer().uploadConfigFile(localFile);

        ++stepCount;
      }

      for (File localFile : serverFilesToDelete) {

        String relativePath = ODKFileUtils.asRelativePath(sc.getAppName(), localFile);
        syncStatus.updateNotification(SyncProgressState.TABLE_FILES,
            R.string.sync_deleting_file_on_server,
            new Object[] { relativePath }, stepCount * stepSize, false);

        sc.getSynchronizer().deleteConfigFile(localFile);

        ++stepCount;
      }

    } else {
      // if we are pulling, we want to pull the server files that are different
      // down from the server, then remove the local files that are not present
      // on the server.

      for (OdkTablesFileManifestEntry entry : manifestDocument.entries) {
        File localFile = ODKFileUtils.asConfigFile(sc.getAppName(), entry.filename);
        String relativePath = ODKFileUtils.asRelativePath(sc.getAppName(), localFile);

        syncStatus.updateNotification(SyncProgressState.TABLE_FILES,
            R.string.sync_verifying_local_file,
            new Object[] { relativePath }, stepCount * stepSize, false);

        // make sure our copy is current; outcome is true if the file was changed
        boolean hasChanged = compareAndDownloadConfigFile(tableId, entry, localFile);
        // and if it was the table properties file, remember whether it changed.
        if (relativePath.equals(tableIdPropertiesFile)) {
          tablePropertiesChanged = hasChanged;
        }
        // remove it from the set of app-level files we found before the sync
        relativePathsOnDevice.remove(relativePath);

        // this is the corrected step size based upon matching files
        stepSize = 100.0 / (1 + relativePathsOnDevice.size() + manifestDocument.entries.size());

        ++stepCount;
      }

      for (String relativePath : relativePathsOnDevice) {

        syncStatus.updateNotification(SyncProgressState.TABLE_FILES,
            R.string.sync_deleting_local_file,
            new Object[] { relativePath }, stepCount * stepSize, false);

        // and remove any remaining files, as these do not match anything on
        // the server.
        File localFile = ODKFileUtils.asAppFile(sc.getAppName(), relativePath);
        if (!localFile.delete()) {
          deviceAndServerEntirelyMatch = false;
          log.e(LOGTAG, "Unable to delete " + localFile.getAbsolutePath());
        }

        ++stepCount;
      }

      if (tablePropertiesChanged && (onChange != null)) {
        // update this table's KVS values...
        onChange.onTablePropertiesChanged(tableId);
      }
    }

    if ( deviceAndServerEntirelyMatch ) {

      // Update the ETag for the manifest so that we can detect a no-file-changes state
      // and minimize the bytes across the wire.
      //
      // Use the matching of the ETag to indicate the device and server content exactly match.
      try {
        sc.getSynchronizer().updateManifestSyncETag(tableId, manifestDocument.eTag);
      } catch (RemoteException e) {
        log.e(LOGTAG, "Error while trying to update the manifest sync etag");
        log.printStackTrace(e);
      }

    }
  }

  /**
   * Determine whether or not we need to pull this configuration file and attempt to
   * pull it if we do. Either succeeds or throws an exception.
   *
   * @param tableId
   * @param entry
   * @param localFile
   * @return true if the file was updated; false if it was left unchanged.
   * @throws HttpClientWebException
   * @throws IOException
   * @throws RemoteException
   */
  private boolean compareAndDownloadConfigFile(String tableId, OdkTablesFileManifestEntry entry,
      File localFile) throws HttpClientWebException, IOException, RemoteException {
    String basePath = ODKFileUtils.getAppFolder(sc.getAppName());

    // if the file is a placeholder on the server, then don't do anything...
    if (entry.contentLength == 0) {
      throw new IncompleteServerConfigFileBodyMissingException("Missing config file body");
    }
    // now we need to look through the manifest and see where the files are
    // supposed to be stored. Make sure you don't return a bad string.
    if (entry.filename == null || entry.filename.equals("")) {
      log.i(LOGTAG, "returned a null or empty filename");
      throw new ClientDetectedVersionMismatchedServerResponseException("Manifest entry does not have filename!");
    } else {

      URI uri = null;
      URL urlFile = null;
      try {
        log.i(LOGTAG, "[compareAndDownloadConfigFile] downloading at url: " + entry.downloadUrl);
        urlFile = new URL(entry.downloadUrl);
        uri = urlFile.toURI();
      } catch (MalformedURLException e) {
        log.e(LOGTAG, e.toString());
        log.printStackTrace(e);
        throw new ClientDetectedVersionMismatchedServerResponseException("Manifest entry has an invalid downloadUrl");
      } catch (URISyntaxException e) {
        log.e(LOGTAG, e.toString());
        log.printStackTrace(e);
        throw new ClientDetectedVersionMismatchedServerResponseException("Manifest entry has an invalid downloadUrl");
      }

      // Before we try dl'ing the file, we have to make the folder,
      // b/c otherwise if the folders down to the path have too many non-
      // existent folders, we'll get a FileNotFoundException when we open
      // the FileOutputStream.
      String folderPath = localFile.getParent();
      ODKFileUtils.createFolder(folderPath);
      if (!localFile.exists()) {
        // the file doesn't exist on the system
        // filesToDL.add(localFile);
        boolean success = false;
        try {
          sc.getSynchronizer().downloadFile(localFile, uri);
          sc.getSynchronizer().updateFileSyncETag(uri, tableId, localFile.lastModified(), entry.md5hash);
          success = true;
        } finally {
          if ( !success ) {
            log.e(LOGTAG, "trouble downloading file " + entry.filename + " + for first time");
          }
        }
      } else {
        boolean hasUpToDateEntry = true;
        String md5hash = null;
        try {
          md5hash = sc.getSynchronizer().getFileSyncETag(uri, tableId, localFile.lastModified());
        } catch (RemoteException e1) {
          log.printStackTrace(e1);
          log.e(LOGTAG, "database access error (ignoring)");
        }
        if (md5hash == null) {
          // file exists, but no record of what is on the server
          // compute local value
          hasUpToDateEntry = false;
          md5hash = ODKFileUtils.getMd5Hash(sc.getAppName(), localFile);
        }
        // so as it comes down from the manifest, the md5 hash includes a
        // "md5:" prefix. Add that and then check.
        if (!md5hash.equals(entry.md5hash)) {
          hasUpToDateEntry = false;
          // it's not up to date, we need to download it.
          boolean success = false;
          try {
            sc.getSynchronizer().downloadFile(localFile, uri);
            sc.getSynchronizer().updateFileSyncETag(uri, tableId, localFile.lastModified(), md5hash);
            success = true;
          } finally {
            if ( !success ) {
              log.e(LOGTAG, "trouble downloading new version of file " + entry.filename);
            }
          }
        } else {
          if (!hasUpToDateEntry) {
            sc.getSynchronizer().updateFileSyncETag(uri, tableId, localFile.lastModified(), md5hash);
          }
          // no change -- we have the file; it didn't change.
          return false;
        }
      }
    }
    return true;
  }

  /**
   * If attachmentState is NONE, then this just returns false and is a no-op.
   * Otherwise, it always fetches the row-level file manifest and builds up the
   * set of files that should be pulled down from the server and pushed up to
   * the server. Based upon that and the attachmentState actions, it determines
   * whether the row can be transitioned into the synced state (from synced_pending_files).
   *
   * @param serverInstanceFileUri
   * @param tableId
   * @param localRow
   * @param attachmentState
   * @return true if sync state should move to synced (from synced_pending_files)
   * @throws HttpClientWebException
   * @throws IOException
   * @throws RemoteException
   */
  public boolean syncRowLevelFileAttachments(String serverInstanceFileUri, String tableId,
      SyncRowPending localRow, SyncAttachmentState attachmentState) throws HttpClientWebException, IOException, RemoteException  {

    if (localRow.getUriFragments().isEmpty()) {
      throw new IllegalStateException("should never get here!");
    }

    boolean fullySyncedUploads = false;
    boolean impossibleToFullySyncDownloadsServerMissingFileToDownload = false;
    boolean fullySyncedDownloads = false;


    // If we are not syncing instance files, then return without checking manifest against local
    // files. Return false to indicate that the row should be left in a synced_pending_files
    // state.
    if (attachmentState.equals(SyncAttachmentState.NONE)) {
      return false;
    }

     // 1) Get this row's instanceId (rowId)
    String instanceId = localRow.getRowId();
    log.i(LOGTAG, "syncRowLevelFileAttachments requesting a row-level manifest for " + instanceId);

    // 2) get the list of file attachments in this row
    List<String> localRowPathUris = localRow.getUriFragments();

    // 3) get the content-hash of the above list. This is used, together with the attachementState
    //    when fetching the attachment manifest and updating the manifest ETag.
    String uriFragmentHash = localRow.getUriFragmentHash();

    // 4) Get the list of files on the server
    FileManifestDocument manifestDocument =
        sc.getSynchronizer().getRowLevelFileManifest(serverInstanceFileUri, tableId, instanceId,
            attachmentState, uriFragmentHash);

    if ( manifestDocument == null ) {
      // if the row attachment state, list of file attachments, and manifest on the server
      // have not changed since the last time we sync'd using the same
      // attachmentState, then we are in the same outcome state as we were before.
      // i.e., we remain in sync_pending_files.
      log.i(LOGTAG, "syncRowLevelFileAttachments no change short-circuit at row-level manifest for " + instanceId);
      return false;
    }

    // 5) Create a list of files that need to be uploaded to or downloaded from the server.
    // Track the sizes of the files to download so we can fetch them in smaller groups.
    List<CommonFileAttachmentTerms> filesToUpload = new ArrayList<CommonFileAttachmentTerms>();
    HashMap<CommonFileAttachmentTerms, Long> filesToDownloadSizes = new HashMap<>();

    // If the row is repeatedly updated, we only want to pull or push the
    // files required by the current version of the row.
    //
    // Iterate over the files that exist on the server, but only
    // process the files that are being referenced in the current row.
    for (OdkTablesFileManifestEntry entry : manifestDocument.entries) {

      // we only care about the files that are referenced in the current row
      if ( localRowPathUris.contains(entry.filename) ) {
        // this is something we want to do something with

        // remove from the list -- anything left over is orphaned or needs to be
        // pushed up to the server because the server doesn't know about it.
        localRowPathUris.remove(entry.filename);

        CommonFileAttachmentTerms cat = sc.getSynchronizer().createCommonFileAttachmentTerms(
            serverInstanceFileUri, tableId, instanceId, entry.filename);

        if (entry.md5hash == null || entry.md5hash.length() == 0) {
          // server doesn't know about it
          if (cat.localFile.exists()) {
            log.i(LOGTAG, "syncRowLevelFileAttachments local file exists; server has entry but not file. Add to uploads list for " + instanceId);
            filesToUpload.add(cat);
          } else {
            log.w(LOGTAG, "syncRowLevelFileAttachments local file does not exist; file is not available on server for " + instanceId);
            impossibleToFullySyncDownloadsServerMissingFileToDownload = true;
          }
        } else {
          // server has the file
          if (cat.localFile.exists()) {
            // Check if the server and local versions match
            String localMd5 = ODKFileUtils.getMd5Hash(sc.getAppName(), cat.localFile);

            if (!localMd5.equals(entry.md5hash)) {
              // Found, but it is wrong locally, so we need to pull it
              log.e(LOGTAG, "syncRowLevelFileAttachments Row-level Manifest: md5Hash on server does not match local file hash!");
              filesToDownloadSizes.put(cat, entry.contentLength);
            }
          } else {
            log.i(LOGTAG, "syncRowLevelFileAttachments local file does not exist; server has entry and file. Add to downloads list for " + instanceId);
            // we don't have it -- we need to download it.
            filesToDownloadSizes.put(cat, entry.contentLength);
          }
        }
      }
    }

    // at this point, localRowPathUris will contain the rowPathUri entries that are
    // present in the actual row but are not marked as present on the server (i.e.,
    // are missing from the server's list of files for this row.
    //
    // If the file for this rowPathUri exists locally, we need to upload it.
    //
    // If the file for this rowPathUri does not exist locally, then we should flag
    // this as a missing file to download. i.e., the server does not yet have it, so
    // we cannot complete our own row-level download to bring everything up-to-date.
    for (String rowPathUri : localRowPathUris) {

      CommonFileAttachmentTerms cat = sc.getSynchronizer().createCommonFileAttachmentTerms(
          serverInstanceFileUri, tableId, instanceId, rowPathUri);

      if (cat.localFile.exists()) {
        log.i(LOGTAG, "syncRowLevelFileAttachments local file exists; server does not have entry. Add to uploads list for " + instanceId);
        filesToUpload.add(cat);
      } else {
        log.w(LOGTAG, "syncRowLevelFileAttachments local file does not exist; file is not available on server for " + instanceId);
        impossibleToFullySyncDownloadsServerMissingFileToDownload = true;
      }
    }

    ///////////////////////////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////////////////////////
    // We now have the complete set of files to upload and files to download
    //
    // Proceed to do the requested attachment sync interactions
    ///////////////////////////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////////////////////////

    // 4) Split that list of files to upload into 10MB batches and upload those to the server
    if (filesToUpload.isEmpty()) {
      log.i(LOGTAG, "syncRowLevelFileAttachments no files to send to server -- they are all synced");
      fullySyncedUploads = true;
    } else if (attachmentState.equals(SyncAttachmentState.SYNC) ||
               attachmentState.equals(SyncAttachmentState.UPLOAD)) {
      long batchSize = 0;
      List<CommonFileAttachmentTerms> batch = new LinkedList<CommonFileAttachmentTerms>();
      for (CommonFileAttachmentTerms fileAttachment : filesToUpload) {

        // Check if adding the file exceeds the batch limit. If so, upload the current batch and
        // start a new one.
        // Note: If the batch is empty then this is just one giant file and it will get uploaded
        // on the next iteration.
        if (batchSize + fileAttachment.localFile.length() > MAX_BATCH_SIZE && !batch.isEmpty()) {
          log.i(LOGTAG, "syncRowLevelFileAttachments uploading batch for " + instanceId);
          sc.getSynchronizer().uploadInstanceFileBatch(batch, serverInstanceFileUri,
              instanceId, tableId);
          batch.clear();
          batchSize = 0;
        }

        batch.add(fileAttachment);
        batchSize += fileAttachment.localFile.length();
      }

      if ( !batch.isEmpty() ) {
        // Upload the final batch
        log.i(LOGTAG, "syncRowLevelFileAttachments uploading batch for " + instanceId);
        sc.getSynchronizer().uploadInstanceFileBatch(batch, serverInstanceFileUri,
                instanceId, tableId);
      }

      fullySyncedUploads = true;
    }
    // 5) Download the files from the server
    if (filesToDownloadSizes.isEmpty()){
      log.i(LOGTAG, "syncRowLevelFileAttachments no files to fetch from server -- they are all synced");
      fullySyncedDownloads = !impossibleToFullySyncDownloadsServerMissingFileToDownload;
    } else if (attachmentState.equals(SyncAttachmentState.SYNC) ||
               attachmentState.equals(SyncAttachmentState.DOWNLOAD)) {
      long batchSize = 0;
      List<CommonFileAttachmentTerms> batch = new LinkedList<CommonFileAttachmentTerms>();

      for (CommonFileAttachmentTerms fileAttachment : filesToDownloadSizes.keySet()) {

        // Check if adding the file exceeds the batch limit. If so, download the current batch
        // and start a new one.
        // Note : If the batch is empty, then this is just one giant file and it will get
        // downloaded on the next iteration.
        if (batchSize + filesToDownloadSizes.get(fileAttachment) > MAX_BATCH_SIZE &&
            !batch.isEmpty()) {
          log.i(LOGTAG, "syncRowLevelFileAttachments downloading batch for " + instanceId);
          sc.getSynchronizer().downloadInstanceFileBatch(batch,
              serverInstanceFileUri, instanceId, tableId);
          batch.clear();
          batchSize = 0;
        }

        batch.add(fileAttachment);
        batchSize += filesToDownloadSizes.get(fileAttachment);
      }

      if ( !batch.isEmpty() ) {
        // download the final batch
        log.i(LOGTAG, "syncRowLevelFileAttachments downloading batch for " + instanceId);
        sc.getSynchronizer().downloadInstanceFileBatch(batch, serverInstanceFileUri,
                instanceId, tableId);
      }

      fullySyncedDownloads = !impossibleToFullySyncDownloadsServerMissingFileToDownload;
    }

    if ( attachmentState == SyncAttachmentState.NONE ||
            ((fullySyncedUploads || (attachmentState == SyncAttachmentState.DOWNLOAD)) &&
             (fullySyncedDownloads || (attachmentState == SyncAttachmentState.UPLOAD))) ) {
      // there may be synced_pending_files rows, but all of the uploads we want to do
      // have been uploaded, and all of the downloads we want to do have been downloaded.
      //
      // Therefore, we can update our eTag for the manifest incorporating our local state
      // so that we can short-circuit the file checks the next time we sync. We'll still
      // request the manifest, but if it hasn't changed, we call it good enough.
      try {
        // One might think that the uriFragmentHash needs to be updated here, but it does not.
        // Upon entering this routine, it tracked the content of the columns containing
        // file attachments (and just that content -- not whether or not the files existed
        // locally or on the server). That content has not changed, so the uriFragmentHash
        // value continues to be valid now, once all the file attachments have been synced.
        sc.getSynchronizer().updateRowLevelManifestSyncETag(serverInstanceFileUri, tableId,
                instanceId, attachmentState, uriFragmentHash, manifestDocument.eTag);
      } catch (RemoteException e) {
        log.printStackTrace(e);
        log.e(LOGTAG, "database access error (ignoring)");
      }
    }

    if ( fullySyncedUploads && fullySyncedDownloads ) {
      log.i(LOGTAG, "syncRowLevelFileAttachments SUCCESS syncing file attachments for " + instanceId);
      return true;
    } else {
      // we are fully sync'd if we were able to fully sync both the uploads and the downloads
      // we were not able to do this.
      log.i(LOGTAG, "syncRowLevelFileAttachments PENDING file attachments for " + instanceId);
      return false;
    }
  }
}
