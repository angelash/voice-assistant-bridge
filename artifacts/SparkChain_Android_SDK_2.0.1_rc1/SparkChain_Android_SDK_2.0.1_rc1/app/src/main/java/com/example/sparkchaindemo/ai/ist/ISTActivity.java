package com.example.sparkchaindemo.ai.ist;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.sparkchaindemo.R;
import com.example.sparkchaindemo.ai.raasr.GetFilePathFromUri;
import com.iflytek.sparkchain.core.SparkChain;
import com.iflytek.sparkchain.core.SparkChainConfig;
import com.iflytek.sparkchain.core.ist.IST;
import com.iflytek.sparkchain.core.ist.ISTCallbacks;

import java.io.FileOutputStream;

public class ISTActivity extends AppCompatActivity {
    private static final String TAG = "AEELog";
    private static final int AUDIO_FILE_SELECT_CODE = 1001;

    private final String appID = "";
    private final String apiKey = "";
    private final String apiSecret = "";

    TextView chatOutputText;
    private String selectedAudioPath = "/sdcard/iflytek/444.pcm"; // 默认音频路径

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.api_ist);
        chatOutputText = findViewById(R.id.chat_output_text);
        init();
    }

    protected void init() {
        listener();
    }

    private void listener(){
        findViewById(R.id.start_init).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                //初始化
                initSDK();
            }
        });
        findViewById(R.id.select_audio_btn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.d(TAG, "select_audio_btn clicked");
                showFileChooser();
            }
        });
        findViewById(R.id.test_btn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chatOutputText.setText("");
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        testIst(false);
                    }
                }).start();
            }
        });
        findViewById(R.id.test_btn_bm).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chatOutputText.setText("");
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        testIst(true);
                    }
                }).start();
            }
        });

        findViewById(R.id.test_btn_stop).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mIST.stop();
                isrun = false;
            }
        });
        findViewById(R.id.creat_task_btn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chatOutputText.setText("");
                if(!TextUtils.isEmpty(url)){
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            if(isrun)return;
                            mIST.createTask("createTask",url,"audio/L16;rate=16000", "raw","tag");
                        }
                    }).start();
                }
            }
        });
        findViewById(R.id.get_result_btn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chatOutputText.setText("");
                if(!TextUtils.isEmpty(taskID)){
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            if(isrun)return;
                            mIST.queryTask(taskID,"tag");
                        }
                    }).start();
                }
            }
        });

    }

    private void initSDK(){
        SparkChainConfig config = SparkChainConfig.builder()
                .appID(appID)
                .apiKey(apiKey)
                .apiSecret(apiSecret)
                .logLevel(666);
        int ret = SparkChain.getInst().init(getApplicationContext(), config);
        Log.d(TAG,"sparkChain int ret:" + ret);
        chatOutputText.setText("sparkChain int ret:" + ret);
    }

    private IST mIST;
    private String orderid = "";
    boolean isrun = false;
    String language = "zh_cn";
    String domain = "pro_ost_ed";
    String accent = "mandarin";
    String url = "";
    String taskID = "";
    private void testIst(boolean ismp) {
        if(isrun)return;
        isrun = true;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                chatOutputText.setText("");
            }
        });
        if(mIST == null)mIST = new IST();

        mIST.language(language);
        mIST.domain(domain);
        mIST.accent(accent);
        Log.d(TAG, "当前音频路径为: " + selectedAudioPath);
        mIST.registerCallbacks(new ISTCallbacks() {
            @Override
            public void onResult(IST.ISTResult result, Object usrTag) {
                Log.d(TAG, "Key:" + result.getKey());
                Log.d(TAG, "code:" + result.getCode());
                Log.d(TAG, "TaskId:" + result.getTaskId());
                Log.d(TAG, "Result:" + result.getResult());
                Log.d(TAG, "TaskStatus:" + result.getTaskStatus());
                Log.d(TAG, "Url:" + result.getUrl());
                Log.d(TAG, "tag:" + (String)usrTag);
                url = result.getUrl();
                taskID = result.getTaskId();
                String text = "Key:"+ result.getKey() + "\n"+"Result:"+ result.getResult() + "\n"+"Url:"+result.getUrl()+ "\n"+"TaskId:"+result.getTaskId()+ "\n";
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        chatOutputText.setText(text);

                        FileOutputStream fos2 = null;
                        try {
                            fos2 = new FileOutputStream("/sdcard/iflytek/ist_output.txt", true);
                            fos2.write(text.getBytes());
                            fos2.write("\r\n".getBytes());//写入换行
                            fos2.flush();
                            fos2.close();
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }
                });
                isrun = false;
            }

            @Override
            public void onProcess(String process, Object usrTag) {
                Log.d(TAG, "onProcess:" + process);
                showInfo("上传进度：" + process);
            }

            @Override
            public void onError(IST.ISTError error, Object usrTag) {
                Log.d(TAG, "errorcode:" + error.getCode());
                Log.d(TAG, "sid:" + error.getSid());
                Log.d(TAG, "errortag:" + (String)usrTag);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        String text = "onError code:"+error.getCode()+" msg:" + error.getErrMsg()+" sid:" + error.getSid() + "\n";
                        chatOutputText.setText(text);
                    }
                });
                isrun = false;
            }

        });
        if(!ismp){
            showInfo("开始上传音频：" + selectedAudioPath);
            mIST.upload(selectedAudioPath,"test_1","tag");
            showInfo("正在上传音频。。。");
        }else{
            showInfo("开始分片上传音频：" + selectedAudioPath);
            mIST.mpUpload(selectedAudioPath, "upload222", 5*1024*1024,"tag");
            showInfo("正在上传音频。。。");
        }

    }

    /***************
     * 调用文件管理器，让用户选择要传入的音频文件
     * ****************/
    private void showFileChooser() {
        Log.d(TAG, "showFileChooser");
        //调用系统文件管理器
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        //设置文件格式
        intent.setType("*/*");
        startActivityForResult(intent, AUDIO_FILE_SELECT_CODE);
    }

    /***************
     * 监听用户选择的音频文件，获取文件所在的路径
     * ****************/
    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        switch (requestCode) {
            case AUDIO_FILE_SELECT_CODE:
                if (data != null) {
                    Uri uri = data.getData();
                    String path = GetFilePathFromUri.getFileAbsolutePath(this, uri);
                    if (path != null && !path.isEmpty()) {
                        selectedAudioPath = path;
                    }
                }
                String text = "当前音频路径为: " + selectedAudioPath + "\n";
                chatOutputText.setText(text);
                Log.d(TAG, "当前音频路径为: " + selectedAudioPath);
                break;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

}
