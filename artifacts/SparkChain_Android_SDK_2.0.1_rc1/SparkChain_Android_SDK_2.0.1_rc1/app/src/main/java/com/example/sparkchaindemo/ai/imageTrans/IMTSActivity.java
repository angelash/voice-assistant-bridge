package com.example.sparkchaindemo.ai.imageTrans;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.sparkchaindemo.R;
import com.iflytek.sparkchain.core.imageTrans.ImageTrans;
import com.iflytek.sparkchain.core.imageTrans.ImageTransCallbacks;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

public class IMTSActivity extends AppCompatActivity {

    private final String appID = "";
    private final String apiKey = "";
    private final String apiSecret = "";
    private AtomicBoolean isRunning = new AtomicBoolean(false);

    private TextView txtResult;
    private ImageView imageView, resultImageView;
    private Button btnSelectImage, btnInit, btnTranslate;
    private Spinner spinnerOcrLang, spinnerFromLang, spinnerToLang;

    private String currentImagePath = null;
    private boolean isSdkInited = false;
    private boolean isTranslating = false;
    private ImageTrans imageTrans;

    // 语言选项数组
    private String[] ocrLanguages = {"ch_en", "ja", "ko", "fr"};
    private String[] sourceLanguages = {"cn", "en", "ja", "ko", "fr"};
    private String[] targetLanguages = {"cn", "en", "de", "ru", "fr", "ko", "pt", "ja", "es", "it", "vi"};

