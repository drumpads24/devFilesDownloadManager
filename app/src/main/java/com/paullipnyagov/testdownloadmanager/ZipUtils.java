package com.paullipnyagov.testdownloadmanager;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.zip.ZipInputStream;

public class ZipUtils {

    public static int getZipEntryCount(File zipFile) throws IOException {
        int entryCount = 0;
        ZipInputStream zis = new ZipInputStream(
                new BufferedInputStream(new FileInputStream(zipFile)));
        while ((zis.getNextEntry()) != null) {
            entryCount++;
        }
        return entryCount;
    }
}
