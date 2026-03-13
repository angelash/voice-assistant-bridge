package com.example.sparkchaindemo.llm.online_llm.personatetts;



import com.example.sparkchaindemo.adapter.ParamInfo;

import java.util.ArrayList;
import java.util.List;
/*************************
 * 超拟人合成Demo的配置文件
 * create by wxw
 * 2024-12-17
 * **********************************/
public class PersonateTTSParams {

    public String vcn = "x5_lingyuyan_flow";
    public int pitch = 50;
    public int speed = 50;
    public int volume = 50;

    /**
     * 测试文本资源，一下资源顺序与发音人、语种相对应
     * @return
     */


    public static List<ParamInfo> getVCN() {
        List<ParamInfo> vcnList = new ArrayList<>();
        vcnList.add(new ParamInfo("x5_lingyuyan_flow","聆玉言(女)"));
        vcnList.add(new ParamInfo("x4_lingxiaoxuan_oral","聆晓璇(女)"));
        vcnList.add(new ParamInfo("x4_lingfeiyi_oral","聆飞逸(男)"));
        return vcnList;
    }

}
