package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.databinding.ItemHistoryBinding;
import com.fongmi.android.tv.utils.ImgUtil;

import java.util.ArrayList;
import java.util.List;

public class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.ViewHolder> {

    private final OnItemClickListener mListener;
    private final List<History> mItems;
    private boolean delete; // 是否处于删除模式

    public interface OnItemClickListener {
        void onItemClick(History item);
        void onItemDelete(History item);
    }

    public HistoryAdapter(OnItemClickListener listener) {
        this.mListener = listener;
        this.mItems = new ArrayList<>();
    }

    public void setDelete(boolean delete) {
        this.delete = delete;
        notifyDataSetChanged();
    }

    public boolean isDelete() {
        return delete;
    }

    public void addAll(List<History> items) {
        mItems.clear();
        mItems.addAll(items);
        notifyDataSetChanged();
    }

    public void remove(History item) {
        int index = mItems.indexOf(item);
        if (index == -1) return;
        mItems.remove(index);
        notifyItemRemoved(index);
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(ItemHistoryBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        History item = mItems.get(position);
        holder.binding.name.setText(item.getVodName());
        holder.binding.remark.setText(item.getVodRemarks());
        ImgUtil.load(item.getVodPic(), holder.binding.image);
        
        // 核心：根据模式显示/隐藏删除图标
        holder.binding.delete.setVisibility(delete ? View.VISIBLE : View.GONE);
        
        holder.itemView.setOnClickListener(v -> {
            if (delete) {
                mListener.onItemDelete(item);
            } else {
                mListener.onItemClick(item);
            }
        });
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        private final ItemHistoryBinding binding;

        ViewHolder(@NonNull ItemHistoryBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
