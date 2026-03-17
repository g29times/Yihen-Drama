package com.yihen.core.model.strategy.video.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.yihen.config.properties.MinioProperties;
import com.yihen.constant.MinioConstant;
import com.yihen.controller.vo.CharactersRequestVO;
import com.yihen.controller.vo.VideoModelRequestVO;
import com.yihen.core.model.strategy.video.VideoModelStrategy;
import com.yihen.core.model.strategy.video.dto.VideoTaskDTO;
import com.yihen.entity.*;
import com.yihen.enums.SceneCode;
import com.yihen.http.HttpExecutor;
import com.yihen.mapper.ModelDefinitionMapper;
import com.yihen.service.CharacterService;
import com.yihen.service.ModelManageService;
import com.yihen.service.PromptTemplateService;
import com.yihen.util.MinioUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 火山引擎视频生成策略
 */
@Slf4j
@Component
public class VolcanoVideoModelStrategy implements VideoModelStrategy {

    private static final String STRATEGY_TYPE = "volcano";
    private static final String MODEL_BASE_URL = "https://ark.cn-beijing.volces.com/api/v3";

    @Autowired
    private ModelDefinitionMapper modelDefinitionMapper;

    @Autowired
    private HttpExecutor httpExecutor;


    @Autowired
    private PromptTemplateService promptTemplateService;

    @Autowired
    private MinioUtil minioUtil;

    @Autowired
    private MinioProperties minioProperties;

    @Autowired
    private ModelManageService modelManageService;

    @Autowired
    private CharacterService characterService;

    @Value("${volcano.video-image-fallback-url:}")
    private String videoImageFallbackUrl;

    // 创建一个固定大小的线程池

    @Override
    public String create(CharactersRequestVO charactersRequestVO) throws Exception {
        // 1. 获取模型实例
        ModelInstance modelInstance = modelManageService.getModelInstanceById(charactersRequestVO.getModelInstanceId());

        // 2. 获取厂商定义的baseurl
        String baseUrl = modelDefinitionMapper.getBaseUrlById(modelInstance.getModelDefId());
        
        // 3. 拼接发送请求信息
        HashMap<String, Object> body = (HashMap<String, Object>) modelInstance.getParams();
        if (ObjectUtils.isEmpty(body)) {
            body = new HashMap<>();
        }
        body.put("model", modelInstance.getModelCode());

        Object content = buildBody(charactersRequestVO.getCharacterId(), charactersRequestVO.getDescription());

        body.put("content", content);

        // 3. 发送请求
        String response = httpExecutor.post(baseUrl, modelInstance.getPath(), modelInstance.getApiKey(), body).block();

        // 4. 解析结果
        return extractResponse(response);
    }

    @Override
    public String createShotVideoTask(VideoModelRequestVO videoModelRequestVO) throws Exception {
        // 1. 获取模型实例
        ModelInstance modelInstance = modelManageService.getModelInstanceById(videoModelRequestVO.getModelInstanceId());

        // 2. 获取厂商定义的baseurl
        String baseUrl = modelDefinitionMapper.getBaseUrlById(modelInstance.getModelDefId());

        // 3. 拼接发送请求信息
        HashMap<String, Object> body = (HashMap<String, Object>) videoModelRequestVO.getParams();
        if (ObjectUtils.isEmpty(body)) {
            body = new HashMap<>();
        }
        body.put("model", modelInstance.getModelCode());
        body.put("watermark", false);

        Object content = buildBody((Storyboard) videoModelRequestVO.getObject());

        body.put("content", content);

        // 3. 发送请求
        String response = httpExecutor.post(baseUrl, modelInstance.getPath(), modelInstance.getApiKey(), body).block();

        // 4. 解析结果
        return extractResponse(response);
    }

    private static final String CONTENT_TEMPLATE = "人物的基本描述（只关注其中的外貌描述）如下: %s," +
            "生成符合该描述的视频,视频内容为: 图中的人物，对着镜头打招呼，说：你好，我是：%s";

    private Object buildBody(Storyboard storyboard) {

        String imageUrl = resolvePublicImageUrl(storyboard != null ? storyboard.getThumbnail() : null);

        // 3. 创建JSON数组并填充数据
        JSONArray jsonArray = new JSONArray();

        // 添加文本提示词对象
        JSONObject textObject = new JSONObject();
        textObject.put("text", storyboard.getVideoPrompt());
        textObject.put("type", "text");
        jsonArray.add(textObject);

        // 添加图片URL对象
        JSONObject imageObject = new JSONObject();
        JSONObject imageUrlObject = new JSONObject();
        imageUrlObject.put("url", imageUrl);
        imageObject.put("image_url", imageUrlObject);
        imageObject.put("type", "image_url");
        jsonArray.add(imageObject);

        return jsonArray;
    }

