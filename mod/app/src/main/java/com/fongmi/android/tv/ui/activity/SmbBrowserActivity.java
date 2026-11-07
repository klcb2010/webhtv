package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Build;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.smb.SmbClient;
import com.fongmi.android.tv.smb.SmbNetworkScanner;
import com.fongmi.android.tv.smb.SmbPlaybackProxy;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SmbBrowserActivity extends AppCompatActivity {

    private enum Mode { HOSTS, SHARES, FILES }

    private static final int REQUEST_LOCAL_NETWORK = 7301;
    private static final String LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<String> displayItems = new ArrayList<>();
    private final List<String> hosts = new ArrayList<>();
    private final List<String> shares = new ArrayList<>();
    private final List<SmbClient.Entry> files = new ArrayList<>();
    private final Map<String, String> savedHosts = new LinkedHashMap<>();

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
        loadSavedHosts();
        buildUi();
        scanNetwork();
    }

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(12));
        int surface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface, 0xFF10131B);
        int onSurface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, 0xFFFFFFFF);
        int onSurfaceVariant = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFFB8C4D8);
        root.setBackgroundColor(surface);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);

        MaterialButton back = themedButton("返回");
        back.setOnClickListener(v -> goBack());
        toolbar.addView(back, new LinearLayout.LayoutParams(-2, -2));

        title = new TextView(this);
        title.setText(getString(R.string.smb_browser_title));
        title.setTextColor(onSurface);
        title.setTextSize(21);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setPadding(dp(12), 0, dp(8), 0);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));

        MaterialButton scan = themedButton(getString(R.string.smb_scan));
        scan.setOnClickListener(v -> scanNetwork());
        toolbar.addView(scan, new LinearLayout.LayoutParams(-2, -2));
        root.addView(toolbar);

        status = new TextView(this);
        status.setTextColor(onSurfaceVariant);
        status.setTextSize(14);
        status.setPadding(dp(8), dp(12), dp(8), dp(12));
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));

        list = new ListView(this);
        list.setDividerHeight(dp(1));
        list.setDivider(new ColorDrawable(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant, 0x334F5B6B)));
        list.setBackgroundColor(surface);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, displayItems);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> onItemClick(position));
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            if (mode != Mode.HOSTS || position < 0 || position >= hosts.size()) return false;
            showHostActions(hosts.get(position));
            return true;
        });
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));

        setContentView(root);
    }

    private MaterialButton themedButton(String label) {
        int background = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimaryContainer, 0xFF394B63);
        int foreground = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnPrimaryContainer, 0xFFFFFFFF);
        MaterialButton button = new MaterialButton(this);
        button.setText(label);
        button.setTextColor(foreground);
        button.setBackgroundTintList(ColorStateList.valueOf(background));
        button.setCornerRadius(dp(10));
        button.setInsetTop(0);
        button.setInsetBottom(0);
        return button;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void loadSavedHosts() {
        savedHosts.clear();
        String json = getSharedPreferences("smb_hosts", MODE_PRIVATE).getString("saved", "{}");
        try {
            JSONObject object = new JSONObject(json);
            java.util.Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String host = keys.next();
                savedHosts.put(host, object.optString(host, ""));
            }
        } catch (Exception ignored) {
        }
    }

    private void persistSavedHosts() {
        JSONObject object = new JSONObject();
        try {
            for (Map.Entry<String, String> entry : savedHosts.entrySet()) {
                object.put(entry.getKey(), entry.getValue());
            }
            getSharedPreferences("smb_hosts", MODE_PRIVATE).edit().putString("saved", object.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private List<String> hostLabels() {
        List<String> labels = new ArrayList<>();
        for (String host : hosts) {
            String note = savedHosts.get(host);
            labels.add((note == null ? "" : "★ ") + host + (note == null || note.isEmpty() ? "" : "  ·  " + note));
        }
        return labels;
    }

    private void showHostActions(String value) {
        boolean saved = savedHosts.containsKey(value);
        String[] actions = saved ? new String[]{"修改备注", "取消保存"} : new String[]{"保存并备注"};
        new MaterialAlertDialogBuilder(this)
                .setTitle(value)
                .setItems(actions, (dialog, which) -> {
                    if (saved && which == 1) {
                        savedHosts.remove(value);
                        persistSavedHosts();
                        setItems(hostLabels());
                    } else {
                        showHostNoteDialog(value);
                    }
                })
                .show();
    }

    private void showHostNoteDialog(String value) {
        EditText note = field("备注（可留空）", InputType.TYPE_CLASS_TEXT);
        note.setText(savedHosts.getOrDefault(value, ""));
        new MaterialAlertDialogBuilder(this)
                .setTitle("保存 SMB 主机")
                .setView(note)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("保存", (dialog, which) -> {
                    savedHosts.put(value, note.getText().toString().trim());
                    persistSavedHosts();
                    setItems(hostLabels());
                })
                .show();
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
        if (Build.VERSION.SDK_INT >= 37
                && checkSelfPermission(LOCAL_NETWORK_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            setStatus(getString(R.string.smb_local_network_permission));
            requestPermissions(new String[]{LOCAL_NETWORK_PERMISSION}, REQUEST_LOCAL_NETWORK);
            return;
        }
        scanNetworkInternal();
    }

    private void scanNetworkInternal() {
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
                    for (String saved : savedHosts.keySet()) if (!hosts.contains(saved)) hosts.add(saved);
                    setItems(hostLabels());
                    setStatus(found.isEmpty() ? getString(R.string.smb_no_servers)
                            : getString(R.string.smb_server_count, found.size()));
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    hosts.clear();
                    hosts.addAll(savedHosts.keySet());
                    setItems(hostLabels());
                    setStatus(hosts.isEmpty() ? getString(R.string.smb_no_servers) : getString(R.string.smb_server_count, hosts.size()));
                });
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
        loginAttempted = false;
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
                                if (found.isEmpty()) {
                                    setStatus(getString(R.string.smb_no_shares));
                                    showManualShareDialog();
                                }
                            });
                        } catch (Exception e) {
                            runOnUiThread(() -> {
                                setStatus(getString(R.string.smb_connect_failed) + "：" + shortError(e));
                                showManualShareDialog();
                            });
                        }
                    });
                })
                .show();
    }

    private void showManualShareDialog() {
        EditText input = field(getString(R.string.smb_share_name), InputType.TYPE_CLASS_TEXT);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.smb_manual_share)
                .setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.smb_open_share, (dialog, which) -> {
                    String value = input.getText().toString().trim();
                    if (!value.isEmpty()) openShare(value);
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
            String name = remotePath.substring(remotePath.lastIndexOf((char) 92) + 1);
            VideoActivity.start(this, SiteApi.PUSH, url, name);
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
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_LOCAL_NETWORK) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                scanNetworkInternal();
            } else {
                setStatus(getString(R.string.smb_local_network_permission));
            }
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
