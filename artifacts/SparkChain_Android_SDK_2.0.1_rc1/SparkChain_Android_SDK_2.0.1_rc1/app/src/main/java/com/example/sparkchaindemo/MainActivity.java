package com.example.sparkchaindemo;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.sparkchaindemo.ai.AIMainActivity;
import com.example.sparkchaindemo.llm.online_llm.online_llm_mainActivity;

public class MainActivity extends AppCompatActivity implements View.OnClickListener{
    private Button btn_llm,btn_ai;
    private TextView tv_notification;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        initView();
    }

    private void initView(){
        btn_ai = findViewById(R.id.main_ai);
        btn_llm = findViewById(R.id.main_llm);

        btn_ai.setOnClickListener(this);
        btn_llm.setOnClickListener(this);
    }

    private void jump(Class jumpAct) {
        try {
            Intent intent = new Intent(this, jumpAct);
            startActivity(intent);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    @Override
    public void onClick(View view) {
        switch (view.getId()){
            case R.id.main_ai:
                jump(AIMainActivity.class);
                break;
            case R.id.main_llm:
                jump(online_llm_mainActivity.class);
                break;
        }
    }
}
