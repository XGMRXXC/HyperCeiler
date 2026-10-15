/*
  * This file is part of HyperCeiler.

  * HyperCeiler is free software: you can redistribute it and/or modify
  * it under the terms of the GNU Affero General Public License as
  * published by the Free Software Foundation, either version 3 of the
  * License.

  * This program is distributed in the hope that it will be useful,
  * but WITHOUT ANY WARRANTY; without even the implied warranty of
  * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  * GNU Affero General Public License for more details.

  * You should have received a copy of the GNU Affero General Public License
  * along with this program.  If not, see <https://www.gnu.org/licenses/>.

  * Copyright (C) 2023-2026 HyperCeiler Contributions
*/
package com.sevtinge.hyperceiler.utils;

import android.os.Handler;
import android.os.Looper;

import com.sevtinge.hyperceiler.BuildConfig;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 检查更新：查询本 fork 的 GitHub Releases，取最新一个 release 与当前运行版本比较。
 *
 * 版本号取自 tag 末尾的 rNNNN（就是构建时的提交数，也是 versionCode），
 * 所以比较的是整数，不需要解析版本名。
 */
public final class UpdateChecker {

    /** 本 fork 的仓库。 */
    private static final String REPO = "XGMRXXC/HyperCeiler";
    private static final String API = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final Pattern VERSION_IN_TAG = Pattern.compile("r(\\d+)\\s*$");

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private UpdateChecker() {
    }

    public interface Callback {
        void onResult(Result result);
    }

    public static final class Result {
        public boolean ok;
        public boolean hasUpdate;
        public int currentVersion;
        public int latestVersion;
        public String tag = "";
        public String name = "";
        public String url = "";
        public String body = "";
        public String error = "";

        public static Result failure(String message) {
            Result r = new Result();
            r.ok = false;
            r.error = message;
            r.currentVersion = BuildConfig.VERSION_CODE;
            return r;
        }
    }

    /** 后台线程查询，结果回到主线程。 */
    public static void check(Callback callback) {
        new Thread(() -> {
            Result result;
            try {
                result = query();
            } catch (Throwable t) {
                result = Result.failure(t.getClass().getSimpleName() + ": " + t.getMessage());
            }
            Result finalResult = result;
            MAIN.post(() -> callback.onResult(finalResult));
        }, "HyperCeiler-UpdateCheck").start();
    }

    private static Result query() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(API).openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("User-Agent", "HyperCeiler/" + BuildConfig.VERSION_NAME);
            conn.setInstanceFollowRedirects(true);

            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                return Result.failure("HTTP " + code);
            }

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }

            JSONObject json = new JSONObject(sb.toString());
            Result result = new Result();
            result.ok = true;
            result.currentVersion = BuildConfig.VERSION_CODE;
            result.tag = json.optString("tag_name", "");
            result.name = json.optString("name", "");
            result.url = json.optString("html_url", "");
            result.body = json.optString("body", "");

            Matcher matcher = VERSION_IN_TAG.matcher(result.tag);
            result.latestVersion = matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
            result.hasUpdate = result.latestVersion > result.currentVersion;
            return result;
        } finally {
            conn.disconnect();
        }
    }
}
