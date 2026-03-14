package com.example.sparkchaindemo.llm.online_llm.bm;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.sparkchaindemo.R;
import com.example.sparkchaindemo.utils.FileUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
/*************************
 * 大模型识别类型选择：中文识别大模型；多语种识别大模型
 * create by wxw
 * 2024-12-17
 * **********************************/
public class bmModeChoiceActivity extends AppCompatActivity implements View.OnClickListener{
    private static final String TAG = "AEELog";
    private Button btn_bmc, btn_bmm;
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.online_llm_bm_choice);
        initView();
        createWorkDir();
    }

    private void createWorkDir()  {
        String path = "/sdcard/iflytek/asr";
        FileUtils.deleteDirectory(path);
        File folder = new File(path);
        boolean success = folder.mkdirs();
        if (success) {
            // 文件夹创建成功
            try {
                copyFilesFromAssets();
            }catch (Exception e){
                e.printStackTrace();
                Toast.makeText(getApplicationContext(),"在线识别音频文件拷贝失败，请检查是否有sdcard读写权限",Toast.LENGTH_LONG).show();
            }
        } else {
            // 文件夹创建失败
            Toast.makeText(getApplicationContext(),"在线识别音频文件拷贝失败，请检查是否有sdcard读写权限",Toast.LENGTH_LONG).show();
        }
    }

    private void copyFilesFromAssets() throws IOException {
        String[] fileNames = getAssets().list("");
        if (fileNames != null && fileNames.length > 0) {
            for (String fileName : fileNames) {
                if (fileName.endsWith(".pcm")) {
                    try {
                        InputStream inputStream = getAssets().open(fileName);
                        OutputStream outputStream = new FileOutputStream("/sdcard/iflytek/asr/" + fileName);
                        byte[] buffer = new byte[1024];
                        int length;
                        while ((length = inputStream.read(buffer)) != -1) {
                            outputStream.write(buffer, 0, length);
                        }
                        inputStream.close();
                        outputStream.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }



    private void initView(){
        btn_bmc = findViewById(R.id.online_llm_bmc_choice);
        btn_bmm = findViewById(R.id.online_llm_bmm_choice);
        btn_bmc.setOnClickListener(this);
        btn_bmm.setOnClickListener(this);
    }
    @Override
    public void onClick(View view) {
        switch(view.getId()){
            case R.id.online_llm_bmc_choice:
                jump(bmcDemoActivity.class);
                break;
            case R.id.online_llm_bmm_choice:
                jump(bmmDemoActivity.class);
                break;
        }
    }

    private void jump(Class jumpAct) {
        try {
            Intent intent = new Intent(this, jumpAct);
            startActivity(intent);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
