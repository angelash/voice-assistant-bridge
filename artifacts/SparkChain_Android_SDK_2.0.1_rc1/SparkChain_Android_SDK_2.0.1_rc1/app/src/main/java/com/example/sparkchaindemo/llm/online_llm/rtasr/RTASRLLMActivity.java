package com.example.sparkchaindemo.llm.online_llm.rtasr;

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
import com.iflytek.sparkchain.core.asr.RegionType;
import com.iflytek.sparkchain.core.rtasr.RTASR;
import com.iflytek.sparkchain.core.rtasr.RTASRCallbacks;
import com.sparkchain.audio.AudioEncoder;
import com.sparkchain.audio.AudioFormat;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/*************************
 * 实时语音转写Demo
 * create by wxw
 * 2024-12-16
 * **********************************/
public class RTASRLLMActivity extends AppCompatActivity implements View.OnClickListener, AudioRecorderManager.AudioDataCallback{
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
    public AudioEncoder encoder;
    String audiotype = "opus-wb"; //pcm opus_wb speex-wb

    @Override
    public void onClick(View view) {
        switch (view.getId()){
            case R.id.ai_rtasr_file_btn:
                tv_result.setText("识别结果：\n");
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
            int ret;
            if(audiotype.equals("speex-wb")){
                byte[] encodedData = encoder.encode(data, false);
                if(null != encodedData){
                    Log.d(TAG,"encodedData size "+encodedData.length);
                    ret = mRTASR.write(encodedData.clone());
                    writeFile(encodedData.clone(),getFilesDir().getAbsolutePath() + "/encode.spx");
                }
            }else if(audiotype.equals("opus-wb")){
                byte[] encodedData = encoder.encode(data, false);
                if(null != encodedData){
                    Log.d(TAG,"encodedData size "+encodedData.length);
                    ret = mRTASR.write(encodedData.clone());
                    writeFile(encodedData.clone(),getFilesDir().getAbsolutePath() + "/encode.opus");
                }
            }else{
                ret = mRTASR.write(data);
                if (ret != 0) {
                    isWrite.set(false);
                }
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
        tv_transResult.setVisibility(View.GONE);
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
        if(audiotype.equals("speex-wb")){
            encoder = new AudioEncoder(AudioFormat.SPEEX_WB, 16000, 7);
            encoder.setIflytekSelfDefinedHeader(true);
        }else if(audiotype.equals("opus-wb")){
            encoder = new AudioEncoder(AudioFormat.OPUS_WB, 16000, 8);
        }
    }

    protected void init() {
        mRTASR = new RTASR("",RegionType.CN_LLM_TYPE);//创建大模型实时转写实例
        mRTASR.registerCallbacks(mRtAsrCallbacks);//注册监听回调
    }


    RTASRCallbacks mRtAsrCallbacks = new RTASRCallbacks() {
        @Override
        public void onResult(RTASR.RtAsrResult result, Object usrTag) {
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
                        tv_result.setText(asrFinalResult+ data);
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
                    }
                }
            });
        }

        @Override
        public void onError(RTASR.RtAsrError error, Object usrTag) {
            int code   = error.getCode();    //错误码
            String msg = error.getErrMsg();  //错误信息
            String sid = error.getSid();     //交互sid
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
            init();
        }
        mRTASR.setParams("lang", "autodialect");//autodialect:支持中英 +202 种方言免切识别autominor:支持 37 个语种免切识别(暂需联系人工对接)
        mRTASR.setParams("samplerate", "16000");
        if(audiotype.equals("speex-wb")){
            mRTASR.setParams("audio_encode", "speex-7");
        }else if(audiotype.equals("opus-wb")){
            mRTASR.setParams("audio_encode", "opus-wb");
        }else{
            mRTASR.setParams("audio_encode", "pcm_s16le");
        }
        if(mode == ASRMode.CN){
            audioPath = "/sdcard/iflytek/asr/cn_test.pcm";//转写音频路径，开发者可根据自身需求修改，但要求有读写权限。Demo仅演示读音频转写。SDK亦支持从麦克风实时读入音频去转写，这里不做展示。
        }else{
            audioPath = "/sdcard/iflytek/asr/en_test.pcm";//转写音频路径，开发者可根据自身需求修改，但要求有读写权限。Demo仅演示读音频转写。SDK亦支持从麦克风实时读入音频去转写，这里不做展示。
        }


        asrFinalResult = "识别结果：\n";
        transFinalResult = "翻译结果：\n";
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                tv_result.setText(asrFinalResult);
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
        if(audiotype.equals("pcm")){
            FileInputStream fs = null;
            try {
                fs = new FileInputStream(audioPath);
                byte[] buffer = new byte[1280];
                int len = 0;
                while (-1 != (len = fs.read(buffer))) {
                    if(!isrun){
                        break;
                    }
                    if(len>0){
                        mRTASR.write(buffer.clone());
                        Thread.sleep(40);
                    }
                }
                fs.close();
                Thread.sleep(10);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }

        }else if(audiotype.equals("speex-wb")){
            InputStream input = null;
            try {
                input = getAssets().open("cn_test.pcm");
                byte[] buffer = new byte[1280];
                int bytesRead;
                int size = input.available();
                int writelength = 0;
                while ((bytesRead = input.read(buffer)) != -1) {
                    if(!isrun){
                        break;
                    }
                    writelength += bytesRead;
                    if(writelength < size){
                        byte[] encodedData = encoder.encode(buffer, false);
                        if(null != encodedData){
                            Log.d(TAG,"encodedData size "+encodedData.length);
                            mRTASR.write(encodedData.clone());
                            writeFile(encodedData.clone(),getFilesDir().getAbsolutePath() + "/encode.spx");
                        }
                    }else{
                        byte[] last = new byte[bytesRead];
                        System.arraycopy(buffer, 0, last, 0, bytesRead);
                        byte[] encodedData = encoder.encode(last, true);
                        if(null != encodedData){
                            Log.d(TAG,"encodedData size "+encodedData.length);
                            mRTASR.write(encodedData.clone());
                            writeFile( encodedData.clone(),getFilesDir().getAbsolutePath() + "/encode.spx");
                        }
                    }
                    try {
                        Thread.sleep(40);
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }
                input.close();
                encoder.finish();
                byte[] end = encoder.getEncodedData();
                if(null != end){
                    Log.d(TAG,"encodedData size "+end.length);
                    mRTASR.write(end.clone());
                    writeFile( end.clone(),getFilesDir().getAbsolutePath() + "/encode.spx");
                }
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }

        } else if(audiotype.equals("opus-wb")){
            InputStream input = null;
            try {
                input = getAssets().open("cn_test.pcm");
                byte[] buffer = new byte[1280];
                int bytesRead;
                int size = input.available();
                int writelength = 0;
                while ((bytesRead = input.read(buffer)) != -1) {
                    if(!isrun){
                        break;
                    }
                    writelength += bytesRead;
                    if(writelength < size){
                        byte[] encodedData = encoder.encode(buffer, false);
                        if(null != encodedData){
                            Log.d(TAG,"encodedData size "+encodedData.length);
                            mRTASR.write(encodedData.clone());
                            writeFile(encodedData.clone(),getFilesDir().getAbsolutePath() + "/encode.opus");
                        }
                    }else{
                        byte[] last = new byte[bytesRead];
                        System.arraycopy(buffer, 0, last, 0, bytesRead);
                        byte[] encodedData = encoder.encode(last, true);
                        if(null != encodedData){
                            Log.d(TAG,"encodedData size "+encodedData.length);
                            mRTASR.write(encodedData.clone());
                            writeFile( encodedData.clone(),getFilesDir().getAbsolutePath() + "/encode.opus");
                        }
                    }
                    try {
                        Thread.sleep(40);
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }
                input.close();
                encoder.finish();
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        }
            if(isrun)
                mRTASR.stop();
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public static void writeFile(byte[] data, String filePath) {
        File file = new File(filePath);
        FileOutputStream fos = null;
        try {
            //文件不存在则创建文件
            File parentFile = file.getParentFile();
            if (!parentFile.exists()) {
                parentFile.mkdirs();
                if (!file.exists()) {
                    file.createNewFile();
                }
            }
            fos = new FileOutputStream(file, true);
            fos.write(data);
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                if (fos != null) {
                    fos.flush();
                    fos.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
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
                    XXPermissions.startPermissionActivity(RTASRLLMActivity.this,denied);
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
            init();
        }
        mRTASR.setParams("lang", "autodialect");//autodialect:支持中英 +202 种方言免切识别autominor:支持 37 个语种免切识别(暂需联系人工对接)
        mRTASR.setParams("samplerate", "16000");
        if(audiotype.equals("speex-wb")){
            mRTASR.setParams("audio_encode", "speex-7");
        }else if(audiotype.equals("opus-wb")){
            mRTASR.setParams("audio_encode", "opus-wb");
        }else{
            mRTASR.setParams("audio_encode", "pcm_s16le");
        }
        if(mode == ASRMode.CN){
            audioPath = "/sdcard/iflytek/asr/cn_test.pcm";//转写音频路径，开发者可根据自身需求修改，但要求有读写权限。Demo仅演示读音频转写。SDK亦支持从麦克风实时读入音频去转写，这里不做展示。
        }else{
            audioPath = "/sdcard/iflytek/asr/en_test.pcm";//转写音频路径，开发者可根据自身需求修改，但要求有读写权限。Demo仅演示读音频转写。SDK亦支持从麦克风实时读入音频去转写，这里不做展示。
        }


        asrFinalResult = "识别结果：\n";
        transFinalResult = "翻译结果：\n";
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                tv_result.setText(asrFinalResult);
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
