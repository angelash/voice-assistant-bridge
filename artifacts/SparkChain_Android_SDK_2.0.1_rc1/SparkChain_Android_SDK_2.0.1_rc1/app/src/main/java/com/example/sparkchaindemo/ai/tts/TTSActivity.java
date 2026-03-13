package com.example.sparkchaindemo.ai.tts;

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
import com.iflytek.sparkchain.core.tts.OnlineTTS;
import com.iflytek.sparkchain.core.tts.TTS;
import com.iflytek.sparkchain.core.tts.TTSCallbacks;

import java.io.File;

/*************************
 * 在线合成Demo
 * create by wxw
 * 2024-12-14
 * **********************************/
public class TTSActivity extends AppCompatActivity implements SeekBar.OnSeekBarChangeListener,
        AdapterView.OnItemSelectedListener,View.OnClickListener{
    public static final String WORK_DIR = "/sdcard/iflytek/Onlinetts";
    private final String TAG = "AEELog";
    private Button btn_Test_start,btn_Test_stop;
    private String text;


    private OnlineTTS mOnlineTTS;

    private Spinner vncSpinner;

    private SeekBar mPitchSeekBar,mSpeedSeekBar,mVolumeSeekBar;

    private TextView mPitchTxt,mSpeedTxt,mVolumeTxt,tv_notification;

    private EditText mEd_Text;


    private TTSParams mTTSParams = new TTSParams();
    private void initView(){
        btn_Test_start = findViewById(R.id.ai_tts_play_btn);
        btn_Test_stop = findViewById(R.id.ai_tts_stop_btn);
        btn_Test_start.setOnClickListener(this);
        btn_Test_stop.setOnClickListener(this);

        vncSpinner = findViewById(R.id.ai_tts_vcn_spinner);
        SpinnerAdapter vncAdapter = new SpinnerAdapter(this, TTSParams.getVCN());
        vncSpinner.setAdapter(vncAdapter);
        vncSpinner.setOnItemSelectedListener(this);

        mPitchSeekBar = findViewById(R.id.ai_tts_pitch_seekbar);
        mSpeedSeekBar = findViewById(R.id.ai_tts_speed_seekbar);
        mVolumeSeekBar = findViewById(R.id.ai_tts_volume_seekbar);
        mPitchSeekBar.setOnSeekBarChangeListener(this);
        mSpeedSeekBar.setOnSeekBarChangeListener(this);
        mVolumeSeekBar.setOnSeekBarChangeListener(this);

        mPitchTxt = findViewById(R.id.ai_tts_pitch_txt);
        mSpeedTxt = findViewById(R.id.ai_tts_speed_txt);
        mVolumeTxt = findViewById(R.id.ai_tts_volume_txt);
        tv_notification = findViewById(R.id.ai_tts_notification);
        mEd_Text = findViewById(R.id.ai_tts_input);
    }


    TTSCallbacks mTTSCallback = new TTSCallbacks() {
        @Override
        public void onResult(TTS.TTSResult result, Object o) {
            Log.e(TAG,"status："+result.getStatus());
            //解析获取的交互结果，示例展示所有结果获取，开发者可根据自身需要，选择获取。
            byte[] audio    = result.getData();//音频数据
            int len        = result.getLen();//音频数据长度
            int status     = result.getStatus();//数据状态
            String ced     = result.getCed();//进度
            String sid     = result.getSid();//sid

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
            String msg = "合成出错！code:"+errCode+",msg:"+errMsg+",sid:"+sid;
            Log.d(TAG, "onError:errCode:" + errCode+ ",errMsg:" + errMsg);
            showInfo(msg);
            if(isPlaying){
                //如果此时已经播报，则停止播报
                stop();
            }
        }
    };

    private void showInfo(String text){
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                tv_notification.setText(text);
            }
        });
    }


    private void start(){
        Log.d(TAG,"start-->");
        Log.d(TAG,"vcn = " + mTTSParams.vcn);     //发音人
        Log.d(TAG,"pitch = " + mTTSParams.pitch); //语调
        Log.d(TAG,"speed = " + mTTSParams.speed); //语速
        Log.d(TAG,"volume = " + mTTSParams.volume);//音量
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
         * 在线合成发音人设置接口，发音人可从构造方法中设入，也可通过功能参数动态修改。
         * xiaoyan，晓燕，⼥：中⽂
         * *******************/
        mOnlineTTS = new OnlineTTS(mTTSParams.vcn);
//        mOnlineTTS.vcn(mTTSParams.vcn);
        /********************
         * aue(必填):
         * 音频编码，可选值：raw：未压缩的pcm
         * lame：mp3 (当aue=lame时需传参sfl=1)
         * speex-org-wb;7： 标准开源speex（for speex_wideband，即16k）数字代表指定压缩等级（默认等级为8）
         * speex-org-nb;7： 标准开源speex（for speex_narrowband，即8k）数字代表指定压缩等级（默认等级为8）
         * speex;7：压缩格式，压缩等级1~10，默认为7（8k讯飞定制speex）
         * speex-wb;7：压缩格式，压缩等级1~10，默认为7（16k讯飞定制speex）
         * ****************************/
//        mOnlineTTS.aue("raw");
        mOnlineTTS.speed(mTTSParams.speed);//语速：0对应默认语速的1/2，100对应默认语速的2倍。最⼩值:0, 最⼤值:100
        mOnlineTTS.pitch(mTTSParams.pitch);//语调：0对应默认语速的1/2，100对应默认语速的2倍。最⼩值:0, 最⼤值:100
        mOnlineTTS.volume(mTTSParams.volume);//音量：0是静音，1对应默认音量1/2，100对应默认音量的2倍。最⼩值:0, 最⼤值:100
        mOnlineTTS.bgs(0);//合成音频的背景音 0:无背景音（默认值） 1:有背景音
        mOnlineTTS.registerCallbacks(mTTSCallback);
        int ret = mOnlineTTS.aRun(text);
        if(ret!=0){
            showInfo("合成出错!ret="+ret);
        }
    }

    private void stop(){
        if(mOnlineTTS !=null) {
            mAudioPlayHandler.removeCallbacksAndMessages(null);
            mAudioPlayHandler.sendEmptyMessage(AUDIOPLAYER_END);
//            mOnlineTTS.stop();
            mOnlineTTS = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d("WXW","onDestory");
        if(isPlaying){
            isPlaying = false;
            mAudioPlayHandler.removeCallbacksAndMessages(null);
            mAudioPlayHandler.sendEmptyMessage(AUDIOPLAYER_END);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.ai_tts);
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
                Toast.makeText(getApplicationContext(),"在线合成音频存放路径创建失败，请手动创建/sdcard/iflytek/Onlinetts",Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()){
            case R.id.ai_tts_play_btn:
                start();
                break;
            case R.id.ai_tts_stop_btn:
                stop();
                break;
        }
    }

    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        switch (parent.getId()) {
            case R.id.ai_tts_vcn_spinner:
                mTTSParams.vcn = TTSParams.getVCN().get(position).value;
                break;
            default:
                break;
        }
    }

    @Override
    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        switch (seekBar.getId()) {
            case R.id.ai_tts_pitch_seekbar:
                mTTSParams.pitch = progress;
                mPitchTxt.setText(String.valueOf(progress));
                break;
            case R.id.ai_tts_speed_seekbar:
                mTTSParams.speed = progress;
                mSpeedTxt.setText(String.valueOf(progress));
                break;
            case R.id.ai_tts_volume_seekbar:
                mTTSParams.volume = progress;
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