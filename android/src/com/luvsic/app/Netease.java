package com.luvsic.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * 网易云公开搜索：只取歌曲元数据（歌名 / 歌手 / 专辑 / 时长）。
 *
 * 用于本地曲库的「补全未知歌手」——只做文本查询，不取歌词、不解析音频地址、
 * 不下载任何音频文件。
 */
public class Netease {

    /** 兼容原 OnlineMusic.search(keyword, page, limit) 的三参数调用；page 目前忽略。 */
    public static JSONObject search(String keyword, int page, int limit) {
        return search(keyword, limit);
    }

    public static JSONObject search(String keyword, int limit) {
        JSONObject out = new JSONObject();
        try {
            String q = URLEncoder.encode(keyword == null ? "" : keyword, "UTF-8");
            String url = "https://music.163.com/api/search/get/web?s=" + q
                    + "&type=1&limit=" + limit + "&offset=0";
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestProperty("Referer", "https://music.163.com/");
            c.setRequestProperty("User-Agent", "Mozilla/5.0");
            c.setConnectTimeout(12000);
            c.setReadTimeout(12000);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
            }
            in.close();
            c.disconnect();

            JSONObject arr = new JSONObject(bo.toString("UTF-8"));
            JSONObject result = arr.optJSONObject("result");
            JSONArray raw = result == null ? null : result.optJSONArray("songs");

            JSONArray songs = new JSONArray();
            for (int i = 0; raw != null && i < raw.length(); i++) {
                JSONObject s = raw.optJSONObject(i);
                if (s == null) {
                    continue;
                }
                JSONObject item = new JSONObject();
                item.put("title", s.optString("name"));

                JSONArray as = s.optJSONArray("artists");
                StringBuilder sb = new StringBuilder();
                for (int j = 0; as != null && j < as.length(); j++) {
                    JSONObject a = as.optJSONObject(j);
                    if (a == null) {
                        continue;
                    }
                    if (sb.length() > 0) {
                        sb.append(" / ");
                    }
                    sb.append(a.optString("name"));
                }
                item.put("artist", sb.toString());

                JSONObject al = s.optJSONObject("album");
                item.put("album", al == null ? "" : al.optString("name"));
                item.put("duration", s.optLong("duration") / 1000);
                songs.put(item);
            }

            out.put("ok", true);
            out.put("songs", songs);
        } catch (Exception e) {
            try {
                out.put("ok", false);
                out.put("msg", e.toString());
                out.put("songs", new JSONArray());
            } catch (Exception ignored) {
                // JSONObject.put 在失败分支里也可能抛 JSONException，忽略
            }
        }
        return out;
    }
}
