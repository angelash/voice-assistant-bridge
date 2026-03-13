package com.example.sparkchaindemo.llm.online_llm;

import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.sparkchaindemo.R;
import com.example.sparkchaindemo.llm.online_llm.bm.bmModeChoiceActivity;
import com.example.sparkchaindemo.llm.online_llm.chat.ChatActivity;
import com.example.sparkchaindemo.llm.online_llm.function.ChatWithFuctionCallActivity;
import com.example.sparkchaindemo.llm.online_llm.raasr.RAASRLLMActivity;
import com.example.sparkchaindemo.llm.online_llm.rtasr.RTASRLLMActivity;
import com.example.sparkchaindemo.llm.online_llm.tti.ttiDemoActivity;
import com.example.sparkchaindemo.llm.online_llm.embedding.EmbeddingActivity;

import com.example.sparkchaindemo.llm.online_llm.image_understanding.ImageUnderstanding;
import com.example.sparkchaindemo.llm.online_llm.personatetts.PersonateTTSActivity;
import com.hjq.permissions.OnPermission;
import com.hjq.permissions.XXPermissions;
import com.iflytek.sparkchain.core.LogLvl;
import com.iflytek.sparkchain.core.SparkChain;
import com.iflytek.sparkchain.core.SparkChainConfig;

import java.util.List;

public class online_llm_mainActivity extends AppCompatActivity implements View.OnClickListener{
    private static final String TAG = "AEELog";
    private Button btn_Chat, btn_ImageGeneration,btn_ImageUnderstanding,btn_Embedding,btn_initSDK,btn_PersonateTTS,btn_asr,btn_ChatWithFuction,btn_rtasr,btn_raasr;
    private TextView tv_result;
    private boolean isAuth = false;


    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.online_llm_main);
        btn_Chat = findViewById(R.id.online_llm_main_chat);
        btn_ImageGeneration = findViewById(R.id.online_llm_main_imageGeneration);
        btn_ImageUnderstanding = findViewById(R.id.online_llm_main_imageUnderstanding);
        btn_Embedding = findViewById(R.id.online_llm_main_embedding);
        btn_initSDK = findViewById(R.id.online_llm_main_initSDK);
        btn_PersonateTTS = findViewById(R.id.online_llm_main_personatetts);
        btn_asr = findViewById(R.id.online_llm_main_asr);
        btn_ChatWithFuction = findViewById(R.id.online_llm_main_fuction);
        btn_rtasr = findViewById(R.id.online_llm_main_rtasr);
        btn_raasr = findViewById(R.id.online_llm_main_raasr);
        btn_Chat.setOnClickListener(this);
        btn_ImageGeneration.setOnClickListener(this);
        btn_ImageUnderstanding.setOnClickListener(this);
        btn_Embedding.setOnClickListener(this);
        btn_initSDK.setOnClickListener(this);
        btn_PersonateTTS.setOnClickListener(this);
        btn_asr.setOnClickListener(this);
        btn_ChatWithFuction.setOnClickListener(this);
        btn_rtasr.setOnClickListener(this);
        btn_raasr.setOnClickListener(this);
        tv_result = findViewById(R.id.online_llm_main_notification);
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
                    initSDK();
                }
            }

            @Override
            public void noPermission(List<String> denied, boolean quick) {
                if(quick){
                    Log.e(TAG,"onDenied:被永久拒绝授权，请手动授予权限");
                    XXPermissions.startPermissionActivity(online_llm_mainActivity.this,denied);
                }else{
                    Log.e(TAG,"onDenied:权限获取失败");
                }
            }
        });
    }




    private void showInfo(String text){
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                tv_result.setText(text);
            }
        });
    }

    private String getAndroidId() {
        try {
            return Settings.Secure.getString(this.getContentResolver(),Settings.Secure.ANDROID_ID);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return "";
    }
    /*************************
     * 初始化SparkChainSDK
     * *******************************/
    private void initSDK() {
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        SparkChain.getInst().unInit();

    }

    private void jump(Class jumpAct) {
        try {
            Intent intent = new Intent(this, jumpAct);
            startActivity(intent);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void jump(Class jumpAct,String type) {
        try {
            Intent intent = new Intent(this, jumpAct);
            intent.putExtra("type",type);
            startActivity(intent);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    boolean isFirst=true;
    @Override
    public void onClick(View view) {

        switch(view.getId()){
            case R.id.online_llm_main_initSDK:
                getPermission();
                break;
            case R.id.online_llm_main_chat:
                if(!isAuth){
                    showInfo("SDK未初始化，请先初始化SDK");
                    return;
                }
                if (isFirst) {
                    isFirst=false;
                    jump(ChatActivity.class,"deep");//文本交互
                }else{
                    jump(ChatActivity.class,"normal");//文本交互
                }
                break;
            case R.id.online_llm_main_imageGeneration:
                if(!isAuth){
                    showInfo("SDK未初始化，请先初始化SDK");
                    return;
                }
                jump(ttiDemoActivity.class);//图片生成
                break;
            case R.id.online_llm_main_imageUnderstanding:
                if(!isAuth){
                    showInfo("SDK未初始化，请先初始化SDK");
                    return;
                }
                jump(ImageUnderstanding.class);//图片理解
                break;
            case R.id.online_llm_main_embedding:
                if(!isAuth){
                    showInfo("SDK未初始化，请先初始化SDK");
                    return;
                }
                jump(EmbeddingActivity.class);//Embedding
                break;
            case R.id.online_llm_main_personatetts:
                if(!isAuth){
                    showInfo("SDK未初始化，请先初始化SDK");
                    return;
                }
                jump(PersonateTTSActivity.class);//personatetts
                break;
            case R.id.online_llm_main_asr:
                if(!isAuth){
                    showInfo("SDK未初始化，请先初始化SDK");
                    return;
                }
                jump(bmModeChoiceActivity.class);//asr
                break;
            case R.id.online_llm_main_fuction:
                if(!isAuth){
                    showInfo("SDK未初始化，请先初始化SDK");
                    return;
                }
                jump(ChatWithFuctionCallActivity.class);//fuctionCall
                break;
            case R.id.online_llm_main_rtasr:
                if(!isAuth){
                    showInfo("SDK未初始化，请先初始化SDK");
                    return;
                }
                jump(RTASRLLMActivity.class);
                break;
            case R.id.online_llm_main_raasr:
                if(!isAuth){
                    showInfo("SDK未初始化，请先初始化SDK");
                    return;
                }
                jump(RAASRLLMActivity.class);
                break;
        }
    }
}
