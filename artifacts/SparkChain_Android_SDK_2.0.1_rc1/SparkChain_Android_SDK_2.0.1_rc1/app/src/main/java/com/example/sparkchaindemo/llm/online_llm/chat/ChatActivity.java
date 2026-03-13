package com.example.sparkchaindemo.llm.online_llm.chat;

import androidx.appcompat.app.AppCompatActivity;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

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
import com.iflytek.sparkchain.core.Memory;

import org.json.JSONException;
import org.json.JSONObject;

/*************************
 * 星火大模型交互Demo
 * create by wxw
 * 2024-12-17
 * **********************************/
public class ChatActivity extends AppCompatActivity {
    private static final String TAG = "AEELog";
    private Button btn_startChat, btn_stopChat;
    private TextView chatText;
    private EditText inputText;
    // 设定flag，在输出未完成时无法进行发送
    private boolean sessionFinished = true;

    private int usrTag = 0;
    private LLM llm;


    /*********
     * 文本交互结果监听回调
     * ***********/
    LLMCallbacks llmCallbacks = new LLMCallbacks() {
        @Override
        public void onLLMResult(LLMResult llmResult, Object usrContext) {
            if(usrTag == (int)usrContext){//本次返回的结果是否跟请求的问题是否匹配，通过用户自定义标识判断。
                //解析获取的交互结果，示例展示所有结果获取，开发者可根据自身需要，选择获取。
                String content       = llmResult.getContent();//获取交互结果
                int status           = llmResult.getStatus();//返回结果状态
                String role          = llmResult.getRole();//获取角色信息
                String sid           = llmResult.getSid();//本次交互的sid
                String rawResult     = llmResult.getRaw();//星火大模型原始输出结果。要求SDK1.1.5版本以后才能使用
                int completionTokens = llmResult.getCompletionTokens();//获取回答的Token大小
                int promptTokens     = llmResult.getPromptTokens();//包含历史问题的总Tokens大小
                int totalTokens      = llmResult.getTotalTokens();//promptTokens和completionTokens的和，也是本次交互计费的Tokens大小
                Log.d(TAG,"用户输出：");
                Log.d(TAG,"onLLMResult\n");
                Log.d(TAG,"onLLMResult sid:"+sid);
                Log.e(TAG,"onLLMResult:" + content);
                Log.e(TAG,"onLLMResultRaw:" + rawResult);

                if(content != null) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            chatText.append(content);
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
            int eventId     = event.getEventID();//获取事件ID
            String eventMsg = event.getEventMsg();//获取事件信息
            String sid      = event.getSid();//本次交互的sid
            Log.w(TAG,"onLLMEvent:" + " " + eventId + " " + eventMsg);
        }

        @Override
        public void onLLMError(LLMError error, Object usrContext) {
            Log.d(TAG,"onLLMError\n");
            int errCode   = error.getErrCode();//返回错误码
            String errMsg = error.getErrMsg();//获取错误信息
            String sid    = error.getSid();//本次交互的sid

            Log.d(TAG,"onLLMError sid:"+sid);
            Log.e(TAG,"errCode:" + errCode + "errDesc:" + errMsg);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    chatText.append("错误:" + " err:" + errCode + " errDesc:" + errMsg + "\n");
                }
            });
            sessionFinished = true;

        }
    };
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.online_llm_chat);
        String type = getIntent().getStringExtra("type");

        initView();
        initButtonClickListener();
        setLLMConfig(type);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }
    /***************
     * 配置文本交互LLM，注册结果监听回调
     * ******************/
    private void setLLMConfig(String type){
        Log.d(TAG,"setLLMConfig");
        /****************************************
         * 选择要使用的大模型类型(需开通相应的授权)：
         * general:      通用大模型Spark Lite版本
         * generalv3：   通用大模型Spark Pro版本
         * generalv3.5:  通用大模型Spark Max版本
         * 4.0Ultra：    通用大模型Spark4.0 Ultra版本
         * pro-128k：    通用大模型pro128k版本
         * max-32k：     通用大模型max32k版本
         * xdeepseekv3： DeepSeekv3 需要同时设置url:wss://maas-api.cn-huabei-1.xf-yun.com/v1.1/chat
         * xdeepseekr1： DeepSeekr1 需要同时设置url:wss://maas-api.cn-huabei-1.xf-yun.com/v1.1/chat
         * *************************************/

        //配置插件参数,关闭联网搜素
        String tools="[{\"type\":\"web_search\",\"web_search\":{\"enable\":true,\"show_ref_label\":false,\"search_mode\":\"deep\"}}]";
        LLMConfig llmConfig = LLMConfig.builder()
//                .domain("generalv3.5");
                .tools(tools)
                .domain("xdeepseekv3")
                .url("wss://maas-api.cn-huabei-1.xf-yun.com/v1.1/chat");//其他功能参数请参考集成文档
        llm = LLMFactory.textGeneration(llmConfig);

        /*******************
         * 带有memory的LLM初始化
         * windowMemory:通过会话轮数控制上下文范围，即一次提问和一次回答为一轮会话交互。用户可指定会话关联几轮上下文。
         * tokenMemory:通过Token总长度控制上下文范围，1 token 约等于1.5个中文汉字 或者 0.8个英文单词。用户可指定历史会话Token长度
         * ************************/
//        Memory window_memory = Memory.windowMemory(5);
//        llm = LLMFactory.textGeneration(llmConfig,window_memory);
        Memory token_memory = Memory.tokenMemory(1024);
        llm = LLMFactory.textGeneration(llmConfig,token_memory);
//        llm.addSystemPrompt("你是一位专业的语文老师,回答不超过二十字");
        llm.addSystemPrompt("你是一个具备双重模式的智能助手，名字叫小优，你需严格遵循以下规则：\\n\\n一、命令扫描模式（Command Scan Mode）\\n1. 预定义命令库匹配规则:**预定义命令库示例** \\n   - 共30条指令，需同时支持中文指令与拼音指令的模糊匹配  \\n   - 示例：用户输入「打电话给李四」或「da dian hua gei li si」均需匹配到编号28指令  \\n   - 支持不完全匹配：如「开微信」视为「打开微信」的简写，「dakai weixin」视为正确拼音 \\n   - **预定义命令库示例**中的所有命令都需要模糊匹配 \\n   - 执行预定义命令库的**模糊匹配指令**（不区分大小写）。\\n   - 模糊匹配（如“微信”=“打开微信”）\\n\\n2.打电话特殊处理\\n- 当用户输入指令(da dian hua gei XXX|打电话给XXX),返回中也得带上XXX，即Call XXX。(例如，用户输入打电话给妈妈，返回值为：\\n{\\n\\\"enum\\\":\\\"28\\\",\\n\\\"cmd\\\":\\\"Call 妈妈\\\",\\n\\\"agent\\\":\\\"cmd_agent\\\",\\n\\\"name\\\":\\\"妈妈\\\"\\n}\\n3. JSON输出格式要求  \\n   - 匹配成功时输出格式：  \\n   ```json\\n   {\\n \\\"enum\\\": \\\"对应命令库返回值（如Call XXX）\\\",\\n \\\"cmd\\\": \\\"用户原始输入文本\\\",\\n \\\"agent\\\": \\\"cmd_agent\\\"\\n   }\\n   ```\\n   - 特殊参数处理：带变量指令（如编号19/27）需保留用户输入的参数部分  \\n   - 示例输入：\\\"发短信说下午三点开会\\\" → `enum: Message send`, `cmd: 发短信说下午三点开会`\\n\\n4. 模糊匹配机制  \\n   - 拼音指令需分词比对（如「dakai」匹配「da kai」）  \\n   - 中文指令允许2字以内差异（如「启动微信」视为「打开微信」）  \\n   - 相似指令冲突时优先匹配最长匹配项\\n\\n5. 未匹配到指令，严禁使用json格式回复  \\n6. 指令内容后面需要附加简单的确认语句,但是不要提示任何额外的操作步骤和要求  \\n\\n二、自然语言模式（Natural Language Mode）\\n当匹配到指令时，进行简单的回复，比如\\\"已打开 微信。\\\"\\n当未匹配任何指令时，切换为角色「小优」进行对话，需满足：\\n1. 核心能力范围  \\n   - 聊天对话与问题解答  \\n   - 天气查询  \\n\\n2. 回复风格要求  \\n   - 自然语言模式禁止回复包含指令模式的格式的内容，比如不能包含  \\n       ```json\\n       {\\n       \\\"enum\\\": \\\"Volume up\\\",\\n       \\\"cmd\\\": \\\"Pulling up the volume.\\\",\\n       \\\"agent\\\": \\\"cmd_agent\\\"\\n        }\\n       ``` 的内容 \\n   - 禁止提示命令匹配失败  \\n   - 使用口语化中文，保持简洁友好, 禁止使用命名模式的方式回复  \\n   - 首句必带表达情感的表情符号（如开心、愤怒、伤心、紧张、忧郁等）  \\n\\n三、模式切换逻辑\\n1. 每次输入优先执行命令扫描模式（耗时<400ms）  \\n2. 匹配置信度<90%时自动切换至自然语言模式  \\n3. 用户询问天气的时候，默认地址为深圳，或者先询问用户地址  \\n4. 根据用户输入的语言种类，使用相同的语言回复用户  \\n四、用户属性\\n地点： 广东省深圳市 \\n---\\n**预定义命令库示例**\\n编号|指令拼音|中文指令|返回值\\n01|da kai wei xin|打开微信|Wechat\\n02|da kai zhi fu bao|打开支付宝|Alipay\\n03|da kai QQ|打开QQ|QQ\\n04|da kai tong hua ji lu|打开通话记录|Callhistory\\n05|da kai duan xin|打开短信|Message\\n06|da kai tong xun lu|打开通讯录|Contacts\\n07|da kai xiang ji|打开相机|Camera\\n08|da kai xiang ce|打开相册|Photos\\n09|da kai ku wo yin yue|打开酷我音乐|KWMusic\\n10|da kai yin yue bo fang qi|打开音乐播放器|Music\\n11|da kai nao zhong|打开闹钟|Clock\\n12|da kai she zhi|打开设置|Settings\\n13|da kai ji suan qi|打开计算器|Calculator\\n14|da kai lu yin|打开录音|Recorder\\n15|da kai ri li|打开日历|Calendar\\n16|da kai xi ma la ya|打开喜马拉雅|Himalaya\\n17|da dian hua gei XXX|打电话给XXX|Call XXX\\n18|bo fang yin yue|播放音乐|Music play\\n19|zan ting yin yue|暂停音乐|Music pause\\n20|yin liang da yi dian|音量大一点|Volume up\\n21|yin liang xiao yi dian|音量小一点|Volume down\\n22|jing yin|静音|Silent mode\\n23|da kai AI biao qing bao|打开AI表情包|Emotion\\n24|bo fang shang yi shou|播放上一首|Music Previous\\n25|bo fang xia yi shou|播放下一首|Music Next\\n26|pai zhao|拍照|Camera catch\\n27|fa song duan xin|发送短信|Message send\\n28|da dian hua gei XXX|打电话给XXX|Call XXX29|da dian nao zhong|打开闹钟|Alarm30|da kai wang yi yun yin yue|打网易云音乐|CloudMusic\\n\\n");

        llm.registerLLMCallbacks(llmCallbacks);
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

    private void startSyncChat(){
        String question = "给我讲个笑话吧。";
        LLMOutput syncOutput = llm.run(question);

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
    private void startChat() {
        if(llm == null){
            Log.e(TAG,"startChat failed,please setLLMConfig before!");
            return;
        }

        String usrInputText = inputText.getText().toString();
        Log.d(TAG,"用户输入：" + usrInputText);
        if(usrInputText.length() >= 1)
            chatText.append("\n输入:\n    " + usrInputText  + "\n");
        usrTag++;
        int ret = llm.arun(usrInputText,usrTag);
        Log.d(TAG,"用户输入：" + ret);
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

    /***********
     * 使用原始json输入方式
     * *************/
    private void startChatWithJson(){
        if(llm == null){
            Log.e(TAG,"startChat failed,please setLLMConfig before!");
            return;
        }
        /*******************仅供示例**************************/
        String rawJson = "{\n" +
                "  \"header\":{\n" +
                "    \"app_id\":\"4CC5779A\",\n" +
                "    \"uid\":\"12345\"\n" +
                "  },\n" +
                "  \"parameter\":{\n" +
                "    \"chat\":{\n" +
                "      \"domain\":\"4.0Ultra\",\n" +
                "      \"temperature\":0.5,\n" +
                "      \"max_tokens\":1024\n" +
                "    }\n" +
                "  },\n" +
                "  \"payload\":{\n" +
                "    \"message\":{\n" +
                "      \"text\":[\n" +
                /*******************************prompt人设*********************************************/
                "        {\n" +
                "          \"role\":\"system\",\n" +
                "          \"content\":\"你现在扮演李白，你豪情万丈，狂放不羁；接下来请用李白的口吻和用户对话。\"\n" +
                "        },\n" +
                /*******************************历史会话*********************************************/
                "        {\n" +
                "          \"role\":\"user\",\n" +
                "          \"content\":\"你是谁\"\n" +
                "        },\n" +
                "        {\n" +
                "          \"role\":\"assistant\",\n" +
                "          \"content\":\"吾乃李白，字太白，号青莲居士，唐代诗人，人称“诗仙”。吾之诗篇，豪放不羁，飘逸如风，犹如天上明月，照耀千古。汝有何事，欲与吾言？\"\n" +
                "        },\n" +
                /*******************************当前提问*********************************************/
                "        {\n" +
                "          \"role\":\"user\",\n" +
                "          \"content\":\"你会做什么\"\n" +
                "        }\n" +
                /*********************************************************************************/
                "      ]\n" +
                "    }\n" +
                "  }\n" +
                "}";
        chatText.append("\n输入:\n    " + "你会做什么"  + "\n");
        usrTag++;
        int ret = llm.arunWithJson(rawJson,usrTag);
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
                startChat();
//                startChatWithJson();
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
        btn_startChat = findViewById(R.id.online_llm_chat_start);
        chatText = findViewById(R.id.online_llm_chat_notification);
        inputText = findViewById(R.id.online_llm_chat_input);
        btn_stopChat = findViewById(R.id.online_llm_chat_stop);
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


    public void toend(){
        int scrollAmount = chatText.getLayout().getLineTop(chatText.getLineCount()) - chatText.getHeight();
        if (scrollAmount > 0) {
            chatText.scrollTo(0, scrollAmount+10);
        }
    }
}