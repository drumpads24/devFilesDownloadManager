package com.paullipnyagov.testdownloadmanager;

import android.content.Context;

import com.paullipnyagov.myutillibrary.MyLog;
import com.paullipnyagov.myutillibrary.otherUtils.MiscUtils;
import com.paullipnyagov.ref2_presetmanagers.PresetConfigManagers.PresetsConfigUpdater;
import com.paullipnyagov.ref2_utils.ExternalStorageUtils;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

import settings.Constants;

/**
 * Created by lkorn on 21/03/2018.
 */

public class PresetFilesManager {

    /*
    * Files are written to ensure preset was downloaded successfully.
    * EXT versions are used in newer app builds because writing file without
    * extensions may have called problems with some users downloading presets.
    */
    private static final String LDP_INSURANCE_FILE_NAME = "preset_downloaded";
    private static final String LDP_INSURANCE_FILE_NAME_EXT = "preset_downloaded.dp";
    private static final String LDP_VERSION_FILE_NAME = "version";
    private static final String LDP_VERSION_FILE_NAME_EXT = "version.dp";

    private static volatile PresetFilesManager mInstance; // singleton
    private static Context mContext;

    private PresetsDownloadManager mPresetsDownloadManager;
    private PresetsConfigUpdater mPresetConfigUpdater;

    // pass Application, not Activity context here
    public static void init(Context appContext) {
        mContext = appContext;
    }

    // Double Checked Locking & volatile initialization
    public static PresetFilesManager getInstance() {
        PresetFilesManager localInstance = mInstance;
        if (localInstance == null) {
            synchronized (PresetFilesManager.class) {
                localInstance = mInstance;
                if (localInstance == null) {
                    mInstance = localInstance = new PresetFilesManager();
                }
            }
        }
        return localInstance;
    }

    private PresetFilesManager() {
        mPresetsDownloadManager = new PresetsDownloadManager(mContext);
        mPresetConfigUpdater = new PresetsConfigUpdater();
    }

    public static PresetsDownloadManager getPresetDownloadQueue() {
        return getInstance().mPresetsDownloadManager;
    }

    public static boolean isPresetDownloaded(int presetId) {
        if (!ExternalStorageUtils.isExternalStorageMounted()) {
            return false;
        }

        File downloadDirectory = ExternalStorageUtils.getAppDir(
                mContext, Constants.LDP_DIR_DOWNLOAD_PATH);
        if (downloadDirectory == null) {
            MyLog.e("[FileSystemHelper] Preset download dir for preset id " + presetId +
                    " couldn't be accessed");
            return false;
        }

        File presetDirectory = new File(downloadDirectory, presetId + "/");
        if (!presetDirectory.exists()) {
            // no directory -> preset wasn't downloaded
            return false;
        }

        return checkInsuranceExists(presetDirectory);
    }


    public static boolean writeInsuranceFile(File dir) throws IOException {
        File insurance = new File(dir, LDP_INSURANCE_FILE_NAME_EXT);
        return insurance.createNewFile();
    }

    public static boolean deleteInsuranceFile(File dir) {
        boolean hasDeletedOld;
        boolean hasDeletedExt;
        File insurance = new File(dir, LDP_INSURANCE_FILE_NAME);
        if (insurance.exists()) {
            hasDeletedOld = insurance.delete();
        } else {
            hasDeletedOld = true;
        }
        File insuranceExt = new File(dir, LDP_INSURANCE_FILE_NAME_EXT);
        if (insuranceExt.exists()) {
            hasDeletedExt = insuranceExt.delete();
        } else {
            hasDeletedExt = true;
        }
        return hasDeletedExt && hasDeletedOld;
    }

    public static boolean checkInsuranceExists(File dir) {
        File insurance = new File(dir, LDP_INSURANCE_FILE_NAME);
        if (insurance.exists()) {
            // found old, no-extension version of insurance file
            return true;
        }
        File insuranceExt = new File(dir, LDP_INSURANCE_FILE_NAME_EXT);
        if (insuranceExt.exists()) {
            // found new version of file with extension
            return true;
        }
        return false;
    }

