package com.paullipnyagov.testdownloadmanager;

import android.content.Context;
import android.widget.Toast;

import com.paullipnyagov.googleanalyticslibrary.GoogleAnalyticsUtil;
import com.paullipnyagov.myutillibrary.MyLog;
import com.paullipnyagov.myutillibrary.otherUtils.ToastFactory;
import com.paullipnyagov.myutillibrary.systemUtils.FileSystemUtils;
import com.paullipnyagov.presetconfigworker.configData.PresetConfigInfo;
import com.paullipnyagov.ref2_utils.ExternalStorageUtils;

import java.io.File;
import java.util.ArrayList;

import settings.Constants;

public class PresetsDownloadManager {
    private PresetsFileDownloader mCurrentDownload = null;
    private final Context mAppContext;

    private ArrayList<PresetConfigInfo> mDownloadQueue = new ArrayList<>();
    private int mCurrentDownloadPresetId = 0;

    public interface OnPresetDownloadEventListener {
        void onPresetDownloadCompleted(int id);

        void onPresetDownloadFailed(int id);
    }

    private ArrayList<OnPresetDownloadEventListener> mListeners = new ArrayList<>();

    public void addOnPresetDownloadListener(OnPresetDownloadEventListener listener) {
        mListeners.add(listener);
    }

    public void removeOnPresetDownloadListener(OnPresetDownloadEventListener listener) {
        mListeners.remove(listener);
    }

    public PresetsDownloadManager(Context appContext) {
        mAppContext = appContext;
    }

    public void onDestroy() {
        recycleCurrentDownload();
    }

    public void download(PresetConfigInfo presetConfigInfo) {
        MyLog.d("[PresetsDownloadManager] Adding preset " + presetConfigInfo.getName() +
                " id: " + presetConfigInfo.getId() + " to download queue");
        for (int i = 0; i < mDownloadQueue.size(); i++) {
            if (mDownloadQueue.get(i).getId() == presetConfigInfo.getId()) {
                // already in queue
                MyLog.d("[PresetsDownloadManager] Preset is already in queue.");
                logCurrentQueue();
                return;
            }
        }
        mDownloadQueue.add(presetConfigInfo);
        MyLog.d("[PresetsDownloadManager] Preset added to download queue successfully");
        logCurrentQueue();
        if (mCurrentDownload == null) {
            MyLog.d("[PresetsDownloadManager] Queue is empty, starting download immediately");
            startNextDownload();
        }
    }

    private void startNextDownload() {
        logCurrentQueue();
        recycleCurrentDownload();

        if (mDownloadQueue.size() < 1) {
            mCurrentDownloadPresetId = 0;
            MyLog.d("[PresetsDownloadManager] All downloads have completed");
            return; //done
        }
        final PresetConfigInfo info = mDownloadQueue.get(0);

        String outputFile = getTempFileOutputPath();
        String unzippedDirectoryPath = getPresetUnzippedPath(mAppContext, info.getId());

        if (outputFile == null || unzippedDirectoryPath == null) {
            MyLog.e("[PresetsDownloadManager] Error while trying to get special directory path");
            showDownloadErrorAndClearQueue(info.getId());
            return; //error. interrupt download
        }
        // don't care if dir was there or not, ignore result
        FileSystemUtils.deleteDirectoryRecursive(new File(unzippedDirectoryPath));

        mCurrentDownload = new PresetsFileDownloader();
        mCurrentDownloadPresetId = info.getId();
        mCurrentDownload.downloadFile(new Runnable() {
            @Override
            public void run() {
                if (!mCurrentDownload.isDownloadCompletedSuccessfully()) {
                    MyLog.e("[PresetsDownloadManager] Error during preset download logged to analytics: "
                            + mCurrentDownload.getErrorMessage());
                    GoogleAnalyticsUtil.trackFailedDownloadPreset(mAppContext,
                            info.getName(), mCurrentDownload.getErrorMessage());
                    showDownloadErrorAndClearQueue(info.getId());
                    return;
                }

                GoogleAnalyticsUtil.trackSuccessDownloadPreset(mAppContext, info.getName());

                ToastFactory.makeText(mAppContext, mAppContext.getString(R.string.preset_downloaded, info.getName()),
                        Toast.LENGTH_LONG).show();
                mDownloadQueue.remove(0);

                for (OnPresetDownloadEventListener listener : mListeners) {
                    listener.onPresetDownloadCompleted(mCurrentDownloadPresetId);
                }

                MyLog.d("[PresetsDownloadManager] preset downloaded: " + info.getName());
                startNextDownload();
            }
        }, info.getPath(), outputFile, unzippedDirectoryPath, info.getVersion());
        GoogleAnalyticsUtil.trackStartDownloadPreset(mAppContext, info.getName());
    }

