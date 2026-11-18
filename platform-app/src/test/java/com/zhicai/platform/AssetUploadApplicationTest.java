package com.zhicai.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhicai.platform.catalog.AssetWriteMapper;
import com.zhicai.platform.catalog.AssetService;
import com.zhicai.platform.common.ApiException;
import com.zhicai.platform.identity.*;
import com.zhicai.platform.media.*;
import com.zhicai.platform.write.CatalogWriteExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssetUploadApplicationTest {
 final AssetWriteMapper assets=mock(AssetWriteMapper.class);
 @SuppressWarnings("unchecked") final ObjectProvider<CatalogWriteExecutor> executors=mock(ObjectProvider.class);
 final AssetUploadApplication app=new AssetUploadApplication(new AssetService(assets),new AssetFileStore(""),new ImageUploadValidator(),executors,new ObjectMapper(),"http://127.0.0.1:8080/assets/products/");
 AccountView actor(AccountType type,List<String> permissions){return new AccountView("1","actor","Actor",type,permissions,List.of(),List.of(),true,0);}
 @Test void forbiddenActorsCannotReachFilesOrExecutor(){
  for(var actor:Arrays.asList(null,actor(AccountType.CUSTOMER,List.of("catalog:write")),actor(AccountType.STAFF,List.of("catalog:read"))))
   assertThatThrownBy(()->app.upload(actor,new byte[]{1},"image/png",UUID.randomUUID().toString(),UUID.randomUUID().toString())).isInstanceOf(ApiException.class);
  verifyNoInteractions(assets,executors);
 }
 @Test void missingKeyAndDisabledWriterFailClosedBeforeImageDecode(){
  var actor=actor(AccountType.STAFF,List.of("catalog:write"));
  assertThatThrownBy(()->app.upload(actor,new byte[]{1},"image/png",null,UUID.randomUUID().toString())).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status()).isEqualTo(400));
  assertThatThrownBy(()->app.upload(actor,new byte[]{1},"image/png",UUID.randomUUID().toString(),UUID.randomUUID().toString())).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status()).isEqualTo(503));
  verifyNoInteractions(assets);
 }
}