    private String resolvePublicImageUrl(String pathOrUrl) {
        if (!ObjectUtils.isEmpty(videoImageFallbackUrl)) {
            return videoImageFallbackUrl;
        }
        if (ObjectUtils.isEmpty(pathOrUrl)) {
            throw new IllegalStateException("参考图为空");
        }

        String trimmed = pathOrUrl.trim();
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed;
        }

        String objectName = minioUtil.parseObjectName(trimmed);
        while (objectName != null && objectName.startsWith("/")) {
            objectName = objectName.substring(1);
        }
        if (ObjectUtils.isEmpty(objectName)) {
            throw new IllegalStateException("参考图 objectName 解析为空：" + pathOrUrl);
        }

        if (minioProperties != null && !ObjectUtils.isEmpty(minioProperties.getPublicDownloadEndpoint())) {
            String base = minioProperties.getPublicDownloadEndpoint();
            if (base.endsWith("/")) {
                base = base.substring(0, base.length() - 1);
            }
            return base + "/" + MinioConstant.BUCKET_NAME + "/" + encodePath(objectName);
        }

        return minioUtil.getObjectUrl(MinioConstant.BUCKET_NAME, objectName);
    }

    private static String encodePath(String objectName) {
        if (objectName == null) {
            return null;
        }
        String[] parts = objectName.split("/");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append('/');
            }
            String encoded = URLEncoder.encode(parts[i], StandardCharsets.UTF_8).replace("+", "%20");
            sb.append(encoded);
        }
        return sb.toString();
    }

    // 构建body内容
    private Object buildBody(Long characterId , String  description) {
        // 获取角色
        Characters characters = characterService.getById(characterId);

        // 1. 文本提示词
        String text = String.format(CONTENT_TEMPLATE, description, characters.getName());

        String imageUrl = resolvePublicImageUrl(characters.getAvatar());

        // 3. 创建JSON数组并填充数据
        JSONArray jsonArray = new JSONArray();

        // 添加文本提示词对象
        JSONObject textObject = new JSONObject();
        textObject.put("text", text);
        textObject.put("type", "text");
        jsonArray.add(textObject);

        // 添加图片URL对象
        JSONObject imageObject = new JSONObject();
        JSONObject imageUrlObject = new JSONObject();
        imageUrlObject.put("url", imageUrl);
        imageObject.put("image_url", imageUrlObject);
        imageObject.put("type", "image_url");
        jsonArray.add(imageObject);

        return jsonArray;
    }

    @Override
    public String getStrategyType() {
        return STRATEGY_TYPE;
    }

    @Override
    public boolean supports(ModelInstance modelInstance) {
        // 可以根据 modelDefId 或其他属性判断是否支持

        ModelDefinition modelDefinition = modelManageService.getById(modelInstance.getModelDefId());
        // 判断该模型实例对应的厂商BaseURL是否属于火山引擎
        if (MODEL_BASE_URL.equals(modelDefinition.getBaseUrl())) {
            return true;
        }
        return false;
    }

    @Override
    public VideoTaskDTO queryTask(String taskId, Long modelInstanceId) throws Exception {
        ModelInstance modelInstance = modelManageService.getModelInstanceById(modelInstanceId);
        // 2. 获取厂商定义的baseurl
        String baseUrl = modelManageService.getBaseUrlById(modelInstance.getModelDefId());
        log.info("发送请求，查询任务: {}", taskId);
        // 发送请求
        String response = httpExecutor.get(baseUrl, modelInstance.getPath()+"/"+taskId, modelInstance.getApiKey()).block();
        log.info("收到响应，{}", response);

        // 统一传输类型
        VideoTaskDTO videoTaskDTO = new VideoTaskDTO();

        // 解析请求响应
        // 1. 转成JSONObject对象
        JSONObject jsonObject = JSONObject.parseObject(response);
        if (jsonObject.containsKey("error") ) {
            // 调用失败
            String errorMessage = jsonObject.getJSONObject("error").getString("message");
            if (jsonObject.containsKey("status")) {
                videoTaskDTO.setMessage(errorMessage);
            } else {
                throw new Exception(errorMessage);

            }


        }

        // 提取出status和video_url
        String status = jsonObject.getString("status");
        if (status.equals("succeeded")) {
            String videoUrl = jsonObject.getJSONObject("content").getString("video_url");
            videoTaskDTO.setVideoUrl(videoUrl);
        }


        videoTaskDTO.setStatus(status);


        return videoTaskDTO;
    }

    /**
     * 响应结果提取
     */
    private String extractResponse(String response) throws Exception {
        JSONObject jsonObject = JSONObject.parseObject(response);
        if (jsonObject.containsKey("error")) {
            String errorMessage = jsonObject.getJSONObject("error").getString("message");
            throw new Exception(errorMessage);
        }

        String id = jsonObject.getString("id");
        if (ObjectUtils.isEmpty(id)) {
            throw new Exception("返回结果结构正确，但是返回数据为空！再次尝试");
        }

        return id;
    }
}
