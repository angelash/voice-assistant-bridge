package com.example.sparkchaindemo.ai.itrans;

import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.example.sparkchaindemo.R;
import com.example.sparkchaindemo.adapter.SpinnerAdapter;
import com.iflytek.sparkchain.core.its.ITS;
import com.iflytek.sparkchain.core.its.ITSCallbacks;
import com.iflytek.sparkchain.core.its.TransType;
/*************************
 * 在线翻译Demo
 * create by wxw
 * 2024-12-14
 * **********************************/
public class ITSActivity extends AppCompatActivity {
    private static final String TAG = "AEELog";
    private TextView tv_result;
    private Spinner sp_language,sp_itransType;
    private Button btn_itrans;
    private EditText ed_input;

    private String language = "cn2en";//cn2en:中译英；en2cn：英译中。demo仅展示中英互译，其他语种体验请先获取授权，然后自行修改demo
    private TransType transType = TransType.ITRANS;


    private ITS its = null;

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.ai_translate);
        initView();
    }

    private void initView(){
        tv_result = findViewById(R.id.ai_trans_result);
        tv_result.setMovementMethod(new ScrollingMovementMethod());
        sp_language = findViewById(R.id.ai_trans_language);
        sp_itransType = findViewById(R.id.ai_trans_itrans_type);
        btn_itrans = findViewById(R.id.ai_trans_translate_btn);
        ed_input = findViewById(R.id.ai_trans_input);
        btn_itrans.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        testIts();
                    }
                }).start();
            }
        });

        SpinnerAdapter languageSpinner = new SpinnerAdapter(this, ITSParams.getLanguage());
        SpinnerAdapter transTypeSpinner = new SpinnerAdapter(this, ITSParams.getTransType());

        sp_language.setAdapter(languageSpinner);
        sp_itransType.setAdapter(transTypeSpinner);

        sp_language.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                language = ITSParams.getLanguage().get(position).value;
                try{
                    ed_input.setText(ITSParams.testTxt().get(position));
                }catch(Exception e){
                    ed_input.setText("");
                }
                tv_result.setText("");
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });

        sp_itransType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String type = ITSParams.getTransType().get(position).value;
                if("ITRANS".equals(type)){
                    transType = TransType.ITRANS;  //机器翻译
                }else if("NIUTRANS".equals(type)){
                    transType = TransType.NIUTRANS;//小牛翻译
                }
                its = null;

            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });

    }

    ITSCallbacks miTransCallback = new ITSCallbacks() {
        @Override
        public void onResult(ITS.ITSResult itsResult, Object o) {
            //以下信息需要开发者根据自身需求，如无必要，可不需要解析执行。
            String srcText = itsResult.getTransResult().getSrc(); //翻译的源文本
            String dstText = itsResult.getTransResult().getDst(); //翻译结果
            int status     = itsResult.getStatus();               //翻译结果状态
            String from    = itsResult.getFrom();                 //源语种
            String to      = itsResult.getTo();                   //目标语种
            String sid     = itsResult.getSid();                  //本次交互的SID

            Log.d(TAG,"{src:"+srcText+",dst:"+dstText+",status:"+status+",from:"+from+",to:"+to+",sid:"+sid+"}");
            String showResult = "源文本:"+srcText+"\n翻译结果:"+dstText+"\n";
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    tv_result.append(showResult);
                    toend();
                }
            });

            isrun = false;
        }

        @Override
        public void onError(ITS.ITSError itsError, Object o) {
            int code   = itsError.getCode();    //错误码
            String msg = itsError.getErrMsg();  //错误信息
            String sid = itsError.getSid();     //交互sid
            String showError = "翻译出错！错误码:"+code+"\n错误信息:"+msg+"\nSid:"+sid+"\n";
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    tv_result.append(showError);
                }
            });

            isrun = false;
        }
    };


    boolean isrun = false;

    private void testIts() {
        if(isrun) return;
        isrun = true;
        /********************
         * cn:中文
         * en:英文
         * 其他语种详见集成文档的语种列表
         *
         * TransType.ITRANS:机器翻译新版
         * TransType.NIUTRANS:小牛翻译
         * TransType.ITRANS_SG:海外翻译
         * ****************************/

        Log.d(TAG,"language = " + language);
        Log.d(TAG,"transType = " + transType);
        String input = ed_input.getText().toString();

        String[] languages = language.split("2");
        String from = "cn";
        String to = "to";
        // 输出分割后的结果
        if (languages.length == 2) {
            from = languages[0]; // "源语种"
            to = languages[1]; // "目标语种"
        } else {
            Log.d(TAG,"language type Error!!!!!!Use Default value.");
        }

        Log.d(TAG,"from = " + from + ",to = " + to);

        if(its == null){
            Log.d(TAG,"new ITS!");
            its = new ITS(transType);
        }

        its.fromlanguage(from);//设置源语种
        its.tolanguage(to);//设置目标语种
        //其他接口参考集成文档，demo当前省略
        its.registerCallbacks(miTransCallback);
        int ret = its.arun(input,"12345");
        Log.d(TAG,"its.arun ret:" + ret);
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
