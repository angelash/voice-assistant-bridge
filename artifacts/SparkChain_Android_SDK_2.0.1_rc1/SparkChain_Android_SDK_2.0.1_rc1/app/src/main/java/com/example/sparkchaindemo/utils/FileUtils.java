package com.example.sparkchaindemo.utils;

import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

public class FileUtils {
    private static final int MAX_LOG_LENGTH = 4000; // 最大日志长度
    public static void writeFile(String path, byte[] bytes) {
        boolean append = false;
        try {
            File file = new File(path);
            if (file.exists()) {
                append = true;
            }else {
                file.createNewFile();
            }
            FileOutputStream out = new FileOutputStream(path,append);//指定写到哪个路径中
            FileChannel fileChannel = out.getChannel();
            fileChannel.write(ByteBuffer.wrap(bytes)); //将字节流写入文件中
            fileChannel.force(true);//强制刷新
            fileChannel.close();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
           e.printStackTrace();
        }
    }

    public static String getSDPath(){
        File sdDir = null;
        boolean sdCardExist = Environment.getExternalStorageState()
                .equals(Environment.MEDIA_MOUNTED);//判断sd卡是否存在
        if(sdCardExist)
        {
            sdDir = Environment.getExternalStorageDirectory();//获取跟目录
        }
        return sdDir.toString();
    }

    public static void longLog(String tag, String message) {
        if (message.length() > MAX_LOG_LENGTH) {
            int chunkCount = message.length() / MAX_LOG_LENGTH; // 计算需要分成多少段
            for (int i = 0; i <= chunkCount; i++) {
                int max = MAX_LOG_LENGTH * (i + 1);
                if (max >= message.length()) {
                    Log.d(tag, message.substring(MAX_LOG_LENGTH * i));
                } else {
                    Log.d(tag, message.substring(MAX_LOG_LENGTH * i, max));
                }
            }
        } else {
            Log.d(tag, message);
        }
    }


    public static boolean deleteDirectory(String filePath) {
        // 如果dir不以文件分隔符结尾，自动添加文件分隔符
        if (!filePath.endsWith(File.separator))
            filePath = filePath + File.separator;
        File dirFile = new File(filePath);
        // 如果dir对应的文件不存在，或者不是一个目录，则退出
        if ((!dirFile.exists()) || (!dirFile.isDirectory())) {
            return false;
        }
        boolean flag = true;
        // 删除文件夹中的所有文件包括子目录
        File[] files = dirFile.listFiles();
        for (File file : files) {
            // 删除子文件
            if (file.isFile()) {
                flag = file.delete();
                if (!flag) break;
            }
            // 删除子目录
            else if (file.isDirectory()) {
                flag = deleteDirectory(file.getAbsolutePath());
                if (!flag) break;
            }
        }
        if (!flag) return false;
        // 删除当前目录
        return dirFile.delete();
    }



    public static void saveToFile(String fileName,String inputText) {
        File file = new File(fileName);
        try {
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(inputText.getBytes());
            fos.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
