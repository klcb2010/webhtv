package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.app.Dialog;
import android.content.pm.PackageManager;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.graphics.Typeface;
import android.graphics.Color;
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
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.smb.SmbClient;
import com.fongmi.android.tv.smb.SmbNetworkScanner;
import com.fongmi.android.tv.smb.SmbPlaybackProxy;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
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

    private enum Mode { HOSTS, SHARES, FILES, HIDDEN_FOLDERS, FAVORITES }

    private static final int REQUEST_LOCAL_NETWORK = 7301;
    private static final int REQUEST_INSTALL_APK_PERMISSION = 7302;
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
    private final Set<String> favorites = new LinkedHashSet<>();
    private final List<String> hiddenEntries = new ArrayList<>();

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
    private boolean showingFavorites;
    private boolean installingApk;
    private File pendingApkFile;

    public static void start(Activity activity) {
        activity.startActivity(new android.content.Intent(activity, SmbBrowserActivity.class));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        loadSavedHosts();
        buildUi();
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (mode == Mode.HOSTS && !showingHiddenHosts && !showingHiddenFolders && !showingFavorites) finish();
                else goBack();
            }
        });
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

        MaterialButton back = themedButton("关闭");
        back.setOnClickListener(v -> finish());
        MaterialButton favoritesButton = themedButton("收藏夹");
        favoritesButton.setOnClickListener(v -> showFavorites());
        MaterialButton hiddenButton = themedButton("显示隐藏");
        hiddenButton.setOnClickListener(v -> showHiddenItems());
        back.setId(View.generateViewId());
        favoritesButton.setId(View.generateViewId());
        hiddenButton.setId(View.generateViewId());
        addToolbarButton(toolbar, back);
        addToolbarButton(toolbar, favoritesButton);
        addToolbarButton(toolbar, hiddenButton);
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
        configureToolbarFocus(back, favoritesButton, hiddenButton);
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
            if (showingHiddenHosts || showingHiddenFolders) {
                if (!isTvDevice()) activateHiddenItem(position);
                return true;
            }
            if (showingFavorites) {
                if (!isTvDevice()) removeFavoriteAt(position);
                return true;
            }
            if (mode == Mode.SHARES && position >= 0 && position < shares.size()) {
                showShareActions(shares.get(position));
                return true;
            }
            if (mode == Mode.FILES) {
                int filePosition = position;
                if(filePosition>=0&&filePosition<visibleFiles.size()) {
                    SmbClient.Entry entry=visibleFiles.get(filePosition);
                    String remote=path.isEmpty()?entry.name:path+"\\"+entry.name;
                    showFileActions(remote,entry.directory); return true;
                }
            }
            if (mode != Mode.HOSTS || position < 0 || position >= hosts.size()) return false;
            showHostActions(hosts.get(position));
            return true;
        });
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));

        setContentView(root);
    }

    private void configureToolbarFocus(MaterialButton back, MaterialButton favorites, MaterialButton hidden) {
        MaterialButton[] buttons = new MaterialButton[]{back, favorites, hidden};
        for (int i = 0; i < buttons.length; i++) {
            MaterialButton button = buttons[i];
            button.setFocusable(true);
            button.setFocusableInTouchMode(false);
            button.setNextFocusDownId(list.getId());
            if (i > 0) button.setNextFocusLeftId(buttons[i - 1].getId());
            if (i < buttons.length - 1) button.setNextFocusRightId(buttons[i + 1].getId());
        }
        list.setNextFocusUpId(back.getId());
    }

    private void addToolbarButton(LinearLayout toolbar, MaterialButton button) {
        button.setFocusable(true);
        button.setFocusableInTouchMode(false);
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
        android.content.SharedPreferences preferences = getSharedPreferences("smb_hosts", MODE_PRIVATE);
        loadStringSet(preferences, "hidden", hiddenHosts);
        loadStringSet(preferences, "hidden_folders", hiddenFolders);
        loadStringSet(preferences, "favorites", favorites);
        String json = preferences.getString("saved", "{}");
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

    private void loadStringSet(android.content.SharedPreferences preferences, String key, Set<String> target) {
        try {
            Object stored = preferences.getAll().get(key);
            if (!(stored instanceof Set<?>)) return;
            Set<String> snapshot = new LinkedHashSet<>();
            for (Object value : (Set<?>) stored) {
                if (value instanceof String) snapshot.add((String) value);
            }
            target.clear();
            target.addAll(snapshot);
        } catch (RuntimeException error) {
            if (logActive()) android.util.Log.d("WebHTV-SMB", "load key=" + key + " failed: " + error.getClass().getSimpleName());
        }
    }

    private void persistStringSet(String key, Set<String> values) {
        boolean saved = getSharedPreferences("smb_hosts", MODE_PRIVATE).edit()
                .putStringSet(key, new HashSet<>(values)).commit();
        if (!saved) Toast.makeText(this, "隐藏记录保存失败，请重试", Toast.LENGTH_LONG).show();
    }

    private static boolean logActive() { return false; }

    private void persistSavedHosts() {
        JSONObject object = new JSONObject();
        try {
            for (Map.Entry<String, String> entry : savedHosts.entrySet()) {
                object.put(entry.getKey(), entry.getValue());
            }
            getSharedPreferences("smb_hosts", MODE_PRIVATE).edit().putString("saved", object.toString()).commit();
        } catch (Exception ignored) {
        }
    }

    private void persistHiddenHosts() {
        persistStringSet("hidden", hiddenHosts);
    }

    private void persistHiddenFolders() {
        persistStringSet("hidden_folders", hiddenFolders);
    }

    private String folderKey(String folderPath) {
        return host + "\n" + share + "\n" + folderPath;
    }

    private String shareKey(String shareName) {
        return host + "\n" + shareName + "\n";
    }

    private boolean isHiddenHost(String value) {
        String expected = normalizeSmbPath(value);
        for (String item : hiddenHosts) if (normalizeSmbPath(item).equals(expected)) return true;
        return false;
    }

    private boolean isHiddenShare(String shareName) {
        if (host == null || shareName == null) return false;
        String expectedHost = normalizeSmbPath(host);
        String expectedShare = normalizeSmbPath(shareName);
        for (String value : hiddenFolders) {
            String[] parts = value.split("\\n", 3);
            if (parts.length == 3 && normalizeSmbPath(parts[2]).isEmpty()
                    && normalizeSmbPath(parts[0]).equals(expectedHost)
                    && normalizeSmbPath(parts[1]).equals(expectedShare)) return true;
        }
        return false;
    }

    private boolean isHiddenFolder(String folderPath) {
        if (host == null || share == null || folderPath == null) return false;
        String expectedHost = normalizeSmbPath(host);
        String expectedShare = normalizeSmbPath(share);
        String expectedPath = normalizeSmbPath(folderPath);
        for (String value : hiddenFolders) {
            String[] parts = value.split("\\n", 3);
            if (parts.length == 3 && !normalizeSmbPath(parts[2]).isEmpty()
                    && normalizeSmbPath(parts[0]).equals(expectedHost)
                    && normalizeSmbPath(parts[1]).equals(expectedShare)
                    && normalizeSmbPath(parts[2]).equals(expectedPath)) return true;
        }
        return false;
    }

    private String normalizeSmbPath(String value) {
        if (value == null) return "";
        String normalized = value.trim().replace('/', '\\');
        while (normalized.endsWith("\\") && !normalized.isEmpty()) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.toLowerCase(java.util.Locale.ROOT);
    }

    private void showHiddenItems() {
        showingHiddenHosts=true; showingHiddenFolders=true; showingFavorites=false;
        mode=Mode.HIDDEN_FOLDERS; title.setText("显示隐藏"); hiddenEntries.clear();
        List<String> ips=new ArrayList<>(hiddenHosts); ips.sort(String.CASE_INSENSITIVE_ORDER);
        for(String value:ips) hiddenEntries.add("IP|"+value);
        List<String> paths=new ArrayList<>(hiddenFolders);
        paths.sort((x,y)->hiddenLabel(x).compareToIgnoreCase(hiddenLabel(y)));
        for(String value:paths) hiddenEntries.add("PATH|"+value);
        List<String> labels=new ArrayList<>();
        for(String entry:hiddenEntries) {
            String[] pair=entry.split("\\|",2); String value=pair[1];
            if(pair[0].equals("IP")) labels.add(value);
            else { String[] p=value.split("\\n",3);
                labels.add(p.length==3?p[0]+" / "+p[1]+(p[2].isEmpty()?"":" / "+p[2]):value); }
        }
        setItems(labels);
        setStatus(hiddenEntries.isEmpty()?"没有隐藏项目":(isTvDevice()?"短按恢复显示":"长按恢复显示"));
    }
    private String hiddenLabel(String value) {
        String[] p=value.split("\\n",3);
        return p.length==3?p[0]+" / "+p[1]+" / "+p[2]:value;
    }
    private void activateHiddenItem(int position) {
        if(position<0||position>=hiddenEntries.size()) return;
        String[] p=hiddenEntries.get(position).split("\\|",2);
        if(p[0].equals("IP")) restoreHiddenHost(p[1]); else restoreHiddenFolderValue(p[1]);
    }
    private void restoreHiddenFolderValue(String value) {
        if(value==null||!hiddenFolders.remove(value)) return;
        persistHiddenFolders(); showHiddenItems();
        Toast.makeText(this,"已恢复显示路径",Toast.LENGTH_SHORT).show();
    }

    private String favoriteKey(String remote, boolean directory) {
        return host+"\n"+share+"\n"+remote+"\n"+(directory?"D":"F");
    }
    private void addFavorite(String remote, boolean directory) {
        if(remote==null||remote.isEmpty()) return;
        favorites.add(favoriteKey(remote,directory)); persistStringSet("favorites",favorites);
        Toast.makeText(this,"已加入收藏夹",Toast.LENGTH_SHORT).show();
    }
    private void showFolderActions(String folderPath) { showFileActions(folderPath,true); }
    private void showFileActions(String remote, boolean directory) {
        String key=favoriteKey(remote,directory); boolean saved=favorites.contains(key);
        String[] actions=directory?new String[]{"隐藏此文件夹",saved?"取消收藏":"加入收藏夹"}
                :new String[]{saved?"取消收藏":"加入收藏夹"};
        new MaterialAlertDialogBuilder(this).setTitle(remote).setItems(actions,(d,w)->{
            if(directory&&w==0) hideFolder(remote);
            else { if(saved) favorites.remove(key); else favorites.add(key);
                persistStringSet("favorites",favorites); }
        }).show();
    }
    private void showFavorites() {
        showingFavorites=true; showingHiddenHosts=false; showingHiddenFolders=false;
        mode=Mode.FAVORITES; title.setText("收藏夹");
        List<String> labels=new ArrayList<>();
        for(String value:favorites) { String[] p=value.split("\\n",4);
            if(p.length==4) labels.add((p[3].equals("D")?"📁  ":"★  ")+p[0]+" / "+p[1]+" / "+p[2]); }
        setItems(labels);
        setStatus(favorites.isEmpty()?"收藏夹为空":(isTvDevice()?"短按打开，菜单键取消收藏":"短按打开，长按取消收藏"));
    }
    private void openFavorite(int position) {
        List<String> values=new ArrayList<>(favorites);
        if(position<0||position>=values.size()) return;
        String[] p=values.get(position).split("\\n",4); if(p.length!=4)return;
        host=p[0]; share=p[1]; String remote=p[2]; boolean dir=p[3].equals("D");
        if(dir) { path=remote; if(path.isEmpty()) openShare(share); else loadDirectory(); return; }
        int cut=remote.lastIndexOf((char)92); String name=cut<0?remote:remote.substring(cut+1);
        path=cut<0?"":remote.substring(0,cut);
        if(SmbClient.isVideo(name)||SmbClient.isAudio(name)) play(remote);
        else if(SmbClient.isImage(name)) showImage(remote);
        else if(SmbClient.isApk(name)) installApk(remote);
    }

    private void showShareActions(String shareName) {
        if (shareName == null || shareName.isEmpty()) return;
        new MaterialAlertDialogBuilder(this).setTitle(shareName)
                .setItems(new String[]{"隐藏此路径"},(d,w)->hideShare(shareName)).show();
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

    private void removeFavoriteAt(int position) {
        List<String> values=new ArrayList<>(favorites);
        if(position<0||position>=values.size()) return;
        favorites.remove(values.get(position)); persistStringSet("favorites",favorites); showFavorites();
        Toast.makeText(this,"已取消收藏",Toast.LENGTH_SHORT).show();
    }

    private boolean isTvDevice() {
        return getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK);
    }

    private void restoreHiddenHost(String value) {
        if (value == null) return;
        String stored = null;
        for (String item : hiddenHosts) if (normalizeSmbPath(item).equals(normalizeSmbPath(value))) { stored = item; break; }
        if (stored == null || !hiddenHosts.remove(stored)) return;
        persistHiddenHosts();
        refreshHostsQuietly();
        showHiddenItems();
        Toast.makeText(this, "已恢复显示：" + value, Toast.LENGTH_SHORT).show();
    }

    private void refreshHostsQuietly() {
        worker.execute(() -> {
            try {
                List<String> found = SmbNetworkScanner.scan();
                runOnUiThread(() -> {
                    discoveredHosts.clear();
                    discoveredHosts.addAll(found);
                    hosts.clear();
                    for (String item : found) if (!isHiddenHost(item)) hosts.add(item);
                    for (String saved : savedHosts.keySet())
                        if (!isHiddenHost(saved) && !hosts.contains(saved)) hosts.add(saved);
                    if (mode == Mode.HOSTS) setItems(hostLabels());
                });
            } catch (Exception ignored) { }
        });
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
                    for (String value : found) if (!isHiddenHost(value)) hosts.add(value);
                    for (String saved : savedHosts.keySet()) {
                        if (!isHiddenHost(saved) && !hosts.contains(saved)) hosts.add(saved);
                    }
                    setItems(hostLabels());
                    setStatus(hosts.isEmpty() ? getString(R.string.smb_no_servers)
                            : getString(R.string.smb_server_count, hosts.size()));
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    hosts.clear();
                    for (String saved : savedHosts.keySet()) if (!isHiddenHost(saved)) hosts.add(saved);
                    setItems(hostLabels());
                    setStatus(hosts.isEmpty() ? getString(R.string.smb_no_servers) : getString(R.string.smb_server_count, hosts.size()));
                });
            }
        });
    }

    private void onItemClick(int position) {
        if(showingFavorites) { openFavorite(position); return; }
        if(showingHiddenHosts||showingHiddenFolders) { if(isTvDevice()) activateHiddenItem(position); return; }
        if (mode == Mode.HOSTS) {
            if (position < hosts.size()) connectHost(hosts.get(position), false);
            return;
        }
        if (mode == Mode.SHARES) {
            if (position < shares.size()) openShare(shares.get(position));
            return;
        }
        if (mode == Mode.FILES) {
            if (position < 0 || position >= visibleFiles.size()) return;
            SmbClient.Entry entry = visibleFiles.get(position);
            String remotePath = path.isEmpty() ? entry.name : path + "\\" + entry.name;
            if (entry.directory) {
                path = remotePath;
                loadDirectory();
            } else if (SmbClient.isVideo(entry.name) || SmbClient.isAudio(entry.name)) {
                play(remotePath);
            } else if (SmbClient.isImage(entry.name)) {
                showImage(remotePath);
            } else if (SmbClient.isApk(entry.name)) {
                installApk(remotePath);
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
                        if (!isHiddenShare(shareName)) shares.add(shareName);
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
                                    if (!isHiddenShare(shareName)) shares.add(shareName);
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
                    for (SmbClient.Entry entry : found) {
                        String remotePath = path.isEmpty() ? entry.name : path + "\\" + entry.name;
                        if (entry.directory && isHiddenFolder(remotePath)) continue;
                        visibleFiles.add(entry);
                        String icon = entry.directory ? "📁  " : SmbClient.isApk(entry.name) ? "📦  "
                                : SmbClient.isImage(entry.name) ? "🖼️  " : SmbClient.isAudio(entry.name) ? "♫  "
                                : SmbClient.isVideo(entry.name) ? "▶  " : "📄  ";
                        labels.add(icon + entry.name);
                    }
                    setItems(labels);
                    title.setText(host + " / " + share + (path.isEmpty() ? "" : " / " + path));
                    setStatus(found.isEmpty() ? getString(R.string.smb_no_files) : "视频/音乐可播放，图片可查看，APK 可安装");
                });
            } catch (Exception e) {
                runOnUiThread(() -> setStatus(getString(R.string.smb_connect_failed) + "：" + shortError(e)));
            }
        });
    }

    private void installApk(String remotePath) {
        if (installingApk) return;
        installingApk = true;
        setStatus("正在下载 APK 安装包");

        final String targetHost = host;
        final String targetShare = share;
        final String targetUsername = username;
        final String targetPassword = password;
        final String targetDomain = domain;

        worker.execute(() -> {
            File apkFile = null;
            try {
                File directory = new File(getCacheDir(), "smb-apk");
                if (!directory.exists() && !directory.mkdirs()) {
                    throw new IOException("无法创建 APK 缓存目录");
                }

                String fileName = remotePath.substring(remotePath.lastIndexOf((char) 92) + 1)
                        .replaceAll("[^A-Za-z0-9._-]", "_");
                if (fileName.isEmpty() || !fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".apk")) {
                    throw new IOException("无效的 APK 文件名");
                }

                apkFile = new File(directory, fileName);
                if (apkFile.exists() && !apkFile.delete()) {
                    throw new IOException("无法覆盖缓存中的同名 APK");
                }

                try (SmbClient.RemoteFile remote = SmbClient.openFile(
                        targetHost, targetShare, remotePath,
                        targetUsername, targetPassword, targetDomain);
                     FileOutputStream output = new FileOutputStream(apkFile)) {
                    long size = remote.length();
                    if (size <= 0) throw new IOException("APK 文件为空");

                    byte[] buffer = new byte[64 * 1024];
                    long offset = 0;
                    while (offset < size) {
                        int count = remote.read(buffer, offset, 0,
                                (int) Math.min((long) buffer.length, size - offset));
                        if (count <= 0) throw new IOException("APK 下载不完整");
                        output.write(buffer, 0, count);
                        offset += count;
                    }
                    output.flush();
                }

                File readyFile = apkFile;
                runOnUiThread(() -> {
                    installingApk = false;
                    if (isFinishing()) return;
                    setStatus("APK 下载完成，正在打开安装程序");
                    launchApkInstaller(readyFile);
                });
            } catch (Exception e) {
                if (apkFile != null && apkFile.exists()) apkFile.delete();
                runOnUiThread(() -> {
                    installingApk = false;
                    if (isFinishing()) return;
                    setStatus("APK 下载失败：" + shortError(e));
                    Toast.makeText(this, "APK 下载失败：" + shortError(e), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void launchApkInstaller(File apkFile) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !getPackageManager().canRequestPackageInstalls()) {
            pendingApkFile = apkFile;
            new MaterialAlertDialogBuilder(this)
                    .setTitle("允许安装应用")
                    .setMessage("需要允许 WebHTV 安装未知来源应用，授权后会继续打开安装程序。")
                    .setNegativeButton(android.R.string.cancel, (dialog, which) -> pendingApkFile = null)
                    .setPositiveButton("前往设置", (dialog, which) -> {
                        try {
                            Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + getPackageName()));
                            startActivityForResult(intent, REQUEST_INSTALL_APK_PERMISSION);
                        } catch (Exception e) {
                            pendingApkFile = null;
                            Toast.makeText(this, "无法打开安装权限设置", Toast.LENGTH_LONG).show();
                        }
                    })
                    .show();
            return;
        }
        openApkInstaller(apkFile);
    }

    private void openApkInstaller(File apkFile) {
        try {
            Uri uri = FileProvider.getUriForFile(
                    this, getPackageName() + ".provider", apkFile);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开 APK 安装程序：" + shortError(e), Toast.LENGTH_LONG).show();
        }
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

    private void showImage(String remotePath) {
        try {
            String url = SmbPlaybackProxy.createUrl(host, share, remotePath, username, password, domain);
            String name = remotePath.substring(remotePath.lastIndexOf((char) 92) + 1);

            LinearLayout content = new LinearLayout(this);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setBackgroundColor(Color.BLACK);

            TextView imageTitle = new TextView(this);
            imageTitle.setText(name);
            imageTitle.setTextColor(MaterialColors.getColor(this,
                    com.google.android.material.R.attr.colorOnPrimaryContainer, Color.WHITE));
            imageTitle.setTextSize(18);
            imageTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            imageTitle.setGravity(Gravity.CENTER_VERTICAL);
            imageTitle.setPadding(dp(20), dp(12), dp(20), dp(12));
            imageTitle.setBackgroundColor(MaterialColors.getColor(this,
                    com.google.android.material.R.attr.colorPrimaryContainer, 0xFF394B63));
            content.addView(imageTitle, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

            FrameLayout imageContainer = new FrameLayout(this);
            imageContainer.setClipChildren(false);
            imageContainer.setClipToPadding(false);
            content.addView(imageContainer, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

            ImageView image = new ImageView(this);
            image.setBackgroundColor(Color.BLACK);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setAdjustViewBounds(true);
            image.setFocusable(true);
            image.setFocusableInTouchMode(false);
            imageContainer.addView(image, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER));

            Dialog dialog = new Dialog(this);
            dialog.setContentView(content);
            dialog.setCanceledOnTouchOutside(false);
            dialog.setOnKeyListener((dialogInterface, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() > 0) return false;
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                        || keyCode == KeyEvent.KEYCODE_ENTER
                        || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                    toggleImageFullscreen(dialog, imageTitle);
                    return true;
                }
                return false;
            });
            image.setOnClickListener(v -> {
                if (isTvDevice()) {
                    toggleImageFullscreen(dialog, imageTitle);
                } else if (getResources().getDisplayMetrics().widthPixels
                        >= getResources().getDisplayMetrics().heightPixels) {
                    toggleImageFullscreen(dialog, imageTitle);
                } else {
                    togglePhoneImageRotation(dialog, imageTitle, image, imageContainer);
                }
            });
            dialog.setOnShowListener(ignored -> {
                Window window = dialog.getWindow();
                if (window != null) {
                    window.setBackgroundDrawableResource(android.R.color.transparent);
                    window.setLayout(
                            (int) (getResources().getDisplayMetrics().widthPixels * 0.92f),
                            (int) (getResources().getDisplayMetrics().heightPixels * 0.84f));
                }
                image.requestFocus();
            });
            dialog.show();

            Glide.with(this)
                    .load(url)
                    .override(getResources().getDisplayMetrics().widthPixels,
                            getResources().getDisplayMetrics().heightPixels)
                    .downsample(DownsampleStrategy.AT_MOST)
                    .placeholder(android.R.drawable.ic_menu_gallery)
                    .error(android.R.drawable.ic_menu_report_image)
                    .fitCenter()
                    .into(image);
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.smb_connect_failed) + "：" + shortError(e), Toast.LENGTH_LONG).show();
        }
    }

    private void togglePhoneImageRotation(Dialog dialog, TextView imageTitle,
                                          ImageView image, FrameLayout container) {
        if (image.getRotation() != 0f) {
            image.setRotation(0f);
            image.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER));
            toggleImageFullscreen(dialog, imageTitle);
            return;
        }

        // 竖屏点击旋转时同步进入全屏，第二次点击恢复原图并退出全屏。
        toggleImageFullscreen(dialog, imageTitle);
        image.setRotation(90f);
        container.post(() -> {
            if (!dialog.isShowing() || image.getRotation() == 0f) return;
            int width = container.getWidth();
            int height = container.getHeight();
            if (width <= 0 || height <= 0) return;
            image.setLayoutParams(new FrameLayout.LayoutParams(height, width, Gravity.CENTER));
        });
    }

    private void toggleImageFullscreen(Dialog dialog, TextView imageTitle) {
        Window window = dialog.getWindow();
        if (window == null) return;
        boolean fullscreen = imageTitle.getVisibility() == View.VISIBLE;
        if (fullscreen) {
            imageTitle.setVisibility(View.GONE);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        } else {
            imageTitle.setVisibility(View.VISIBLE);
            window.setLayout(
                    (int) (getResources().getDisplayMetrics().widthPixels * 0.92f),
                    (int) (getResources().getDisplayMetrics().heightPixels * 0.84f));
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }

    private String shortError(Exception error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) return error.getClass().getSimpleName();
        return message.length() > 90 ? message.substring(0, 90) : message;
    }

    private void goBack() {
        if (showingFavorites || showingHiddenHosts || showingHiddenFolders) {
            showingFavorites = false;
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
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_INSTALL_APK_PERMISSION) return;

        File apkFile = pendingApkFile;
        pendingApkFile = null;
        if (apkFile == null) return;

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || getPackageManager().canRequestPackageInstalls()) {
            openApkInstaller(apkFile);
        } else {
            Toast.makeText(this, "尚未允许 WebHTV 安装未知来源应用", Toast.LENGTH_LONG).show();
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
                int filePosition=position;
                if(filePosition>=0&&filePosition<visibleFiles.size()) {
                    SmbClient.Entry entry=visibleFiles.get(filePosition);
                    String remote=path.isEmpty()?entry.name:path+"\\"+entry.name;
                    showFileActions(remote,entry.directory); return true;
                }
            }
            if(mode==Mode.FAVORITES && position>=0 && position<favorites.size()) {
                removeFavoriteAt(position); return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
