package com.paullipnyagov.testdownloadmanager;

import android.annotation.SuppressLint;
import android.os.AsyncTask;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class PresetsFileDownloader {

    private static final float DOWNLOAD_PERCENT_IN_OVERALL_PROGRESS = 75f;
    private static final int FILE_BUFFER_SIZE = 8192;

    private volatile int taskProgress;
    private volatile boolean cancelDownload = false;

    private AsyncTask<Void, Void, Boolean> mRunningTask = null;

    private boolean mIsError = false;
    private String mError = "unknown";

    private final Object mMutex = new Object();

    public PresetsFileDownloader() {
        // default constructor
    }

    private void downloadPresetZip(String _url, String outputPath) {
        InputStream inputStream = null;
        FileOutputStream outputStream = null;

        long partitionFreeSpace = FileUtils.getPartitionFreeSpace(outputPath);
        if (partitionFreeSpace <= 0) { // 0 or -1 is returned in case of error
            riseError("[PresetsFileDownloader] Failed to determine free space. Preset will not be downloaded", null);
            return;
        }

        final String downloadError = "[PresetsFileDownloader] Error while downloading preset. Free space: ";
        try {
            URL url = new URL(_url);
            HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
            urlConnection.connect();
            int fileSize = urlConnection.getContentLength();

            inputStream = urlConnection.getInputStream();

            // opens an output stream to save into file
            outputStream = new FileOutputStream(outputPath);

            int bytesRead = -1;
            int totalSizeRead = 0;
            byte[] buffer = new byte[FILE_BUFFER_SIZE];
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                synchronized (mMutex) {
                    if (cancelDownload) {
                        outputStream.close();
                        inputStream.close();
                        return;
                    }
                }
                totalSizeRead = totalSizeRead + bytesRead;
                outputStream.write(buffer, 0, bytesRead);
                // progress goes from 0 to 75% while downloading and from 76 to 100% while unzipping
                taskProgress = (int) (((float) totalSizeRead / (float) fileSize)
                        * DOWNLOAD_PERCENT_IN_OVERALL_PROGRESS);
            }

            outputStream.close();
            inputStream.close();
        } catch (Exception e) {
            riseError(downloadError + partitionFreeSpace, e);
        } finally {
            try {
                if (inputStream != null) {
                    inputStream.close();
                }
            } catch (Exception e) {
                riseError(downloadError + partitionFreeSpace, e);
            }
            try {
                if (outputStream != null) {
                    outputStream.close();
                }
            } catch (Exception e) {
                riseError(downloadError + partitionFreeSpace, e);
            }
        }
    }

    private void unzipDownloadedFile(File zipFile, File targetDirectory) {
        ZipInputStream zis = null;
        //first count number of files to update progress bar
        int entryCount = 0;
        final String unzipPresetError = "[PresetsFileDownloader] Error while unzipping downloaded preset. ";
        try {
            entryCount = ZipUtils.getZipEntryCount(zipFile);
        } catch (IOException e) {
            riseError(unzipPresetError, e);
            return;
        } finally {
            String error = FileUtils.tryCloseStreams(zis);
            if (error != null) {
                riseError(unzipPresetError + error, null);
            }
        }

        synchronized (mMutex) {
            if (cancelDownload || mIsError) {
                return;
            }
        }

        // extract files one by one and create directory structure if needed
        try {
            zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)));
            ZipEntry ze;
            int count;
            byte[] buffer = new byte[FILE_BUFFER_SIZE];
            int currentEntry = 0;
            while ((ze = zis.getNextEntry()) != null) {
                synchronized (mMutex) {
                    if (cancelDownload) {
                        return;
                    }
                }
                currentEntry++;
                File file = new File(targetDirectory, ze.getName());
                File dir = ze.isDirectory() ? file : file.getParentFile();
                if (!dir.isDirectory() && !dir.mkdirs()) {
                    mIsError = true;
                    return;
                }
                if (ze.isDirectory()) {
                    continue;
                }
                FileOutputStream fOut = null;
                try {
                    fOut = new FileOutputStream(file);
                    while ((count = zis.read(buffer)) != -1) {
                        synchronized (mMutex) {
                            if (cancelDownload) {
                                return;
                            }
                        }
                        fOut.write(buffer, 0, count);
                    }
                    synchronized (mMutex) {
                        if (cancelDownload) {
                            return;
                        }
                    }
                } catch (IOException e) {
                    riseError(unzipPresetError, e);
                    return;
                } finally {
                    if (fOut != null) {
                        try {
                            fOut.close();
                        } catch (IOException e) {
                            riseError(unzipPresetError, e);
                        }
                    }
                }
                taskProgress = (int) (DOWNLOAD_PERCENT_IN_OVERALL_PROGRESS + ((float) currentEntry / (float)
                        entryCount * (100.f - DOWNLOAD_PERCENT_IN_OVERALL_PROGRESS)));
            }
        } catch (IOException e) {
            riseError(unzipPresetError, e);
        } finally {
            if (zis != null) {
                try {
                    zis.close();
                } catch (IOException e) {
                    riseError(unzipPresetError, e);
                }
            }
        }
    }

    private void writeVersionFile(File targetDirectory, String presetVersion) {
        if (mIsError) { // additional check for analytics
            riseError("[PresetsFileDownloader] Entered writeVersionFile is error state", null);
            return;
        }
        try {
            String result = PresetFilesManager.writeVersionFile(targetDirectory, presetVersion);
            if (result != null) {
                riseError(result, null);
            }
        } catch (IOException e) {
            riseError("[PresetsFileDownloader] Error while writing version file [with extension]: ", e);
        }
    }

    private void writeInsuranceFile(File targetDirectory) {
        final String insuranceFileError = "[PresetsFileDownloader] Can't write insurance file after preset download!";
        try {
            if (!PresetFilesManager.writeInsuranceFile(targetDirectory)) {
                riseError(insuranceFileError, null);
            }
        } catch (IOException e) {
            riseError(insuranceFileError, e);
        }
    }

    private void riseError(String message, Exception e) {
        if (e == null) {
            e = new Exception("assertion failed, no exception");
        }
        e.printStackTrace();
        mError = message + ", " + e.toString() + " ";
        mIsError = true;
    }

    @SuppressLint("StaticFieldLeak")
    public void downloadFile(final Runnable onDownloadCompletedRunnable,
                             final String url, final String downloadPath,
                             final String unzipDirectoryPath, final String presetVersion) {
        if (mRunningTask != null) {
            return;
        }

        mRunningTask = new AsyncTask<Void, Void, Boolean>() {
            String resultString = null;

            @Override
            protected Boolean doInBackground(Void... params) {
                downloadPresetZip(url, downloadPath);
                if (!mIsError && !cancelDownload) {
                    // don't continue if error
                    unzipDownloadedFile(new File(downloadPath), new File(unzipDirectoryPath));
                }
                synchronized (mMutex) {
                    if (!mIsError && !cancelDownload) {
                        writeVersionFile(new File(unzipDirectoryPath), presetVersion);
                    }
                }
                synchronized (mMutex) {
                    if (!mIsError && !cancelDownload) {
                        // write preset_downloaded file to indicate that process went ok
                        writeInsuranceFile(new File(unzipDirectoryPath));
                    }
                }
                taskProgress = 100;
                return true;
            }

            @Override
            protected void onPostExecute(Boolean result) {
                super.onPostExecute(result);
                onDownloadCompletedRunnable.run();
            }
        };
        MyThreadPool.executeAsyncTaskParallel(mRunningTask, MyThreadPool.TASK_TYPE_PRIMARY);
    }

    public void recycle() {
        synchronized (mMutex) {
            if (mRunningTask != null) {
                mRunningTask.cancel(true);
            }
            cancelDownload = true;
        }
    }

    public boolean isDownloadCompletedSuccessfully() {
        return !mIsError;
    }

    public String getErrorMessage() {
        return mError;
    }

    public int getTaskProgress() {
        return taskProgress;
    }
}
