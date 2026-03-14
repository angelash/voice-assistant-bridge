package com.example.sparkchaindemo.llm.online_llm.function;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

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
import com.iflytek.sparkchain.core.LLMTools;

/*************************
 * 文本交互中FunctionCall功能体验Demo
 * create by wxw
 * 2024-12-17
 * **********************************/
public class ChatWithFuctionCallActivity extends AppCompatActivity {
    private static final String TAG = "AEELog";
    private Button btn_startChat, btn_stopChat;
    private TextView chatText;
    private EditText inputText;
    // 设定flag，在输出未完成时无法进行发送
    private boolean sessionFinished = true;

    private int token = 0;
    private LLM llm;


    /*********
     * 文本交互结果监听回调
     * ***********/
    LLMCallbacks llmCallbacks = new LLMCallbacks() {
        @Override
        public void onLLMResult(LLMResult llmResult, Object usrContext) {
            if(token == (int)usrContext){//本次返回的结果是否跟请求的问题是否匹配，通过用户自定义标识判断。
                //解析获取的交互结果，示例展示所有结果获取，开发者可根据自身需要，选择获取。
                String fuctionResult = llmResult.getFunctionCall();//获取fuctioncall结果
                String content       = llmResult.getContent();//获取交互结果
                int status           = llmResult.getStatus();//返回结果状态
                String role          = llmResult.getRole();//获取角色信息
                String sid           = llmResult.getSid();//本次交互的sid
                String rawResult     = llmResult.getRaw();//星火大模型原始输出结果。要求SDK1.1.5版本以后才能使用
                int completionTokens = llmResult.getCompletionTokens();//获取回答的Token大小
                int promptTokens     = llmResult.getPromptTokens();//包含历史问题的总Tokens大小
                int totalTokens      = llmResult.getTotalTokens();//promptTokens和completionTokens的和，也是本次交互计费的Tokens大小

                Log.e(TAG,"onLLMResult:" + content);

                if(content != null) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            chatText.append(content);
                            toend();
                        }
                    });
                }

                if(fuctionResult != null) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            chatText.append(fuctionResult);
                            toend();
                        }
                    });
                }

                if(status == 2){//2表示大模型结果返回完成
                    Log.e(TAG,"completionTokens:" + completionTokens + "promptTokens:" + promptTokens + "totalTokens:" + totalTokens);
                    sessionFinished = true;
                }
            }
        }

        @Override
        public void onLLMEvent(LLMEvent event, Object usrContext) {
            Log.d(TAG,"onLLMEvent\n");
            Log.w(TAG,"onLLMEvent:" + " " + event.getEventID() + " " + event.getEventMsg());
        }

        @Override
        public void onLLMError(LLMError error, Object usrContext) {
            Log.d(TAG,"onLLMError\n");
            Log.d(TAG,"onLLMError sid:"+error.getSid());
            Log.e(TAG,"errCode:" + error.getErrCode() + "errDesc:" + error.getErrMsg());
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    chatText.append("错误:" + " err:" + error.getErrCode() + " errDesc:" + error.getErrMsg() + "\n");
                }
            });
            sessionFinished = true;

        }
    };
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.online_llm_chatwithfuction);

        initView();
        initButtonClickListener();
        setLLMConfig();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }
    /***************
     * 配置文本交互LLM，注册结果监听回调
     * ******************/
    private void setLLMConfig(){
        Log.d(TAG,"setLLMConfig");
        /****************************************
         * 选择要使用的大模型类型(需开通相应的授权)：
         * general:      通用大模型Spark Lite版本
         * generalv3：   通用大模型Spark Pro版本
         * generalv3.5:  通用大模型Spark Max版本
         * 4.0Ultra：    通用大模型Spark4.0 Ultra版本
         * pro-128k：    通用大模型pro128k版本
         * max-32k：     通用大模型max32k版本
         * *************************************/
        LLMConfig llmConfig = LLMConfig.builder()
                .domain("4.0Ultra");
//        llmConfig.showRefLabel(true);//返回信源信息，4.0Utral版本支持，其他版本传递无效。

//        Memory window_memory = Memory.windowMemory(5);
        llm = LLMFactory.textGeneration(llmConfig);
        llm.registerLLMCallbacks(llmCallbacks);
    }

    private String getAndroidId() {
        try {
            return Settings.Secure.getString(this.getContentResolver(),Settings.Secure.ANDROID_ID);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return "";
    }
    /***************
     * 取消本次交互
     * ****************/
    private void stopChat(){
        if(llm == null){
            Log.e(TAG,"startChat failed,please setLLMConfig before!");
            return;
        }
        llm.stop();
    }

    private void startSyncChatWithFuction(){
        LLMTools tools = new LLMTools();
        tools.setType("functions");
        tools.setDescription(fuction.fuction);

        String question = "今天合肥的天气怎么样。";
        LLMOutput syncOutput = llm.run(question,tools,60);
        //解析获取的结果，示例展示所有结果获取，开发者可根据自身需要，选择获取。
        String content       = syncOutput.getContent();//获取调用结果
        String syncRaw       = syncOutput.getRaw();//星火原始回复
        int errCode          = syncOutput.getErrCode();//获取结果ID,0:调用成功，非0:调用失败
        String errMsg        = syncOutput.getErrMsg();//获取错误信息
        String role          = syncOutput.getRole();//获取角色信息
        String sid           = syncOutput.getSid();//获取本次交互的sid
        int completionTokens = syncOutput.getCompletionTokens();//获取回答的Token大小
        int promptTokens     = syncOutput.getPromptTokens();//包含历史问题的总Tokens大小
        int totalTokens      = syncOutput.getTotalTokens();//promptTokens和completionTokens的和，也是本次交互计费的Tokens大小

        if(errCode == 0) {
            Log.i(TAG, "同步调用：" +  role + ":" + content);
        }else {
            Log.e(TAG, "同步调用：" +  "errCode" + errCode + " errMsg:" + errMsg);
        }
    }


    /***************
     * 开始交互，异步
     * ****************/
    private void startChatWithFuction() {
        if(llm == null){
            Log.e(TAG,"startChat failed,please setLLMConfig before!");
            return;
        }

        LLMTools tools = new LLMTools();
        tools.setType("functions"); //function:functionCall功能
        tools.setDescription(fuction.fuction);//function协议，开发者自己按照规定的格式定义。可参考集成文档中高级功能部分。

        String usrInputText = inputText.getText().toString();
        Log.d(TAG,"用户输入：" + usrInputText);
        if(usrInputText.length() >= 1)
            chatText.append("\n输入:\n    " + usrInputText  + "\n");
        token++;
        int ret = llm.arun(usrInputText,tools,token);
        if(ret != 0){
            Log.e(TAG,"SparkChain failed:\n" + ret);
            return;
        }

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                inputText.setText("");
                chatText.append("输出:\n    ");
            }
        });

        sessionFinished = false;
    }

    private void initButtonClickListener() {
        btn_startChat.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startChatWithFuction();
                toend();
            }
        });

        btn_stopChat.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                stopChat();
            }
        });
        // 监听文本框点击时间,跳转到底部
        inputText.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                toend();
            }
        });
    }

    private void initView() {
        btn_startChat = findViewById(R.id.online_llm_function_start);
        chatText = findViewById(R.id.online_llm_function_notification);
        inputText = findViewById(R.id.online_llm_function_input);
        btn_stopChat = findViewById(R.id.online_llm_function_stop);
        chatText.setMovementMethod(new ScrollingMovementMethod());

        GradientDrawable drawable = new GradientDrawable();
        // 设置圆角弧度为5dp
        drawable.setCornerRadius(dp2px(this, 5f));
        // 设置边框线的粗细为1dp，颜色为黑色【#000000】
        drawable.setStroke((int) dp2px(this, 1f), Color.parseColor("#000000"));
        inputText.setBackground(drawable);
    }

    private float dp2px(Context context, float dipValue) {
        if (context == null) {
            return 0;
        }
        final float scale = context.getResources().getDisplayMetrics().density;
        return (float) (dipValue * scale + 0.5f);
    }

    public static void showToast(final Activity context, final String content){

        context.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                int random = (int) (Math.random()*(1-0)+0);
                Toast.makeText(context,content,random).show();
            }
        });

    }
    /*************************
     * 显示控件自动下移
     * *******************************/
    public void toend(){
        int scrollAmount = chatText.getLayout().getLineTop(chatText.getLineCount()) - chatText.getHeight();
        if (scrollAmount > 0) {
            chatText.scrollTo(0, scrollAmount+10);
        }
    }
}