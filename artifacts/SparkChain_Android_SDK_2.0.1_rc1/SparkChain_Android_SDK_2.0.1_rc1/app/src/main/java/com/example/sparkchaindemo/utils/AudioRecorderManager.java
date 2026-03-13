package com.example.sparkchaindemo.utils;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.atomic.AtomicBoolean;

public class AudioRecorderManager {
    private final String TAG = "AudioRecorder";
    private int sampleRateInHz = 16000;
    private int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
    private int channels = AudioFormat.CHANNEL_IN_MONO;
    private int bufferSize;
    private static AudioRecorderManager mInstance;
    private AudioRecord mRecorder;
    private AtomicBoolean isStart = new AtomicBoolean();
    private Thread recordThread;
    private String path = "/sdcard/iflytek/audio.wav";
    private AudioDataCallback callback;

    public AudioRecorderManager() {
        this(16000, AudioFormat.ENCODING_PCM_16BIT, AudioFormat.CHANNEL_IN_MONO);
    }

    public AudioRecorderManager(int sampleRateInHz, int audioFormat, int channels) {
        this.sampleRateInHz = sampleRateInHz;
        this.audioFormat = audioFormat;
        this.channels = channels;
        bufferSize = AudioRecord.getMinBufferSize(sampleRateInHz, channels, audioFormat);
        mRecorder = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleRateInHz, channels, audioFormat, bufferSize);
    }

    public void registerCallBack(AudioDataCallback callback) {
        this.callback = callback;
//        handler = new Handler(Looper.getMainLooper());
    }

    public static AudioRecorderManager getInstance(){
        if (mInstance == null) {
            synchronized (AudioRecorderManager.class) {
                if (mInstance == null) {
                    mInstance = new AudioRecorderManager();
                }
            }
        }
        return mInstance;
    }
    /**
     * 销毁线程方法
     */
    public void destroyThread() {
        synchronized (this){
            try {
                isStart.set(false);
                if (null != recordThread && recordThread.isAlive()) {
                    try {
//                        Thread.sleep(500);
                        recordThread.interrupt();
                        recordThread.join(); // 确保线程已终止
                    } catch (Exception e) {
                        e.printStackTrace();
                    }finally {
                        recordThread = null;
                    }
                }
//                recordThread = null;
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                recordThread = null;
            }
        }
    }

    /**
     * 启动录音线程
     */
    private void startThread() {
        destroyThread();
        isStart.set(true);
        Log.i(TAG,"recordThread："+(recordThread == null));
        if (recordThread == null) {
            recordThread = new Thread(recordRunnable);
            recordThread.start();
        }
    }

    /**
     * 录音线程
     */
    Runnable recordRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                if (mRecorder != null) {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
                    int bytesRecord;
                    //int bufferSize = 320;
                    byte[] tempBuffer = new byte[bufferSize];
                    if (mRecorder.getState() != AudioRecord.STATE_INITIALIZED) {
                        stopRecord();
                        return;
                    }
                    mRecorder.startRecording();
                    //writeToFileHead();
                    while (isStart.get()) {
                        synchronized (this){
                            if (null != mRecorder) {
                                bytesRecord = mRecorder.read(tempBuffer, 0, bufferSize);
                                if (bytesRecord == AudioRecord.ERROR_INVALID_OPERATION || bytesRecord == AudioRecord.ERROR_BAD_VALUE) {
                                    continue;
                                }
                                if (bytesRecord != 0 && bytesRecord != -1 && isStart.get()) {
                                    //在此可以对录制音频的数据进行二次处理 比如变声，压缩，降噪，增益等操作
                                    // 使用RMS方法计算音量
                                    double sumSquares = 0.0;
                                    int sampleCount = bytesRecord / 2;  // 每个样本16位(2字节)

                                    for (int i = 0; i < bytesRecord; i += 2) {
                                        // 将两个字节转换为一个16位短整型
                                        short sample = (short) ((tempBuffer[i] & 0xFF) |
                                                ((tempBuffer[i + 1] & 0xFF) << 8));
                                        // 计算平方和
                                        sumSquares += (double)sample * sample;
                                    }

                                    // 计算RMS (均方根)
                                    double rms = Math.sqrt(sumSquares / sampleCount);

                                    // 转换为分贝值 (防止除以0)
                                    double db = -120.0; // 默认极低值
                                    if (rms > 1e-10) {  // 避免log(0)
                                        db = 20 * Math.log10(rms / 32767.0);
                                    }

                                    // 映射到0-9音量等级
                                    int volume = 0;
                                    if (db > -60) {
                                        // 更符合人耳感知的映射：-60dB(0级)到-20dB(9级)
                                        volume = (int) Math.min(9, Math.max(0, (db + 60) * 9 / 40.0));
                                    }
                                    callback.onAudioVolume(db,volume);
                                    //我们这里直接将pcm音频原数据写入文件 这里可以直接发送至服务器 对方采用AudioTrack进行播放原数据
                                    callback.onAudioData(tempBuffer,bytesRecord);
                                } else {
                                    break;
                                }
                            }
                        }
                    }
                }

            } catch (Exception e) {
                Log.w(TAG,"录音异常:"+e.toString());
                e.printStackTrace();
            }finally {
                if (mRecorder != null) {
                    mRecorder.stop();
                    mRecorder.release();
                    mRecorder = null;
                }
            }
        }

    };


    /**
     * 启动录音
     */
    public void startRecord() {
        try {
            startThread();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 停止录音
     */
    public void stopRecord() {
        destroyThread();
        synchronized (this){
            try {
                if (callback != null) {
                    callback=null;
                }
                if (mRecorder != null) {
                    if (mRecorder.getState() == AudioRecord.STATE_INITIALIZED) {
                        mRecorder.stop();
                    }
                    if (mRecorder != null) {
                        mRecorder.release();
                    }
                    mRecorder=null;
                }
                if (mInstance != null) {
                    mInstance=null;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }finally {
                mInstance = null; // 确保单例实例被释放
            }
        }
    }

    protected void writeDataToFile(String path, byte[] bytes, boolean append) {

        try {
            File file = new File(path);
            if (!file.exists()) {
                file.createNewFile();
            }
            FileOutputStream out = new FileOutputStream(path, append);//指定写到哪个路径中
            FileChannel fileChannel = out.getChannel();
            fileChannel.write(ByteBuffer.wrap(bytes)); //将字节流写入文件中
            fileChannel.force(true);//强制刷新
            fileChannel.close();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            Log.e(TAG, "writeFile:" + e.toString());
        }
    }

    public interface AudioDataCallback {
        void onAudioData(byte[] data, int size);

        void onAudioVolume(double db,int volume);
    }

}
