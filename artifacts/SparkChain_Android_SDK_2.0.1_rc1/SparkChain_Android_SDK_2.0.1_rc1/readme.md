# README

## 一、Sample使用

1. 使用androidStudio导入Sample项目

2. 在string.xml中配置好APPID三元组。注意，在使用能力前需要先获得该能力的使用授权！

3. 如果使用的是实时语音转写或录音文件转写能力，还需要额外在RAASRActivity.java或RTASRActivity.java中填写对应的APIKey，该值可从控制台查看。具体如下：

   | KEY         | 查看方法                                                    |
   | ----------- | ----------------------------------------------------------- |
   | RTASRAPIKEY | https://console.xfyun.cn/services/rta 中查看APIKey的值      |
   | RAASRAPIKEY | https://console.xfyun.cn/services/lfasr 中查看SecretKey的值 |

4. 填写完信息后，执行run即可运行体验效果。

## 二、Sample结构

.
├── ai
│   ├── SDK初始化
│   ├── 在线合成
│   ├── 在线翻译
│   ├── 实时语音转写
│   ├── 录音文件转写
│   └── 语音听写
└── llm
    └── online_llm
        ├── Embedding
        ├── FunctionCall
        ├── SDK初始化
        ├── 图片理解
        ├── 图片生成
        ├── 大模型识别
        ├── 大模型通用对话
        └── 超拟人合成

## 三、常见问题

### 1.jdk版本适配问题

demo工程默认使用的是jdk 1.8，如需使用其他jdk版本，需要修改gradle（/gradle/wrapper/gradle-wrapper.properties/）以及AGP(build.gradle-dependencies-classpath)版本，可参考以下配置：

jdk|gradle|AGP|备注
-|-|-|-
11|7.0.2|7.0.0
17|8.0.2|8.0.0|gradle.properties增加android.nonFinalResIds=false，AndroidMenifest.xml中去除package，在app - build.gradle - android下增加 namespace "com.example.sparkchaindemo" ， 其他见编译报错修改即可
