package com.example.sparkchaindemo.ai;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.sparkchaindemo.R;
import com.example.sparkchaindemo.ai.asr.asrActivity;
import com.example.sparkchaindemo.ai.imageTrans.IMTSActivity;
import com.example.sparkchaindemo.ai.ist.ISTActivity;
import com.example.sparkchaindemo.ai.itrans.ITSActivity;
import com.example.sparkchaindemo.ai.raasr.RAASRActivity;
import com.example.sparkchaindemo.ai.rtasr.RTASRActivity;
import com.example.sparkchaindemo.ai.tts.TTSActivity;
import com.example.sparkchaindemo.llm.online_llm.online_llm_mainActivity;
import com.example.sparkchaindemo.utils.FileUtils;
import com.hjq.permissions.OnPermission;
import com.hjq.permissions.XXPermissions;
import com.iflytek.sparkchain.core.AiStatus;
import com.iflytek.sparkchain.core.LogLvl;
import com.iflytek.sparkchain.core.SparkChain;
import com.iflytek.sparkchain.core.SparkChainConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

public class AIMainActivity extends AppCompatActivity implements View.OnClickListener{
    private static final String TAG = "AEELog";
    private TextView tv_notification;

    private boolean isAuth = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.ai_main);
        initView();
    }

    private void initView(){
        findViewById(R.id.ai_main_sdk_init).setOnClickListener(this);
        findViewById(R.id.ai_main_its).setOnClickListener(this);
        findViewById(R.id.ai_main_rtasr).setOnClickListener(this);
        findViewById(R.id.ai_main_tts).setOnClickListener(this);
        findViewById(R.id.ai_main_asr).setOnClickListener(this);
        findViewById(R.id.ai_main_raasr).setOnClickListener(this);
        findViewById(R.id.ai_main_imts).setOnClickListener(this);
        findViewById(R.id.ai_main_ist).setOnClickListener(this);


        tv_notification = findViewById(R.id.ai_main_notification);

    }

    private void showInfo(String text){
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                tv_notification.setText(text);
            }
        });
    }

    private void getPermission(){
        XXPermissions.with(this).permission("android.permission.WRITE_EXTERNAL_STORAGE"
                , "android.permission.READ_EXTERNAL_STORAGE"
                , "android.permission.INTERNET"
                , "android.permission.MANAGE_EXTERNAL_STORAGE").request(new OnPermission() {
            @Override
            public void hasPermission(List<String> granted, boolean all) {
                Log.d(TAG,"SDK获取系统权限成功:"+all);
                for(int i=0;i<granted.size();i++){
                    Log.d(TAG,"获取到的权限有："+granted.get(i));
                }
                if(all){
                    createWorkDir();
                    SDKInit();
                }
            }

            @Override
            public void noPermission(List<String> denied, boolean quick) {
                if(quick){
                    Log.e(TAG,"onDenied:被永久拒绝授权，请手动授予权限");
                    XXPermissions.startPermissionActivity(AIMainActivity.this,denied);
                }else{
                    Log.e(TAG,"onDenied:权限获取失败");
                }
            }
        });
    }


    private void SDKInit(){
        Log.d(TAG,"initSDK");
        // 初始化SDK，Appid等信息在清单中配置
        SparkChainConfig sparkChainConfig = SparkChainConfig.builder();
        sparkChainConfig.appID(getResources().getString(R.string.appid))
                .apiKey(getResources().getString(R.string.apikey))
                .apiSecret(getResources().getString(R.string.apiSecret))//应用申请的appid三元组
                .logPath("/sdcard/iflytek/SparkChain.log")
                .logLevel(LogLvl.VERBOSE.getValue());

        int ret = SparkChain.getInst().init(getApplicationContext(),sparkChainConfig);
        String result;
        if(ret == 0){
            result = "SDK初始化成功,请选择相应的功能点击体验。";
            isAuth = true;
        }else{
            result = "SDK初始化失败,错误码:" + ret;
            isAuth = false;
        }
        Log.d(TAG,result);
        showInfo(result);
    }

    private void jump(Class jumpAct) {
        try {
            Intent intent = new Intent(this, jumpAct);
            startActivity(intent);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        SparkChain.getInst().unInit();

    }


    @Override
    public void onClick(View view) {
        switch (view.getId()){
            case R.id.ai_main_sdk_init:
                getPermission();
                break;
            case R.id.ai_main_its:
                if(!isAuth){
                    showInfo("SDK未初始化，请先初始化SDK");
                    return;
                }
                jump(ITSActivity.class);
                break;
            case R.id.ai_main_rtasr:
                if(!isAuth){
                    showInfo("SDK未初始化，请先初始化SDK");
                    return;
                }
                jump(RTASRActivity.class);
                break;
            case R.id.ai_main_tts:
                if(!isAuth){
                    showInfo("SDK未初始化，请先初始化SDK");
                    return;
                }
                jump(TTSActivity.class);
                break;
            case R.id.ai_main_asr:
                if(!isAuth){
                    showInfo("SDK未初始化，请先初始化SDK");
                    return;
                }
                jump(asrActivity.class);
                break;
            case R.id.ai_main_raasr:
                if(!isAuth){
                    showInfo("SDK未初始化，请先初始化SDK");
                    return;
                }
                jump(RAASRActivity.class);
                break;
            case R.id.ai_main_imts:
                if(!isAuth){
                    showInfo("SDK未初始化，请先初始化SDK");
                    return;
                }
                jump(IMTSActivity.class);
                break;
            case R.id.ai_main_ist:
                if(!isAuth){
                    showInfo("SDK未初始化，请先初始化SDK");
                    return;
                }
                jump(ISTActivity.class);
                break;
        }
    }

    /*************************
     * 从assets目录中拷贝测试音频到本地
     * *******************************/
    private void createWorkDir()  {
        String path = "/sdcard/iflytek/asr";
        FileUtils.deleteDirectory(path);
        File folder = new File(path);
        boolean success = folder.mkdirs();
        if (success) {
            // 文件夹创建成功
            try {
                copyFilesFromAssets();
            }catch (Exception e){
                e.printStackTrace();
                Toast.makeText(getApplicationContext(),"在线识别音频文件拷贝失败，请检查是否有sdcard读写权限",Toast.LENGTH_LONG).show();
            }
        } else {
            // 文件夹创建失败
            Toast.makeText(getApplicationContext(),"在线识别音频文件拷贝失败，请检查是否有sdcard读写权限",Toast.LENGTH_LONG).show();
        }
    }

    private void copyFilesFromAssets() throws IOException {
        String[] fileNames = getAssets().list("");
        if (fileNames != null && fileNames.length > 0) {
            for (String fileName : fileNames) {
                if (fileName.endsWith(".pcm")) {
                    try {
                        InputStream inputStream = getAssets().open(fileName);
                        OutputStream outputStream = new FileOutputStream("/sdcard/iflytek/asr/" + fileName);
                        byte[] buffer = new byte[1024];
                        int length;
                        while ((length = inputStream.read(buffer)) != -1) {
                            outputStream.write(buffer, 0, length);
                        }
                        inputStream.close();
                        outputStream.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }
}
