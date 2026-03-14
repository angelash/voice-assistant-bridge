package com.example.sparkchaindemo.llm.online_llm.tti;


import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.sparkchaindemo.R;
import com.iflytek.sparkchain.core.LLM;
import com.iflytek.sparkchain.core.LLMCallbacks;
import com.iflytek.sparkchain.core.LLMConfig;
import com.iflytek.sparkchain.core.LLMError;
import com.iflytek.sparkchain.core.LLMEvent;
import com.iflytek.sparkchain.core.LLMFactory;
import com.iflytek.sparkchain.core.LLMOutput;
import com.iflytek.sparkchain.core.LLMResult;
/*************************
 * 图片生成Demo
 * create by wxw
 * 2024-12-17
 * **********************************/
public class ttiDemoActivity extends AppCompatActivity implements View.OnClickListener{
    private static final String TAG = "AEELog";
    LLM llm;
    private ImageView imageView;
    private TextView tv_result;

    private Button btn_tti_run_start, btn_tti_arun_start, btn_tti_stop;

    private EditText ed_input;

    /*********
     * 图片生成结果监听回调
     * ***********/
    private LLMCallbacks mLLMCallbacksListener = new LLMCallbacks() {
        @Override
        public void onLLMResult(LLMResult result, Object o) {
            //解析获取的交互结果，示例展示所有结果获取，开发者可根据自身需要，选择获取。
            byte [] bytes        = result.getImage();//一次性返回完整结果，因此不需要获取status去判断结果是否返回完成
            String role          = result.getRole();//获取角色信息
            String sid           = result.getSid();//本次交互的sid
            int completionTokens = result.getCompletionTokens();//获取回答的Token大小
            int promptTokens     = result.getPromptTokens();//包含历史问题的总Tokens大小
            int totalTokens      = result.getTotalTokens();//promptTokens和completionTokens的和，也是本次交互计费的Tokens大小

            showImage(bytes);
            showInfo("图片生成结束。");
        }

        @Override
        public void onLLMEvent(LLMEvent event, Object o) {
            int eventId     = event.getEventID();//获取事件ID
            String eventMsg = event.getEventMsg();//获取事件信息
            String sid      = event.getSid();//本次交互的sid
        }

        @Override
        public void onLLMError(LLMError error, Object o) {
            int errCode   = error.getErrCode();//返回错误码
            String errMsg = error.getErrMsg();//获取错误信息
            String sid    = error.getSid();//本次交互的sid

            String errInfo = "出错了，错误码：" + errCode + ",错误信息：" + errMsg;
            showInfo(errInfo);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.online_llm_tti);
        ed_input = findViewById(R.id.online_llm_tti_input_text);
        imageView = findViewById(R.id.online_llm_tti_output_iv);
        tv_result = findViewById(R.id.online_llm_tti_Notification);
        btn_tti_run_start = findViewById(R.id.online_llm_tti_run_start_btn);
        btn_tti_arun_start = findViewById(R.id.online_llm_tti_arun_start_btn);
        btn_tti_stop = findViewById(R.id.online_llm_tti_stop_btn);
        btn_tti_run_start.setOnClickListener(this);
        btn_tti_arun_start.setOnClickListener(this);
        btn_tti_stop.setOnClickListener(this);
        setLLMConfig();
    }

    @Override
    public void onClick(View view) {
        switch(view.getId()){
            case R.id.online_llm_tti_arun_start_btn:
                if(llm != null){
                    clearImage();
                    showInfo("图片生成中，请稍后.....");
                    tti_arun_start();
                }
                break;
            case R.id.online_llm_tti_run_start_btn:
                if(llm != null){
                    clearImage();
                    showInfo("图片生成中，请稍后.....");
                    new Thread(){
                        @Override
                        public void run() {//由于同步请求后该线程会卡主，为了防止卡主线程，故开启一个线程进行同步请求
                            super.run();
                            tti_run_start();
                        }
                    }.start();
                }
                break;
            case R.id.online_llm_tti_stop_btn:
                if(llm != null){
                    tti_stop();
                    showInfo("已取消图片生成。");
                }
                break;
        }
    }
    /***************
     * 取消交互
     * ****************/
    private void tti_stop(){
        llm.stop();
    }
    /***************
     * 开始交互，异步
     * ****************/
    private void tti_arun_start(){
        String content = ed_input.getText().toString();
        Log.d("SparkChain","content: " + content);
        //异步请求
        llm.arun(content);
    }
    /***************
     * 开始交互，同步
     * ****************/
    private void tti_run_start(){
        String content = ed_input.getText().toString();
        Log.d("SparkChain","content: " + content);
        //同步请求
        LLMOutput syncOutput = llm.run(content);

        //解析获取的结果，示例展示所有结果获取，开发者可根据自身需要，选择获取。
        byte [] bytes        = syncOutput.getImage();//获取交互结果
        int errCode          = syncOutput.getErrCode();//获取结果ID,0:调用成功，非0:调用失败
        String errMsg        = syncOutput.getErrMsg();//获取错误信息
        String role          = syncOutput.getRole();//获取角色信息
        String sid           = syncOutput.getSid();//获取本次交互的sid
        int completionTokens = syncOutput.getCompletionTokens();//获取回答的Token大小
        int promptTokens     = syncOutput.getPromptTokens();//包含历史问题的总Tokens大小
        int totalTokens      = syncOutput.getTotalTokens();//promptTokens和completionTokens的和，也是本次交互计费的Tokens大小

        if(errCode == 0) {
            if(bytes!=null)
                Log.d(TAG, "同步调用：" +  bytes.length);
            else {
                Log.d(TAG, "同步调用：获取结果失败");
                return;
            }
            showImage(bytes);
            showInfo("图片生成结束。");
        }else {
            Log.d(TAG, "同步调用：" +  "errCode" + errCode + " errMsg:" + errMsg);
        }
    }


    private void showImage(byte [] bytes){
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);//把二进制图片流转换成图片
                imageView.setImageBitmap(Bitmap.createScaledBitmap(bmp,bmp.getWidth(),bmp.getHeight(),false));//把图片设置到对应的控件
            }
        });
    }

    private void clearImage(){
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                imageView.setImageDrawable(null);
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
    /***************
     * 配置文本交互LLM，注册结果监听回调
     * ******************/
    private void setLLMConfig(){
        LLMConfig llmConfig = LLMConfig.builder()
                .maxToken(2048);//回答的tokens的最大长度。其他功能参数请参考集成文档
        /********************
         * 构建图片生成的LLM，入参为要生成的图片尺寸。当前支持：
         * 512*512 默认
         * 640*360
         * 640*480
         * 640*640
         * 680*512
         * 512*680
         * 768*768
         * 720*1280
         * 1280*720
         * 1024*1024
         * **************************/
        llm = LLMFactory.imageGeneration(512,512,llmConfig);
        llm.registerLLMCallbacks(mLLMCallbacksListener);
    }
}
