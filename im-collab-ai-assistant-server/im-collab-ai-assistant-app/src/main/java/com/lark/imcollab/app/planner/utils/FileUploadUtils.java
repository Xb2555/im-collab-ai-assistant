package com.lark.imcollab.app.planner.utils;

import com.lark.imcollab.common.config.CosClientConfig;
import com.qcloud.cos.model.PutObjectRequest;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.UUID;

public class FileUploadUtils {

    private static CosClientConfig cosClientConfig;
    private static com.qcloud.cos.COSClient cosClient;

    public static void initialize(CosClientConfig config, com.qcloud.cos.COSClient client) {
        cosClientConfig = config;
        cosClient = client;
    }

    public static String uploadToCos(MultipartFile file, String subDir) throws IOException {
        File tempFile = File.createTempFile("cos-", file.getOriginalFilename());
        try (FileOutputStream fos = new FileOutputStream(tempFile)) {
            fos.write(file.getBytes());
        }

        String originalFilename = file.getOriginalFilename();
        String fileExtension = originalFilename.substring(originalFilename.lastIndexOf("."));
        String uniqueFileName = subDir + "/" + UUID.randomUUID() + fileExtension;

        PutObjectRequest putObjectRequest = new PutObjectRequest(
                cosClientConfig.getBucket(), uniqueFileName, tempFile);
        cosClient.putObject(putObjectRequest);

        tempFile.delete();

        return cosClientConfig.getHost() + "/" + uniqueFileName;
    }

    public static boolean isValidImageFile(MultipartFile file) {
        String fileName = file.getOriginalFilename();
        if (fileName == null) return false;

        String fileExtension = fileName.substring(fileName.lastIndexOf(".")).toLowerCase();
        return fileExtension.matches("\\.(jpg|jpeg|png|gif|webp)$");
    }

}
