package com.zhicai.platform.web;

import com.zhicai.platform.common.ApiException;
import com.zhicai.platform.identity.AccountView;
import com.zhicai.platform.media.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.*;
import java.io.IOException;

@RestController
public class AdminAssetController {
    private final AssetUploadApplication uploads;
    public AdminAssetController(AssetUploadApplication uploads){this.uploads=uploads;}
    @PostMapping(value="/api/v1/admin/assets/images",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<String> upload(@RequestPart("file") MultipartFile file,MultipartHttpServletRequest request,
        @RequestHeader(value="Idempotency-Key",required=false)String key,@AuthenticationPrincipal AccountView actor){
        int count=request.getMultiFileMap().values().stream().mapToInt(java.util.List::size).sum();
        if(count!=1 || !request.getParameterMap().isEmpty())throw new ApiException(400,"VALIDATION_ERROR","仅允许一个file图片字段");
        if(file.getSize()>ImageUploadValidator.MAX_BYTES)throw new ApiException(413,"PAYLOAD_TOO_LARGE","单张图片不能超过5MiB");
        byte[] bytes;try{bytes=file.getBytes();}catch(IOException error){throw new ApiException(400,"INVALID_IMAGE","无法读取上传文件");}
        var result=uploads.upload(actor,bytes,file.getContentType(),key,TraceFilter.id(request));
        return ResponseEntity.status(result.status()).contentType(MediaType.APPLICATION_JSON).body(result.body());
    }
}
