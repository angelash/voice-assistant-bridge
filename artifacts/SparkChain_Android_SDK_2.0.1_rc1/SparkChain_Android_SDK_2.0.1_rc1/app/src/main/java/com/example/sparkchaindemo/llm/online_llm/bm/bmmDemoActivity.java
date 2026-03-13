package com.example.sparkchaindemo.llm.online_llm.bm;

import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;


import com.example.sparkchaindemo.R;
import com.example.sparkchaindemo.adapter.SpinnerAdapter;
import com.example.sparkchaindemo.utils.AudioRecorderManager;
import com.hjq.permissions.OnPermission;
import com.hjq.permissions.XXPermissions;
import com.iflytek.sparkchain.core.asr.ASR;
import com.iflytek.sparkchain.core.asr.AsrCallbacks;
import com.iflytek.sparkchain.core.asr.AudioAttributes;
import com.iflytek.sparkchain.core.asr.Segment;
import com.iflytek.sparkchain.core.asr.Transcription;
import com.iflytek.sparkchain.core.asr.Vad;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/*************************
 * 多语种识别大模型Demo
 * create by wxw
 * 2024-12-17
 * **********************************/
public class bmmDemoActivity extends AppCompatActivity implements View.OnClickListener, AudioRecorderManager.AudioDataCallback{

    private final String TAG = "AEELog";
    private TextView tv_result;
    private Spinner mTypeSpinner;
    private String filePath;
    private Button btn_audio_start,btn_file_start;
    private ASR asr;
    private String startMode = "NONE";
    private String cacheInfo = "";