    private static final int REQUEST_IMAGE_PICK = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.api_imagetrans);

        txtResult = findViewById(R.id.txt_result);
        imageView = findViewById(R.id.imageView);
        resultImageView = findViewById(R.id.resultImageView);
        btnSelectImage = findViewById(R.id.btn_select_image);
        //btnInit = findViewById(R.id.btn_init);
        btnTranslate = findViewById(R.id.btn_translate);

        // 初始化Spinner
        spinnerOcrLang = findViewById(R.id.spinner_ocr_lang);
        spinnerFromLang = findViewById(R.id.spinner_from_lang);
        spinnerToLang = findViewById(R.id.spinner_to_lang);

        // 设置Spinner适配器
        setupSpinners();

        btnSelectImage.setOnClickListener(v -> selectImage());
        //btnInit.setOnClickListener(v -> initSDK());
        btnTranslate.setOnClickListener(v -> startImageTrans());
    }

    private void setupSpinners() {
        // 创建适配器
        android.widget.ArrayAdapter<String> ocrAdapter = new android.widget.ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, ocrLanguages);
        ocrAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        android.widget.ArrayAdapter<String> fromAdapter = new android.widget.ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, sourceLanguages);
        fromAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        android.widget.ArrayAdapter<String> toAdapter = new android.widget.ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, targetLanguages);
        toAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        // 设置适配器
        spinnerOcrLang.setAdapter(ocrAdapter);
        spinnerFromLang.setAdapter(fromAdapter);
        spinnerToLang.setAdapter(toAdapter);

        // 设置默认选择
        spinnerOcrLang.setSelection(0); // ch_en
        spinnerFromLang.setSelection(0); // cn
        spinnerToLang.setSelection(1);   // en

        // 设置图片点击放大功能
        setupImageClickListeners();
    }

    private void setupImageClickListeners() {
        // 原图点击放大
        imageView.setOnClickListener(v -> {
            if (imageView.getDrawable() != null) {
                showFullScreenImage(imageView.getDrawable(), "原图");
            }
        });

        // 结果图片点击放大
        resultImageView.setOnClickListener(v -> {
            if (resultImageView.getDrawable() != null) {
                showFullScreenImage(resultImageView.getDrawable(), "翻译结果");
            }
        });
    }

    private void showFullScreenImage(android.graphics.drawable.Drawable drawable, String title) {
        // 创建全屏显示对话框
        android.app.Dialog dialog = new android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);

        // 创建ImageView
        ImageView fullScreenImageView = new ImageView(this);
        fullScreenImageView.setLayoutParams(new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        fullScreenImageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        fullScreenImageView.setImageDrawable(drawable);
        fullScreenImageView.setBackgroundColor(android.graphics.Color.BLACK);

        // 添加点击关闭功能
        fullScreenImageView.setOnClickListener(v -> dialog.dismiss());

        // 设置对话框内容
        dialog.setContentView(fullScreenImageView);

        // 显示对话框
        dialog.show();

        // 添加提示信息
        Toast.makeText(this, "点击图片可关闭全屏显示", Toast.LENGTH_SHORT).show();
    }

    private void selectImage() {
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        startActivityForResult(intent, REQUEST_IMAGE_PICK);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_IMAGE_PICK && resultCode == RESULT_OK && data != null) {
            Uri selectedImage = data.getData();
            try {
                InputStream inputStream = getContentResolver().openInputStream(selectedImage);
                Bitmap bitmap = BitmapFactory.decodeStream(inputStream);
                imageView.setImageBitmap(bitmap);

                // 保存图片到本地文件
                File tempFile = new File(getCacheDir(), "temp_image.jpg");
                FileOutputStream fos = new FileOutputStream(tempFile);
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos);
                fos.close();
                currentImagePath = tempFile.getAbsolutePath();
            } catch (Exception e) {
                e.printStackTrace();
                Toast.makeText(this, "图片处理失败", Toast.LENGTH_SHORT).show();
            }
        }
    }


    private void startImageTrans() {
        if (currentImagePath == null) {
            Toast.makeText(this, "请先选择图片", Toast.LENGTH_SHORT).show();
            return;
        }
        if (isTranslating) {
            Toast.makeText(this, "正在翻译中...", Toast.LENGTH_SHORT).show();
            return;
        }
        txtResult.setText("");
        isTranslating = true;

        if (imageTrans == null) {
            //假设ImageTrans构造参数为(ocrLang, fromLang, toLang)
            //imageTrans = new ImageTrans();
            imageTrans = new ImageTrans("ch_en", "cn", "en");
        }

        // 获取用户选择的语言设置
        String selectedOcrLang = spinnerOcrLang.getSelectedItem().toString();
        String selectedFromLang = spinnerFromLang.getSelectedItem().toString();
        String selectedToLang = spinnerToLang.getSelectedItem().toString();

        // 设置语言参数
        imageTrans.ocrLanguage(selectedOcrLang);
        imageTrans.fromLanguage(selectedFromLang);
        imageTrans.toLanguage(selectedToLang);
        imageTrans.registerCallbacks(new ImageTransCallbacks() {
            @Override
            public void onResult(ImageTrans.ImageTransResult result, Object usrTag) {
                runOnUiThread(() -> {
                    Log.d("IMTSActivity", "iamgeTrans status: " + result.getStatus());
                    Log.d("IMTSActivity","sid: " + result.getSid());
                    byte[] imageBytes = result.getItsImage();
                    if (imageBytes != null && imageBytes.length > 0) {
                        try {
                            // 将保存的图片显示在resultImageView中
                            Bitmap resultBitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
                            if (resultBitmap != null) {
                                resultImageView.setImageBitmap(resultBitmap);

                                // 保存图片到本地文件
                                File outFile = new File("/sdcard/iflytek/output_image.jpg");
                                FileOutputStream fos = new FileOutputStream(outFile);
                                fos.write(imageBytes);
                                fos.close();

                                Log.d("IMTSActivity", "图片已保存: " + outFile.getAbsolutePath());
                                Toast.makeText(IMTSActivity.this, "翻译结果图片已显示并保存", Toast.LENGTH_SHORT).show();
                            } else {
                                Log.e("IMTSActivity", "无法解码结果图片");
                                Toast.makeText(IMTSActivity.this, "结果图片解码失败", Toast.LENGTH_SHORT).show();
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                            Log.e("IMTSActivity", "图片处理失败: " + e.getMessage());
                            Toast.makeText(IMTSActivity.this, "图片处理失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        Log.d("IMTSActivity", "没有接收到结果图片数据");
                    }

                    String text = result.getItsOutput();
                    if(!text.isEmpty()) {
                        Log.d("IMTSActivity", "翻译结果：" + text);
                        txtResult.setText(text);
                    }

                    int size = result.getBlockText().length;
                    for(int i=0;i<size;++i)
                    {
                        Log.d("IMTSActivity", "src：" + result.getBlockText()[i].getSrc());
                        Log.d("IMTSActivity", "dst：" + result.getBlockText()[i].getDst());
                    }

                    if(result.getStatus() == 3)
                    {
                        isTranslating = false;
                    }

                });
            }

            @Override
            public void onError(ImageTrans.ImageTransError error, Object usrTag) {
                runOnUiThread(() -> {
                    txtResult.setText("error: " + error.getCode() + " " + error.getErrMsg());
                    isTranslating = false;
                });
            }
        });

        File file = new File(currentImagePath);
        byte[] imageData = null;
        try {
            FileInputStream fis = new FileInputStream(file);
            imageData = new byte[(int) file.length()];
            fis.read(imageData);
            fis.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        imageTrans.returnType(3);
        // 2. 调用 arun(byte[] imageData, String tag)
        if (imageData != null) {
            int ret = imageTrans.arun(imageData, "jpg","tag");
            Log.d("IMTSActivity", "imageTrans.arun ret:" + ret);
            if(ret != 0) {
                isTranslating = false;
                txtResult.setText("arun调用失败，错误码：" + ret);
            }
        }
    }
}
