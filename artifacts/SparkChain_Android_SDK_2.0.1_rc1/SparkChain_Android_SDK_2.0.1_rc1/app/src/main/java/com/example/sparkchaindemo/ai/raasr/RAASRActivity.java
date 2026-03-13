package com.example.sparkchaindemo.ai.raasr;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.sparkchaindemo.R;
import com.example.sparkchaindemo.llm.online_llm.image_understanding.GetFilePathFromUri;
import com.example.sparkchaindemo.utils.FileUtils;
import com.iflytek.sparkchain.core.raasr.RAASR;
import com.iflytek.sparkchain.core.raasr.RAASRCallbacks;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
/*************************
 * 录音文件转写Demo
 * create by wxw
 * 2024-12-17
 * **********************************/
public class RAASRActivity extends AppCompatActivity implements View.OnClickListener {
    private static final String TAG = "AEELog";
    private TextView tv_result;
    private RAASR mRAASR;
    private String RAASRAPIKEY = "";
    private static final int AUDIO_FILE_SELECT_CODE = 1024;
    private String orderId = null;

    private long resultGenTime = 0; //raasr转写结果预计生成时间
    private String requestId;
    private String resultTypes = "transfer";
    private TextView tv_audioPathInfo;
    private String audioPath = "/sdcard/iflytek/asr/cn_test.pcm";
    private Button btn_stop,btn_upload;

    RAASRCallbacks mRAASRCallbacks = new RAASRCallbacks() {
        @Override
        public void onResult(RAASR.RaAsrResult raAsrResult, Object usrTag) {
            //以下信息需要开发者根据自身需求，如无必要，可不需要解析执行。
            int status                                 = raAsrResult.getStatus();//订单流程状态
            String orderResult                         = raAsrResult.getOrderResult();//转写结果
            RAASR.RaAsrTransResult[] raAsrTransResults = raAsrResult.getTransResult();//翻译结果实例
            orderId                                    = raAsrResult.getOrderId();//转写订单ID
            long originalDuration                      = raAsrResult.getOriginalDuration();//原始音频时长，单位毫秒
            long realDuration                          = raAsrResult.getRealDuration();//真实音频时长，单位毫秒
            int taskEstimateTime                       = raAsrResult.getTaskEstimateTime();//订单预估耗时，单位毫秒
            String usrContext                          = (String)usrTag;
            resultGenTime = System.currentTimeMillis()+taskEstimateTime;

            String info = "{status:"+status+",orderId:"+orderId+",originalDuration:"
                    +originalDuration+",realDuration:"+realDuration+",taskEstimateTime:"+taskEstimateTime+",usrContext:"+usrContext+"}\n";
            Log.d(TAG,info);
            switch(usrContext){
                case "UPLOAD":
                    Log.d(TAG,"UPLOAD");
                    showInfo("音频上传成功！订单号为:"+orderId+"\n");
                    setStopButton(btn_stop,false);
                    seleteResult();
                    break;
                case "SELETE":
                    Log.d(TAG,"SELETE");
                    FileUtils.longLog(TAG,orderResult+"\n");
                    if("transfer".equals(resultTypes)){
                        if(!TextUtils.isEmpty(orderResult)) {
                            showInfo("转写结果：" + analysisResult(orderResult) + "\n");
                            setStopButton(btn_upload,true);
                        }else {
                            showInfo("未查询到转写结果，正在重新查询...\n");
                            seleteResult();
                        }
                    }else if("translate".equals(resultTypes)){
                        String transResult = "";
                        for (int i = 0; i < raAsrTransResults.length; i++) {
                            transResult = transResult + raAsrTransResults[i].getDst();
                        }
                        showInfo("翻译结果："+transResult+"\n");
                    }
                    break;
            }
            toend();
        }

        @Override
        public void onError(RAASR.RaAsrError raAsrError, Object o) {
            String errMsg  = raAsrError.getErrMsg();//错误信息
            int errCode    = raAsrError.getCode();//错误码
            String orderId = raAsrError.getOrderId();//转写订单ID
            int failType   = raAsrError.getFailType();//订单异常状态
            String info = "{errMsg:"+errMsg+",errCode:"+errCode+",orderId:"+orderId+",failType:"+failType+"}\n";
            Log.d(TAG,info);
        }

        @Override
        public void onProcess(double v, double v1, double v2, double v3, Object o) {
            String info = "上传进度 - 总计: "+v2+" 字节, 已上传: "+v3+" 字节\n";
            //Log.d(TAG,info);
            showInfo("process："+info);
            //String info = catche + "正在查询订单号为:"+orderId+"的结果，预计需要"+ needTime +"秒，还请耐心等待...\n";
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    tv_result.setText(info);
                    toend();
                }
            });
        }
    };
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.ai_raasr);
        initView();
        initRAASR();
    }

    private void initView(){
        btn_upload = findViewById(R.id.ai_raasr_start);
        findViewById(R.id.ai_raasr_getResult).setOnClickListener(this);
        btn_stop = findViewById(R.id.ai_raasr_stop);
        findViewById(R.id.ai_raasr_audiopath).setOnClickListener(this);
        tv_result = findViewById(R.id.ai_raasr_notification);
        tv_result.setMovementMethod(new ScrollingMovementMethod());
        tv_audioPathInfo = findViewById(R.id.ai_raasr_pathinfo);
        btn_stop.setOnClickListener(this);
        btn_upload.setOnClickListener(this);
        setStopButton(btn_stop,false);
    }
    int count = 0;
    private void runRaasr(){
        String resultType = "transfer";//结果类型。transfer:转写，translate:翻译。具体参考集成文档
        resultTypes = resultType;
        if(mRAASR == null){
            initRAASR();
        }
        setStopButton(btn_upload,false);
        orderId = null;
        mRAASR.transLanguage("en");//翻译目标语种
        mRAASR.language("cn");//识别语种
        mRAASR.roleType(0);//是否开启角色分离,0:关闭，1:打开
        Log.d(TAG,"当前音频路径为:"+audioPath);

        count ++;
        requestId = "第"+count+"次请求";//客户端用于标记任务的唯一id，最大长度64字符，由客户端保证唯一性，服务回调结果时会会包含此参数

        int ret = mRAASR.uploadAsync(audioPath,requestId,"UPLOAD");
        setStopButton(btn_stop,true);
        Log.d(TAG,"RAASR start:"+ret);
        if(ret !=0 ){
            showInfo("转写启动出错，错误码:"+ret+"\n");
        }else{
            showInfo("正在上传音频，还请耐心稍等...\n");
        }
    }

    private void seleteResult(){
        if(mRAASR !=null && orderId!=null){
            new Thread(new Runnable() {
                @Override
                public void run() {
                    //转写结果生成预计还需要的时间
                    long genRemainTime = resultGenTime-System.currentTimeMillis();
                    long needTime = 10;
                    if(genRemainTime > 0){
                        needTime = genRemainTime/1000 + 1;
                    }
                    String catche = tv_result.getText().toString();
                    showInfo("正在查询订单号为:"+orderId+"的结果，预计需要"+needTime+"秒，还请耐心等待...\n");
                    while(needTime>0){
                        try {
                            Thread.sleep(1000);
                            needTime --;
                            String info = catche + "正在查询订单号为:"+orderId+"的结果，预计需要"+ needTime +"秒，还请耐心等待...\n";
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    tv_result.setText(info);
                                    toend();
                                }
                            });
                        } catch (InterruptedException e) {
//                          throw new RuntimeException(e);
                        }
                    }
                    resultGenTime = 0;
                    String resultType = "transfer";//结果类型。transfer:转写，translate:翻译。具体参考集成文档
                    mRAASR.resultType(resultType);
                    int ret = mRAASR.getResultOnceAsync(orderId,"SELETE");
                    if(ret != 0){
                        showInfo("转写查询出错，错误码:"+ret+"\n");
                    }
                }
            }).start();
        }else{
            showInfo("没有获取到orderId或者raasr实例！请先点击开始测试或等结果出来后重试！\n");
        }
    }

    private void initRAASR(){
        RAASRAPIKEY = getResources().getString(R.string.RAASRAPIKEY);
        mRAASR = new RAASR(RAASRAPIKEY);
        mRAASR.registerCallbacks(mRAASRCallbacks);
    }

    @Override
    public void onClick(View view) {
        switch (view.getId()){
            case R.id.ai_raasr_start:
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        runRaasr();
                    }
                }).start();
                break;
