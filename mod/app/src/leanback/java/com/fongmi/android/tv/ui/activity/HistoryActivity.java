package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.Product;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.databinding.ActivityHistoryBinding;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.ui.adapter.HistoryAdapter;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;
import com.fongmi.android.tv.utils.HistoryResume;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

/**
 * 电视端历史页。删除交互与手机端一致：
 * 1) 点「删除」→ 进入删除模式（封面显示删除图标）
 * 2) 点某一封面 → 删除单条
 * 3) 再点「删除」→ 确认后清空全部
 * 4) 返回键 → 退出删除模式
 */
public class HistoryActivity extends BaseActivity implements HistoryAdapter.OnClickListener {

    private ActivityHistoryBinding mBinding;
    private HistoryAdapter mAdapter;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, HistoryActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityHistoryBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        setRecyclerView();
        getHistory();
        if (mBinding.deleteButton != null) {
            mBinding.deleteButton.setOnClickListener(v -> onDelete());
            updateDeleteButtonText();
        }
        if (mBinding.reportButton != null) mBinding.reportButton.setVisibility(android.view.View.GONE);
    }

    private void onDelete() {
        if (mAdapter.getItemCount() == 0) return;
        if (!mAdapter.isDelete()) {
            mAdapter.setDelete(true);
            updateDeleteButtonText();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_delete_record)
                .setMessage(R.string.dialog_delete_history)
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> {
                    mAdapter.clear();
                    mBinding.progressLayout.showContent(true, 0);
                    updateDeleteButtonText();
                    RefreshEvent.history();
                })
                .show();
    }

    private void updateDeleteButtonText() {
        if (mBinding.deleteButton == null || mAdapter == null) return;
        mBinding.deleteButton.setText(mAdapter.isDelete()
                ? R.string.dialog_delete_record
                : R.string.setting_delete);
    }

    private void setRecyclerView() {
        mBinding.recycler.setHasFixedSize(true);
        mBinding.recycler.setItemAnimator(null);
        mBinding.recycler.setAdapter(mAdapter = new HistoryAdapter(this));
        mBinding.recycler.setLayoutManager(new GridLayoutManager(this, Product.getColumn()));
        mBinding.recycler.addItemDecoration(new SpaceItemDecoration(Product.getColumn(), 16));
    }

    private void getHistory() {
        mAdapter.setItems(History.get(), () -> {
            mBinding.progressLayout.showContent(true, mAdapter.getItemCount());
            updateDeleteButtonText();
        });
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        if (event.getType() == RefreshEvent.Type.HISTORY) getHistory();
    }

    @Override
    public void onItemClick(History item) {
        HistoryResume.open(this, item);
    }

    @Override
    public void onItemDelete(History item) {
        mAdapter.remove(item.deleteAndSync(), () -> {
            mBinding.progressLayout.showContent(true, mAdapter.getItemCount());
            if (mAdapter.getItemCount() == 0) {
                mAdapter.setDelete(false);
                updateDeleteButtonText();
            }
            RefreshEvent.history();
        });
    }

    @Override
    public boolean onLongClick() {
        mAdapter.setDelete(!mAdapter.isDelete());
        updateDeleteButtonText();
        return true;
    }

    @Override
    protected void onBackInvoked() {
        if (mAdapter != null && mAdapter.isDelete()) {
            mAdapter.setDelete(false);
            updateDeleteButtonText();
        } else {
            super.onBackInvoked();
        }
    }
}
