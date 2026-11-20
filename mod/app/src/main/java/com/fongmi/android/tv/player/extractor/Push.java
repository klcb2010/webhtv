package com.fongmi.android.tv.player.extractor;

import android.net.Uri;
import android.os.SystemClock;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.player.Source;
import com.fongmi.android.tv.ui.activity.VideoActivity;
import com.fongmi.android.tv.utils.UrlUtil;

/**
 * 对齐默壳/上游：start 推送页 + 短暂等待 + 返回空串。
 * 额外：handoff 标记，让 setPlayer/onError 跳过「播放地址加载失败」与 startFlow 长重试，
 * 避免空地址把已切入的 push_agent 播放冲掉。
 */
public class Push implements Source.Extractor {

    private static volatile long sHandoffUntilMs;

    public static void markHandoff() {
        sHandoffUntilMs = SystemClock.uptimeMillis() + 4000;
    }

    public static boolean isHandoff() {
        return SystemClock.uptimeMillis() < sHandoffUntilMs;
    }

    public static boolean consumeHandoff() {
        if (!isHandoff()) return false;
        sHandoffUntilMs = 0;
        return true;
    }

    @Override
    public boolean match(Uri uri) {
        return "push".equals(UrlUtil.scheme(uri));
    }

    @Override
    public String fetch(String url) throws Exception {
        String target = url != null && url.length() > 7 ? url.substring(7) : "";
        markHandoff();
        if (App.activity() != null) {
            VideoActivity.start(App.activity(), target);
        }
        // 默壳为 500ms；略缩短，给 singleTop onNewIntent 时间切到 push_agent
        SystemClock.sleep(200);
        return "";
    }

    @Override
    public void stop() {
    }

    @Override
    public void exit() {
    }
}
