package com.example.sparkchaindemo.llm.online_llm.bm;
import com.example.sparkchaindemo.adapter.ParamInfo;

import java.util.ArrayList;
import java.util.List;
/*************************
 * 多语种识别大模型Demo的配置文件
 * create by wxw
 * 2024-12-17
 * **********************************/
public class bmmDemoParams {

    public static List<ParamInfo> getTypes(){
        List<ParamInfo> typeList = new ArrayList<>();
        typeList.add(new ParamInfo("/sdcard/iflytek/asr/cn.pcm","中文"));
        typeList.add(new ParamInfo("/sdcard/iflytek/asr/en.pcm","英文"));
        typeList.add(new ParamInfo("/sdcard/iflytek/asr/ja.pcm","日语"));
        typeList.add(new ParamInfo("/sdcard/iflytek/asr/ko.pcm","韩语"));
        typeList.add(new ParamInfo("/sdcard/iflytek/asr/fr.pcm","法语"));
        typeList.add(new ParamInfo("/sdcard/iflytek/asr/es.pcm","西班牙语"));
        typeList.add(new ParamInfo("/sdcard/iflytek/asr/ru.pcm","俄语"));
        typeList.add(new ParamInfo("/sdcard/iflytek/asr/de.pcm","德语"));
        return typeList;
    }
}
