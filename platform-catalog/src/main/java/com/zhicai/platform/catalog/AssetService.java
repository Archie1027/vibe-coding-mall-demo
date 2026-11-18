package com.zhicai.platform.catalog;

import com.zhicai.platform.common.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class AssetService {
    private final AssetWriteMapper mapper;
    public AssetService(AssetWriteMapper mapper){this.mapper=mapper;}
    @Transactional(propagation=Propagation.MANDATORY)
    public AssetWriteMapper.PublicAsset create(String key,String type,long size,String owner){
        String extension=switch(type==null?"":type){case "image/jpeg"->"jpg";case "image/png"->"png";case "image/webp"->"webp";default->null;};
        if(!generatedKey(key)||extension==null||!key.endsWith("."+extension)||size<1||size>5*1024*1024||owner==null||!owner.matches("[1-9][0-9]{0,18}"))
            throw new ApiException(400,"VALIDATION_ERROR","图片资源元数据不合法");
        var row=new AssetWriteMapper.NewAsset(key,type,size,owner);
        if(mapper.insert(row)!=1||row.id==null)throw new IllegalStateException("Asset identity not returned");
        return new AssetWriteMapper.PublicAsset(row.id.toString(),key,type,size);
    }
    @Transactional(readOnly=true)
    public AssetWriteMapper.PublicAsset publicByKey(String key){
        if(!generatedKey(key))throw new ApiException(404,"NOT_FOUND","图片不存在");
        return mapper.publicByKey(key);
    }
    private static boolean generatedKey(String key){return key!=null&&key.matches("upload-[0-9a-f]{32}\\.(jpg|png|webp)");}
}
