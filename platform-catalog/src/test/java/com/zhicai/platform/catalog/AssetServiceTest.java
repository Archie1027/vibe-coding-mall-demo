package com.zhicai.platform.catalog;

import com.zhicai.platform.common.ApiException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssetServiceTest {
 final AssetWriteMapper mapper=mock(AssetWriteMapper.class);final AssetService service=new AssetService(mapper);
 final String key="upload-"+"a".repeat(32)+".png";
 @Test void metadataCreationIsOwnedByCatalogAndRequiresGeneratedIdentity(){
  when(mapper.insert(any())).thenAnswer(call->{((AssetWriteMapper.NewAsset)call.getArgument(0)).id=5L;return 1;});
  assertThat(service.create(key,"image/png",100,"1")).isEqualTo(new AssetWriteMapper.PublicAsset("5",key,"image/png",100));
 }
 @Test void invalidMetadataCannotReachSql(){
  for(String bad:new String[]{"../escape.png","filename.png","upload-"+"a".repeat(32)+".jpg"})
   assertThatThrownBy(()->service.create(bad,"image/png",100,"1")).isInstanceOf(ApiException.class);
  assertThatThrownBy(()->service.create(key,"image/png",0,"1")).isInstanceOf(ApiException.class);
  assertThatThrownBy(()->service.create(key,"image/png",100,"0")).isInstanceOf(ApiException.class);verifyNoInteractions(mapper);
 }
 @Test void missingIdentityFailsAndNonGeneratedReadsDoNotQueryMapper(){
  assertThatThrownBy(()->service.create(key,"image/png",100,"1")).isInstanceOf(IllegalStateException.class);
  clearInvocations(mapper);assertThatThrownBy(()->service.publicByKey("../escape.png")).isInstanceOf(ApiException.class);verifyNoInteractions(mapper);
 }
}