//            case R.id.ai_raasr_getResult:
//                seleteResult();
//                break;

            case R.id.ai_raasr_stop:
                //uploadAsync上传过程中，可通过该方法打断.
                int ret = mRAASR.stop();
                showInfo("停止上传，ret:"+ret+"\n");
                setStopButton(btn_stop,false);
                setStopButton(btn_upload,true);
                break;
            case R.id.ai_raasr_audiopath:
                Log.d(TAG,"audioPath");
                showFileChooser();
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
                    audioPath = path;
                }
                tv_audioPathInfo.setText("当前音频路径为:"+audioPath);
                break;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void showInfo(String text){
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                tv_result.append(text);
            }
        });
    }

    private void setStopButton(Button btn,boolean enable){
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                btn.setEnabled(enable);
            }
        });
    }


    /********************************
     * 解析转写的lattice结果
     * *************************************************/
    private List<String> extractChineseCharacters(String jsonString) {
        List<String> chineseCharacters = new ArrayList<>();
        try {
            JSONObject object = new JSONObject(jsonString);
            JSONArray latticeArray = object.getJSONArray("lattice");
            for (int i = 0; i < latticeArray.length(); i++) {
                JSONObject jsonObject = latticeArray.getJSONObject(i);
                String json1Best = jsonObject.getString("json_1best");
                JSONObject stObject = new JSONObject(json1Best).getJSONObject("st");
                JSONArray rtArray = stObject.getJSONArray("rt");
                for (int j = 0; j < rtArray.length(); j++) {
                    JSONArray wsArray = rtArray.getJSONObject(j).getJSONArray("ws");
                    for (int k = 0; k < wsArray.length(); k++) {
                        JSONArray cwArray = wsArray.getJSONObject(k).getJSONArray("cw");
                        for (int l = 0; l < cwArray.length(); l++) {
                            JSONObject cwObject = cwArray.getJSONObject(l);
                            String word = cwObject.getString("w");
                            chineseCharacters.add(word);
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            Log.d(TAG,"extractChineseCharacters:"+e.toString());
        }
        return chineseCharacters;
    }

    private String analysisResult(String orderResult){
        List<String> resultList = extractChineseCharacters(orderResult);
        StringBuilder sb = new StringBuilder();
        for (String str : resultList) {
            sb.append(str);
        }
        String result = sb.toString();
        Log.d(TAG,"analysisResult:"+result);
        return result;
    }

    /*************************
     * 显示控件自动下移
     * *******************************/
    public void toend(){
        int scrollAmount = tv_result.getLayout().getLineTop(tv_result.getLineCount()) - tv_result.getHeight();
        if (scrollAmount > 0) {
            tv_result.scrollTo(0, scrollAmount+10);
        }
    }
}
