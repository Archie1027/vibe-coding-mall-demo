package com.zhicai.platform;

import com.zhicai.platform.identity.*;
import com.zhicai.platform.media.AssetUploadApplication;
import com.zhicai.platform.security.SecurityConfiguration;
import com.zhicai.platform.web.*;
import com.zhicai.platform.write.CatalogWriteExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminAssetController.class)
@Import({SecurityConfiguration.class,ErrorResponder.class})
class AdminAssetSecurityTest {
 @Autowired MockMvc mvc;
 @MockitoBean AuthService auth;
 @MockitoBean AssetUploadApplication uploads;
 void token(String token,AccountType type,List<String> permissions){when(auth.authenticate(token)).thenReturn(new AccountView("1","actor","Actor",type,permissions,List.of(),List.of(),true,0));}
 MockMultipartFile file()throws Exception{return new MockMultipartFile("file","../../client-name.png","image/png",ImageUploadValidatorTest.image("png"));}
 @Test void onlyStaffWritersCanUpload()throws Exception{
  mvc.perform(multipart("/api/v1/admin/assets/images").file(file())).andExpect(status().isUnauthorized());
  token("reader",AccountType.STAFF,List.of("catalog:read"));token("customer",AccountType.CUSTOMER,List.of("catalog:write"));
  for(String token:List.of("reader","customer"))mvc.perform(multipart("/api/v1/admin/assets/images").file(file()).header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
  verifyNoInteractions(uploads);
 }
 @Test void uploadPreservesOriginal201EnvelopeAndDoesNotUseClientFilename()throws Exception{
  token("writer",AccountType.STAFF,List.of("catalog:write"));String result="{\"code\":\"OK\",\"data\":{\"id\":\"9007199254740993\"},\"traceId\":\"original\"}";
  when(uploads.upload(any(),any(byte[].class),eq("image/png"),eq("asset-idempotency-key"),anyString())).thenReturn(new CatalogWriteExecutor.Response(201,result,true));
  mvc.perform(multipart("/api/v1/admin/assets/images").file(file()).header("Authorization","Bearer writer").header("Idempotency-Key","asset-idempotency-key"))
   .andExpect(status().isCreated()).andExpect(content().json(result));
 }
 @Test void rejectsMissingExtraAndOversizeParts()throws Exception{
  token("writer",AccountType.STAFF,List.of("catalog:write"));
  mvc.perform(multipart("/api/v1/admin/assets/images").header("Authorization","Bearer writer")).andExpect(status().isBadRequest());
  mvc.perform(multipart("/api/v1/admin/assets/images").file(file()).file(new MockMultipartFile("other","x.png","image/png",new byte[]{1})).header("Authorization","Bearer writer")).andExpect(status().isBadRequest());
  mvc.perform(multipart("/api/v1/admin/assets/images").file(new MockMultipartFile("file","x.png","image/png",new byte[5*1024*1024+1])).header("Authorization","Bearer writer")).andExpect(status().isPayloadTooLarge());
  verifyNoInteractions(uploads);
 }
}
