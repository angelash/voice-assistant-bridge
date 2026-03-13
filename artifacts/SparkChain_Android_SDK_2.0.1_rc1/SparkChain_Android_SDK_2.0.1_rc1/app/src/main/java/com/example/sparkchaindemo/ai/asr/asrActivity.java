package com.example.sparkchaindemo.ai.asr;

import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.sparkchaindemo.R;
import com.example.sparkchaindemo.adapter.SpinnerAdapter;
import com.example.sparkchaindemo.utils.AudioRecorderManager;
import com.hjq.permissions.OnPermission;
import com.hjq.permissions.XXPermissions;
import com.iflytek.sparkchain.core.asr.ASR;
import com.iflytek.sparkchain.core.asr.AsrCallbacks;
import com.iflytek.sparkchain.core.asr.Segment;
import com.iflytek.sparkchain.core.asr.Transcription;
import com.iflytek.sparkchain.core.asr.Vad;

import java.io.FileInputStream;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

/*************************
 * 语音听写Demo
 * create by wxw
 * 2024-12-14
 * **********************************/
public class asrActivity extends AppCompatActivity implements View.OnClickListener, AudioRecorderManager.AudioDataCallback {

    private static final String TAG = "AEELog";
    private Spinner sp_language;
    private TextView tv_result;
    private Button btn_audio_start,btn_file_start;
    private String language = "zh_cn";
    private ASR mAsr = null;
    private boolean isrun = false;
    private boolean isdws = false;
    private String startMode = "NONE";
    private String cacheInfo = "";
    private AudioRecorderManager audioRecorderManager;
    private AtomicBoolean isWrite = new AtomicBoolean(false);
    AsrCallbacks mAsrCallbacks = new AsrCallbacks() {
        @Override
        public void onResult(ASR.ASRResult asrResult, Object o) {
            Log.e(TAG,"result:"+asrResult.getStatus());
            //以下信息需要开发者根据自身需求，如无必要，可不需要解析执行。
            int begin     = asrResult.getBegin();         //识别结果所处音频的起始点
            int end       = asrResult.getEnd();           //识别结果所处音频的结束点
            int status    = asrResult.getStatus();        //结果数据状态，0：识别的第一块结果,1：识别中间结果,2：识别最后一块结果
            String result = asrResult.getBestMatchText(); //识别结果
            String sid    = asrResult.getSid();           //sid

            List<Vad> vads = asrResult.getVads();
            List<Transcription> transcriptions = asrResult.getTranscriptions();
            int vad_begin = -1;
            int vad_end = -1;
            String word = null;
            for(Vad vad:vads){
                vad_begin = vad.getBegin();
                vad_end = vad.getEnd();                   //VAD结果
                Log.d(TAG,"vad={begin:"+vad_begin+",end:"+vad_end+"}");
            }
            for(Transcription transcription : transcriptions){
                List<Segment> segments = transcription.getSegments();
                for(Segment segment:segments){
                    word = segment.getText();              //分词结果
//                    Log.d(TAG,"word={word:"+word+"}");
                }
            }
            String info = "result={begin:"+begin+",end:"+end+",status:"+status+",result:"+result+",sid:"+sid+"}";
            Log.d(TAG,info);
            /****************************此段为为了UI展示结果，开发者可根据自己需求改动*****************************************/
            if(status == 0){
                if(isdws){
                    cacheInfo = tv_result.getText().toString(); //获取信息记录
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            tv_result.setText(cacheInfo+"识别结果："+result);
                        }
                    });
                }
            }else if(status == 2){
                if(isdws){
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            tv_result.setText(cacheInfo+"识别结果："+result+"\n");
                        }
                    });
                }else{
                    showInfo(result+"\n");
                }
                stopAsr();
            }else{
                if(isdws){
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            tv_result.setText(cacheInfo+"识别结果："+result);
                        }
                    });
                }
            }
            toend();
            /*********************************************************************/
        }

        @Override
        public void onError(ASR.ASRError asrError, Object o) {
            int code = asrError.getCode();
            String msg = asrError.getErrMsg();
            String sid = asrError.getSid();
            String info = "error={code:"+code+",msg:"+msg+",sid:"+sid+"}";
            Log.d(TAG,info);
            showInfo("识别出错!错误码："+code+",错误信息："+msg+",sid:"+sid+"\n");
            stopAsr();
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    btn_audio_start.setText("麦克风识别");
                    btn_audio_start.setEnabled(true);
                    btn_file_start.setEnabled(true);
                }
            });
            isrun = false;
        }

        @Override
        public void onBeginOfSpeech() {

        }

        @Override
        public void onEndOfSpeech() {

        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.ai_asr);
        initView();
