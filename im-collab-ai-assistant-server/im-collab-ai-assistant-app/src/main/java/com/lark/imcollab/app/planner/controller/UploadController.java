package com.lark.imcollab.app.planner.controller;

import com.lark.imcollab.app.planner.utils.FileUploadUtils;
import com.lark.imcollab.common.config.CosClientConfig;
import com.lark.imcollab.common.model.entity.BaseResponse;
import com.lark.imcollab.common.model.enums.BusinessCode;
import com.lark.imcollab.common.utils.ResultUtils;
import com.qcloud.cos.COSClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "文件上传")
@RestController
@RequestMapping("/upload")
@RequiredArgsConstructor
public class UploadController {

    private final CosClientConfig cosClientConfig;
    private final COSClient cosClient;

    @PostConstruct
    public void init() {
        FileUploadUtils.initialize(cosClientConfig, cosClient);
    }

    @Operation(summary = "上传图片")
    @PostMapping("/img")
    public BaseResponse<?> uploadImage(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResultUtils.error(BusinessCode.PARAMS_ERROR, "请选择有效的图片文件");
        }
        if (!FileUploadUtils.isValidImageFile(file)) {
            return ResultUtils.error(BusinessCode.PARAMS_ERROR, "上传的文件不是图片");
        }
        if (file.getSize() > 10 * 1024 * 1024) {
            return ResultUtils.error(BusinessCode.PARAMS_ERROR, "图片大小不能超过10MB");
        }

        try {
            String url = FileUploadUtils.uploadToCos(file, "im-images");
            return ResultUtils.success(url);
        } catch (Exception e) {
            return ResultUtils.error(BusinessCode.SYSTEM_ERROR, "图片上传失败：" + e.getMessage());
        }
    }
}