    private void logCurrentQueue() {
        String queue = "[PresetsDownloadManager] Presets download queue: ";
        for (int i = 0; i < mDownloadQueue.size(); i++) {
            queue = queue + mDownloadQueue.get(i).getId() + ", ";
        }
        MyLog.d(queue);
    }

    private String getTempFileOutputPath() {
        // temp directory must be cleared before new download process can start
        if (!PresetFilesManager.cleanTempDownloadsDirectory(mAppContext)) {
            return null;
        }
        File tempDirectory = ExternalStorageUtils.getAppDir(mAppContext, Constants.LDP_DIR_TEMP_PATH);
        if (tempDirectory == null) {
            return null;
        }
        String path = tempDirectory.getAbsolutePath();
        return path + "/preset.zip";
    }

    private String getPresetUnzippedPath(Context context, int presetId) {
        File unzipDirectory = ExternalStorageUtils.getAppDir(context, Constants.LDP_DIR_DOWNLOAD_PATH);
        if (unzipDirectory == null) {
            return null;
        }
        String path = unzipDirectory.getAbsolutePath();
        return path + "/" + presetId + "/";
    }

    private void showDownloadErrorAndClearQueue(int failedPresetId) {
        String failedToDownloadNames = mAppContext.getString(R.string.error_downloading_presets) + " ";
        for (int i = 0; i < mDownloadQueue.size(); i++) {
            failedToDownloadNames = failedToDownloadNames + mDownloadQueue.get(i).getName();
            if (i < mDownloadQueue.size() - 1) {
                failedToDownloadNames = failedToDownloadNames + ", ";
            }
        }
        failedToDownloadNames = failedToDownloadNames + mAppContext.getString(
                R.string.error_downloading_presets_try_again);
        ToastFactory.makeText(mAppContext, failedToDownloadNames, Toast.LENGTH_LONG).show();

        mDownloadQueue = new ArrayList<>();
        for (OnPresetDownloadEventListener listener : mListeners) {
            listener.onPresetDownloadFailed(failedPresetId);
        }
        MyLog.d("[PresetsDownloadManager] Download queue is cleared");
        recycleCurrentDownload();
        mCurrentDownloadPresetId = 0;
    }

    public int getCurrentDownloadPresetId() {
        return mCurrentDownloadPresetId;
    }

    // int - 0 to 100 in %
    public int getCurrentDownloadProgress() {
        if (mCurrentDownload == null) {
            return 0;
        }
        return mCurrentDownload.getTaskProgress();
    }

    /*
     * Returns progress in % for current download, or -1 if download is in queue
     */
    public int getDownloadProgress(int presetId) {
        if (mDownloadQueue.size() < 1 /*|| mCurrentDownload == null*/) {
            // queue is empty - all downloads completed
            return Constants.LDP_DOWNLOAD_COMPLETED;
        }

        for (int i = 1; i < mDownloadQueue.size(); i++) {
            if (mDownloadQueue.get(i).getId() == presetId) {
                // download is on non-zero position in queue
                return Constants.LDP_DOWNLOAD_IS_IN_QUEUE;
            }
        }

        if (mDownloadQueue.get(0).getId() == presetId) {
            // operation is currently ongoing
            return mCurrentDownload.getTaskProgress();
        }

        // given ID is not in queue, so download has completed earlier
        return Constants.LDP_DOWNLOAD_COMPLETED;
    }

    public void cancelDownload(int presetId) {
        if (mDownloadQueue.size() < 1) {
            MyLog.d("[PresetsDownloadManager] Can't cancel preset " + presetId + " download: queue empty");
            return;
        }
        if (mDownloadQueue.get(0).getId() == presetId) {
            recycleCurrentDownload();
            mDownloadQueue.remove(0);
            startNextDownload();
        } else {
            for (int i = 0; i < mDownloadQueue.size(); i++) {
                if (mDownloadQueue.get(i).getId() == presetId) {
                    mDownloadQueue.remove(i);
                }
            }
        }
        MyLog.d("[PresetsDownloadManager] Download of preset " + presetId + " removed from queue");
        logCurrentQueue();
    }

    private void recycleCurrentDownload() {
        if (mCurrentDownload != null) {
            mCurrentDownload.recycle();
            mCurrentDownload = null;
            MyLog.d("[PresetsDownloadManager] Current download is recycled");
        }
    }
}
