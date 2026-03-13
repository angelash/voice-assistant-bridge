package com.example.sparkchaindemo.llm.online_llm.image_understanding;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
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
import com.iflytek.sparkchain.core.Memory;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
/*************************
 * 图片理解Demo
 * create by wxw
 * 2024-12-17
 * **********************************/
public class ImageUnderstanding extends AppCompatActivity implements View.OnClickListener{
    private static final String TAG = "AEELog";
    private static final int AUDIO_FILE_SELECT_CODE = 1024;

    private Button btn_imgInput,btn_arunStart,btn_stop;

    private TextView tv_Notification;

    private EditText ed_textInput;

    private String imagePath = null;
    private int token = 0;
    LLM llm;
    /*********
     * 图片理解结果监听回调
     * ***********/
    private LLMCallbacks mLLMCallbacksListener = new LLMCallbacks() {
        @Override
        public void onLLMResult(LLMResult llmResult, Object usrContext) {
            if(token == (int)usrContext){
                Log.d(TAG,"onLLMResult\n");

                //解析获取的交互结果，示例展示所有结果获取，开发者可根据自身需要，选择获取。
                String content       = llmResult.getContent();//获取交互结果
                int status           = llmResult.getStatus();//返回结果状态
                String role          = llmResult.getRole();//获取角色信息
                String sid           = llmResult.getSid();//本次交互的sid
                int completionTokens = llmResult.getCompletionTokens();//获取回答的Token大小
                int promptTokens     = llmResult.getPromptTokens();//包含历史问题的总Tokens大小
                int totalTokens      = llmResult.getTotalTokens();//promptTokens和completionTokens的和，也是本次交互计费的Tokens大小

                Log.e(TAG,"onLLMResult:" + content);
                if(content != null) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            tv_Notification.append(content);
                            toend();
                        }
                    });
                }
                if(status == 2){//2表示大模型结果返回完成
                    Log.e(TAG,"completionTokens:" + completionTokens + "promptTokens:" + promptTokens + "totalTokens:" + totalTokens);
                }
            }
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
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    tv_Notification.append("错误:" + " err:" + errCode + " errDesc:" + errMsg + "\n");
                }
            });
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.online_llm_image_understanding);
        initView();
    }

    private void initView(){
        btn_imgInput = findViewById(R.id.online_llm_image_understanding_imginput);
        btn_arunStart = findViewById(R.id.online_llm_image_understanding_arun_start_btn);
        btn_stop = findViewById(R.id.online_llm_image_understanding_arun_stop_btn);
        tv_Notification = findViewById(R.id.online_llm_image_understanding_Notification);
        tv_Notification.setMovementMethod(new ScrollingMovementMethod());
        ed_textInput = findViewById(R.id.online_llm_image_understanding_input_text);
        btn_imgInput.setOnClickListener(this);
        btn_arunStart.setOnClickListener(this);
        btn_stop.setOnClickListener(this);
        setLLMConfig();
    }

    private void showInfo(String text){
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                tv_Notification.setText(text);
            }
        });
    }
    /***************
     * 配置文本交互LLM，注册结果监听回调
     * ******************/
    private void setLLMConfig(){
        LLMConfig llmConfig = LLMConfig.builder()
                .domain("imagev3")
                .maxToken(2048);//回答的tokens的最大长度。其他配置参数见集成文档
        /*******************
         * 带有memory的LLM初始化
         * windowMemory:通过会话轮数控制上下文范围，即一次提问和一次回答为一轮会话交互。用户可指定会话关联几轮上下文。
         * tokenMemory:通过Token总长度控制上下文范围，1 token 约等于1.5个中文汉字 或者 0.8个英文单词。用户可指定历史会话Token长度
         * ************************/
        Memory window_memory = Memory.windowMemory(5);
        llm = LLMFactory.imageUnderstanding(llmConfig,window_memory);
//      Memory token_memory = Memory.tokenMemory(1024);
//      llm = LLMFactory.textGeneration(llmConfig,token_memory);

        llm.registerLLMCallbacks(mLLMCallbacksListener);
    }
    /***************
     * 开始交互，异步
     * ****************/
    private void startChat() {
        if(llm == null){
            Log.e(TAG,"startChat failed,please setLLMConfig before!");
            return;
        }
        String usrInputText = ed_textInput.getText().toString();
        Log.d(TAG,"用户输入：" + usrInputText);
        if(usrInputText.length() >= 1)
            tv_Notification.append("\n输入:\n    " + usrInputText  + "\n");
        token++;
        Log.e(TAG,"SparkChain imagePath:\n" + imagePath);
        int ret = -1;
        if(imagePath!=null) {
            llm.clearHistory();//重新传图片前，需要清空memory，因为memory带有上一次图片的信息
            ret = llm.arun(usrInputText, readFileByBytes(imagePath), token);//首轮会话需要带上图片信息
        }else {
            ret = llm.arun(usrInputText, token);//多轮会话可以不用携带图片信息，SDK会在历史会话中自动拼接图片信息。
        }
        if(ret != 0){
            Log.e(TAG,"SparkChain failed:\n" + ret);
        }

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                ed_textInput.setText("");
                tv_Notification.append("输出:\n    ");
                imagePath = null;//第一轮会话后清空图片信息
            }
        });
    }

    /***************
     * 开始交互，同步。仅展示如何使用，demo中未使用此方式
     * ****************/
    private void syncStartChat(){
        if(llm == null){
            Log.e(TAG,"startChat failed,please setLLMConfig before!");
            return;
        }
        String usrInputText = ed_textInput.getText().toString();
        Log.d(TAG,"用户输入：" + usrInputText);
        if(usrInputText.length() >= 1)
            tv_Notification.append("\n输入:\n    " + usrInputText  + "\n");
        token++;
        Log.e(TAG,"SparkChain imagePath:\n" + imagePath);
        int ret = -1;
        LLMOutput syncOutput = null;
        if(imagePath!=null){
            llm.clearHistory();//重新传图片前，需要清空memory，因为memory带有上一次图片的信息
            syncOutput = llm.run(usrInputText,readFileByBytes(imagePath));
        } else{
            syncOutput = llm.run(usrInputText);
        }
        //以下信息需要开发者根据自身需求，如无必要，可不需要解析执行。
        String content       = syncOutput.getContent();//获取调用结果
        int errCode          = syncOutput.getErrCode();//获取结果ID,0:调用成功，非0:调用失败
        String errMsg        = syncOutput.getErrMsg();//获取错误信息
        String role          = syncOutput.getRole();//获取角色信息
        String sid           = syncOutput.getSid();//获取本次交互的sid
        int completionTokens = syncOutput.getCompletionTokens();//获取回答的Token大小
        int promptTokens     = syncOutput.getPromptTokens();//包含历史问题的总Tokens大小
        int totalTokens      = syncOutput.getTotalTokens();//promptTokens和completionTokens的和，也是本次交互计费的Tokens大小


        if(errCode == 0) {
            Log.i(TAG, "同步调用：" +  role+ ":" + content);
            showInfo(content);
        }else {
            String results = "同步调用：" +  "errCode" + errCode + " errMsg:" + errMsg;
            showInfo(results);
            Log.e(TAG, results);
        }
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                ed_textInput.setText("");
                tv_Notification.append("输出:\n    ");
                imagePath = null;
            }
        });
    }

    /***************
     * 取消交互
     * ****************/
    private void stop(){
        if(llm == null){
            Log.e(TAG,"startChat failed,please setLLMConfig before!");
            return;
        }
        llm.stop();
    }

    @Override
    public void onClick(View view) {
        switch (view.getId()){
            case R.id.online_llm_image_understanding_imginput:
                showFileChooser();
                break;
            case R.id.online_llm_image_understanding_arun_start_btn:
                startChat();
//                syncStartChat();
                break;
            case R.id.online_llm_image_understanding_arun_stop_btn:
                stop();
                break;
        }
    }
    /***************
     * 调用文本管理器，让用户选择要传入的图片
     * ****************/
    private void showFileChooser() {
        Log.d(TAG,"showFileChooser");
        //调用系统文件管理器
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        //设置文件格式
        intent.setType("*/*");
        startActivityForResult(intent, AUDIO_FILE_SELECT_CODE);
    }
    /***************
     * 监听用户选择的图片，获取图片所在的路径
     * ****************/
    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        switch (requestCode) {
            case AUDIO_FILE_SELECT_CODE:
                if (data != null) {
                    Uri uri = data.getData();
                    String path = GetFilePathFromUri.getFileAbsolutePath(this, uri);
                    imagePath = path;
                }
                showInfo("图片已设置完成:"+imagePath);
                break;
        }
        super.onActivityResult(requestCode, resultCode, data);

        Log.d(TAG,"imagePath = " + imagePath);
    }
    /***************
     * 把对应路径的图片转换成二进制流
     * ****************/
    private byte[] readFileByBytes(String fileName) {
        FileInputStream in = null;
        try {
            in = new FileInputStream(fileName);
        } catch (FileNotFoundException e) {
            Log.e("AEE", "readFileByBytes:" + e.toString());
        }
        byte[] bytes = null;
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(1024);
            byte[] temp = new byte[1024];
            int size = 0;
            while ((size = in.read(temp)) != -1) {
                out.write(temp, 0, size);
            }
            in.close();
            bytes = out.toByteArray();
        } catch (Exception e1) {
            e1.printStackTrace();
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return bytes;
    }
    /*************************
     * 显示控件自动下移
     * *******************************/
    public void toend(){
        int scrollAmount = tv_Notification.getLayout().getLineTop(tv_Notification.getLineCount()) - tv_Notification.getHeight();
        if (scrollAmount > 0) {
            tv_Notification.scrollTo(0, scrollAmount+10);
        }
    }
}
