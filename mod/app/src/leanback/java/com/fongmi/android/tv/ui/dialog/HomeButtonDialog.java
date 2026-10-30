package com.fongmi.android.tv.ui.dialog;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.HomeButton;
import com.fongmi.android.tv.databinding.AdapterHomeButtonBinding;
import com.fongmi.android.tv.databinding.DialogHomeButtonBinding;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class HomeButtonDialog extends BaseAlertDialog {

    private DialogHomeButtonBinding binding;
    private Runnable callback;
    private ButtonAdapter adapter;

    public static void show(FragmentActivity activity, Runnable callback) {
        HomeButtonDialog dialog = new HomeButtonDialog();
        dialog.callback = callback;
        dialog.show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogHomeButtonBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        adapter = new ButtonAdapter();
        binding.recycler.setHasFixedSize(true);
        binding.recycler.setItemAnimator(null);
        binding.recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.recycler.setAdapter(adapter);
        updateSummary();
    }

    @Override
    protected void initEvent() {
        binding.reset.setOnClickListener(view -> {
            HomeButton.reset();
            adapter.reload();
            updateSummary();
            notifyChanged();
            binding.reset.post(binding.reset::requestFocus);
        });
    }

    private void updateSummary() {
        binding.summary.setText(getString(R.string.home_buttons_selected, HomeButton.getButtons().size(), HomeButton.all().size()));
    }

    private void notifyChanged() {
        if (callback != null) callback.run();
    }

    @Override
    public void onStart() {
        super.onStart();
        setWidth(0.55f);
        try {
            android.app.Dialog d = getDialog();
            if (d != null && d.getWindow() != null) {
                d.getWindow().setBackgroundDrawableResource(R.drawable.shape_home_button_dialog_bg);
            }
        } catch (Throwable ignored) {
        }
        binding.recycler.post(() -> {
            RecyclerView.ViewHolder holder = binding.recycler.findViewHolderForAdapterPosition(0);
            if (holder instanceof ButtonAdapter.ViewHolder) {
                ((ButtonAdapter.ViewHolder) holder).focusSelect();
            } else {
                binding.recycler.requestFocus();
            }
        });
    }

    private class ButtonAdapter extends RecyclerView.Adapter<ButtonAdapter.ViewHolder> {

        private List<HomeButton> items = HomeButton.sortedAll();

        void reload() {
            items = HomeButton.sortedAll();
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ViewHolder(AdapterHomeButtonBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            holder.bind(items.get(position));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        private void toggle(HomeButton item, int position) {
            if (HomeButton.isSetting(item)) return;
            Map<Integer, HomeButton> selected = HomeButton.getButtonsMap();
            if (selected.containsKey(item.getId())) selected.remove(item.getId());
            else selected.put(item.getId(), item);
            HomeButton.save(HomeButton.getMap(selectedInSortedOrder(selected)));
            notifyItemChanged(position);
            updateSummary();
            notifyChanged();
            binding.recycler.post(() -> {
                RecyclerView.ViewHolder holder = binding.recycler.findViewHolderForAdapterPosition(position);
                if (holder instanceof ViewHolder) ((ViewHolder) holder).focusSelect();
            });
        }

        private List<HomeButton> selectedInSortedOrder(Map<Integer, HomeButton> selected) {
            List<HomeButton> sorted = new ArrayList<>();
            for (HomeButton item : items) if (selected.containsKey(item.getId())) sorted.add(item);
            return sorted;
        }

        private void move(int from, int to) {
            if (from < 0 || to < 0 || from >= items.size() || to >= items.size()) return;
            Collections.swap(items, from, to);
            HomeButton.saveSorted(HomeButton.getMap(items));
            HomeButton.save(HomeButton.getMap(selectedInSortedOrder(HomeButton.getButtonsMap())));
            notifyItemMoved(from, to);
            notifyItemChanged(from);
            notifyItemChanged(to);
            binding.recycler.post(() -> {
                RecyclerView.ViewHolder holder = binding.recycler.findViewHolderForAdapterPosition(to);
                if (!(holder instanceof ViewHolder)) return;
                ViewHolder vh = (ViewHolder) holder;
                if (from > to) vh.binding.up.requestFocus();
                else vh.binding.down.requestFocus();
            });
            notifyChanged();
        }

        private class ViewHolder extends RecyclerView.ViewHolder {

            private final AdapterHomeButtonBinding binding;
            private HomeButton item;

            ViewHolder(@NonNull AdapterHomeButtonBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
                binding.select.setOnClickListener(view -> {
                    int pos = getBindingAdapterPosition();
                    if (pos != RecyclerView.NO_POSITION && item != null) toggle(item, pos);
                });
                binding.up.setOnClickListener(view -> move(getBindingAdapterPosition(), getBindingAdapterPosition() - 1));
                binding.down.setOnClickListener(view -> move(getBindingAdapterPosition(), getBindingAdapterPosition() + 1));
            }

            void bind(HomeButton item) {
                this.item = item;
                boolean selected = HomeButton.getButtonsMap().containsKey(item.getId());
                boolean locked = HomeButton.isSetting(item);
                binding.text.setText(item.getName());
                binding.checkMark.setText(selected ? "✓" : "");
                binding.select.setAlpha(locked ? 1f : (selected ? 1f : 0.65f));
                binding.up.setEnabled(getBindingAdapterPosition() > 0);
                binding.down.setEnabled(getBindingAdapterPosition() < items.size() - 1);
                binding.up.setVisibility(getBindingAdapterPosition() > 0 ? View.VISIBLE : View.INVISIBLE);
                binding.down.setVisibility(getBindingAdapterPosition() < items.size() - 1 ? View.VISIBLE : View.INVISIBLE);
            }

            void focusSelect() {
                binding.select.requestFocus();
            }
        }
    }
}
