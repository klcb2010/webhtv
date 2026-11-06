package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.smb.SmbClient;
import com.fongmi.android.tv.smb.SmbNetworkScanner;
import com.fongmi.android.tv.smb.SmbPlaybackProxy;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SmbBrowserActivity extends AppCompatActivity {

    private enum Mode { HOSTS, SHARES, FILES }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<String> displayItems = new ArrayList<>();
    private final List<String> hosts = new ArrayList<>();
    private final List<String> shares = new ArrayList<>();
    private final List<SmbClient.Entry> files = new ArrayList<>();

    private LinearLayout root;
    private TextView title;
    private TextView status;
    private ListView list;
    private ArrayAdapter<String> adapter;

    private Mode mode = Mode.HOSTS;
    private String host;
    private String share;
    private String path = "";
    private String username = "";
    private String password = "";
    private String domain = "";
    private boolean loginAttempted;

    public static void start(Activity activity) {
        activity.startActivity(new android.content.Intent(activity, SmbBrowserActivity.class));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        scanNetwork();
    }

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(12));
        root.setBackgroundColor(0xFF10131B);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);

        Button back = new Button(this);
        back.setText("返回");
        back.setOnClickListener(v -> goBack());
        toolbar.addView(back, new LinearLayout.LayoutParams(-2, -2));

        title = new TextView(this);
        title.setText(getString(R.string.smb_browser_title));
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(21);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setPadding(dp(12), 0, dp(8), 0);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));

        Button scan = new Button(this);
        scan.setText(getString(R.string.smb_scan));
        scan.setOnClickListener(v -> scanNetwork());
        toolbar.addView(scan, new LinearLayout.LayoutParams(-2, -2));
        root.addView(toolbar);

        status = new TextView(this);
        status.setTextColor(0xFFB8C4D8);
        status.setTextSize(14);
        status.setPadding(dp(8), dp(12), dp(8), dp(12));
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));

        list = new ListView(this);
        list.setDividerHeight(dp(1));
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, displayItems);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> onItemClick(position));
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));

        setContentView(root);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void setStatus(String value) {
        status.setText(value == null ? "" : value);
    }

    private void setItems(List<String> values) {
        displayItems.clear();
        if (values != null) displayItems.addAll(values);
        adapter.notifyDataSetChanged();
    }

    private void scanNetwork() {
        mode = Mode.HOSTS;
        host = null;
        share = null;
        path = "";
        title.setText(getString(R.string.smb_browser_title));
        setItems(new ArrayList<>());
        setStatus(getString(R.string.smb_scanning));
        worker.execute(() -> {
            try {
                List<String> found = SmbNetworkScanner.scan();
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    hosts.clear();
                    hosts.addAll(found);
                    List<String> labels = new ArrayList<>(found);
                    setItems(labels);
                    setStatus(found.isEmpty() ? getString(R.string.smb_no_servers)
                            : getString(R.string.smb_server_count, found.size()));
                });
            } catch (Exception e) {
                runOnUiThread(() -> setStatus(getString(R.string.smb_no_servers)));
            }
        });
    }

    private void onItemClick(int position) {
        if (mode == Mode.HOSTS) {
            if (position < hosts.size()) connectHost(hosts.get(position), false);
            return;
        }
        if (mode == Mode.SHARES) {
            if (position < shares.size()) openShare(shares.get(position));
            return;
        }
        if (mode == Mode.FILES) {
            if (!path.isEmpty()) {
                if (position == 0) {
                    int cut = path.lastIndexOf('\\');
                    path = cut < 0 ? "" : path.substring(0, cut);
                    loadDirectory();
                    return;
                }
                position--;
            }
            if (position < 0 || position >= files.size()) return;
            SmbClient.Entry entry = files.get(position);
            String remotePath = path.isEmpty() ? entry.name : path + "\\" + entry.name;
            if (entry.directory) {
                path = remotePath;
                loadDirectory();
            } else if (isVideo(entry.name)) {
                play(remotePath);
            } else {
                Toast.makeText(this, R.string.smb_play_video_only, Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void connectHost(String value, boolean withCredentials) {
        host = value;
        setStatus("正在连接 " + value + " …");
        worker.execute(() -> {
            try {
                List<String> found = SmbClient.listShares(host, username, password, domain);
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    shares.clear();
                    shares.addAll(found);
                    mode = Mode.SHARES;
                    path = "";
                    title.setText(host);
                    setItems(found);
                    if (found.isEmpty()) {
                        setStatus(getString(R.string.smb_no_shares));
                        if (!loginAttempted) showAuthDialog();
                    } else {
                        setStatus("选择共享目录");
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    setStatus(getString(R.string.smb_connect_failed) + "：" + shortError(e));
                    if (!loginAttempted) showAuthDialog();
                    else Toast.makeText(this, R.string.smb_auth_failed, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void showAuthDialog() {
        loginAttempted = true;
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(22), dp(8), dp(22), 0);

        EditText user = field(getString(R.string.smb_username), InputType.TYPE_CLASS_TEXT);
        EditText pass = field(getString(R.string.smb_password),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText realm = field(getString(R.string.smb_domain), InputType.TYPE_CLASS_TEXT);
        user.setText(username);
        realm.setText(domain);
        form.addView(user);
        form.addView(pass);
        form.addView(realm);

        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.smb_auth_title) + " · " + host)
                .setView(form)
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> setStatus(getString(R.string.smb_auth_failed)))
                .setPositiveButton(R.string.smb_login, (dialog, which) -> {
                    username = user.getText().toString().trim();
                    password = pass.getText().toString();
                    domain = realm.getText().toString().trim();
                    worker.execute(() -> {
                        try {
                            List<String> found = SmbClient.listShares(host, username, password, domain);
                            runOnUiThread(() -> {
                                shares.clear();
                                shares.addAll(found);
                                mode = Mode.SHARES;
                                path = "";
                                title.setText(host);
                                setItems(found);
                                setStatus(found.isEmpty() ? getString(R.string.smb_no_shares) : "选择共享目录");
                                if (found.isEmpty()) Toast.makeText(this, R.string.smb_auth_failed, Toast.LENGTH_LONG).show();
                            });
                        } catch (Exception e) {
                            runOnUiThread(() -> {
                                setStatus(getString(R.string.smb_auth_failed) + "：" + shortError(e));
                                Toast.makeText(this, R.string.smb_auth_failed, Toast.LENGTH_LONG).show();
                            });
                        }
                    });
                })
                .show();
    }

    private EditText field(String hint, int inputType) {
        EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setHint(hint);
        edit.setInputType(inputType);
        return edit;
    }

    private void openShare(String value) {
        share = value;
        path = "";
        title.setText(host + " / " + share);
        loadDirectory();
    }

    private void loadDirectory() {
        mode = Mode.FILES;
        setStatus("正在读取目录…");
        worker.execute(() -> {
            try {
                List<SmbClient.Entry> found = SmbClient.listFiles(host, share, path, username, password, domain);
                runOnUiThread(() -> {
                    files.clear();
                    files.addAll(found);
                    List<String> labels = new ArrayList<>();
                    if (!path.isEmpty()) labels.add("..  " + getString(R.string.smb_parent));
                    for (SmbClient.Entry entry : found) {
                        labels.add((entry.directory ? "📁  " : "▶  ") + entry.name);
                    }
                    setItems(labels);
                    title.setText(host + " / " + share + (path.isEmpty() ? "" : " / " + path));
                    setStatus(found.isEmpty() ? getString(R.string.smb_no_files) : "选择视频文件播放");
                });
            } catch (Exception e) {
                runOnUiThread(() -> setStatus(getString(R.string.smb_connect_failed) + "：" + shortError(e)));
            }
        });
    }

    private void play(String remotePath) {
        try {
            String url = SmbPlaybackProxy.createUrl(host, share, remotePath, username, password, domain);
            VideoActivity.push(this, url);
            finish();
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.smb_connect_failed) + "：" + shortError(e), Toast.LENGTH_LONG).show();
        }
    }

    private boolean isVideo(String name) {
        String value = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
        return value.endsWith(".mp4") || value.endsWith(".mkv") || value.endsWith(".avi")
                || value.endsWith(".mov") || value.endsWith(".m4v") || value.endsWith(".ts")
                || value.endsWith(".m2ts") || value.endsWith(".wmv") || value.endsWith(".flv")
                || value.endsWith(".webm") || value.endsWith(".mpg") || value.endsWith(".mpeg")
                || value.endsWith(".vob");
    }

    private String shortError(Exception error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) return error.getClass().getSimpleName();
        return message.length() > 90 ? message.substring(0, 90) : message;
    }

    private void goBack() {
        if (mode == Mode.FILES) {
            if (!path.isEmpty()) {
                int cut = path.lastIndexOf('\\');
                path = cut < 0 ? "" : path.substring(0, cut);
                loadDirectory();
            } else {
                mode = Mode.SHARES;
                title.setText(host);
                setItems(shares);
                setStatus("选择共享目录");
            }
        } else if (mode == Mode.SHARES) {
            mode = Mode.HOSTS;
            title.setText(getString(R.string.smb_browser_title));
            setItems(hosts);
            setStatus(hosts.isEmpty() ? getString(R.string.smb_no_servers) : getString(R.string.smb_server_count, hosts.size()));
        } else {
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        if (mode == Mode.HOSTS) super.onBackPressed();
        else goBack();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
