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
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.json.JSONObject;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SmbBrowserActivity extends AppCompatActivity {

    private enum Mode { HOSTS, SHARES, FILES, HIDDEN_FOLDERS }

    private static final int REQUEST_LOCAL_NETWORK = 7301;
    private static final String LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<String> displayItems = new ArrayList<>();
    private final List<String> hosts = new ArrayList<>();
    private final List<String> shares = new ArrayList<>();
    private final List<SmbClient.Entry> files = new ArrayList<>();
    private final List<SmbClient.Entry> visibleFiles = new ArrayList<>();
    private final Map<String, String> savedHosts = new LinkedHashMap<>();
    private final Set<String> hiddenHosts = new LinkedHashSet<>();
    private final Set<String> hiddenFolders = new LinkedHashSet<>();

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
    private boolean showingHiddenHosts;
    private boolean showingHiddenFolders;

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
        toolbar.setFocusable(false);
        toolbar.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);

        title = new TextView(this);
        title.setText(getString(R.string.smb_browser_title));
        title.setTextColor(onSurface);
        title.setTextSize(21);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);

        MaterialButton back = themedButton("返回");
        back.setOnClickListener(v -> goBack());
        MaterialButton hidden = themedButton("显示隐藏IP");
        hidden.setOnClickListener(v -> showHiddenHosts());
        MaterialButton hiddenFoldersButton = themedButton("显示隐藏文件夹");
        hiddenFoldersButton.setOnClickListener(v -> showHiddenFolders());
        back.setId(View.generateViewId());
        hidden.setId(View.generateViewId());
        hiddenFoldersButton.setId(View.generateViewId());

        addToolbarButton(toolbar, back);
        addToolbarButton(toolbar, hidden);
        addToolbarButton(toolbar, hiddenFoldersButton);
        root.addView(toolbar);

        status = new TextView(this);
        status.setTextColor(onSurfaceVariant);
        status.setTextSize(14);
        status.setPadding(dp(8), dp(12), dp(8), dp(12));
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));

        list = new ListView(this);
        list.setId(View.generateViewId());
        list.setFocusable(true);
        list.setFocusableInTouchMode(true);
        list.setDividerHeight(dp(1));
        list.setDivider(new ColorDrawable(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant, 0x334F5B6B)));
        list.setBackgroundColor(surface);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, displayItems);
        list.setAdapter(adapter);
        configureToolbarFocus(back, hidden, hiddenFoldersButton);
        list.setOnKeyListener((view, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN
                    && keyCode == KeyEvent.KEYCODE_DPAD_UP
                    && list.getSelectedItemPosition() <= 0) {
                back.requestFocus();
                return true;
            }
            return false;
        });
        list.setOnItemClickListener((parent, view, position, id) -> onItemClick(position));
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            if (showingHiddenHosts) {
                if (position < 0 || position >= hiddenHosts.size()) return false;
                if (!isTvDevice()) restoreHiddenHost(new ArrayList<>(hiddenHosts).get(position));
                return true;
            }
            if (showingHiddenFolders) {
                if (position < 0 || position >= hiddenFolders.size()) return false;
                if (!isTvDevice()) restoreHiddenFolder(position);
                return true;
            }
            if (mode == Mode.SHARES && position >= 0 && position < shares.size()) {
                showShareActions(shares.get(position));
                return true;
            }
            if (mode == Mode.FILES) {
                int filePosition = position - (path.isEmpty() ? 0 : 1);
                if (filePosition >= 0 && filePosition < visibleFiles.size() && visibleFiles.get(filePosition).directory) {
                    String folderPath = path.isEmpty() ? visibleFiles.get(filePosition).name : path + "\\" + visibleFiles.get(filePosition).name;
                    showFolderActions(folderPath);
                    return true;
                }
            }
            if (mode != Mode.HOSTS || position < 0 || position >= hosts.size()) return false;
            showHostActions(hosts.get(position));
            return true;
        });
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));

        setContentView(root);
    }

    private void configureToolbarFocus(MaterialButton back, MaterialButton hidden, MaterialButton hiddenFoldersButton) {
        MaterialButton[] buttons = new MaterialButton[]{back, hidden, hiddenFoldersButton};
        for (int i = 0; i < buttons.length; i++) {
            MaterialButton button = buttons[i];
            button.setFocusable(true);
            button.setFocusableInTouchMode(true);
            button.setNextFocusDownId(list.getId());
            if (i > 0) button.setNextFocusLeftId(buttons[i - 1].getId());
            if (i < buttons.length - 1) button.setNextFocusRightId(buttons[i + 1].getId());
        }
        list.setNextFocusUpId(back.getId());
    }

    private void addToolbarButton(LinearLayout toolbar, MaterialButton button) {
        button.setFocusable(true);
        button.setFocusableInTouchMode(true);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setSingleLine(true);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(2), button.getPaddingTop(), dp(2), button.getPaddingBottom());
        button.setTextSize(18);
        button.setAutoSizeTextTypeUniformWithConfiguration(12, 18, 1, TypedValue.COMPLEX_UNIT_SP);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1f);
        params.leftMargin = dp(3);
        params.rightMargin = dp(3);
        toolbar.addView(button, params);
    }

    private MaterialButton themedButton(String label) {
        int background = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimaryContainer, 0xFF394B63);
        int foreground = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnPrimaryContainer, 0xFFFFFFFF);
        MaterialButton button = new MaterialButton(this);
        button.setText(label);
        button.setTextColor(foreground);
        int focusedBackground = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSecondaryContainer, 0xFF536A88);
        button.setBackgroundTintList(new ColorStateList(
                new int[][]{
                        new int[]{android.R.attr.state_focused},
                        new int[]{android.R.attr.state_pressed},
                        new int[]{}
                },
                new int[]{focusedBackground, focusedBackground, background}
        ));
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
        hiddenHosts.clear();
        hiddenHosts.addAll(getSharedPreferences("smb_hosts", MODE_PRIVATE).getStringSet("hidden", new HashSet<>()));
        hiddenFolders.clear();
        hiddenFolders.addAll(getSharedPreferences("smb_hosts", MODE_PRIVATE).getStringSet("hidden_folders", new HashSet<>()));
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

    private void persistHiddenHosts() {
        getSharedPreferences("smb_hosts", MODE_PRIVATE).edit()
                .putStringSet("hidden", new HashSet<>(hiddenHosts)).apply();
    }

    private void persistHiddenFolders() {
        getSharedPreferences("smb_hosts", MODE_PRIVATE).edit()
                .putStringSet("hidden_folders", new HashSet<>(hiddenFolders)).apply();
    }

    private String folderKey(String folderPath) {
        return host + "\n" + share + "\n" + folderPath;
    }

    private String shareKey(String shareName) {
        return host + "\n" + shareName + "\n";
    }

    private void showHiddenFolders() {
        showingHiddenHosts = false;
        showingHiddenFolders = true;
        mode = Mode.HIDDEN_FOLDERS;
        title.setText("已隐藏的 SMB 文件夹");
        List<String> labels = new ArrayList<>();
        for (String value : hiddenFolders) {
            String[] parts = value.split("\\n", 3);
            if (parts.length == 3) {
                labels.add(parts[2].isEmpty() ? parts[0] + " / " + parts[1]
                        : parts[0] + " / " + parts[1] + " / " + parts[2]);
            } else labels.add(value);
        }
        setItems(labels);
        setStatus(hiddenFolders.isEmpty() ? "没有隐藏的文件夹"
                : (isTvDevice() ? "选择文件夹即可恢复显示" : "长按文件夹可恢复显示"));
    }

    private void showFolderActions(String folderPath) {
        if (folderPath == null || folderPath.isEmpty()) return;
        new MaterialAlertDialogBuilder(this)
                .setTitle(folderPath)
                .setItems(new String[]{"隐藏此文件夹"}, (dialog, which) -> hideFolder(folderPath))
                .show();
    }

    private void showShareActions(String shareName) {
        if (shareName == null || shareName.isEmpty()) return;
        new MaterialAlertDialogBuilder(this)
                .setTitle(shareName)
                .setItems(new String[]{"隐藏此路径"}, (dialog, which) -> hideShare(shareName))
                .show();
    }

    private void hideShare(String shareName) {
        if (host == null || shareName == null || shareName.isEmpty()) return;
        hiddenFolders.add(shareKey(shareName));
        persistHiddenFolders();
        shares.remove(shareName);
        setItems(shares);
        setStatus("已隐藏路径：" + host + " / " + shareName);
    }

    private void hideFolder(String folderPath) {
        if (host == null || share == null || folderPath == null || folderPath.isEmpty()) return;
        hiddenFolders.add(folderKey(folderPath));
        persistHiddenFolders();
        loadDirectory();
        Toast.makeText(this, "已隐藏文件夹：" + folderPath, Toast.LENGTH_SHORT).show();
    }

    private void restoreHiddenFolder(int position) {
        if (position < 0 || position >= hiddenFolders.size()) return;
        String value = new ArrayList<>(hiddenFolders).get(position);
        hiddenFolders.remove(value);
        persistHiddenFolders();
        showHiddenFolders();
        Toast.makeText(this, "已恢复显示文件夹", Toast.LENGTH_SHORT).show();
    }

    private boolean isTvDevice() {
        return getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK);
    }

    private void showHiddenHosts() {
        showingHiddenHosts = true;
        title.setText("已隐藏的 SMB 地址");
        setItems(new ArrayList<>(hiddenHosts));
        setStatus(hiddenHosts.isEmpty() ? "没有隐藏的地址"
                : (isTvDevice() ? "选择地址即可恢复显示" : "长按地址可恢复显示"));
    }

    private void restoreHiddenHost(String value) {
        if (value == null || !hiddenHosts.remove(value)) return;
        persistHiddenHosts();
        if (!hosts.contains(value)) hosts.add(value);
        if (showingHiddenHosts) showHiddenHosts();
        else setItems(hostLabels());
        Toast.makeText(this, "已恢复显示：" + value, Toast.LENGTH_SHORT).show();
    }

    private void hideHost(String value) {
        if (value == null || value.isEmpty()) return;
        hiddenHosts.add(value);
        persistHiddenHosts();
        hosts.remove(value);
        setItems(hostLabels());
        setStatus("已隐藏地址：" + value);
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
        String[] actions = saved
                ? new String[]{"修改备注", "取消保存", "隐藏此地址"}
                : new String[]{"保存并备注", "隐藏此地址"};
        new MaterialAlertDialogBuilder(this)
                .setTitle(value)
                .setItems(actions, (dialog, which) -> {
                    if ((!saved && which == 0) || (saved && which == 0)) {
                        showHostNoteDialog(value);
                    } else if (saved && which == 1) {
                        savedHosts.remove(value);
                        persistSavedHosts();
                        setItems(hostLabels());
                    } else {
                        hideHost(value);
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
        showingHiddenHosts = false;
        showingHiddenFolders = false;
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
                    for (String value : found) if (!hiddenHosts.contains(value)) hosts.add(value);
                    for (String saved : savedHosts.keySet()) {
                        if (!hiddenHosts.contains(saved) && !hosts.contains(saved)) hosts.add(saved);
                    }
                    setItems(hostLabels());
                    setStatus(hosts.isEmpty() ? getString(R.string.smb_no_servers)
                            : getString(R.string.smb_server_count, hosts.size()));
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    hosts.clear();
                    for (String saved : savedHosts.keySet()) if (!hiddenHosts.contains(saved)) hosts.add(saved);
                    setItems(hostLabels());
                    setStatus(hosts.isEmpty() ? getString(R.string.smb_no_servers) : getString(R.string.smb_server_count, hosts.size()));
                });
            }
        });
    }

    private void onItemClick(int position) {
        if (showingHiddenHosts) {
            if (isTvDevice() && position >= 0 && position < hiddenHosts.size()) {
                restoreHiddenHost(new ArrayList<>(hiddenHosts).get(position));
            }
            return;
        }
        if (showingHiddenFolders) {
            if (isTvDevice()) restoreHiddenFolder(position);
            return;
        }
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
            if (position < 0 || position >= visibleFiles.size()) return;
            SmbClient.Entry entry = visibleFiles.get(position);
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
                    for (String shareName : found) {
                        if (!hiddenFolders.contains(shareKey(shareName))) shares.add(shareName);
                    }
                    mode = Mode.SHARES;
                    path = "";
                    title.setText(host);
                    setItems(shares);
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
                                for (String shareName : found) {
                                    if (!hiddenFolders.contains(shareKey(shareName))) shares.add(shareName);
                                }
                                mode = Mode.SHARES;
                                path = "";
                                title.setText(host);
                                setItems(shares);
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
                    visibleFiles.clear();
                    List<String> labels = new ArrayList<>();
                    if (!path.isEmpty()) labels.add("..  " + getString(R.string.smb_parent));
                    for (SmbClient.Entry entry : found) {
                        String remotePath = path.isEmpty() ? entry.name : path + "\\" + entry.name;
                        if (entry.directory && hiddenFolders.contains(folderKey(remotePath))) continue;
                        visibleFiles.add(entry);
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
            VideoActivity.startSmb(this, SiteApi.PUSH, url, name);
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
        if (showingHiddenHosts || showingHiddenFolders) {
            showingHiddenHosts = false;
            showingHiddenFolders = false;
            mode = Mode.HOSTS;
            title.setText(getString(R.string.smb_browser_title));
            setItems(hostLabels());
            setStatus(hosts.isEmpty() ? getString(R.string.smb_no_servers)
                    : getString(R.string.smb_server_count, hosts.size()));
        } else if (mode == Mode.HIDDEN_FOLDERS) {
            showingHiddenFolders = false;
            mode = Mode.HOSTS;
            title.setText(getString(R.string.smb_browser_title));
            setItems(hostLabels());
            setStatus(hosts.isEmpty() ? getString(R.string.smb_no_servers) : getString(R.string.smb_server_count, hosts.size()));
        } else if (mode == Mode.FILES) {
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
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (isTvDevice() && (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_BUTTON_MODE)) {
            int position = list == null ? ListView.INVALID_POSITION : list.getSelectedItemPosition();
            if (mode == Mode.SHARES && position >= 0 && position < shares.size()) {
                showShareActions(shares.get(position));
                return true;
            }
            if (mode == Mode.FILES) {
                int filePosition = position - (path.isEmpty() ? 0 : 1);
                if (filePosition >= 0 && filePosition < visibleFiles.size()
                        && visibleFiles.get(filePosition).directory) {
                    String folderPath = path.isEmpty() ? visibleFiles.get(filePosition).name
                            : path + "\\" + visibleFiles.get(filePosition).name;
                    showFolderActions(folderPath);
                    return true;
                }
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        if (mode == Mode.HOSTS && !showingHiddenHosts) super.onBackPressed();
        else goBack();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
