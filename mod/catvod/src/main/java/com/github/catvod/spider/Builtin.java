package com.github.catvod.spider;

import android.content.Context;

import com.github.catvod.crawler.Spider;

/**
 * WebHome / 纯网页站外壳：api=csp_Builtin 时由壳子加载 homePage，不依赖 jar 内实现。
 * 无 homePage 时返回空分类，避免 ClassNotFound 导致只显示站点名。
 */
public class Builtin extends Spider {

    @Override
    public void init(Context context, String extend) throws Exception {
        super.init(context, extend);
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        return "{\"class\":[],\"list\":[]}";
    }

    @Override
    public String homeVideoContent() throws Exception {
        return "{\"list\":[]}";
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, java.util.HashMap<String, String> extend) throws Exception {
        return "{\"list\":[],\"page\":1,\"pagecount\":1,\"limit\":0,\"total\":0}";
    }

    @Override
    public String detailContent(java.util.List<String> ids) throws Exception {
        return "{\"list\":[]}";
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        return "{\"list\":[]}";
    }

    @Override
    public String playerContent(String flag, String id, java.util.List<String> vipFlags) throws Exception {
        return "{\"parse\":0,\"playUrl\":\"\",\"url\":\"" + (id == null ? "" : id.replace("\"", "")) + "\"}";
    }
}