//        initASR();
    }

    private void initASR(){
        if(mAsr == null){
            mAsr = new ASR();
            mAsr.registerCallbacks(mAsrCallbacks);
        }
    }
    int count = 0;
    private void runAsr_file(){
        if(isrun){
            showInfo("正在识别中，请勿重复开启。\n");
            return;
        }
        if(mAsr == null){
            initASR();
        }
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                btn_audio_start.setText("录音中\n");
                btn_audio_start.setEnabled(false);
                btn_file_start.setEnabled(false);
            }
        });
        isdws = false;
        mAsr.language(language);//语种，zh_cn:中文，en_us:英文。其他语种参见集成文档
        mAsr.domain("iat");//应用领域,iat:日常用语。其他领域参见集成文档
        mAsr.accent("mandarin");//方言，mandarin:普通话。方言仅当language为中文时才会生效。其他方言参见集成文档。
        mAsr.vinfo(true);//返回子句结果对应的起始和结束的端点帧偏移值。
        if("zh_cn".equals(language)){
            mAsr.dwa("wpgs");//动态修正
            isdws = true;
        }

        count++;
        int ret = mAsr.start(count+"");//入参为用户自定义标识，用户关联onResult结果。
        //带有AudioAttributes的start示例如下，开发者根据自身需求二选一即可。
        //AudioAttributes attr = new AudioAttributes();
        //attr.setSampleRate(16000);
        //attr.setEncoding("raw");
        //attr.setChannels(1);
        //attr.setBitdepth(16);
        //int ret = asr.start(attr,count+"");

        if(ret == 0){
            isrun = true;
            write();
        }else{
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    btn_audio_start.setText("麦克风识别");
                    btn_audio_start.setEnabled(true);
                    btn_file_start.setEnabled(true);
                }
            });
            isrun = false;
            showInfo("识别开启失败，错误码:"+ret+"\n");
        }
    }

    private void runAsr_Audio(){
        if(isrun){
            showInfo("正在识别中，请勿重复开启。\n");
            return;
        }

        if(mAsr == null){
            initASR();
        }
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                showInfo("已开启录音，说完后请点击停止识别，即可获得最终结果\n");
                btn_audio_start.setText("录音中\n");
                btn_audio_start.setEnabled(false);
                btn_file_start.setEnabled(false);
            }
        });
        isdws = false;
        mAsr.language(language);//语种，zh_cn:中文，en_us:英文。其他语种参见集成文档
        mAsr.domain("iat");//应用领域,iat:日常用语。其他领域参见集成文档
        mAsr.accent("mandarin");//方言，mandarin:普通话。方言仅当language为中文时才会生效。其他方言参见集成文档。
        mAsr.vinfo(true);//返回子句结果对应的起始和结束的端点帧偏移值。
        if("zh_cn".equals(language)){
            mAsr.dwa("wpgs");//动态修正
            isdws = true;
        }

        count++;
        int ret = mAsr.start(count+"");
        if(ret != 0){
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    btn_audio_start.setText("麦克风识别");
                    btn_audio_start.setEnabled(true);
                    btn_file_start.setEnabled(true);
                }
            });
            isrun = false;
            showInfo("识别开启失败，错误码:"+ret+"\n");
        }else{
            isrun = true;
            isWrite.set(true);
            if (audioRecorderManager == null) {
                audioRecorderManager = AudioRecorderManager.getInstance();
            }
            audioRecorderManager.startRecord();
            audioRecorderManager.registerCallBack(this);
        }
    }

    private String getAudioPath(){
        String path = "";
        showInfo("选择的语种:"+language+"\n");
        switch(language){
            case "zh_cn":
                path = "/sdcard/iflytek/asr/cn.pcm";//测试音频路径，默认该目录，开发者可根据自身情况调整。要求有读写权限
                break;
            case "en_us":
                path = "/sdcard/iflytek/asr/en.pcm";
                break;
        }
        return path;
    }

    private void write(){
        String filePath = getAudioPath();
        showInfo("识别音频路径:"+filePath+"\n");
        showInfo("正在识别中，请稍等...\n");
        try{
            FileInputStream fs = new FileInputStream(filePath);
            byte[] buffer = new byte[1280];
            int len = 0;
            while (-1 != (len = fs.read(buffer))) {
                if(!isrun){
                    break;
                }
                if(len>0){
                    mAsr.write(buffer.clone());
                    Thread.sleep(40);
                }
            }
            fs.close();
            Thread.sleep(10);
            mAsr.stop(false);//音频输入完毕后要调用stop通知云端和SDK，音频输入结束。true:立即结束，false:等云端最后一包下发后结束
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    private void stopAsr() {
        if (isrun) {
            if ("AUDIO".equals(startMode)) {
                if (mAsr != null) {
                    if (audioRecorderManager != null) {
                        audioRecorderManager.stopRecord();
                        audioRecorderManager = null;
                    }
                    mAsr.stop(false);
                    showInfo("\n已停止录音。\n");
                }
            }else{
                if(mAsr!=null) {
                    mAsr.stop(true);//取消。true:立即结束，false:等大模型返回最后一帧数据后结束
                    showInfo("\n已停止识别。\n");
                }
            }
            startMode = "NONE";
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    btn_audio_start.setText("麦克风识别");
                    btn_audio_start.setEnabled(true);
                    btn_file_start.setEnabled(true);
                }
            });
            isrun = false;
        }else{
            showInfo("\n已停止识别。\n");
        }
    }

    private void initView(){
        btn_file_start = findViewById(R.id.ai_asr_file_start_btn);
        btn_audio_start = findViewById(R.id.ai_asr_audio_start_btn);
        btn_file_start.setOnClickListener(this);
        btn_audio_start.setOnClickListener(this);
        findViewById(R.id.ai_asr_stop_btn).setOnClickListener(this);
        sp_language = findViewById(R.id.ai_asr_language);
        tv_result = findViewById(R.id.ai_asr_result);
        tv_result.setMovementMethod(new ScrollingMovementMethod());
        SpinnerAdapter languageSpinner = new SpinnerAdapter(this, asrParams.getLanguage());
        sp_language.setAdapter(languageSpinner);
        sp_language.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                language = asrParams.getLanguage().get(position).value;
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });
    }
    private void getPermission(){
        XXPermissions.with(this).permission("android.permission.RECORD_AUDIO").request(new OnPermission() {
            @Override
            public void hasPermission(List<String> granted, boolean all) {
                Log.d(TAG,"SDK获取系统权限成功:"+all);
                for(int i=0;i<granted.size();i++){
                    Log.d(TAG,"获取到的权限有："+granted.get(i));
                }
                if(all){
                    runAsr_Audio();
                }
            }

            @Override
            public void noPermission(List<String> denied, boolean quick) {
                if(quick){
                    Log.e(TAG,"onDenied:被永久拒绝授权，请手动授予权限");
                    XXPermissions.startPermissionActivity(asrActivity.this,denied);
                }else{
                    Log.e(TAG,"onDenied:权限获取失败");
                }
            }
        });
    }

    @Override
    public void onClick(View view) {
        switch (view.getId()){
            case R.id.ai_asr_file_start_btn:
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        startMode = "FILE";
                        runAsr_file();
                    }
                }).start();
                break;
            case R.id.ai_asr_audio_start_btn:
                startMode = "AUDIO";
                getPermission();
                break;
            case R.id.ai_asr_stop_btn:
                stopAsr();
                break;
        }
    }

    private void showInfo(String text){
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                tv_result.append(text);
            }
        });
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

    @Override
    public void onAudioData(byte[] data, int size) {
        if (isWrite.get()) {
            int ret = mAsr.write(data);
            if (ret != 0) {
                isWrite.set(false);
            }
        }
    }

    @Override
    public void onAudioVolume(double db, int volume) {

    }
}
