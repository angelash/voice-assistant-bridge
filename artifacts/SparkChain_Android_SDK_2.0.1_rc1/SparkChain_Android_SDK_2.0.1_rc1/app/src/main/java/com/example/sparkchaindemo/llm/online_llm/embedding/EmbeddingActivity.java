package com.example.sparkchaindemo.llm.online_llm.embedding;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.sparkchaindemo.R;
import com.iflytek.sparkchain.core.Embedding;
import com.iflytek.sparkchain.core.EmbeddingOutput;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.math.BigDecimal;
import java.util.ArrayList;
/*************************
 * 文本向量化Demo，包含知识原文向量化以及用户问题向量化
 * create by wxw
 * 2024-12-17
 * **********************************/
public class EmbeddingActivity extends AppCompatActivity implements View.OnClickListener{
    private static final String TAG = "AEELog";
    private Button bt_EmbeddingP,bt_EmbeddingQ;

    private TextView tv_notification;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.online_llm_embedding);
        initView();
    }

    private void initView(){
        bt_EmbeddingP = findViewById(R.id.online_llm_embedding_startEmbeddingP);
        bt_EmbeddingQ = findViewById(R.id.online_llm_embedding_startEmbeddingQ);
        bt_EmbeddingP.setOnClickListener(this);
        bt_EmbeddingQ.setOnClickListener(this);
        tv_notification = findViewById(R.id.online_llm_embedding_notification);
    }

    private void showInfo(String text){
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                tv_notification.setText(text);
            }
        });
    }

    private void startEmbedding(String input,String domain,String filePath){
        EmbeddingOutput output = Embedding.getInst().embedding(input,domain);
        int errcode         = output.getErrCode();     //获取请求结果，0：成功，非0：请求失败。
        String rawResult    = output.getRaw();         //获取大模型返回的原始结果，格式为json。
        String errMsg       = output.getErrMsg();      //获取错误信息，注意：如果调用成功，则此接口会返回空。
        String sid          = output.getSid();         //获取本次交互的sid。
        ArrayList<Float> af = output.getResultArray(); //获取本次请求文本的向量化结果。

        Log.d(TAG,"raw:"+rawResult);
        Log.d(TAG,"ret:"+errcode);
        Log.d(TAG,"msg:"+errMsg);
        if(errcode == 0){
            try(BufferedWriter writer = new BufferedWriter(new FileWriter(filePath))){
                for(float value : af){
                    BigDecimal bm = new BigDecimal(value);
                    writer.write(bm.toString());
                    writer.newLine();
                }
                showInfo("写入文件成功。路径为："+filePath);
            }catch (Exception e){
                e.printStackTrace();
                showInfo("写入文件失败。请检查路径是否有读写权限:" + filePath);
            }
        }else{
            showInfo("Embedding转换失败，错误码：" + errcode);
        }
    }



    /***********
     * 文档向量化建模
     * ***************/
    private void startEmbeddingP(){
        String input = "这段话的内容变成向量化是什么样的？";
        String domain = "para";   //向量化类型。para:知识原文向量化,query:用户问题向量化
        String filePath = "/sdcard/iflytek/embedding_androidP.txt";//结果文件存放路径，开发者可根据自身需求修改，要求有读写权限。
        startEmbedding(input,domain,filePath);
    }
    /***********
    * 问题向量化建模
    * ***************/
    private void startEmbeddingQ(){
        String input = "这段话的内容变成向量化是什么样的？";
        String domain = "query";  //向量化类型。para:知识原文向量化,query:用户问题向量化
        String filePath = "/sdcard/iflytek/embedding_androidQ.txt";//结果文件存放路径，开发者可根据自身需求修改，要求有读写权限。
        startEmbedding(input,domain,filePath);
    }


    @Override
    public void onClick(View view) {
        switch(view.getId()){
            case R.id.online_llm_embedding_startEmbeddingP:
                startEmbeddingP();
                break;
            case R.id.online_llm_embedding_startEmbeddingQ:
                startEmbeddingQ();
                break;
        }
    }
}
