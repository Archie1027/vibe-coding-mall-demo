package com.zhicai.platform.media;

import com.zhicai.platform.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;

@Component
public class AssetFileStore {
    private final Path root;
    public AssetFileStore(@Value("${app.catalog.upload-root:}") String directory){root=directory==null || directory.isBlank()?null:Path.of(directory).toAbsolutePath().normalize();}
    public void ensureConfigured(){if(root==null)throw new ApiException(503,"DEPENDENCY_UNAVAILABLE","图片上传存储尚未配置");}
    public Path path(String key){
        ensureConfigured();
        if(key==null || !key.matches("upload-[0-9a-f]{32}\\.(jpg|png|webp)"))throw new ApiException(404,"NOT_FOUND","图片不存在");
        Path file=root.resolve(key).normalize();
        if(!file.getParent().equals(root) || Files.isSymbolicLink(root) || Files.isSymbolicLink(file))throw new ApiException(404,"NOT_FOUND","图片不存在");
        return file;
    }
    public String save(ImageUploadValidator.Validated image)throws IOException{
        ensureConfigured();Files.createDirectories(root);
        String key="upload-"+UUID.randomUUID().toString().replace("-","")+"."+image.extension();Path file=path(key);boolean created=false;
        try(var output=Files.newOutputStream(file,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){created=true;output.write(image.bytes());}
        catch(IOException error){if(created)try{Files.deleteIfExists(file);}catch(IOException cleanup){error.addSuppressed(cleanup);}throw error;}
        return key;
    }
    public void remove(String key)throws IOException{Files.deleteIfExists(path(key));}
}