    // returns error message to be shown in toast on null if no error
    public static String writeVersionFile(File dir, String presetVersion) throws IOException {
        BufferedWriter writer = null;
        try {
            File versionFile = new File(dir, LDP_VERSION_FILE_NAME_EXT);
            if (versionFile.exists()) {
                return "Error creating version file: file already exists!!!";
            }
            if (!versionFile.createNewFile()) {
                return "Error creating version file: createNewFile returned false! Free space: " +
                        versionFile.getFreeSpace();
            }
            writer = new BufferedWriter(new FileWriter(versionFile));
            // additional checks for analytics
            if (!versionFile.exists()) {
                return "Error while writing version file: file was not created. Free space: " +
                        versionFile.getFreeSpace();
            }
            if (versionFile.isDirectory()) {
                return "Error while writing version file: directory was created instead of file!";
            }
            writer.write(presetVersion);
        } finally {
            if (writer != null) {
                writer.close();
            }
        }
        return null;
    }

    public static String readVersionFile(File dir) throws IOException {
        BufferedReader reader = null;
        File versionFile = new File(dir, LDP_VERSION_FILE_NAME);
        File versionFileExt = new File(dir, LDP_VERSION_FILE_NAME_EXT);

        File workingFile = null;

        if (versionFile.exists()) {
            workingFile = versionFile;
        }
        if (versionFileExt.exists()) {
            // should read newer ext version if it exists
            workingFile = versionFileExt;
        }

        if (workingFile == null) {
            // version file is absent
            return null;
        }

        try {
            reader = new BufferedReader(new FileReader(workingFile));
            return reader.readLine();
        } finally {
            if (reader != null) {
                reader.close();
            }
        }
    }

    // returns false if preset version doesn't match and should be re-downloaded
    public static boolean comparePresetVersion(File dir, String newestVersion) {
        try {
            String currentVersion = readVersionFile(dir);
            if (currentVersion == null &&
                    !(newestVersion == null || newestVersion.equals("") || newestVersion.equals("0"))) {
                return false;
            }
            if (currentVersion == null) { // and preset version is null, "" or 0 - means not set
                return true;
            }
            return currentVersion.equals(newestVersion);
        } catch (IOException e) {
            MyLog.e("[PresetsFileSystemHelper] Error while reading version file: " + e.toString());
            return false;
        }
    }

    public static boolean deleteVersionFile(File dir) {
        boolean hasDeletedOld;
        boolean hasDeletedExt;
        File presetVersionFile = new File(dir, LDP_VERSION_FILE_NAME);
        File presetVersionFileExt = new File(dir, LDP_VERSION_FILE_NAME_EXT);
        if (presetVersionFile.exists()) {
            hasDeletedOld = presetVersionFile.delete();
        } else {
            hasDeletedOld = true;
        }
        if (presetVersionFileExt.exists()) {
            hasDeletedExt = presetVersionFileExt.delete();
        } else {
            hasDeletedExt = true;
        }

        return hasDeletedExt && hasDeletedOld;
    }

    public static boolean cleanTempDownloadsDirectory(Context context) {
        File tempDirectory = ExternalStorageUtils.getAppDir(context, Constants.LDP_DIR_TEMP_PATH);
        if (tempDirectory == null) {
            return false;
        }

        String[] tempFiles = tempDirectory.list();
        for (String tempFile : tempFiles) {
            File fileToDelete = new File(tempDirectory, tempFile);
            if (!fileToDelete.delete()) {
                MiscUtils.log("[FileSystemHelper] Error removing file " + tempFile, true);
                return false;
            }
        }
        return true; // no error
    }

    public static String getPresetDirPathById(Context context, int presetId) {
        File downloadDirectory = ExternalStorageUtils.getAppDir(context, Constants.LDP_DIR_DOWNLOAD_PATH);
        if (downloadDirectory == null) {
            MiscUtils.log("[FileSystemHelper] Preset download dir for preset id " + presetId +
                    " couldn't be accessed", true);
            return null;
        }

        File presetDirectory = new File(downloadDirectory, presetId + "/");
        if (!presetDirectory.exists()) {
            // no directory -> preset wasn't downloaded
            return null;
        }

        return presetDirectory.getAbsolutePath();
    }

    public static PresetsConfigUpdater getPresetConfigUpdater() {
        return getInstance().mPresetConfigUpdater;
    }

}
