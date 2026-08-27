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

    private final OnClickListener mListener;
    private final List<History> mItems;
    private boolean delete;

    public interface OnClickListener {
        void onItemClick(History item);
        void onItemDelete(History item);
        boolean onLongClick();
    }

    public HistoryAdapter(OnClickListener listener) {
        this.mListener = listener;
        this.mItems = new ArrayList<>();
    }

    public boolean isDelete() {
        return delete;
    }

    public void setDelete(boolean delete) {
        this.delete = delete;
        notifyItemRangeChanged(0, mItems.size());
    }

    public void setItems(List<History> items, Runnable runnable) {
        mItems.clear();
        mItems.addAll(items);
        notifyDataSetChanged();
        runnable.run();
    }

    public History remove(History item, Runnable runnable) {
        int index = mItems.indexOf(item);
        if (index == -1) return item;
        mItems.remove(index);
        notifyItemRemoved(index);
        runnable.run();
        return item;
    }

    public void clear() {
        mItems.clear();
        notifyDataSetChanged();
        History.delete();
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
        
        // 关键：控制封面中间删除图标的显隐
        holder.binding.delete.setVisibility(delete ? View.VISIBLE : View.GONE);
        
        holder.itemView.setOnClickListener(v -> {
            if (delete) mListener.onItemDelete(item);
            else mListener.onItemClick(item);
        });
        
        holder.itemView.setOnLongClickListener(v -> mListener.onLongClick());
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        private final ItemHistoryBinding binding;

        ViewHolder(@NonNull ItemHistoryBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
