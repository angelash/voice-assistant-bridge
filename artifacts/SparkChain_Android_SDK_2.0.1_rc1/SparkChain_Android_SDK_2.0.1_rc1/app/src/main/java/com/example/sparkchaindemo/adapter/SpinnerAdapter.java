package com.example.sparkchaindemo.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;


import com.example.sparkchaindemo.R;

import java.util.List;

public class SpinnerAdapter extends BaseAdapter {

    public List<ParamInfo> paramInfos;

    public Context mContext;
    private OnSpinnerItemClickListener mListener;

    public SpinnerAdapter(Context context, List<ParamInfo> params) {
        this.paramInfos = params;
        mContext = context;
    }

    public void setListener(OnSpinnerItemClickListener listener) {
        this.mListener = listener;
    }

    @Override
    public int getCount() {
        return paramInfos.size();
    }

    @Override
    public Object getItem(int position) {
        return paramInfos.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (convertView == null){
            convertView = LayoutInflater.from(mContext).inflate(R.layout.adapter_item_drop,null);
        }
        TextView textView = convertView.findViewById(R.id.text);
        textView.setText(paramInfos.get(position).showName);
        return convertView;
    }

    public interface OnSpinnerItemClickListener{
        void onItemClicked(ParamInfo paramInfo);
    }
}
