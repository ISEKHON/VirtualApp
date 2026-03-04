package io.virtualapp.utils;

import android.content.Context;
import android.net.Uri;
import android.os.AsyncTask;
import android.util.Log;


import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class ApkFileUtils {
    public static String getApkPathFromAssetsSimple(Context context, String apkFileName) {
        try {
            File cacheDir = context.getExternalCacheDir();
            File outputFile = new File(cacheDir, apkFileName);

            InputStream inputStream = context.getAssets().open(apkFileName);
            OutputStream outputStream = new FileOutputStream(outputFile);

            byte[] buffer = new byte[1024];
            int length;
            while ((length = inputStream.read(buffer)) > 0) {
                outputStream.write(buffer, 0, length);
            }

            outputStream.close();
            inputStream.close();
            String path = outputFile.getPath();
            return path;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}