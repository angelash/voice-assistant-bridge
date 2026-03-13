package com.example.sparkchaindemo.ai.rtasr;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.example.sparkchaindemo.R;
import com.example.sparkchaindemo.utils.AudioRecorderManager;
import com.hjq.permissions.OnPermission;
import com.hjq.permissions.XXPermissions;
import com.iflytek.sparkchain.core.rtasr.RTASR;
import com.iflytek.sparkchain.core.rtasr.RTASRCallbacks;

import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/*************************
 * 实时语音转写Demo
 * create by wxw
 * 2024-12-16
 * **********************************/
public class RTASRActivity extends AppCompatActivity implements View.OnClickListener, AudioRecorderManager.AudioDataCallback{
    private static final String TAG = "AEELog";
    private String RTASRAPIKEY = "";
    private Spinner sp_language;
    TextView tv_result,tv_transResult,tv_audioPath;
    private Button btn_audio_start,btn_file_start;
    private RTASR mRTASR;
    boolean isrun = false;
    String asrFinalResult = "识别结果：\n";
    String transFinalResult = "翻译结果：\n";
    String audioPath = "";
    private String startMode = "NONE";
    private ASRMode language = ASRMode.CN;
    private List<String> languageList = new ArrayList<String>();
    private AudioRecorderManager audioRecorderManager;
    private AtomicBoolean isWrite = new AtomicBoolean(false);

