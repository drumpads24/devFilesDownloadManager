package com.paullipnyagov.testdownloadmanager;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;

public class FileUtils {

    public static String tryCloseStream(Closeable stream) {
        String error = null;
        try {
            stream.close();
        } catch (IOException e) {
            error = "[FileUtils] failed to close stream: " + e.toString();
        }
        return error;
    }

    public static String tryCloseStreams(Closeable... streams) {
        StringBuilder error = null;
        for (int i = 0; i < streams.length; i++) {
            String message = tryCloseStream(streams[i]);
            if (message != null) {
                if (error != null) {
                    error.append("; ");
                } else {
                    error = new StringBuilder();
                }
                error.append(message);
            }
        }
        return error != null ? error.toString() : null;
    }

    public static long getPartitionFreeSpace(String filePath) {
        return getPartitionFreeSpace(new File(filePath));
    }

    public static long getPartitionFreeSpace(File file) {
        // create temp file to receive getFreeSpace (returns 0 if file doesn't exist) and then delete it
        if (!file.exists()) {
            try {
                if (!file.createNewFile()) {
                    return -1; // means error
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        long partitionFreeSpace = file.getFreeSpace();
        if (file.exists()) {
            if (!file.delete()) {
                return -1;
            }
        }

        return partitionFreeSpace;
    }
}
