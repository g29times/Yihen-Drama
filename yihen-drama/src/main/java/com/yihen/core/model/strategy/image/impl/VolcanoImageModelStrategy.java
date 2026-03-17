package com.yihen.core.model.strategy.image.impl;

import com.alibaba.fastjson.JSONObject;
import com.yihen.config.properties.MinioProperties;
import com.yihen.constant.MinioConstant;
import com.yihen.controller.vo.ImageModelRequestVO;
import com.yihen.core.model.strategy.image.ImageModelStrategy;
import com.yihen.entity.*;
import com.yihen.http.HttpExecutor;
import com.yihen.mapper.ModelDefinitionMapper;
import com.yihen.service.ModelManageService;
import com.yihen.util.MinioUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
@Slf4j
@Component
public class VolcanoImageModelStrategy implements ImageModelStrategy {
    private static final String STRATEGY_TYPE = "volcano";
    private static final String MODEL_BASE_URL = "https://ark.cn-beijing.volces.com/api/v3";

    @Autowired
    private ModelManageService modelManageService;

    @Autowired
    private ModelDefinitionMapper modelDefinitionMapper;

    @Autowired
    private MinioUtil minioUtil;

    @Autowired
    private MinioProperties minioProperties;

    @Autowired
    private HttpExecutor httpExecutor;

    @Override
    public String create(ImageModelRequestVO imageModelRequestVO) throws Exception {
        // TODO
        return "";
    }

    // 生成首帧图
    @Override
    public String createByTextAndImage(ImageModelRequestVO imageModelRequestVO) throws Exception {
        long totalStart = System.currentTimeMillis();
        long stageStart;

        // 0. 获取分镜对象
        Storyboard storyboard = (Storyboard) imageModelRequestVO.getObject();
        log.info("[VolcanoImage] enter createByTextAndImage storyboardId={} modelInstanceId={}", storyboard != null ? storyboard.getId() : null, imageModelRequestVO.getModelInstanceId());

        stageStart = System.currentTimeMillis();

        String refObjectPath = null;
        List<Scene> scenes = storyboard.getScenes();
        if (scenes != null && !scenes.isEmpty()) {
            refObjectPath = scenes.get(0).getThumbnail();
        }
        if (ObjectUtils.isEmpty(refObjectPath)) {
            List<Characters> characters = storyboard.getCharacters();
            if (characters != null && !characters.isEmpty()) {
                refObjectPath = characters.get(0).getAvatar();
            }
        }

        if (ObjectUtils.isEmpty(refObjectPath)) {
            throw new IllegalStateException("参考图为空：场景thumbnail和角色avatar都为空");
        }

        String refObjectName = minioUtil.parseObjectName(refObjectPath);
        while (refObjectName != null && refObjectName.startsWith("/")) {
            refObjectName = refObjectName.substring(1);
        }
        if (ObjectUtils.isEmpty(refObjectName)) {
            throw new IllegalStateException("参考图 objectName 解析为空：" + refObjectPath);
        }

        String imageUrl;
        if (minioProperties != null && !ObjectUtils.isEmpty(minioProperties.getPublicDownloadEndpoint())) {
            String base = minioProperties.getPublicDownloadEndpoint();
            if (base.endsWith("/")) {
                base = base.substring(0, base.length() - 1);
            }

            // 构造“可公网直连的对象URL”，避免使用 console 的 download API（外部抓取易超时/重定向/鉴权）
            // 期望 publicDownloadEndpoint 指向 Minio S3 网关或你对外暴露的静态资源代理（能直接 GET 文件）
            String encodedObjectName = encodePath(refObjectName);
            imageUrl = base + "/" + MinioConstant.BUCKET_NAME + "/" + encodedObjectName;
        } else {
            imageUrl = minioUtil.getObjectUrl(MinioConstant.BUCKET_NAME, refObjectName);
        }

        long encodeCost = System.currentTimeMillis() - stageStart;


        // 1. 获取模型实例
        ModelInstance modelInstance = modelManageService.getModelInstanceById(imageModelRequestVO.getModelInstanceId());

        // 2. 获取厂商定义的baseurl
        String baseUrl = modelDefinitionMapper.getBaseUrlById(modelInstance.getModelDefId());

        // 3. 拼接发送请求信息
        HashMap<String, Object> body = (HashMap<String, Object>) modelInstance.getParams();
        if (ObjectUtils.isEmpty(body)) {
            body = new HashMap<>();
        }
        body.put("model", modelInstance.getModelCode());

        // 放入文本提示词
        body.put("prompt", storyboard.getImagePrompt());

        body.put("image", imageUrl);


        // 3. 发送请求
        stageStart = System.currentTimeMillis();
        String response = httpExecutor.post(baseUrl, modelInstance.getPath(), modelInstance.getApiKey(), body).block();
        long httpCost = System.currentTimeMillis() - stageStart;

        // 4. 解析结果
        stageStart = System.currentTimeMillis();
        String result = extractResponse(response);
        long parseCost = System.currentTimeMillis() - stageStart;

        long totalCost = System.currentTimeMillis() - totalStart;
        log.info("[VolcanoImage] storyboardId={} total={}ms encode={}ms http={}ms parse={}ms", storyboard.getId(), totalCost, encodeCost, httpCost, parseCost);

        return result;
    }

    // 响应结果提取
    private String extractResponse(String response) throws Exception {

        // 1. 转成JSONObject对象
        JSONObject jsonObject = JSONObject.parseObject(response);
        if (jsonObject.containsKey("error")) {
            // 调用失败
            String errorMessage = jsonObject.getJSONObject("error").getString("message");
            throw new Exception(errorMessage);
        }

        String content = jsonObject.getJSONArray("data")
                .getJSONObject(0).getString("url");

        if (org.springframework.util.ObjectUtils.isEmpty(content)) {
            throw new Exception("返回结果结构正确，但是返回数据为空！再次尝试");
        }

        return content;

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
            // URLEncoder 默认会把空格转为 +，这里转换成 %20 更符合 URL path 语义
            String encoded = URLEncoder.encode(parts[i], StandardCharsets.UTF_8).replace("+", "%20");
            sb.append(encoded);
        }
        return sb.toString();
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
}