    @Override
    public void onClick(View view) {
        switch (view.getId()){
            case R.id.ai_rtasr_file_btn:
                tv_result.setText("识别结果：\n");
                tv_transResult.setText("翻译结果：\n");
                asrFinalResult = "识别结果：\n";
                transFinalResult = "翻译结果：\n";
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        runRtasr_file(language);
                    }
                }).start();
                break;
            case R.id.ai_rtasr_audio_btn:
                tv_result.setText("识别结果：\n");
                tv_transResult.setText("翻译结果：\n");
                asrFinalResult = "识别结果：\n";
                transFinalResult = "翻译结果：\n";

                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        getPermission();
                    }
                }).start();
                break;
            case R.id.ai_rtasr_btn_stop:
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        if(mRTASR!=null&&isrun){
                            if("FILE".equals(startMode)){
                                mRTASR.stop();
                            }else{
                                if (audioRecorderManager != null) {
                                    audioRecorderManager.stopRecord();
                                    audioRecorderManager = null;
                                }
                                mRTASR.stop();
                            }
                            startMode = "NONE";
                            isrun = false;
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    btn_audio_start.setText("麦克风识别");
                                    btn_audio_start.setEnabled(true);
                                    btn_file_start.setEnabled(true);
                                }
                            });
                        }
                    }
                }).start();
                break;
        }
    }

    @Override
    public void onAudioData(byte[] data, int size) {
        if (isWrite.get()) {
            int ret = mRTASR.write(data);
            if (ret != 0) {
                isWrite.set(false);
            }
        }
    }

    @Override
    public void onAudioVolume(double db, int volume) {

    }

    private enum ASRMode{
        CN,
        EN
    }

    @SuppressLint("MissingInflatedId")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.ai_rtasr);
        languageList.add("中文");
        languageList.add("英文");
        tv_result = findViewById(R.id.ai_rtasr_asrResult);
        tv_result.setMovementMethod(new ScrollingMovementMethod());
        tv_transResult = findViewById(R.id.ai_rtasr_translateResult);
        tv_transResult.setMovementMethod(new ScrollingMovementMethod());
        tv_audioPath = findViewById(R.id.ai_rtasr_testAudioPath);
        sp_language = findViewById(R.id.ai_rtasr_language);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,android.R.layout.simple_spinner_item, languageList);
        sp_language.setAdapter(adapter);
        sp_language.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> adapterView, View view, int position, long l) {
                String selectedItem = adapterView.getItemAtPosition(position).toString();
                Log.d(TAG,"language:"+selectedItem);
                if("中文".equals(selectedItem)){
                    language = ASRMode.CN;
                }else{
                    language = ASRMode.EN;
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> adapterView) {

            }
        });
        btn_file_start = findViewById(R.id.ai_rtasr_file_btn);
        btn_audio_start = findViewById(R.id.ai_rtasr_audio_btn);
        btn_file_start.setOnClickListener(this);
        btn_audio_start.setOnClickListener(this);
        findViewById(R.id.ai_rtasr_btn_stop).setOnClickListener(this);
        init();
    }

    protected void init() {
        RTASRAPIKEY = getResources().getString(R.string.RTASRAPIKEY);
        mRTASR = new RTASR(RTASRAPIKEY);//创建RTASR实例
        mRTASR.registerCallbacks(mRtAsrCallbacks);//注册监听回调
    }


    RTASRCallbacks mRtAsrCallbacks = new RTASRCallbacks() {
        @Override
        public void onResult(RTASR.RtAsrResult result, Object usrTag) {
            Log.e(TAG,"result："+result.getStatus());
            //以下信息需要开发者根据自身需求，如无必要，可不需要解析执行。
            String data      = result.getData();                     //识别结果
            String rawResult = result.getRawResult();                //云端识别的原始结果
            int status       = result.getStatus();                   //数据状态
            String sid       = result.getSid();                      //交互sid
            String src       = result.getTransResult().getSrc();     //翻译源文本
            String dst       = result.getTransResult().getDst();     //翻译结果
            int transStatus  = result.getTransResult().getStatus();  //翻译状态

            runOnUiThread(new Runnable() {
                //结果显示在界面上
                @Override
                public void run() {
                    if(status == 1){//子句流式结果
                        String asrText = asrFinalResult + data;
                        tv_result.setText(asrText);
                        toend(tv_result);
                    }else if(status == 2){//子句plain结果
                        asrFinalResult = asrFinalResult + data;
                    }else if(status == 3){//end结果
                        tv_result.setText(asrFinalResult);
                        toend(tv_result);
                        if(isrun){
                            if("AUDIO".equals(startMode)){
                                if(mRTASR!=null){
                                    if (audioRecorderManager != null) {
                                        audioRecorderManager.stopRecord();
                                        audioRecorderManager = null;
                                    }
                                    mRTASR.stop();
                                }
                            }else{
                                if(mRTASR!=null) {
                                    mRTASR.stop();//停止
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
                        }
                    }else if(status == 0){//翻译结果
                        if(transStatus == 2){
                            //翻译end结果
                            transFinalResult = transFinalResult + dst;
                            tv_transResult.setText(transFinalResult);
                            toend(tv_transResult);
                        }else{
                            String transText = transFinalResult + dst;
                            tv_transResult.setText(transText);
                            toend(tv_transResult);
                        }
                    }
                }
            });
        }

        @Override
        public void onError(RTASR.RtAsrError error, Object usrTag) {
            int code   = error.getCode();    //错误码
            String msg = error.getErrMsg();  //错误信息
            String sid = error.getSid();     //交互sid
            Log.e(TAG,"错误码："+code+" 错误信息:"+msg);
            if (isrun) {
                if ("AUDIO".equals(startMode)) {
                    if (mRTASR != null) {
                        if (audioRecorderManager != null) {
                            audioRecorderManager.stopRecord();
                            audioRecorderManager = null;
                        }
                        mRTASR.stop();
                    }
                }else{
                    if(mRTASR!=null) {
                        mRTASR.stop();//停止
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
            }
        }

        @Override
        public void onBeginOfSpeech() {

        }

        @Override
        public void onEndOfSpeech() {

        }
    };

    int count = 0;//用户自定义标识
    private void runRtasr_file(ASRMode mode) {
        if(isrun)
            return;
        count ++;

        if(mRTASR == null){
            mRTASR = new RTASR(RTASRAPIKEY);//创建RTASR实例
            mRTASR.registerCallbacks(mRtAsrCallbacks);//注册监听回调
        }

        mRTASR.transType("normal");//普通翻译
        mRTASR.transStrategy(1);//策略2：返回中间过程中的结果。其他策略参考集成文档
        if(mode == ASRMode.CN){
            mRTASR.lang("cn");//转写语种 cn:中文,en:英文。其他语种参考集成文档
            mRTASR.targetLang("en");//翻译语种 cn:中文,en:英文。其他语种参考集成文档
            audioPath = "/sdcard/iflytek/asr/audiotemp.pcm";//转写音频路径，开发者可根据自身需求修改，但要求有读写权限。Demo仅演示读音频转写。SDK亦支持从麦克风实时读入音频去转写，这里不做展示。
        }else{
            mRTASR.lang("en");//转写语种 cn:中文,en:英文。其他语种参考集成文档
            mRTASR.targetLang("cn");//翻译语种 cn:中文,en:英文。其他语种参考集成文档
            audioPath = "/sdcard/iflytek/asr/en_test.pcm";//转写音频路径，开发者可根据自身需求修改，但要求有读写权限。Demo仅演示读音频转写。SDK亦支持从麦克风实时读入音频去转写，这里不做展示。
        }


        asrFinalResult = "识别结果：\n";
        transFinalResult = "翻译结果：\n";
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                tv_result.setText(asrFinalResult);
                tv_transResult.setText(transFinalResult);
                tv_audioPath.setText("识别音频路径:" + audioPath);
                btn_audio_start.setEnabled(false);
                btn_file_start.setEnabled(false);
            }
        });
        startMode = "FILE";
        isrun = true;
        int ret = mRTASR.start(count+"");
        Log.d(TAG, "mRTASR.start ret:" + ret+"-count:"+count);
        if(ret != 0){
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    isrun = false;
                    tv_audioPath.setText("转写启动出错，错误码:"+ret);

                }
            });
        }
        try{
            //读取音频文件送引擎转写
            FileInputStream fs = new FileInputStream(audioPath);
            byte[] buffer = new byte[320];
            int len = 0;
            while (-1 != (len = fs.read(buffer))) {
                if(!isrun){
                    Log.d(TAG, "mRTASR sop!!!!!!!!");
                    break;
                }
                if(len>0){
                    mRTASR.write(buffer.clone());
                    Thread.sleep(10);
                }
            }
            fs.close();
            Thread.sleep(10);
            if(isrun)
                mRTASR.stop();
            Thread.sleep(10);
        } catch (Exception e) {
            e.printStackTrace();
        }
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
                    runRtasr_Audio(language);
                }
            }

            @Override
            public void noPermission(List<String> denied, boolean quick) {
                if(quick){
                    Log.e(TAG,"onDenied:被永久拒绝授权，请手动授予权限");
                    XXPermissions.startPermissionActivity(RTASRActivity.this,denied);
                }else{
                    Log.e(TAG,"onDenied:权限获取失败");
                }
            }
        });
    }


    private void runRtasr_Audio(ASRMode mode){
        if(isrun)
            return;
        count ++;
        isrun = true;
        if(mRTASR == null){
            mRTASR = new RTASR(RTASRAPIKEY);//创建RTASR实例
            mRTASR.registerCallbacks(mRtAsrCallbacks);//注册监听回调
        }

        mRTASR.transType("normal");//普通翻译
        mRTASR.transStrategy(2);//策略2：返回中间过程中的结果。其他策略参考集成文档
        if(mode == ASRMode.CN){
            mRTASR.lang("cn");//转写语种 cn:中文,en:英文。其他语种参考集成文档
            mRTASR.targetLang("en");//翻译语种 cn:中文,en:英文。其他语种参考集成文档
        }else{
            mRTASR.lang("en");//转写语种 cn:中文,en:英文。其他语种参考集成文档
            mRTASR.targetLang("cn");//翻译语种 cn:中文,en:英文。其他语种参考集成文档
        }


        asrFinalResult = "识别结果：\n";
        transFinalResult = "翻译结果：\n";
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                tv_result.setText(asrFinalResult);
                tv_transResult.setText(transFinalResult);
                tv_audioPath.setText("识别音频路径:" + audioPath);
                btn_audio_start.setText("录音中\n");
                btn_audio_start.setEnabled(false);
                btn_file_start.setEnabled(false);
            }
        });
        startMode = "AUDIO";
        int ret = mRTASR.start(count+"");
        Log.d(TAG, "mRTASR.start ret:" + ret+"-count:"+count);
        if(ret != 0){
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    isrun = false;
                    tv_audioPath.setText("转写启动出错，错误码:"+ret);

                }
            });
        }else{
            isWrite.set(true);
            if (audioRecorderManager == null) {
                audioRecorderManager = AudioRecorderManager.getInstance();
            }
            audioRecorderManager.startRecord();
            audioRecorderManager.registerCallBack(this);
        }
    }


    /*************************
     * 显示控件自动下移
     * *******************************/
    public void toend(TextView tv){
        int scrollAmount = tv.getLayout().getLineTop(tv.getLineCount()) - tv.getHeight();
        if (scrollAmount > 0) {
            tv.scrollTo(0, scrollAmount+10);
        }
    }

}
