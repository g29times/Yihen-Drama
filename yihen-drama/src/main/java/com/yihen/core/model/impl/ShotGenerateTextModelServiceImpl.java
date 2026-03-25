package com.yihen.core.model.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.yihen.controller.vo.ExtractionResultVO;
import com.yihen.controller.vo.TextModelRequestVO;
import com.yihen.core.model.InfoExtractTextModelService;
import com.yihen.core.model.ShotGenerateTextModelService;
import com.yihen.entity.Characters;
import com.yihen.entity.PromptTemplate;
import com.yihen.entity.Scene;
import com.yihen.entity.Storyboard;
import com.yihen.mapper.ProjectMapper;
import com.yihen.service.ProjectService;
import com.yihen.service.PromptTemplateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.ObjectUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ShotGenerateTextModelServiceImpl extends TextModelServiceImpl implements ShotGenerateTextModelService {
    @Autowired
    private PromptTemplateService promptTemplateService;


    @Autowired
    private ProjectMapper projectMapper;

    @Override
    public  List<Storyboard> extract(TextModelRequestVO textModelRequestVO) throws Exception {
        // 1) 提示词模板
        PromptTemplate promptTemplate =
                promptTemplateService.getDefaultTemplateBySceneCode(textModelRequestVO.getSceneCode());



        // 2) 已有资产（一次查出来，后面不再查库）
        ExtractionResultVO property = projectMapper.getPropertyById(textModelRequestVO.getProjectId());

        // 3) 构建 id -> 资产对象 Map（关键：避免后面循环查库）
        Map<Long, Characters> characterById = new HashMap<>();
        Map<String, Characters> characterByName = new HashMap<>(); // 兜底：有的模型可能只回 name

        if (!ObjectUtils.isEmpty(property) && !ObjectUtils.isEmpty(property.getCharacters()) ) {
            for (Characters c : property.getCharacters()) {
                if (!ObjectUtils.isEmpty(c.getId()) ) characterById.put(c.getId(), c);
                if (!ObjectUtils.isEmpty( c.getName()) ) characterByName.put(c.getName(), c);
            }
        }

        Map<Long, Scene> sceneById = new HashMap<>();
        Map<String, Scene> sceneByName = new HashMap<>();

        if (!ObjectUtils.isEmpty(property) && !ObjectUtils.isEmpty(property.getScenes())) {
            for (Scene s : property.getScenes()) {
                if (!ObjectUtils.isEmpty((s.getId()) )) sceneById.put(s.getId(), s);
                if (!ObjectUtils.isEmpty((s.getName())) ) sceneByName.put(s.getName(), s);
            }
        }

        // 4) 替换模板变量
        String message = promptTemplate.getPromptContent()
                .replace("{{input}}", textModelRequestVO.getText())
                // 这里不要把完整角色/场景对象塞给模型（字段太多，容易导致输出被 max_tokens 截断）
                .replace("{{existing_characters}}", JSON.toJSONString(simplifyCharacters(property != null ? property.getCharacters() : null)))
                .replace("{{existing_scenes}}", JSON.toJSONString(simplifyScenes(property != null ? property.getScenes() : null)));

        textModelRequestVO.setText(message);

        // 5) 调用大模型
        String response = generate(textModelRequestVO.getModelId(), message);

        // 5.1 清洗模型输出，避免混入控制字符/markdown 包裹导致 JSON 解析失败
        String cleanedResponse = sanitizeModelJson(response);

        // 6) 解析 shots -> List<Storyboard>
        List<Storyboard> storyboards = new ArrayList<>();

        try {
            JSONObject root = JSON.parseObject(cleanedResponse);
            JSONArray shotsArray = root.getJSONArray("shots");

            if (ObjectUtils.isEmpty(shotsArray)) {
                // 没分镜就直接返回空
                return storyboards;
            }

            for (int i = 0; i < shotsArray.size(); i++) {
                JSONObject shot = shotsArray.getJSONObject(i);

                Storyboard sb = new Storyboard();
                sb.setShotNumber(i + 1);
                sb.setOrderIndex(i);

                // 兼容字段：shotText / description
                String shotText = shot.getString("shotText");
                if (ObjectUtils.isEmpty(shotText)) shotText = shot.getString("description");
                sb.setDescription(shotText);

                // === 解析并绑定角色（完整 Characters 对象）===
                List<Characters> boundCharacters = new ArrayList<>();
                JSONArray charArr = shot.getJSONArray("characters");
                if (!ObjectUtils.isEmpty(charArr) ) {
                    for (int j = 0; j < charArr.size(); j++) {
                        JSONObject cObj = charArr.getJSONObject(j);

                        Characters resolved = resolveCharacter(cObj, characterById, characterByName);
                        if (!ObjectUtils.isEmpty(resolved)) boundCharacters.add(resolved);
                    }
                }
                sb.setCharacters(boundCharacters);

                // === 解析并绑定场景（完整 Scene 对象）===
                List<Scene> boundScenes = new ArrayList<>();

                // 兼容 1：scene 是单对象
                JSONObject sceneObj = shot.getJSONObject("scene");
                if (!ObjectUtils.isEmpty(sceneObj) ) {
                    Scene resolved = resolveScene(sceneObj, sceneById, sceneByName);
                    if ( !ObjectUtils.isEmpty(resolved)) boundScenes.add(resolved);
                }

                // 兼容 2：scenes 是数组
                JSONArray scenesArr = shot.getJSONArray("scenes");
                if (scenesArr != null && !scenesArr.isEmpty()) {
                    for (int k = 0; k < scenesArr.size(); k++) {
                        JSONObject sObj = scenesArr.getJSONObject(k);
                        Scene resolved = resolveScene(sObj, sceneById, sceneByName);
                        if (resolved != null) boundScenes.add(resolved);
                    }
                }

                sb.setScenes(boundScenes);

                storyboards.add(sb);
            }

        } catch (Exception e) {
            log.error("解析分镜结果失败: {}", e.getMessage(), e);
            log.error("[StoryboardGen] responseLen={} cleanedLen={} responseHead={} responseTail={} cleanedTail={}",
                    response == null ? -1 : response.length(),
                    cleanedResponse == null ? -1 : cleanedResponse.length(),
                    safeSnippet(response, 0, 200),
                    safeTailSnippet(response, 200),
                    safeTailSnippet(cleanedResponse, 200));

            // 常见原因：模型输出被截断 / 包含多余文本。这里做一次重试，强约束只返回完整 JSON。
            try {
                String retryHint = "\n\n你必须仅输出严格 JSON（不要 markdown、不要解释、不要多余文本），并确保 JSON 完整闭合。" +
                        "输出格式：{\"shots\":[{\"shotText\":\"...\",\"characters\":[{\"id\":123,\"name\":\"...\"}],\"scene\":{\"id\":1,\"name\":\"...\"}}]}";
                String retryResponse = generate(textModelRequestVO.getModelId(), message + retryHint);
                String retryCleaned = sanitizeModelJson(retryResponse);
                JSONObject root = JSON.parseObject(retryCleaned);
                JSONArray shotsArray = root.getJSONArray("shots");
                if (ObjectUtils.isEmpty(shotsArray)) {
                    return storyboards;
                }

                // 复用原本解析逻辑：把 shotsArray 再走一遍
                for (int i = 0; i < shotsArray.size(); i++) {
                    JSONObject shot = shotsArray.getJSONObject(i);

                    Storyboard sb = new Storyboard();
                    sb.setShotNumber(i + 1);
                    sb.setOrderIndex(i);

                    String shotText = shot.getString("shotText");
                    if (ObjectUtils.isEmpty(shotText)) shotText = shot.getString("description");
                    sb.setDescription(shotText);

                    List<Characters> boundCharacters = new ArrayList<>();
                    JSONArray charArr = shot.getJSONArray("characters");
                    if (!ObjectUtils.isEmpty(charArr) ) {
                        for (int j = 0; j < charArr.size(); j++) {
                            JSONObject cObj = charArr.getJSONObject(j);
                            Characters resolved = resolveCharacter(cObj, characterById, characterByName);
                            if (!ObjectUtils.isEmpty(resolved)) boundCharacters.add(resolved);
                        }
                    }
                    sb.setCharacters(boundCharacters);

                    List<Scene> boundScenes = new ArrayList<>();
                    JSONObject sceneObj = shot.getJSONObject("scene");
                    if (!ObjectUtils.isEmpty(sceneObj) ) {
                        Scene resolved = resolveScene(sceneObj, sceneById, sceneByName);
                        if ( !ObjectUtils.isEmpty(resolved)) boundScenes.add(resolved);
                    }
                    JSONArray scenesArr = shot.getJSONArray("scenes");
                    if (scenesArr != null && !scenesArr.isEmpty()) {
                        for (int k = 0; k < scenesArr.size(); k++) {
                            JSONObject sObj = scenesArr.getJSONObject(k);
                            Scene resolved = resolveScene(sObj, sceneById, sceneByName);
                            if (resolved != null) boundScenes.add(resolved);
                        }
                    }
                    sb.setScenes(boundScenes);

                    storyboards.add(sb);
                }

                return storyboards;
            } catch (Exception retryEx) {
                log.error("[StoryboardGen] retry parse failed: {}", retryEx.getMessage(), retryEx);
            }

            throw new Exception("解析分镜结果失败: " + e.getMessage());
        }

        return storyboards;
    }

    private String sanitizeModelJson(String raw) {
        if (raw == null) return null;

        String s = raw;

        // 去除可能的 BOM
        if (!s.isEmpty() && s.charAt(0) == '\uFEFF') {
            s = s.substring(1);
        }

        // 如果模型返回被 ```json ... ``` 包起来，尽量截取 JSON 主体
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start >= 0 && end > start) {
            s = s.substring(start, end + 1);
        }

        // 过滤不可见控制字符（保留常见空白字符）
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\n' || ch == '\r' || ch == '\t' || ch >= 0x20) {
                sb.append(ch);
            }
        }

        return sb.toString().trim();
    }

    private String safeSnippet(String s, int start, int maxLen) {
        if (s == null) return null;
        if (start < 0) start = 0;
        if (start >= s.length()) return "";
        int end = Math.min(s.length(), start + Math.max(0, maxLen));
        return s.substring(start, end).replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private String safeTailSnippet(String s, int maxLen) {
        if (s == null) return null;
        int len = s.length();
        int start = Math.max(0, len - Math.max(0, maxLen));
        return safeSnippet(s, start, maxLen);
    }

    private List<Map<String, Object>> simplifyCharacters(List<Characters> characters) {
        if (characters == null || characters.isEmpty()) return List.of();
        List<Map<String, Object>> list = new ArrayList<>();
        for (Characters c : characters) {
            if (c == null) continue;
            Map<String, Object> m = new HashMap<>();
            m.put("id", c.getId());
            m.put("name", c.getName());
            list.add(m);
        }
        return list;
    }

    private List<Map<String, Object>> simplifyScenes(List<Scene> scenes) {
        if (scenes == null || scenes.isEmpty()) return List.of();
        List<Map<String, Object>> list = new ArrayList<>();
        for (Scene s : scenes) {
            if (s == null) continue;
            Map<String, Object> m = new HashMap<>();
            m.put("id", s.getId());
            m.put("name", s.getName());
            list.add(m);
        }
        return list;
    }

    /**
     * 优先按 id 从 property 的缓存 Map 找完整对象；找不到再按 name；
     * 都找不到：fallback 用 LLM 返回对象构造一个（避免空指针）。
     */
    private Characters resolveCharacter(JSONObject cObj,
                                        Map<Long, Characters> byId,
                                        Map<String, Characters> byName) {
        if (cObj == null) return null;

        Long id = cObj.getLong("id");
        if (ObjectUtils.isEmpty(id)) id = cObj.getLong("characterId"); // 兼容其他字段名

        String name = cObj.getString("name");

        Characters cached = null;
        if (!ObjectUtils.isEmpty(id) ) cached = byId.get(id);
        if (ObjectUtils.isEmpty(cached) &&!ObjectUtils.isEmpty(name)) cached = byName.get(name);

        if (cached != null) return cached;

        // fallback：用模型返回的最少信息拼一个（仍然不查库）
        Characters c = new Characters();
        c.setId(id);
        c.setName(name);
        c.setDescription(cObj.getString("description"));
        return c;
    }

    private Scene resolveScene(JSONObject sObj,
                               Map<Long, Scene> byId,
                               Map<String, Scene> byName) {
        if (ObjectUtils.isEmpty(sObj) ) return null;

        Long id = sObj.getLong("id");
        if (ObjectUtils.isEmpty(id)) id = sObj.getLong("sceneId");

        String name = sObj.getString("name");

        Scene cached = null;
        if (!ObjectUtils.isEmpty(id) ) cached = byId.get(id);
        if (ObjectUtils.isEmpty(cached) && !ObjectUtils.isEmpty(name)) cached = byName.get(name);

        if (!ObjectUtils.isEmpty(cached)) return cached;

        Scene s = new Scene();
        s.setId(id);
        s.setName(name);
        s.setDescription(sObj.getString("description"));
        return s;
    }

}