    private boolean isrun = false;
    private AudioRecorderManager audioRecorderManager;
    private AtomicBoolean isWrite = new AtomicBoolean(false);
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.online_llm_bmm);
        initView();
        initASR();
    }

    AsrCallbacks mAsrCallbacks = new AsrCallbacks() {
        @Override
        public void onResult(ASR.ASRResult asrResult, Object o) {
            //以下信息需要开发者根据自身需求，如无必要，可不需要解析执行。
            String result                      = asrResult.getBestMatchText();    //识别结果返回接口，开发者可通过此方法快速获取识别结果。
            int status                         = asrResult.getStatus();           //识别结果返回进度，0：开始，1：中间，2：结束
            String sid                         = asrResult.getSid();              //本次交互的sid
            List<Vad> vads                     = asrResult.getVads();
            List<Transcription> transcriptions = asrResult.getTranscriptions();
            int vad_begin = -1;
            int vad_end = -1;
            String word = null;
            String lg = null;
            for(Vad vad:vads){
                vad_begin = vad.getBegin();                                       //起始的端点帧偏移值，单位：帧（1帧=10ms）
                vad_end = vad.getEnd();                                           //结束的端点帧偏移值，单位：帧（1帧=10ms）
            }
            for(Transcription transcription : transcriptions){
                List<Segment> segments = transcription.getSegments();
                for(Segment segment:segments){
                    word = segment.getText();                                     //字词
                    lg = segment.getLg();                                         //识别语种
                }
            }
            String resultBuf = "onResult:{result:"+result+",status:"+status+",begin:"+vad_begin+",end:"+vad_end+",word:"+word+",lg:"+lg+"}";
            Log.d(TAG,resultBuf);
            showInfo(cacheInfo+"识别结果："+result+"\n","SETTEXT");
            if(status == 2){
                cacheInfo = cacheInfo + "识别结果：" + result+"\n";
                stopAsr();
            }
        }

        @Override
        public void onError(ASR.ASRError asrError, Object o) {
            int errCode   = asrError.getCode();    //错误码
            String errMsg = asrError.getErrMsg();  //错误信息
            String sid    = asrError.getSid();     //本次交互的sid
            showInfo("识别出错了！错误码:"+errCode+",错误信息:"+errMsg+",sid:"+sid+"\n","APPEND");
            stopAsr();
        }

        @Override
        public void onBeginOfSpeech() {

        }

        @Override
        public void onEndOfSpeech() {

        }
    };

    private void initASR(){
        /**************
         * 初始化入参:
         * language:语种，mul_cn:中文
         * domain:领域，slm:大模型识别
         * accent:方言，mandarin:普通话
         * language,domain，accent这三个参数配套使用。
         * language=mul_cn,domain=slm,accent=mandarin代表听写多语种大模型
         * ******************/
        asr = new ASR("mul_cn","slm","mandarin");
//        asr = new ASR("zh_cn","slm","mandarin");
        asr.registerCallbacks(mAsrCallbacks);
    }

    private void initView() {
        mTypeSpinner = findViewById(R.id.online_llm_bmm_language);
        tv_result = findViewById(R.id.online_llm_bmm_result);
        tv_result.setMovementMethod(new ScrollingMovementMethod());
        btn_file_start = findViewById(R.id.online_llm_bmm_file_start_btn);
        btn_audio_start = findViewById(R.id.online_llm_bmm_audio_start_btn);
        findViewById(R.id.online_llm_bmm_stop_btn).setOnClickListener(this);
        btn_file_start.setOnClickListener(this);
        btn_audio_start.setOnClickListener(this);
        SpinnerAdapter typeSpinner = new SpinnerAdapter(this,  bmmDemoParams.getTypes());
        mTypeSpinner.setAdapter(typeSpinner);
        mTypeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                filePath = bmmDemoParams.getTypes().get(position).value;
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });
    }

    private int runAsr_file(String path) {
        int ret = -1;
        if(isrun){
            showInfo("正在识别中，请勿重复开启。\n","APPEND");
            return ret;
        }
        if(asr == null){
            Log.d(TAG, "Asr 初始化失败。");
            showInfo("Asr 初始化失败,请重新进入。\n","APPEND");
            return ret;
        }
        AudioAttributes atr = new AudioAttributes();
        atr.setSampleRate(16000);//采样率。16000:16K
        atr.setEncoding("raw");//音频编码。raw:pcm格式的原始音频
        atr.setChannels(1);//声道。1:单声道
        ret = asr.start(atr, null);
        if (ret != 0) {
            Log.e(TAG, "asr start failed" + ret);
            return ret;
        }
        isrun = true;
        try{
            Log.d(TAG,"ready load audio:"+path);
            showInfo("识别音频路径："+path+"\n","APPEND");
            showInfo("正在识别中，请稍等...\n","APPEND");

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    btn_audio_start.setEnabled(false);
                    btn_file_start.setEnabled(false);
                }
            });


            byte[] data = readStream(path);//识别音频路径，开发者可根据自身需求修改，但要求有读写权限。Demo仅演示读音频识别。SDK亦支持从麦克风实时读入音频去识别，这里不做展示。
            int leftAudioBytes = data.length;
            int byteWrites = 1280;
            int writeLen = 0;
            int index = 0;

            while (leftAudioBytes > 0) {
                if(!isrun){
                    break;
                }
                if (leftAudioBytes > byteWrites) {
                    writeLen = byteWrites;
                } else {
                    writeLen = leftAudioBytes;
                }
                leftAudioBytes -= writeLen;
                byte[] part = Arrays.copyOfRange(data, index * byteWrites,
                        index * byteWrites + writeLen);

                ret = asr.write(part);
                if (ret != 0) {
                    Log.e(TAG, "asr write failed" + ret);
                    return ret;
                }
                index++;
            }
            ret = asr.stop(false);//不报错结束。true:立即结束，false:等大模型返回最后一帧数据后结束
        }catch(Exception e){
            e.printStackTrace();
            ret = asr.stop(true);//报错打断。true:立即结束，false:等大模型返回最后一帧数据后结束
            stopAsr();
        }
        if (ret != 0) {
            Log.e(TAG, "asr stop failed" + ret);
        }
        return ret;
    }

    private int runAsr_Audio() {
        int ret = -1;
        if(isrun){
            showInfo("正在识别中，请勿重复开启。\n","APPEND");
            return ret;
        }
        if(asr == null){
            Log.d(TAG, "Asr 初始化失败。");
            showInfo("Asr 初始化失败,请重新进入。\n","APPEND");
            return ret;
        }
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                showInfo("已开启录音，说完后请点击停止识别，即可获得最终结果\n","APPEND");
                btn_audio_start.setText("录音中\n");
                btn_audio_start.setEnabled(false);
                btn_file_start.setEnabled(false);
            }
        });
        ret = asr.start(null);
        if (ret != 0) {
            Log.e(TAG, "asr start failed" + ret);
        }else{
            isrun = true;
            isWrite.set(true);
            if (audioRecorderManager == null) {
                audioRecorderManager = AudioRecorderManager.getInstance();
            }
            audioRecorderManager.startRecord();
            audioRecorderManager.registerCallBack(this);
        }
        return ret;
    }




    @Override
    protected void onDestroy() {
        super.onDestroy();
        if(asr!=null)
            asr.stop(true);//报错打断。true:立即结束，false:等大模型返回最后一帧数据后结束
    }

    private void stopAsr() {
        if (isrun) {
            if ("AUDIO".equals(startMode)) {
                if (asr != null) {
                    if (audioRecorderManager != null) {
                        audioRecorderManager.stopRecord();
                        audioRecorderManager = null;
                    }
                    asr.stop(false);
                    showInfo("已停止录音。\n", "APPEND");
                }
            } else {
                if (asr != null) {
                    asr.stop(true);//取消。true:立即结束，false:等大模型返回最后一帧数据后结束
                    showInfo("已停止识别。\n", "APPEND");
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
        } else {
            showInfo("已停止识别。\n", "APPEND");
        }
    }

    public byte[] readStream(String filePath) throws Exception {
        FileInputStream fs = new FileInputStream(filePath);
        ByteArrayOutputStream outStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int len = 0;
        while (-1 != (len = fs.read(buffer))) {
            outStream.write(buffer, 0, len);
        }
        outStream.close();
        fs.close();
        return outStream.toByteArray();
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
                    XXPermissions.startPermissionActivity(bmmDemoActivity.this,denied);
                }else{
                    Log.e(TAG,"onDenied:权限获取失败");
                }
            }
        });
    }


    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.online_llm_bmm_file_start_btn:
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        if(asr == null || filePath == null){
                            Log.e(TAG,"start testAsr fail! asr="+asr+",filePath="+filePath);
                            showInfo("没有检测到要识别的音频路径或者没有成功创建实例，请退出重试。\n","APPEND");
                        }
                        startMode = "FILE";
                        int ret = runAsr_file(filePath);
                        if(ret!=0){
                            Log.e(TAG,"start testAsr fail:"+ret);
                            showInfo("识别启动失败，错误码是:"+ret+"\n","APPEND");
                        }
                    }
                }).start();
                break;
            case R.id.online_llm_bmm_audio_start_btn:
                startMode = "AUDIO";
                getPermission();
                break;
            case R.id.online_llm_bmm_stop_btn:
                stopAsr();
                break;
        }
    }
    private void showInfo(String text,String mode){
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if("APPEND".equals(mode)){
                    cacheInfo = cacheInfo + text;
                    tv_result.append(text);
                }
                else
                    tv_result.setText(text);
                toend();
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
            int ret = asr.write(data);
            if (ret != 0) {
                isWrite.set(false);
            }
        }
    }

    @Override
    public void onAudioVolume(double db, int volume) {

    }
}