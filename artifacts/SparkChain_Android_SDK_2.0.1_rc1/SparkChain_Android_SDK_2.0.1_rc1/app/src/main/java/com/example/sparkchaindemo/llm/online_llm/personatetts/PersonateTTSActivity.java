package com.example.sparkchaindemo.llm.online_llm.personatetts;

import android.app.ProgressDialog;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.example.sparkchaindemo.R;
import com.example.sparkchaindemo.adapter.SpinnerAdapter;
import com.example.sparkchaindemo.utils.AudioTrackManager;
import com.example.sparkchaindemo.utils.FileUtils;
import com.iflytek.sparkchain.core.tts.PersonateTTS;
import com.iflytek.sparkchain.core.tts.TTS;
import com.iflytek.sparkchain.core.tts.TTSCallbacks;

import java.io.File;

/*************************
 * 超拟人合成Demo
 * create by wxw
 * 2024-12-17
 * **********************************/
public class PersonateTTSActivity extends AppCompatActivity implements SeekBar.OnSeekBarChangeListener,
        AdapterView.OnItemSelectedListener,View.OnClickListener{
    public static final String WORK_DIR = "/sdcard/iflytek/personatetts";
    private final String TAG = "AEELog";
    private Button btn_Test_start,btn_Test_stop,btn_TestFlow_start;
    private String text;

    private String texts[] = {
            "今天天气很好，很适合出去玩",
            "2024年9月26日，星期四，小明在这一天开启了一段充满惊喜的冒险之旅。",
            "床前明月光，疑似地上霜。",
            "阳光透过斑驳的树叶洒在街道上。微风轻拂，似在诉说着岁月的故事。心，在这宁静的时光里，渐渐沉醉。"
    };

    private PersonateTTS personateTTS;
    private Spinner vncSpinner;

    private SeekBar mPitchSeekBar,mSpeedSeekBar,mVolumeSeekBar;

    private TextView mPitchTxt,mSpeedTxt,mVolumeTxt;

    private EditText mEd_Text;


    private PersonateTTSParams mPersonateTTSParams = new PersonateTTSParams();
    private void initView(){
        btn_Test_start = findViewById(R.id.online_llm_personatetts_play_btn);
        btn_Test_stop = findViewById(R.id.online_llm_personatetts_stop_btn);
        btn_TestFlow_start = findViewById(R.id.online_llm_personatetts_flow_btn);
        btn_Test_start.setOnClickListener(this);
        btn_Test_stop.setOnClickListener(this);
        btn_TestFlow_start.setOnClickListener(this);

        vncSpinner = findViewById(R.id.online_llm_personatetts_vcn_spinner);
        SpinnerAdapter vncAdapter = new SpinnerAdapter(this, PersonateTTSParams.getVCN());
        vncSpinner.setAdapter(vncAdapter);
        vncSpinner.setOnItemSelectedListener(this);

        mPitchSeekBar = findViewById(R.id.online_llm_personatetts_pitch_seekbar);
        mSpeedSeekBar = findViewById(R.id.online_llm_personatetts_speed_seekbar);
        mVolumeSeekBar = findViewById(R.id.online_llm_personatetts_volume_seekbar);
        mPitchSeekBar.setOnSeekBarChangeListener(this);
        mSpeedSeekBar.setOnSeekBarChangeListener(this);
        mVolumeSeekBar.setOnSeekBarChangeListener(this);

        mPitchTxt = findViewById(R.id.online_llm_personatetts_pitch_txt);
        mSpeedTxt = findViewById(R.id.online_llm_personatetts_speed_txt);
        mVolumeTxt = findViewById(R.id.online_llm_personatetts_volume_txt);

        mEd_Text = findViewById(R.id.online_llm_personatetts_input);
    }


    TTSCallbacks mTTSCallback = new TTSCallbacks() {
        @Override
        public void onResult(TTS.TTSResult result, Object o) {
            //解析获取的交互结果，示例展示所有结果获取，开发者可根据自身需要，选择获取。
            byte[] audio    = result.getData();//音频数据
            int len        = result.getLen();//音频数据长度
            int status     = result.getStatus();//数据状态
            int seq        = result.getSeq();//数据序号
            String ced     = result.getCed();//进度
            String pybuf   = result.getPybuf();//拼音结果
            String version = result.getVersion();//引擎版本号
            String sid     = result.getSid();//sid

            String results = "{len="+len+",status="+status+",seq="+seq+",ced="+ced+",pybuf="+pybuf+",version="+version+",sid="+sid;
            Log.d(TAG, results);

            Bundle bundle = new Bundle();
            bundle.putByteArray("audio", audio);
            Message msg = mAudioPlayHandler.obtainMessage();
            msg.what = AUDIOPLAYER_WRITE;
            msg.obj = bundle;
            mAudioPlayHandler.sendMessage(msg);

            if(status == 2){
                //音频合成回调结束状态，注意，此状态不是播报完成状态
                mAudioPlayHandler.sendEmptyMessage(AUDIOPLAYER_END);
            }

        }

        @Override
        public void onError(TTS.TTSError ttsError, Object o) {
            int errCode   = ttsError.getCode();//错误码
            String errMsg = ttsError.getErrMsg();//错误信息
            String sid    = ttsError.getSid();//sid
            Log.d(TAG, "onError:errCode:" + errCode+ ",errMsg:" + errMsg);
            if(isPlaying){
                //如果此时已经播报，则停止播报
                stop();
            }
        }
    };

    private void start(){
        Log.d(TAG,"start-->");
        Log.d(TAG,"vcn = " + mPersonateTTSParams.vcn);     //发音人
        Log.d(TAG,"pitch = " + mPersonateTTSParams.pitch); //语调
        Log.d(TAG,"speed = " + mPersonateTTSParams.speed); //语速
        Log.d(TAG,"volume = " + mPersonateTTSParams.volume);//音量
        text = mEd_Text.getText().toString();
        Log.d(TAG,"text = " + text);
        if(audioTrack == null){
            mAudioPlayHandler.sendEmptyMessage(AUDIOPLAYER_INIT);
        }else{
            if(isPlaying){
                stop();
            }
            mAudioPlayHandler.sendEmptyMessage(AUDIOPLAYER_START);
        }

        /******************
         * 超拟人发音人设置接口，发音人可从构造方法中设入，也可通过功能参数动态修改。
         * x4_lingxiaoxuan_oral，聆⼩璇，⼥：中⽂
         * x4_lingfeizhe_oral，聆⻜哲，男：中⽂
         * *******************/
        personateTTS = new PersonateTTS(mPersonateTTSParams.vcn);
        personateTTS.speed(mPersonateTTSParams.speed);//语速：0对应默认语速的1/2，100对应默认语速的2倍。最⼩值:0, 最⼤值:100
        personateTTS.pitch(mPersonateTTSParams.pitch);//语调：0对应默认语速的1/2，100对应默认语速的2倍。最⼩值:0, 最⼤值:100
        personateTTS.volume(mPersonateTTSParams.volume);//音量：0是静音，1对应默认音量1/2，100对应默认音量的2倍。最⼩值:0, 最⼤值:100
        personateTTS.sparkAssist(true);//是否通过⼤模型进⾏⼝语化。开启:true, 关闭:false
        personateTTS.oralLevel("high");//⼝语化等级。⾼:high, 中:mid, 低:low
        personateTTS.registerCallbacks(mTTSCallback);
        personateTTS.aRun(text);
    }

    private void startflow(){
        Log.d(TAG,"startflow-->");
        Log.d(TAG,"vcn = " + mPersonateTTSParams.vcn);
        Log.d(TAG,"pitch = " + mPersonateTTSParams.pitch);
        Log.d(TAG,"speed = " + mPersonateTTSParams.speed);
        Log.d(TAG,"volume = " + mPersonateTTSParams.volume);
        if(audioTrack == null){
            mAudioPlayHandler.sendEmptyMessage(AUDIOPLAYER_INIT);
        }else{
            if(isPlaying){
                stop();
            }
            mAudioPlayHandler.sendEmptyMessage(AUDIOPLAYER_START);
        }
        /******************
         * 超拟人发音人设置接口，发音人可从构造方法中设入，也可通过功能参数动态修改。
         * x4_lingxiaoxuan_oral，聆⼩璇，⼥：中⽂
         * x4_lingfeizhe_oral，聆⻜哲，男：中⽂
         * *******************/
        personateTTS = new PersonateTTS(mPersonateTTSParams.vcn);
        personateTTS.speed(mPersonateTTSParams.speed);//语速：0对应默认语速的1/2，100对应默认语速的2倍。最⼩值:0, 最⼤值:100
        personateTTS.pitch(mPersonateTTSParams.pitch);//语调：0对应默认语速的1/2，100对应默认语速的2倍。最⼩值:0, 最⼤值:100
        personateTTS.volume(mPersonateTTSParams.volume);//音量：0是静音，1对应默认音量1/2，100对应默认音量的2倍。最⼩值:0, 最⼤值:100
        personateTTS.sparkAssist(true);//是否通过⼤模型进⾏⼝语化。开启:true, 关闭:false
        personateTTS.oralLevel("high");//⼝语化等级。⾼:high, 中:mid, 低:low
        personateTTS.registerCallbacks(mTTSCallback);
        int status = 0;//输入文本状态，0:开始，1:中间，2:结束
        for(int i = 0;i<texts.length;i++){
            if(i == 0){
                status = 0;
            }else if(i == texts.length-1){
                status = 2;
            }else{
                status = 1;
            }
            Log.d(TAG,"status = " + status);
            personateTTS.aRun(texts[i],status);
        }

    }


    private void stop(){
        if(personateTTS!=null) {
            mAudioPlayHandler.removeCallbacksAndMessages(null);
            mAudioPlayHandler.sendEmptyMessage(AUDIOPLAYER_END);
            personateTTS.stop();
            personateTTS = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if(isPlaying){
            isPlaying = false;
            mAudioPlayHandler.removeCallbacksAndMessages(null);
            mAudioPlayHandler.sendEmptyMessage(AUDIOPLAYER_END);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.online_llm_personatetts);
        initView();
        createWorkDir();
        text = getResources().getString(R.string.TTS_TEXT);
        mAudioPlayThread.start();
    }


    private void createWorkDir(){
        File folder = new File(WORK_DIR);
        if (!folder.exists()) {
            boolean success = folder.mkdirs();
            if (success) {
                // 文件夹创建成功
            } else {
                // 文件夹创建失败
                Toast.makeText(getApplicationContext(),"超拟人音频存放路径创建失败，请手动创建/sdcard/iflytek/personatetts",Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()){
            case R.id.online_llm_personatetts_play_btn:
                start();
                break;
            case R.id.online_llm_personatetts_stop_btn:
                stop();
                break;
            case R.id.online_llm_personatetts_flow_btn:
                startflow();
                break;
        }
    }

    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        switch (parent.getId()) {
            case R.id.online_llm_personatetts_vcn_spinner:
                mPersonateTTSParams.vcn = PersonateTTSParams.getVCN().get(position).value;
                break;
            default:
                break;
        }
    }

    @Override
    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        switch (seekBar.getId()) {
            case R.id.online_llm_personatetts_pitch_seekbar:
                mPersonateTTSParams.pitch = progress;
                mPitchTxt.setText(String.valueOf(progress));
                break;
            case R.id.online_llm_personatetts_speed_seekbar:
                mPersonateTTSParams.speed = progress;
                mSpeedTxt.setText(String.valueOf(progress));
                break;
            case R.id.online_llm_personatetts_volume_seekbar:
                mPersonateTTSParams.volume = progress;
                mVolumeTxt.setText(String.valueOf(progress));
                break;
            default:
                break;
        }
    }
    private static final int AUDIOPLAYER_INIT = 0x0000;
    private static final int AUDIOPLAYER_START = 0x0001;
    private static final int AUDIOPLAYER_WRITE = 0x0002;
    private static final int AUDIOPLAYER_END = 0x0003;
    private static final int SAMPLE_RATE = 16000; // 采样率
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO; // 单声道输出
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT; // PCM 16位编码
    private AudioTrack audioTrack;
    private Handler mAudioPlayHandler;
    private boolean isPlaying = false;
    int count = 0;
    private Thread mAudioPlayThread = new Thread(new Runnable() {
        @Override
        public void run() {
            Looper.prepare();
            mAudioPlayHandler = new Handler(Looper.myLooper()){

                @Override
                public void handleMessage(@NonNull Message msg) {
                    super.handleMessage(msg);
                    switch(msg.what){
                        case AUDIOPLAYER_INIT:
                            Log.d(TAG,"audioInit");
                            int minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
                            audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, minBufferSize, AudioTrack.MODE_STREAM);
                            mAudioPlayHandler.sendEmptyMessage(AUDIOPLAYER_START);
                            break;
                        case AUDIOPLAYER_START:
                            Log.d(TAG,"audioStart");
                            if(audioTrack!=null) {
                                isPlaying = true;
                                audioTrack.play();
                            }
                            break;
                        case AUDIOPLAYER_WRITE:
                            count ++;
                            if(count%5 == 0){
                                Log.d(TAG,"audioWrite");
                                count = 0;
                            }
                            Bundle bundle = (Bundle) msg.obj;
                            byte[] audioData = bundle.getByteArray("audio");
                            if(audioTrack!=null&&audioData.length>0){
                                audioTrack.write(audioData,0,audioData.length);
                            }
                            break;
                        case AUDIOPLAYER_END:
                            Log.d(TAG,"audioEnd");
                            if(audioTrack!=null) {
                                audioTrack.stop();
                                isPlaying = false;
                            }
                            break;
                    }
                }
            };
            Looper.loop();
        }
    });
    @Override
    public void onNothingSelected(AdapterView<?> parent) {

    }
    @Override
    public void onStartTrackingTouch(SeekBar seekBar) {

    }

    @Override
    public void onStopTrackingTouch(SeekBar seekBar) {

    }
}