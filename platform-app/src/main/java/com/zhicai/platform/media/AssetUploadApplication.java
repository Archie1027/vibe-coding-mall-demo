package com.zhicai.platform.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhicai.platform.catalog.*;
import com.zhicai.platform.common.ApiException;
import com.zhicai.platform.identity.*;
import com.zhicai.platform.write.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.*;
import java.io.IOException;

@Service
public class AssetUploadApplication {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(AssetUploadApplication.class);
    private final AssetService assets;
    private final AssetFileStore files;
    private final ImageUploadValidator validator;
    private final ObjectProvider<CatalogWriteExecutor> executors;
    private final ObjectMapper json;
    private final String baseUrl;
    public AssetUploadApplication(AssetService assets,AssetFileStore files,ImageUploadValidator validator,@org.springframework.beans.factory.annotation.Qualifier("catalogWriteExecutor") ObjectProvider<CatalogWriteExecutor> executors,ObjectMapper json,
        @Value("${app.catalog.asset-base-url:http://127.0.0.1:8080/assets/products/}") String baseUrl){
        this.assets=assets;this.files=files;this.validator=validator;this.executors=executors;this.json=json;this.baseUrl=baseUrl;
        if(!baseUrl.matches("https?://[^?#]+/"))throw new IllegalArgumentException("Absolute asset directory URL required");
    }
    public CatalogWriteExecutor.Response upload(AccountView actor,byte[] bytes,String declaredType,String key,String trace){
        if(actor==null)throw ApiException.unauthenticated();
        if(actor.accountType()!=AccountType.STAFF || !actor.permissions().contains("catalog:write"))throw new ApiException(403,"FORBIDDEN","没有图片上传权限");
        new WriteRequestFingerprint(json).keyHash(key);var executor=executors.getIfAvailable();
        if(executor==null)throw new ApiException(503,"DEPENDENCY_UNAVAILABLE","图片写入尚未启用");
        files.ensureConfigured();var image=validator.validate(bytes,declaredType);
        var fingerprint=json.createObjectNode().put("fileSha256",image.sha256()).put("sizeBytes",bytes.length).put("contentType",image.contentType());
        return executor.execute(new CatalogWriteExecutor.Request(actor.id(),"POST","/api/v1/admin/assets/images",key,fingerprint,trace),()->{
            final String storageKey;
            try{storageKey=files.save(image);}catch(IOException error){throw new ApiException(503,"DEPENDENCY_UNAVAILABLE","图片存储暂不可用");}
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                @Override public void afterCompletion(int status){
                    if(status==STATUS_ROLLED_BACK)try{files.remove(storageKey);}catch(IOException | RuntimeException error){LOG.error("Rollback image cleanup failed; preserve orphan for inspection: {}",storageKey);}
                    // UNKNOWN is deliberately retained: deleting could break a committed asset.
                }
            });
            var asset=assets.create(storageKey,image.contentType(),bytes.length,actor.id());
            var result=new CatalogModels.Asset(asset.id(),baseUrl+storageKey,image.contentType(),bytes.length);
            var audit=json.valueToTree(result);((com.fasterxml.jackson.databind.node.ObjectNode)audit).put("fileSha256",image.sha256());
            return new CatalogWriteExecutor.Outcome(201,result,new CatalogWriteExecutor.Audit("ASSET_CREATE","ASSET",result.id(),null,audit));
        });
    }
}
