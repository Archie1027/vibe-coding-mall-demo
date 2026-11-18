package com.zhicai.platform.web;

import com.zhicai.platform.catalog.AssetService;
import com.zhicai.platform.common.ApiException;
import com.zhicai.platform.media.AssetFileStore;
import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.nio.file.*;

@RestController
public class UploadedAssetController {
    private final AssetService assets;private final AssetFileStore files;
    public UploadedAssetController(AssetService assets,AssetFileStore files){this.assets=assets;this.files=files;}
    @GetMapping("/assets/products/upload-{token}.{extension}")
    ResponseEntity<Resource> image(@PathVariable String token,@PathVariable String extension){
        String key="upload-"+token+"."+extension;Path path=files.path(key);var asset=assets.publicByKey(key);
        try{
            if(asset==null || !Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || Files.size(path)!=asset.sizeBytes())throw new ApiException(404,"NOT_FOUND","图片不存在");
            return ResponseEntity.ok().contentType(MediaType.parseMediaType(asset.contentType())).contentLength(asset.sizeBytes())
                .cacheControl(CacheControl.noCache()).header("X-Content-Type-Options","nosniff").body(new FileSystemResource(path));
        }catch(IOException error){throw new ApiException(503,"DEPENDENCY_UNAVAILABLE","图片读取暂不可用");}
    }
}
