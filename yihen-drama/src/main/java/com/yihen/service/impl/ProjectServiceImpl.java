package com.yihen.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yihen.asyn.ProjectPersistFacade;
import com.yihen.config.properties.MinioProperties;
import com.yihen.constant.MinioConstant;
import com.yihen.controller.vo.ExtractionResultVO;
import com.yihen.controller.vo.ProjectCreateRequestVO;
import com.yihen.entity.Characters;
import com.yihen.entity.Episode;
import com.yihen.entity.Project;
import com.yihen.entity.Scene;
import com.yihen.enums.ProjectStyle;
import com.yihen.mapper.ProjectMapper;
import com.yihen.service.CharacterService;
import com.yihen.service.EpisodeService;
import com.yihen.service.ProjectService;
import com.yihen.service.SceneService;
import com.yihen.util.MinioUtil;
import com.yihen.util.UrlUtils;
import io.minio.errors.*;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Service("projectServiceImpl")
public class ProjectServiceImpl extends ServiceImpl<ProjectMapper, Project> implements ProjectService {
    @Autowired
    private CharacterService characterService;

    @Autowired
    private ProjectMapper projectMapper;

    @Autowired
    private SceneService sceneService;

    @Autowired
    private ProjectPersistFacade projectPersistFacade;

    @Autowired
    private MinioProperties minioProperties;

    @Autowired
    private MinioUtil minioUtil;

    @Autowired
    @Qualifier("commonExecutor")
    private Executor commonExecutor;

    @Override
    public Project createProject(ProjectCreateRequestVO project) {
        // 合法性校验
        if (ObjectUtils.isEmpty(project.getName())) {
            throw new RuntimeException("项目名称不可为空");
        }

        // 创建项目对象
        Project createdProject = new Project();
        createdProject.setName(project.getName());
        createdProject.setStyleId(project.getStyle());

        save(createdProject);
        return createdProject;
    }

    @Override
    public ExtractionResultVO getPropertyById(Long id) {
        ExtractionResultVO extractionResultVO = projectMapper.getPropertyById(id);

//
//        LambdaQueryWrapper<Characters> charactersLambdaQueryWrapper = new LambdaQueryWrapper<Characters>().eq(Characters::getProjectId, id);
//            List<Characters> characters = characterService.list(charactersLambdaQueryWrapper);
//
//            LambdaQueryWrapper<Scene> sceneLambdaQueryWrapper = new LambdaQueryWrapper<Scene>().eq(Scene::getProjectId, id);
//            List<Scene> scenes = sceneService.list(sceneLambdaQueryWrapper);
//
//            ExtractionResultVO extractionResultVO = new ExtractionResultVO();
//            extractionResultVO.setCharacters(characters);
//            extractionResultVO.setScenes(scenes);
            return extractionResultVO;
    }

    @Override
    public void addEpisodeCount(Long projectId) {
        projectMapper.addEpisodeCount(projectId);
    }

    @Override
    public void reduceEpisodeCount(Long projectId) {
        projectMapper.reduceEpisodeCount(projectId);
    }

    @Override
    public Long getProjectStyleById(Long id) {
        Long projectStyleById = projectMapper.getProjectStyleById(id);

        return projectStyleById;
    }

    @Override
    public void deleteProject(Long id) {
        removeById(id);

        // 删除关联内容
        projectPersistFacade.deleteProjectRelatedContentAsync(id);

    }

    @Override
    public Project getProjectById(Long id) {
        return getById(id);
    }

    @Override
    public void updateProjectById(Project project) {
        updateById(project);
    }

    @Override
    public List<Project> getProject(Page<Project> projectPage) {
        Page<Project> page = page(projectPage);
        return page.getRecords();
    }

    @Override
    public Project upload(Long projectId, MultipartFile file) throws IOException, ServerException, InsufficientDataException, ErrorResponseException, NoSuchAlgorithmException, InvalidKeyException, InvalidResponseException, XmlParserException, InternalException {
        Project project = getById(projectId);

        // 生成保存路径
        String originalFilename = file.getOriginalFilename();
        String extension = UrlUtils.extractFileExtension(originalFilename);
        // 项目封面图片路径 /project/{projectId}/cover/{projectId-projectName.xxx}
        String imgName = project.getId() + "-" +project.getName() + "." + extension;
        String objectName = MinioConstant.PROJECT_COVER_IMG_PATH.formatted(project.getId(), imgName);



        // 修改场景图片url
        String cover = minioUtil.buildDbPath(MinioConstant.BUCKET_NAME, objectName);

        // 校验：是否 Minio中存在图片
        if (!org.springframework.util.ObjectUtils.isEmpty(project.getCover()) && !project.getCover().equals(cover)) {
            // 存在且不相同，则删除原来的
            minioUtil.deleteObject(MinioConstant.BUCKET_NAME, project.getCover());
        }

        project.setCover(cover);
        updateById(project);


        // 上传文件到Minio
        InputStream inputStream = file.getInputStream();
        long size = file.getSize();
        minioUtil.uploadFile(inputStream, (int) size,MinioConstant.BUCKET_NAME, objectName);
        return project;
    }


}
